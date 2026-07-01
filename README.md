# Gemini OAuth Internal

A standalone Clojure library and CLI tool for Google OAuth subscription authentication. 

This project isolates the Google OAuth and token management flow used to authenticate with Google's internal companion/subscription endpoints (e.g., Code Assist / Antigravity). It dynamically extracts the Google OAuth client credentials from the installed Antigravity IDE executable on your machine, enabling other Clojure/Babashka applications to consume the Gemini APIs under the high-quota subscription tier (Google One AI Premium / Gemini Advanced / Gemini Enterprise) instead of public API keys.

---

## ⚠️ Important Endpoint Details

### What does this return?
This library/CLI returns a temporary **Google OAuth Access Token (Bearer Token)**, *not* a public API key. 

### Where can I use this token?
You **cannot** use this token on the standard public Gemini API endpoints (e.g., `https://generativelanguage.googleapis.com/...`). Instead, it must be used on Google's internal Code Assist / Companion subscription endpoints.

These endpoints are defined as constants inside `gemini-oauth-internal.core`:

* **Base URL**: `https://daily-cloudcode-pa.googleapis.com/v1internal` (`default-code-assist-base`)
* **Content Generation**: `https://daily-cloudcode-pa.googleapis.com/v1internal:generateContent` (`subscription-generate-content-url`)
* **Streaming Content**: `https://daily-cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse` (`subscription-stream-generate-content-url`)

### Request Wrapping Format
When querying the subscription endpoints, you must wrap the standard public Gemini `GenerateContentRequest` payload into a specific wrapper structure that includes the target companion `model` and the resolved `project` ID:

```clojure
{:model   "gemini-3-flash-agent" ; subscription model id
 :project "projects/..."         ; companion project id resolved via loadCodeAssist
 :request {...}}                 ; your standard GenerateContentRequest payload
```

---

## Secure Standalone Design (No Hardcoded Secrets)

To protect your repository and comply with security scanners, **this project does not contain any hardcoded client secrets or client IDs in its files or git history.**

Instead, it dynamically resolves the Google OAuth client details:
1. **Dynamic Scanning (First Run)**: On the first run, the library scans standard installation paths (and your shell environment `PATH`) to locate the `agy` (or `antigravity`) Go executable. It extracts the authentic Google OAuth credentials directly from the binary.
2. **Local Caching**: The extracted config is written to `~/.gemini/antigravity-client.json` so that subsequent runs load the settings instantly without scanning the binary again.
3. **Manual Override**: If you wish to run it on a machine without Antigravity installed, you can simply copy your generated `~/.gemini/antigravity-client.json` to the target machine.

---

## Command Line Usage (Babashka CLI)

This project supports fast, low-overhead CLI commands powered by **[Babashka](https://babashka.org/)**.

### 1. Login to Google
Launches your default web browser, redirects you to the Google account consent screen, and saves the tokens upon success.
```bash
bb login
```

### 2. View Authentication Info
Displays your current status, active email, token time-to-live, and target Companion Project ID.
```bash
bb info
```

### 3. Print Access Token
Outputs a valid, auto-refreshed access token directly to stdout. Ideal for scripting and piping credentials into other tools (like `curl`).
```bash
bb token
```

### 4. Configure Client Credentials Manually
If you do not have Antigravity installed, or want to use your own Google OAuth application credentials, you can configure them manually:
```bash
bb configure <client-id> <client-secret>
```

---

## Installation & Setup

To use this in your application, add this project as a local dependency in your `deps.edn`:

```clojure
{:deps
 {gemini-oauth-internal/gemini-oauth-internal
  {:local/root "/Users/baleximii/userdata/clj/gemini-oauth-internal"}}}
```

---

## API Reference

Import the core namespace:

```clojure
(require '[gemini-oauth-internal.core :as oauth])
```

### `(oauth/login-with-google! options)`
Starts the local server, opens the browser to authenticate, and saves the tokens.
- **Options Map** (Optional):
  - `:port`: Port to run the temporary callback server (default: `8085`).
  - `:file-path`: Path to save the credentials JSON (default: `~/.gemini/gcu_antigravity_creds.json`).
  - `:client-cfg`: Custom OAuth client config map (`:client-id`, `:client-secret`, `:scopes`).

### `(oauth/oauth-access-token! file-path client-cfg)`
Reads saved credentials, checks validity, refreshes if within 5 minutes of expiration, and returns the active access token.

### `(oauth/get-auth-info)`
Returns a map containing:
- `:token`: Fresh Google OAuth access token.
- `:project`: Target companion project ID resolved via `loadCodeAssist`.
- `:headers`: Header map containing the Antigravity emulated headers (User-Agent, X-Goog-Api-Client, Client-Metadata, Authorization).

### `(oauth/wrap-request request-payload model project-id)`
Wraps a standard Gemini `GenerateContentRequest` payload into the structure expected by the subscription endpoints.

---

## Code Example: Calling Gemini via Subscription

Here is how you can use this library to make a subscription-based request to the Code Assist API endpoint:

```clojure
(ns my-app.core
  (:require [gemini-oauth-internal.core :as oauth]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

(defn call-gemini [prompt]
  ;; 1. Retrieve the subscription credentials & companion project id
  (let [{:keys [token project headers]} (oauth/get-auth-info)
        
        ;; 2. Construct public GenerateContentRequest payload
        gemini-payload {:contents [{:role "user"
                                    :parts [{:text prompt}]}]}
                                    
        ;; 3. Wrap it into the Code Assist format
        wrapped-body (oauth/wrap-request gemini-payload "gemini-3-flash-agent" project)
        
        ;; 4. Send request to the internal daily/canary Code Assist ring
        api-url oauth/subscription-generate-content-url
        response @(http/post api-url
                             {:headers headers
                              :body (json/generate-string wrapped-body)
                              :timeout 60000})]
                              
    (if (= 200 (:status response))
      (let [parsed (json/parse-string (:body response) true)
            candidates (or (:candidates parsed) (get-in parsed [:response :candidates]))
            text (get-in (first candidates) [:content :parts 0 :text])]
        (println "Response:" text))
      (println "Request failed:" (:status response) (:body response)))))
```
