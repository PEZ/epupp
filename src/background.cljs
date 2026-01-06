(ns background
  "Background service worker for WebSocket connections.
   Runs in extension context, immune to page CSP.
   Relays WebSocket messages to/from content scripts."
  (:require [storage :as storage]
            [url-matching :as url-matching]))

(def ^:private config js/EXTENSION_CONFIG)

(js/console.log "[Scittle Tamper Background] Service worker started")

;; ============================================================
;; Initialization Promise - single source of truth for readiness
;; ============================================================

;; Use a mutable variable (not defonce) so each script wake gets fresh state.
;; The init-promise ensures all operations wait for storage to load.
(def !init-promise (atom nil))

(defn ^:async ensure-initialized!
  "Returns a promise that resolves when initialization is complete.
   Safe to call multiple times - only initializes once per script lifetime."
  []
  (if-let [p @!init-promise]
    (js-await p)
    (let [p (try
              (js-await (storage/init!))
              ;; Ensure built-in userscripts are installed
              (js-await (storage/ensure-gist-installer!))
              (js/console.log "[Background] Initialization complete")
              true
              (catch :default err
                (js/console.error "[Background] Initialization failed:" err)
                ;; Reset so next call can retry
                (reset! !init-promise nil)
                (throw err)))]
      (reset! !init-promise (js/Promise.resolve p))
      p)))

;; ============================================================
;; State - WebSocket connections and pending approvals
;; ============================================================

;; Note: Use def (not defonce) for state that should reset on script wake.
;; WebSocket connections don't survive script termination anyway.
(def !state (atom {:ws/connections {}
                   :pending/approvals {}}))

(defn get-ws
  "Get WebSocket for a tab"
  [tab-id]
  (get-in @!state [:ws/connections tab-id]))

(defn close-ws!
  "Close and remove WebSocket for a tab. Does not send ws-close event."
  [tab-id]
  (when-let [ws (get-ws tab-id)]
    ;; Clear onclose to prevent sending ws-close when deliberately closing
    (set! (.-onclose ws) nil)
    (try
      (.close ws)
      (catch :default e
        (js/console.error "[Background] Error closing WebSocket:" e))))
  (swap! !state update :ws/connections dissoc tab-id))

(defn send-to-tab
  "Send message to content script in a tab"
  [tab-id message]
  (-> (js/chrome.tabs.sendMessage tab-id (clj->js message))
      (.catch (fn [e]
                (js/console.error "[Background] Failed to send to tab:" tab-id "error:" e)))))

(defn handle-ws-connect
  "Create WebSocket connection for a tab"
  [tab-id port]
  ;; Close existing connection if any
  (close-ws! tab-id)

  (let [ws-url (str "ws://localhost:" port "/_nrepl")]
    (js/console.log "[Background] Connecting to:" ws-url "for tab:" tab-id)
    (try
      (let [ws (js/WebSocket. ws-url)]
        (swap! !state assoc-in [:ws/connections tab-id] ws)

        (set! (.-onopen ws)
              (fn []
                (js/console.log "[Background] WebSocket connected for tab:" tab-id)
                (send-to-tab tab-id {:type "ws-open"})))

        (set! (.-onmessage ws)
              (fn [event]
                (send-to-tab tab-id {:type "ws-message"
                                     :data (.-data event)})))

        (set! (.-onerror ws)
              (fn [error]
                (js/console.error "[Background] WebSocket error for tab:" tab-id error)
                (send-to-tab tab-id {:type "ws-error"
                                     :error (str "WebSocket error connecting to " ws-url)})))

        (set! (.-onclose ws)
              (fn [event]
                (js/console.log "[Background] WebSocket closed for tab:" tab-id)
                (send-to-tab tab-id {:type "ws-close"
                                     :code (.-code event)
                                     :reason (.-reason event)})
                (swap! !state update :ws/connections dissoc tab-id))))
      (catch :default e
        (js/console.error "[Background] Failed to create WebSocket:" e)
        (send-to-tab tab-id {:type "ws-error"
                             :error (str e)})))))

(defn handle-ws-send
  "Send data through WebSocket for a tab"
  [tab-id data]
  (when-let [ws (get-ws tab-id)]
    (when (= 1 (.-readyState ws))
      (.send ws data))))

(defn handle-ws-close
  "Close WebSocket for a tab"
  [tab-id]
  (close-ws! tab-id))

(defn get-active-tab-id
  "Get the active tab ID. Returns a promise."
  []
  (js/Promise.
   (fn [resolve _reject]
     (js/chrome.tabs.query #js {:active true :currentWindow true}
                           (fn [tabs]
                             (resolve (when (seq tabs)
                                        (.-id (first tabs)))))))))

;; ============================================================
;; Badge Management - Calculate from source of truth
;; ============================================================

(defn set-badge!
  "Set the badge text and color based on count."
  [n]
  (if (pos? n)
    (do
      (js/chrome.action.setBadgeText #js {:text (str n)})
      (js/chrome.action.setBadgeBackgroundColor #js {:color "#f59e0b"}))
    (js/chrome.action.setBadgeText #js {:text ""})))

(defn count-pending-for-url
  "Count scripts needing approval for a given URL.
   A script needs approval if: enabled, matches URL, and pattern not yet approved."
  [url]
  (if (or (nil? url) (= "" url))
    0
    (let [scripts (storage/get-enabled-scripts)]
      (->> scripts
           (filter (fn [script]
                     (when-let [pattern (url-matching/get-matching-pattern url script)]
                       (not (storage/pattern-approved? script pattern)))))
           count))))

(defn update-badge-for-tab!
  "Update badge based on pending count for a specific tab's URL."
  [tab-id]
  (js/chrome.tabs.get tab-id
                      (fn [tab]
                        (when-not js/chrome.runtime.lastError
                          (let [url (.-url tab)
                                n (count-pending-for-url url)]
                            (set-badge! n))))))

(defn ^:async update-badge-for-active-tab!
  "Update badge based on pending count for the active tab."
  []
  (let [tab-id (js-await (get-active-tab-id))]
    (when tab-id
      (update-badge-for-tab! tab-id))))

(defn clear-pending-approval!
  "Remove a specific script/pattern from pending approvals and update badge."
  [script-id pattern]
  (let [approval-id (str script-id "|" pattern)]
    (swap! !state update :pending/approvals dissoc approval-id))
  (update-badge-for-active-tab!))

(defn sync-pending-approvals!
  "Sync pending approvals atom with storage state.
   Removes stale entries for deleted/disabled scripts or approved patterns."
  []
  (doseq [[approval-id context] (:pending/approvals @!state)]
    (let [script-id (:script/id context)
          pattern (:approval/pattern context)
          script (storage/get-script script-id)]
      (when (or (nil? script)
                (not (:script/enabled script))
                (storage/pattern-approved? script pattern))
        (swap! !state update :pending/approvals dissoc approval-id))))
  (update-badge-for-active-tab!))

;; ============================================================
;; Auto-Injection: Run userscripts on page load
;; ============================================================

(defn execute-in-page
  "Execute a function in page context (MAIN world).
   Returns a promise."
  [tab-id func & args]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.scripting.executeScript
      #js {:target #js {:tabId tab-id}
           :world "MAIN"
           :func func
           :args (clj->js (vec args))}
      (fn [results]
        (if js/chrome.runtime.lastError
          (reject (js/Error. (.-message js/chrome.runtime.lastError)))
          (resolve (when (seq results) (.-result (first results))))))))))

(defn inject-content-script
  "Inject a script file into ISOLATED world."
  [tab-id file]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.scripting.executeScript
      #js {:target #js {:tabId tab-id}
           :files #js [file]}
      (fn [_results]
        (if js/chrome.runtime.lastError
          (reject (js/Error. (.-message js/chrome.runtime.lastError)))
          (resolve true)))))))

;; Page-context functions (pure JS, no Squint runtime)
(def inject-script-fn
  (js* "function(url, isModule) {
    var script = document.createElement('script');
    if (isModule) script.type = 'module';
    if (window.trustedTypes && window.trustedTypes.createPolicy) {
      try {
        // Try using 'default' policy first (commonly allowed by strict CSPs)
        var policy = window.trustedTypes.defaultPolicy;
        if (!policy) {
          policy = window.trustedTypes.createPolicy('default', {
            createScriptURL: function(s) { return s; }
          });
        }
        script.src = policy.createScriptURL(url);
      } catch(e) {
        // Policy creation may be blocked by CSP - fall back to direct assignment
        // This may fail on very strict TT sites, but extension URLs are typically allowed
        console.warn('[Scittle Tamper] TrustedTypes policy creation failed, using direct assignment:', e.message);
        script.src = url;
      }
    } else {
      script.src = url;
    }
    document.head.appendChild(script);
    return 'ok';
  }"))

(def check-scittle-fn
  (js* "function() {
    return {
      hasScittle: !!(window.scittle && window.scittle.core),
      hasWsBridge: !!window.__browserJackInWSBridge
    };
  }"))

;; RESERVED: Direct ClojureScript evaluation via Scittle's eval_string.
;; Currently unused - userscripts are injected as <script type="application/x-scittle">
;; tags and evaluated by Scittle's built-in script tag processing. This function
;; is kept for potential future use cases where direct programmatic evaluation
;; (rather than tag injection) is needed, such as:
;; - Interactive REPL evaluation from the DevTools panel
;; - Dynamic code generation based on page content
;; - Sequential execution with dependency ordering
#_(def eval-cljs-fn
    "Evaluate ClojureScript code via Scittle"
    (js* "function(code) {
    try {
      var result = scittle.core.eval_string(code);
      console.log('[Userscript] Executed successfully');
      return {success: true, result: String(result)};
    } catch(e) {
      console.error('[Userscript] Error:', e);
      return {success: false, error: e.message};
    }
  }"))

(defn poll-until
  "Poll a check function until success or timeout"
  [check-fn success? timeout]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)]
       (letfn [(poll []
                 (-> (check-fn)
                     (.then (fn [result]
                              (cond
                                (success? result) (resolve result)
                                (> (- (js/Date.now) start) timeout)
                                (reject (js/Error. "Timeout waiting for Scittle"))
                                :else (js/setTimeout poll 100))))
                     (.catch reject)))]
         (poll))))))

(defn ^:async ensure-scittle!
  "Ensure Scittle is loaded in the page."
  [tab-id]
  (let [status (js-await (execute-in-page tab-id check-scittle-fn))]
    (when-not (and status (.-hasScittle status))
      (let [scittle-url (js/chrome.runtime.getURL "vendor/scittle.js")]
        (js-await (execute-in-page tab-id inject-script-fn scittle-url false))
        (js-await (poll-until
                   (fn [] (execute-in-page tab-id check-scittle-fn))
                   (fn [r] (and r (.-hasScittle r)))
                   5000))))
    true))

(defn wait-for-bridge-ready
  "Wait for content bridge to be ready by pinging it.
   Returns a promise that resolves when bridge responds."
  [tab-id]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)
           timeout 5000]
       (letfn [(ping []
                 (js/chrome.tabs.sendMessage
                  tab-id
                  #js {:type "bridge-ping"}
                  (fn [response]
                    (cond
                      ;; Success - bridge responded
                      (and response (.-ready response))
                      (do
                        (js/console.log "[Background] Content bridge ready for tab:" tab-id)
                        (resolve true))

                      ;; Timeout
                      (> (- (js/Date.now) start) timeout)
                      (reject (js/Error. "Timeout waiting for content bridge"))

                      ;; Not ready yet or error - retry
                      :else
                      (js/setTimeout ping 50)))))]
         (ping))))))

(defn send-tab-message
  "Send message to a tab and return a promise."
  [tab-id message]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.tabs.sendMessage
      tab-id
      (clj->js message)
      (fn [response]
        (if js/chrome.runtime.lastError
          (reject (js/Error. (.-message js/chrome.runtime.lastError)))
          (resolve response)))))))

;; Listen for messages from content scripts, popup, and panel
(defn ^:async execute-scripts!
  "Execute a list of scripts in the page via Scittle using script tag injection.
   Injects content bridge, waits for readiness signal, then injects userscripts."
  [tab-id scripts]
  (when (seq scripts)
    (try
      (let [trigger-url (js/chrome.runtime.getURL "trigger-scittle.js")]
        ;; First ensure content bridge is loaded
        (js-await (inject-content-script tab-id "content-bridge.js"))
        ;; Wait for bridge to signal readiness via ping response
        (js-await (wait-for-bridge-ready tab-id))
        ;; Clear any old userscript tags (prevents re-execution on bfcache navigation)
        (js-await (send-tab-message tab-id {:type "clear-userscripts"}))
        ;; Inject all userscript tags
        (js-await
         (js/Promise.all
          (clj->js
           (map (fn [script]
                  (-> (send-tab-message tab-id {:type "inject-userscript"
                                                :id (str "userscript-" (:script/id script))
                                                :code (:script/code script)})
                      (.then (fn [_]
                               (js/console.log "[Userscript]" (:script/name script) "tag injected")))))
                scripts))))
        ;; Trigger Scittle to evaluate them
        (js-await (send-tab-message tab-id {:type "inject-script" :url trigger-url}))
        (js/console.log "[Userscript] Triggered Scittle evaluation"))
      (catch :default err
        (js/console.error "[Userscript] Injection error:" err)))))

(defn ws-fail-message []
  "WebSocket connection failed. Is the server running?")

(def set-nrepl-config-fn
  (js* "function(port) {
    window.SCITTLE_NREPL_WEBSOCKET_HOST = 'localhost';
    window.SCITTLE_NREPL_WEBSOCKET_PORT = port;
  }"))

(def check-status-fn
  (js* "function() {
    return {
      hasScittle: !!(window.scittle && window.scittle.core),
      hasScittleNrepl: !!(window.scittle && window.scittle.nrepl && window.scittle.nrepl.core),
      hasWsBridge: !!window.__browserJackInWSBridge,
      hasContentBridge: !!window.__browserJackInContentBridge
    };
  }"))

(def close-websocket-fn
  (js* "function() {
    var ws = window.ws_nrepl;
    if (ws) {
      if (ws.readyState === 0 || ws.readyState === 1) {
        ws.close();
      }
      window.ws_nrepl = null;
    }
  }"))

(def reconnect-nrepl-fn
  (js* "function(port) {
    // Create new WebSocket - will be intercepted by ws-bridge
    var ws = new WebSocket('ws://localhost:' + port + '/_nrepl');
  }"))

(def check-connection-fn
  (js* "function() {
    var ws = window.ws_nrepl;
    if (!ws) return {connected: false, state: -1};
    return {connected: ws.readyState === 1, state: ws.readyState};
  }"))

(defn find-tab-id-by-url-pattern!
  "Return the first tab id matching url-pattern, or nil if none match."
  [url-pattern]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.tabs.query
      #js {:url url-pattern}
      (fn [tabs]
        (cond
          js/chrome.runtime.lastError
          (reject (js/Error. (.-message js/chrome.runtime.lastError)))

          (pos? (.-length tabs))
          (resolve (.-id (aget tabs 0)))

          :else
          (resolve nil)))))))

(defn poll-until-connection
  "Poll for window.ws_nrepl to reach OPEN state."
  [tab-id timeout]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)]
       (letfn [(poll []
                 (-> (execute-in-page tab-id check-connection-fn)
                     (.then (fn [result]
                              (cond
                                (and result (.-connected result))
                                (resolve result)

                                (= 3 (.-state result))
                                (reject (js/Error. (ws-fail-message)))

                                (> (- (js/Date.now) start) timeout)
                                (reject (js/Error. "Timeout"))

                                :else
                                (js/setTimeout poll 100))))
                     (.catch reject)))]
         (poll))))))

(defn ^:async ensure-bridge!
  "Ensure the content bridge is injected and the ws-bridge is installed in the page."
  [tab-id status]
  (js-await (inject-content-script tab-id "content-bridge.js"))
  (when-not (and status (.-hasWsBridge status))
    (let [bridge-url (js/chrome.runtime.getURL "ws-bridge.js")]
      (js-await (execute-in-page tab-id inject-script-fn bridge-url false))))
  (js-await (wait-for-bridge-ready tab-id))
  true)

(defn ^:async ensure-scittle-nrepl!
  "Ensure scittle.nrepl is loaded and connected."
  [tab-id ws-port status]
  (let [nrepl-url (js/chrome.runtime.getURL "vendor/scittle.nrepl.js")]
    (js-await (execute-in-page tab-id close-websocket-fn))
    (js-await (execute-in-page tab-id set-nrepl-config-fn ws-port))
    (if (and status (.-hasScittleNrepl status))
      (js-await (execute-in-page tab-id reconnect-nrepl-fn ws-port))
      (js-await (execute-in-page tab-id inject-script-fn nrepl-url false)))
    (js-await (poll-until-connection tab-id 3000))
    true))

(defn ^:async connect-tab!
  "End-to-end connect flow for a specific tab.
   Ensures bridge + Scittle + scittle.nrepl and waits for connection."
  [tab-id ws-port]
  (when-not (and tab-id ws-port)
    (throw (js/Error. "connect-tab: Missing tab-id or ws-port")))
  (let [status (js-await (execute-in-page tab-id check-status-fn))]
    (js-await (ensure-bridge! tab-id status))
    (js-await (ensure-scittle! tab-id))
    (let [status2 (js-await (execute-in-page tab-id check-status-fn))]
      (js-await (ensure-scittle-nrepl! tab-id ws-port status2)))
    true))

(defn- js-arr->vec
  [arr]
  (if arr (vec arr) []))

(defn ^:async fetch-text!
  [url]
  (let [resp (js-await (js/fetch url))]
    (when-not (.-ok resp)
      (throw (js/Error. (str "Failed to fetch " url " (" (.-status resp) ")"))))
    (js-await (.text resp))))

(defn ^:async install-userscript!
  [script-name site-match script-code-urls]
  (when (or (nil? script-name) (nil? site-match))
    (throw (js/Error. "Missing scriptName or siteMatch")))
  (let [urls (cond
               (nil? script-code-urls) []
               (string? script-code-urls) [script-code-urls]
               :else (js-arr->vec script-code-urls))]
    (when-not (seq urls)
      (throw (js/Error. "Missing scriptCodeUrls")))
    (js-await (ensure-initialized!))
    (let [parts (js-await (js/Promise.all (clj->js (mapv fetch-text! urls))))
          code (.join parts "\n\n")
          id (str "script-" (js/Date.now))
          script {:script/id id
                  :script/name script-name
                  :script/match [site-match]
                  :script/code code
                  :script/enabled true
                  :script/approved-patterns []}]
      (storage/save-script! script))))

(.addListener js/chrome.runtime.onMessage
              (fn [message sender send-response]
                (let [tab-id (when (.-tab sender) (.. sender -tab -id))
                      msg-type (.-type message)]
                  (case msg-type
                    ;; Content script messages (from content-bridge.cljs)
                    "ws-connect" (do (handle-ws-connect tab-id (.-port message)) false)
                    "ws-send" (do (handle-ws-send tab-id (.-data message)) false)
                    ;; Note: "ws-close" is currently unused - page-side close() only cleans up
                    ;; locally, and reconnection is handled by ws-connect calling close-ws! first.
                    ;; Kept for potential future use if explicit close-from-page is needed.
                    "ws-close" (do (handle-ws-close tab-id) false)
                    "ping" false

                    ;; Popup messages
                    "refresh-approvals"
                    (do
                      ;; Reload scripts from storage, then sync pending + badge
                      ((^:async fn []
                         (js-await (storage/load!))
                         (sync-pending-approvals!)))
                      false)

                    "connect-tab"
                    (let [target-tab-id (.-tabId message)
                          ws-port (.-wsPort message)]
                      ((^:async fn []
                         (try
                           (js-await (connect-tab! target-tab-id ws-port))
                           (send-response #js {:success true})
                           (catch :default err
                             (send-response #js {:success false :error (.-message err)})))))
                      true)

                    "check-status"
                    (let [target-tab-id (.-tabId message)]
                      ((^:async fn []
                         (try
                           (let [status (js-await (execute-in-page target-tab-id check-status-fn))]
                             (send-response #js {:success true :status status}))
                           (catch :default err
                             (send-response #js {:success false :error (.-message err)})))))
                      true)

                    "e2e/find-tab-id"
                    (if (.-dev config)
                      (let [url-pattern (.-urlPattern message)]
                        ((^:async fn []
                           (try
                             (if url-pattern
                               (if-let [found-tab-id (js-await (find-tab-id-by-url-pattern! url-pattern))]
                                 (send-response #js {:success true :tabId found-tab-id})
                                 (send-response #js {:success false :error "No matching tab"}))
                               (send-response #js {:success false :error "Missing urlPattern"}))
                             (catch :default err
                               (send-response #js {:success false :error (.-message err)})))))
                        true)
                      (do
                        (send-response #js {:success false :error "Not available"})
                        false))

                    "pattern-approved"
                    ;; Clear this script from pending and execute it
                    (let [script-id (.-scriptId message)
                          pattern (.-pattern message)]
                      (clear-pending-approval! script-id pattern)
                      ;; Execute the script now
                      (when-let [script (storage/get-script script-id)]
                        ((^:async fn []
                           (when-let [active-tab-id (js-await (get-active-tab-id))]
                             (js-await (ensure-scittle! active-tab-id))
                             (js-await (execute-scripts! active-tab-id [script]))))))
                      false)

                    "install-userscript"
                    (let [script-name (.-scriptName message)
                          site-match (.-siteMatch message)
                          script-code-urls (.-scriptCodeUrls message)]
                      ((^:async fn []
                         (try
                           (let [saved (js-await (install-userscript! script-name site-match script-code-urls))]
                             (send-response #js {:success true
                                                 :scriptId (:script/id saved)
                                                 :scriptName (:script/name saved)}))
                           (catch :default err
                             (send-response #js {:success false :error (.-message err)})))))
                      true)

                    "install-from-gist"
                    ;; Manifest already parsed by gist installer using Scittle
                    ;; gistUrl is the raw URL to fetch the script from
                    ;; In Squint, keywords work as accessors on JS objects with string keys
                    (let [manifest (.-manifest message)
                          gist-url (.-gistUrl message)]
                      ((^:async fn []
                         (try
                           (let [script-name (:script-name manifest)
                                 site-match (:site-match manifest)
                                 saved (js-await (install-userscript! script-name site-match gist-url))]
                             (send-response #js {:success true
                                                 :scriptId (:script/id saved)
                                                 :scriptName (:script/name saved)}))
                           (catch :default err
                             (js/console.error "[Install] Install failed:" err)
                             (send-response #js {:success false :error (.-message err)})))))
                      true)

                    ;; Panel messages - ensure Scittle is loaded
                    "ensure-scittle"
                    (let [target-tab-id (.-tabId message)]
                      ((^:async fn []
                         (try
                           (js-await (ensure-scittle! target-tab-id))
                           (send-response #js {:success true})
                           (catch :default err
                             (send-response #js {:success false :error (.-message err)})))))
                      true)  ; Return true to indicate async response

                    ;; Unknown
                    (do (js/console.log "[Background] Unknown message type:" msg-type)
                        false)))))

(defn request-approval!
  "Add script to pending approvals (for popup display) and update badge.
   Uses script-id + pattern as key to prevent duplicates on page reload."
  [script pattern tab-id _url]
  (let [approval-id (str (:script/id script) "|" pattern)]
    ;; Only add if not already pending
    (when-not (get-in @!state [:pending/approvals approval-id])
      (swap! !state update :pending/approvals assoc approval-id
             {:approval/id approval-id
              :script/id (:script/id script)
              :script/name (:script/name script)
              :script/code (:script/code script)
              :approval/pattern pattern
              :approval/tab-id tab-id})
      (js/console.log "[Approval] Pending approval for" (:script/name script) "on pattern" pattern)))
  ;; Always update badge from source of truth
  (update-badge-for-tab! tab-id))

(defn ^:async process-navigation!
  "Process a navigation event after ensuring initialization is complete.
   Find matching scripts, check approvals, execute or prompt."
  [tab-id url]
  (let [scripts (url-matching/get-matching-scripts url)]
    (when (seq scripts)
      (js/console.log "[Auto-inject] Found" (count scripts) "scripts for" url)
      (let [script-contexts (map (fn [script]
                                   (let [pattern (url-matching/get-matching-pattern url script)]
                                     {:script script
                                      :pattern pattern
                                      :approved? (storage/pattern-approved? script pattern)}))
                                 scripts)
            approved (filter :approved? script-contexts)
            unapproved (remove :approved? script-contexts)]
        (when (seq approved)
          (js/console.log "[Auto-inject] Executing" (count approved) "approved scripts")
          (try
            (js-await (ensure-scittle! tab-id))
            (js-await (execute-scripts! tab-id (map :script approved)))
            (catch :default err
              (js/console.error "[Auto-inject] Failed:" (.-message err)))))
        (doseq [{:keys [script pattern]} unapproved]
          (js/console.log "[Auto-inject] Requesting approval for" (:script/name script))
          (request-approval! script pattern tab-id url))))))

(defn ^:async handle-navigation!
  "Handle page navigation by waiting for init then processing.
   Never drops navigation events - always waits for readiness."
  [tab-id url]
  (try
    (js-await (ensure-initialized!))
    (js-await (process-navigation! tab-id url))
    (catch :default err
      (js/console.error "[Auto-inject] Navigation handler error:" err))))

;; Clean up when tab is closed
(.addListener js/chrome.tabs.onRemoved
              (fn [tab-id _remove-info]
                (when (get-ws tab-id)
                  (js/console.log "[Background] Tab closed, cleaning up:" tab-id)
                  (close-ws! tab-id))))

(.addListener js/chrome.webNavigation.onCompleted
              (fn [details]
                ;; Only handle main frame (not iframes)
                (when (zero? (.-frameId details))
                  (handle-navigation! (.-tabId details) (.-url details)))))

;; ============================================================
;; Lifecycle Events
;; ============================================================

;; These ensure initialization happens on browser/extension lifecycle events.
;; The ensure-initialized! pattern guarantees we only init once even if
;; multiple events fire.

(.addListener js/chrome.runtime.onInstalled
              (fn [details]
                (js/console.log "[Background] onInstalled:" (.-reason details))
                (ensure-initialized!)))

(.addListener js/chrome.runtime.onStartup
              (fn []
                (js/console.log "[Background] onStartup")
                (ensure-initialized!)))

;; Start initialization immediately for service worker wake scenarios
(ensure-initialized!)

(js/console.log "[Scittle Tamper Background] Listeners registered")
