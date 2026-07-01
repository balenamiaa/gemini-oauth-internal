(ns gemini-oauth-internal.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [org.httpkit.server :refer [run-server]])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]))

(defonce ^:private shared-client (HttpClient/newHttpClient))

(def ^:private home (System/getProperty "user.home"))

(def default-creds-file-path
  (str home "/.gemini/gcu_antigravity_creds.json"))

(def default-client-cfg-path
  (str home "/.gemini/antigravity-client.json"))

(def default-code-assist-base
  "The base URL for Google's internal Code Assist / Companion API.
   Unlike the public Gemini API (which uses API keys), the subscription/companion API
   expects an OAuth Bearer token authenticated under the Antigravity client."
  "https://daily-cloudcode-pa.googleapis.com/v1internal")

(def subscription-generate-content-url
  "Target endpoint for generating content under the subscription model.
   Expects a POST request containing a wrapped payload:
   {:model model :project project :request GenerateContentRequest}"
  (str default-code-assist-base ":generateContent"))

(def subscription-stream-generate-content-url
  "Target endpoint for streaming generated content under the subscription model.
   Expects a POST request with the wrapped payload, using alt=sse."
  (str default-code-assist-base ":streamGenerateContent?alt=sse"))

(def default-login-port 8085)

;; ---- 1. Dynamically locate the agy / antigravity binary ----

(defn- sh-path [cmd]
  (try
    (let [res (apply sh cmd)]
      (when (zero? (:exit res))
        (first (str/split-lines (str/trim (:out res))))))
    (catch Exception _ nil)))

(defn find-agy-binary
  "Search for the installed agy/antigravity binary on standard system paths or via shell path lookups."
  []
  (let [os (str/lower-case (System/getProperty "os.name"))
        win? (str/includes? os "win")
        which-cmd (if win? ["where" "agy"] ["which" "agy"])
        which-antigravity (if win? ["where" "antigravity"] ["which" "antigravity"])
        candidates (concat
                     [(sh-path which-cmd)
                      (sh-path which-antigravity)]
                     (if win?
                       [(str home "\\.local\\bin\\agy.exe")
                        (some-> (System/getenv "LOCALAPPDATA") (str "\\Programs\\Antigravity\\resources\\app\\bin\\antigravity.exe"))
                        (str home "\\.gemini\\antigravity-cli\\bin\\agentapi.exe")]
                       [(str home "/.local/bin/agy")
                        "/Applications/Antigravity.app/Contents/Resources/app/bin/antigravity"
                        "/Applications/Antigravity.app/Contents/MacOS/Antigravity"
                        (str home "/.gemini/antigravity-cli/bin/agentapi")]))]
    (->> candidates
         (remove nil?)
         distinct
         (filter (fn [p]
                   (try
                     (let [f (io/file p)]
                       (and (.exists f) (.isFile f) (> (.length f) 20000000)))
                     (catch Exception _ false))))
         first)))

;; ---- 2. Scan binary dynamically to extract OAuth credentials ----

(defn- scan-binary [path patterns]
  (with-open [is (io/input-stream path)]
    (let [chunk 4194304 overlap 256 buf (byte-array chunk)]
      (loop [carry "" acc #{}]
        (let [n (.read is buf)]
          (if (neg? n)
            acc
            (let [s (str carry (String. buf 0 n "ISO-8859-1"))
                  acc' (reduce (fn [a re] (into a (re-seq re s))) acc patterns)]
              (recur (subs s (max 0 (- (count s) overlap))) acc'))))))))

(defn- valid-pair? [id secret]
  (try
    (let [body (str "client_id=" id "&client_secret=" secret "&grant_type=refresh_token&refresh_token=invalid")
          req (-> (HttpRequest/newBuilder)
                  (.uri (URI/create "https://oauth2.googleapis.com/token"))
                  (.header "Content-Type" "application/x-www-form-urlencoded")
                  (.POST (HttpRequest$BodyPublishers/ofString body))
                  (.build))
          resp (.send shared-client req (HttpResponse$BodyHandlers/ofString))
          res (json/parse-string (.body resp) true)]
      (= "invalid_grant" (:error res)))
    (catch Exception _ false)))

(defn extract-client-cfg!
  "Locate the agy binary on the system, scan it to extract valid OAuth client credentials,
   and write them to client-cfg-path."
  [client-cfg-path]
  (let [bin (find-agy-binary)]
    (if-not bin
      (throw (Exception. (str "Antigravity CLI or binary ('agy') not found on this system.\n"
                              "This library requires the Antigravity installation to extract Google OAuth credentials.\n"
                              "Please install Antigravity first or manually place your client config at " client-cfg-path)))
      (let [ids (vec (scan-binary bin [#"[0-9]{8,}-[a-z0-9]{20,}\.apps\.googleusercontent\.com"]))
            secrets (vec (scan-binary bin [#"GOCSPX-[A-Za-z0-9_-]{20,28}"]))
            pairs (vec (for [id ids s secrets :when (valid-pair? id s)] {:client-id id :client-secret s}))]
        (if (empty? pairs)
          (throw (Exception. "Found agy binary but no valid Google OAuth client pairings could be extracted."))
          (let [scopes ["https://www.googleapis.com/auth/cloud-platform"
                        "https://www.googleapis.com/auth/userinfo.email"
                        "https://www.googleapis.com/auth/userinfo.profile"
                        "openid"]
                cfg (assoc (first pairs) :scopes scopes :clients pairs)]
            (let [f (io/file client-cfg-path)]
              (io/make-parents f)
              (spit f (json/generate-string cfg {:pretty true})))
            cfg))))))

(defn load-client-cfg
  "Load the extracted Antigravity OAuth client configuration. If not found, attempt
   to dynamically locate the local agy binary and extract credentials."
  ([]
   (load-client-cfg default-client-cfg-path))
  ([path]
   (let [f (io/file path)]
     (if (.exists f)
       (json/parse-string (slurp f) true)
       (do
         (println "Client config not found. Searching for installed Antigravity binary to extract client credentials...")
         (extract-client-cfg! path))))))

;; ---- 3. Standard Request and Token Management ----

(defn- client-platform []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond (str/includes? os "mac") "MACOS"
          (str/includes? os "win") "WINDOWS"
          :else "LINUX")))

(defn get-antigravity-headers
  "Get target headers required to emulate Antigravity IDE requests."
  []
  {"User-Agent" "antigravity"
   "X-Goog-Api-Client" "google-cloud-sdk vscode_cloudshelleditor/0.1"
   "Client-Metadata" (json/generate-string {:ideType "ANTIGRAVITY"
                                             :platform (client-platform)
                                             :pluginType "GEMINI"})})

(defn save-creds!
  ([creds]
   (save-creds! creds default-creds-file-path))
  ([creds file-path]
   (let [f (io/file file-path)]
     (io/make-parents f)
     (spit f (json/generate-string creds {:pretty true})))))

(defn load-creds
  ([]
   (load-creds default-creds-file-path))
  ([file-path]
   (let [f (io/file file-path)]
     (when (.exists f)
       (json/parse-string (slurp f) true)))))

(defn- truncate [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) " ...(" (- (count s) n) " more chars truncated)")
      s)))

(defn refresh-oauth-token!
  "Send a refresh token request to Google OAuth token endpoint using the client config."
  ([refresh-token]
   (refresh-oauth-token! refresh-token (load-client-cfg)))
  ([refresh-token client-cfg]
   (try
     (let [client-id (:client-id client-cfg)
           client-secret (:client-secret client-cfg)
           body (str "client_id=" client-id
                     "&client_secret=" client-secret
                     "&grant_type=refresh_token"
                     "&refresh_token=" refresh-token)
           req (-> (HttpRequest/newBuilder)
                   (.uri (URI/create "https://oauth2.googleapis.com/token"))
                   (.header "Content-Type" "application/x-www-form-urlencoded")
                   (.POST (HttpRequest$BodyPublishers/ofString body))
                   (.build))
           resp (.send shared-client req (HttpResponse$BodyHandlers/ofString))]
       (if (= 200 (.statusCode resp))
         (let [res (json/parse-string (.body resp) true)]
           {:access_token (:access_token res)
            :expiry_date (+ (System/currentTimeMillis) (* (or (:expires_in res) 3600) 1000))})
         (do (println "Failed to refresh OAuth token:" (truncate (.body resp) 300)) nil)))
     (catch Exception e
       (println "Error refreshing OAuth token:" (.getMessage e))
       nil))))

(defn oauth-access-token!
  "Read the saved Google OAuth credentials, auto-refreshing the access token if it
   is close to expiry (within 5 minutes) or expired."
  ([]
   (oauth-access-token! default-creds-file-path (load-client-cfg)))
  ([file-path client-cfg]
   (try
     (let [creds (load-creds file-path)]
       (if creds
         (let [expiry (:expiry_date creds)
               refresh-token (:refresh_token creds)]
           (if (and expiry (> (- expiry (System/currentTimeMillis)) 300000))
             (:access_token creds)
             (if (seq refresh-token)
               (if-let [refreshed (refresh-oauth-token! refresh-token client-cfg)]
                 (let [updated-creds (merge creds refreshed)]
                   (save-creds! updated-creds file-path)
                   (:access_token refreshed))
                 (:access_token creds))
               (:access_token creds))))
         (do (println "Credentials file not found at:" file-path) nil)))
     (catch Exception e
       (println "Error reading or refreshing OAuth token:" (.getMessage e))
       nil))))

(defn jwt-email
  "Extract the email address from a Google OAuth id_token (base64url JWT payload).
   This is for display/diagnostic purposes only (no signature verification)."
  [id-token]
  (try
    (let [payload (second (str/split (str id-token) #"\."))
          padded (str payload (case (mod (count payload) 4) 2 "==" 3 "=" ""))
          decoded (String. (.decode (java.util.Base64/getUrlDecoder) padded) "UTF-8")]
      (:email (json/parse-string decoded true)))
    (catch Exception _ nil)))

(defn logged-in-email
  ([] (logged-in-email default-creds-file-path))
  ([file-path]
   (try
     (:email (load-creds file-path))
     (catch Exception _ nil))))

(defn post-code-assist!
  "POST helper to communicate with the Code Assist backend."
  [method token body & [{:keys [code-assist-base] :or {code-assist-base default-code-assist-base}}]]
  (let [headers (get-antigravity-headers)]
    (.send shared-client
           (-> (HttpRequest/newBuilder)
               (.uri (URI/create (str code-assist-base ":" method)))
               (.header "Content-Type" "application/json")
               (.header "Authorization" (str "Bearer " token))
               (.header "User-Agent" (headers "User-Agent"))
               (.header "X-Goog-Api-Client" (headers "X-Goog-Api-Client"))
               (.header "Client-Metadata" (headers "Client-Metadata"))
               (.timeout (Duration/ofSeconds 60))
               (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
               (.build))
           (HttpResponse$BodyHandlers/ofString))))

(defonce ^:private project-cache (atom nil))

(defn code-assist-project!
  "Resolve the companion project ID (cloudaicompanionProject) required for calling
   Gemini through the Code Assist subscription endpoints."
  ([token]
   (code-assist-project! token default-code-assist-base))
  ([token code-assist-base]
   (or @project-cache
       (let [resp (post-code-assist! "loadCodeAssist" token
                                    {:metadata {:ideType "IDE_UNSPECIFIED"
                                                :platform "PLATFORM_UNSPECIFIED"
                                                :pluginType "GEMINI"}}
                                    {:code-assist-base code-assist-base})]
         (if (= 200 (.statusCode resp))
           (let [project-id (:cloudaicompanionProject (json/parse-string (.body resp) true))]
             (reset! project-cache project-id)
             project-id)
           (throw (Exception. (str "loadCodeAssist failed (" (.statusCode resp) "): "
                                   (truncate (.body resp) 300)))))))))

(defn wrap-request
  "Wraps a standard Gemini GenerateContentRequest payload into the subscription format."
  [request-payload model project-id]
  {:model model
   :project project-id
   :request request-payload})

(defn get-auth-info
  "Helper to fetch both the active access token and resolved companion project-id,
   formatted for use in making requests to Code Assist."
  ([]
   (get-auth-info default-creds-file-path (load-client-cfg) default-code-assist-base))
  ([creds-file client-cfg code-assist-base]
   (let [token (oauth-access-token! creds-file client-cfg)]
     (if token
       {:token token
        :project (code-assist-project! token code-assist-base)
        :headers (assoc (get-antigravity-headers) "Authorization" (str "Bearer " token))}
       (throw (Exception. "Not logged in. Access token could not be obtained."))))))

;; --- Local redirect server for OAuth flow ---

(defn- parse-query-string [qs]
  (if (seq qs)
    (into {} (for [pair (str/split qs #"&")]
               (let [[k v] (str/split pair #"=" 2)]
                 [k (java.net.URLDecoder/decode (or v "") "UTF-8")])))
    {}))

(defn- exchange-code-for-tokens! [code redirect-uri client-cfg]
  (try
    (let [client-id (:client-id client-cfg)
          client-secret (:client-secret client-cfg)
          body (str "client_id=" client-id
                    "&client_secret=" client-secret
                    "&grant_type=authorization_code"
                    "&code=" code
                    "&redirect_uri=" redirect-uri)
          resp (.send shared-client
                      (-> (HttpRequest/newBuilder)
                          (.uri (URI/create "https://oauth2.googleapis.com/token"))
                          (.header "Content-Type" "application/x-www-form-urlencoded")
                          (.POST (HttpRequest$BodyPublishers/ofString body))
                          (.build))
                      (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode resp))
        (let [res (json/parse-string (.body resp) true)]
          (merge res {:expiry_date (+ (System/currentTimeMillis) (* (or (:expires_in res) 3600) 1000))
                      :email (jwt-email (:id_token res))}))))
    (catch Exception e
      (println "Token exchange error:" (.getMessage e))
      nil)))

(defn- run-login-server! [promise-result port]
  (let [server (atom nil)
        handler (fn [req]
                  (let [q (parse-query-string (:query-string req))]
                    (cond
                      (get q "code")
                      (do (deliver promise-result {:code (get q "code")})
                          {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
                           :body "<html><body style='font-family:-apple-system,sans-serif;background:#0b120f;color:#ecf3f0;display:flex;height:100vh;align-items:center;justify-content:center'><div style='text-align:center'><h2>✓ Signed in</h2><p>You can close this tab and return to the terminal / application.</p></div></body></html>"})
                      (get q "error")
                      (do (deliver promise-result {:error (get q "error")})
                          {:status 200 :body (str "Login failed: " (get q "error"))})
                      :else {:status 400 :body "Invalid request"})))]
    (reset! server (run-server handler {:port port}))
    (fn [] ((deref server) :timeout 100))))

(defn login-with-google!
  "Run the Login with Google OAuth flow. Starts a local HTTP server to handle the
   redirect callback, opens the default web browser to Google OAuth consent page,
   exchanges the received authorization code for access/refresh tokens, and saves them."
  ([]
   (login-with-google! {}))
  ([{:keys [port file-path client-cfg]
     :or {port default-login-port
          file-path default-creds-file-path}
     :as opts}]
   (let [client-cfg (or client-cfg (load-client-cfg))
         promise-result (promise)
         redirect-uri (str "http://localhost:" port)
         auth-url (str "https://accounts.google.com/o/oauth2/v2/auth"
                       "?client_id=" (:client-id client-cfg)
                       "&redirect_uri=" redirect-uri
                       "&response_type=code"
                       "&scope=" (str/join "%20" (:scopes client-cfg))
                       "&access_type=offline&prompt=consent")
         stop-server (run-login-server! promise-result port)]
     (println "Starting authentication flow...")
     (println "If your browser does not open automatically, please visit:")
     (println auth-url)
     (println)
     (try
       (let [os (str/lower-case (System/getProperty "os.name"))]
         (cond (str/includes? os "mac") (sh "open" auth-url)
               (str/includes? os "win") (sh "cmd" "/c" "start" auth-url)
               :else (sh "xdg-open" auth-url)))
       (catch Exception _ nil))
     (let [result (deref promise-result 300000 {:error "Timed out waiting for Google login callback (5 minutes)."})]
       (try (stop-server) (catch Exception _ nil))
       (if-let [code (:code result)]
         (if-let [tokens (exchange-code-for-tokens! code redirect-uri client-cfg)]
           (do (save-creds! tokens file-path)
               (reset! project-cache nil)
               {:ok true :email (:email tokens)})
           {:ok false :error "Failed to exchange authorization code for tokens."})
         {:ok false :error (or (:error result) "Login failed.")})))))

;; --- Command Line Entry Point ---

(defn -main
  "CLI interface for managing internal Google OAuth."
  [& args]
  (let [action (first args)]
    (case action
      "login"
      (let [res (login-with-google!)]
        (if (:ok res)
          (do (println "Successfully signed in as:" (:email res))
              (System/exit 0))
          (do (println "Authentication failed:" (:error res))
              (System/exit 1))))

      "token"
      (if-let [token (oauth-access-token!)]
        (do (println token)
            (System/exit 0))
        (do (println "Error: No valid token available. Run 'login' command first.")
            (System/exit 1)))

      "info"
      (let [creds (load-creds)]
        (if creds
          (let [email (:email creds)
                expiry (:expiry_date creds)
                time-left-sec (long (/ (- expiry (System/currentTimeMillis)) 1000))]
            (println "Status: Logged In")
            (println "Email:" email)
            (println "Token Expires In:" (if (pos? time-left-sec) (str time-left-sec " seconds") "Expired"))
            (try
              (let [token (oauth-access-token!)
                    project-id (code-assist-project! token)]
                (println "Companion Project ID:" project-id))
              (catch Exception e
                (println "Companion Project ID: Failed to resolve (" (.getMessage e) ")")))
            (System/exit 0))
          (do (println "Status: Not Logged In")
              (System/exit 0))))

      "configure"
      (let [client-id (second args)
            client-secret (nth args 2 nil)]
        (if (and client-id client-secret)
          (let [scopes ["https://www.googleapis.com/auth/cloud-platform"
                        "https://www.googleapis.com/auth/userinfo.email"
                        "https://www.googleapis.com/auth/userinfo.profile"
                        "openid"]
                cfg {:client-id client-id
                     :client-secret client-secret
                     :scopes scopes}]
            (save-creds! cfg default-client-cfg-path)
            (println "Successfully configured client credentials at:" default-client-cfg-path)
            (System/exit 0))
          (do
            (println "Error: Please provide both <client-id> and <client-secret>.")
            (println "Usage: clj -m gemini-oauth-internal.core configure <client-id> <client-secret>")
            (println "       bb configure <client-id> <client-secret>")
            (System/exit 1))))

      ;; default: show usage info
      (do
        (println "Usage: clj -m gemini-oauth-internal.core <command>")
        (println "Commands:")
        (println "  login       Launch browser and log in with Google account (subscription)")
        (println "  token       Print current valid access token (refreshes if needed)")
        (println "  info        Show login status, email, token lifetime and companion project ID")
        (println "  configure   Manually configure Google OAuth client credentials")
        (System/exit 0)))))
