(ns background
  "Background service worker for WebSocket connections.
   Runs in extension context, immune to page CSP.
   Relays WebSocket messages to/from content scripts."
  (:require [storage :as storage]
            [url-matching :as url-matching]
            [script-utils :as script-utils]
            [registration :as registration]
            [manifest-parser :as manifest-parser]
            [test-logger :as test-logger]
            [background-utils :as bg-utils]
            [log :as log]))

(def ^:private config js/EXTENSION_CONFIG)

(log/info "Background" nil "Service worker started")

;; Install global error handlers early so we catch all errors in test mode
(test-logger/install-global-error-handlers! "background" js/self)

;; DEBUG: Write directly to storage at module load to confirm service worker is running
(.set js/chrome.storage.local #js {:bg-started true :ts (.now js/Date)})

;; ============================================================
;; Initialization Promise - single source of truth for readiness
;; ============================================================

;; Use a mutable variable (not defonce) so each script wake gets fresh state.
;; The init-promise ensures all operations wait for storage to load.
(declare prune-icon-states!)

(def !init-promise (atom nil))

(defn ^:async ensure-initialized!
  "Returns a promise that resolves when initialization is complete.
   Safe to call multiple times - only initializes once per script lifetime."
  []
  (if-let [p @!init-promise]
    (js-await p)
    (let [p (try
              ;; Set test-mode flag in storage for non-bundled scripts (userscript-loader.js)
              (js-await (test-logger/init-test-mode!))
              (js-await (storage/init!))
              ;; Ensure built-in userscripts are installed
              (js-await (storage/ensure-gist-installer!))
              ;; Sync content script registrations for early-timing scripts
              (js-await (registration/sync-registrations!))
              ;; Prune stale icon states from previous session
              (js-await (prune-icon-states!))
              (log/info "Background" nil "Initialization complete")
              ;; Log test event for E2E tests
              (js-await (test-logger/log-event! "EXTENSION_STARTED"
                                                {:version (.-version (.getManifest js/chrome.runtime))}))
              true
              (catch :default err
                (log/error "Background" nil "Initialization failed:" err)
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
(declare update-icon-for-tab!)

(def !state (atom {:ws/connections {}
                   :pending/approvals {}
                   :icon/states {}}))  ; tab-id -> :disconnected | :injected | :connected

(defn get-ws
  "Get WebSocket for a tab"
  [tab-id]
  (get-in @!state [:ws/connections tab-id :ws/socket]))

(defn close-ws!
  "Close and remove WebSocket for a tab. Does not send ws-close event."
  [tab-id]
  (when-let [ws (get-ws tab-id)]
    ;; Clear onclose to prevent sending ws-close when deliberately closing
    (set! (.-onclose ws) nil)
    (try
      (.close ws)
      (catch :default e
        (log/error "Background" "WS" "Error closing WebSocket:" e))))
  (swap! !state update :ws/connections dissoc tab-id))

(defn send-to-tab
  "Send message to content script in a tab.
   Returns the promise - callers should handle errors as appropriate."
  [tab-id message]
  (js/chrome.tabs.sendMessage tab-id (clj->js message)))

(defn handle-ws-connect
  "Create WebSocket connection for a tab.
   Closes existing connection for this tab, AND any other tab on the same port
   (browser-nrepl only supports one client per port)."
  [tab-id port]
  ;; Close existing connection for THIS tab
  (close-ws! tab-id)

  ;; Close any OTHER tab using the same port (browser-nrepl limitation)
  (when-let [other-tab-id (bg-utils/find-tab-on-port (:ws/connections @!state) port tab-id)]
    (log/info "Background" "WS" "Disconnecting tab" other-tab-id "- port" port "claimed by tab" tab-id)
    (close-ws! other-tab-id)
    ;; Update icon for the disconnected tab
    (update-icon-for-tab! other-tab-id :injected))

  ;; Fetch tab title for display
  (js/chrome.tabs.get
   tab-id
   (fn [tab]
     (let [tab-title (if js/chrome.runtime.lastError
                       "Unknown"
                       (or (.-title tab) "Unknown"))
           ws-url (str "ws://localhost:" port "/_nrepl")]
       (log/info "Background" "WS" "Connecting to:" ws-url "for tab:" tab-id)
       (try
         (let [ws (js/WebSocket. ws-url)]
           (swap! !state assoc-in [:ws/connections tab-id]
                  {:ws/socket ws
                   :ws/port port
                   :ws/tab-title tab-title})

           (set! (.-onopen ws)
                 (fn []
                   (log/info "Background" "WS" "Connected for tab:" tab-id)
                   (test-logger/log-event! "WS_CONNECTED" {:tab-id tab-id :port port})
                   (update-icon-for-tab! tab-id :connected)
                   (send-to-tab tab-id {:type "ws-open"})))

           (set! (.-onmessage ws)
                 (fn [event]
                   (send-to-tab tab-id {:type "ws-message"
                                        :data (.-data event)})))

           (set! (.-onerror ws)
                 (fn [error]
                   (log/error "Background" "WS" "Error for tab:" tab-id error)
                   (send-to-tab tab-id {:type "ws-error"
                                        :error (str "WebSocket error connecting to " ws-url)})))

           (set! (.-onclose ws)
                 (fn [event]
                   (log/info "Background" "WS" "Closed for tab:" tab-id)
                   (send-to-tab tab-id {:type "ws-close"
                                        :code (.-code event)
                                        :reason (.-reason event)})
                   (swap! !state update :ws/connections dissoc tab-id)
                   ;; When WS closes, go back to injected state (Scittle still loaded)
                   (update-icon-for-tab! tab-id :injected))))
         (catch :default e
           (log/error "Background" "WS" "Failed to create WebSocket:" e)
           (send-to-tab tab-id {:type "ws-error"
                                :error (str e)})))))))

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
  (bg-utils/count-pending-for-url url
                                  (storage/get-enabled-scripts)
                                  url-matching/get-matching-pattern
                                  storage/pattern-approved?))

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
;; Toolbar Icon State Management
;; ============================================================



(defn- get-icon-paths
  "Get icon paths for a given state. Delegates to tested pure function."
  [state]
  (bg-utils/get-icon-paths state))



(defn- compute-display-icon-state
  "Compute icon state to display based on:
   - Connected is GLOBAL: if ANY tab has REPL connected -> green
   - Injected is TAB-LOCAL: only if active-tab has Scittle -> yellow
   - Otherwise: disconnected (white)"
  [active-tab-id]
  (bg-utils/compute-display-icon-state (:icon/states @!state) active-tab-id))

(defn- ^:async update-icon-now!
  "Update the toolbar icon based on global (connected) and tab-local (injected) state.
   Takes the relevant tab-id to use for tab-local state checking."
  [relevant-tab-id]
  (let [display-state (compute-display-icon-state relevant-tab-id)]
    (js-await (test-logger/log-event! "ICON_STATE_CHANGED" {:tab-id relevant-tab-id :state display-state}))
    (js/chrome.action.setIcon
     #js {:path (get-icon-paths display-state)})))

(defn ^:async update-icon-for-tab!
  "Update icon state for a specific tab, then update the toolbar icon.
   Uses the given tab-id for tab-local state calculation."
  [tab-id state]
  (swap! !state assoc-in [:icon/states tab-id] state)
  (js-await (update-icon-now! tab-id)))



(defn get-icon-state
  "Get current icon state for a tab."
  [tab-id]
  (get-in @!state [:icon/states tab-id] :disconnected))

(defn clear-icon-state!
  "Clear icon state for a tab (when tab closes).
   Does NOT update the toolbar icon - that's handled by onActivated when
   the user switches to another tab."
  [tab-id]
  (swap! !state update :icon/states dissoc tab-id))

;; ============================================================
;; Auto-Injection: Run userscripts on page load
;; ============================================================

(defn ^:async prune-icon-states!
  "Remove icon states for tabs that no longer exist.
   Called on service worker wake to prevent memory leaks from orphaned entries
   when tabs close while the worker is asleep."
  []
  (let [tabs (js-await (js/chrome.tabs.query #js {}))
        valid-ids (set (map #(.-id %) tabs))]
    (swap! !state update :icon/states
           (fn [states]
             (select-keys states valid-ids)))))

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
     (log/info "Background" "Inject" "Injecting" file "into tab" tab-id)
     (js/chrome.scripting.executeScript
      #js {:target #js {:tabId tab-id}
           :files #js [file]}
      (fn [results]
        (log/info "Background" "Inject" "executeScript callback, results:" results "lastError:" js/chrome.runtime.lastError)
        (if js/chrome.runtime.lastError
          (do
            (log/error "Background" "Inject" "Error:" (.-message js/chrome.runtime.lastError))
            (reject (js/Error. (.-message js/chrome.runtime.lastError))))
          (do
            (log/info "Background" "Inject" "Success, results:" (js/JSON.stringify results))
            (resolve true))))))))

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
        console.warn('[Epupp] TrustedTypes policy creation failed, using direct assignment:', e.message);
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
                   5000))
        ;; Update icon to show Scittle is injected (yellow bolt)
        ;; Only if not already connected (green)
        (when (not= :connected (get-icon-state tab-id))
          (js-await (update-icon-for-tab! tab-id :injected)))
        ;; Log test event for E2E tests (after icon update so tests see stable state)
        (js-await (test-logger/log-event! "SCITTLE_LOADED" {:tab-id tab-id}))))
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
                        (log/info "Background" "Bridge" "Content bridge ready for tab:" tab-id)
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
  ;; Log test event at start for E2E tests
  (js-await (test-logger/log-event! "EXECUTE_SCRIPTS_START" {:tab-id tab-id :count (count scripts)}))
  (when (seq scripts)
    (try
      (let [trigger-url (js/chrome.runtime.getURL "trigger-scittle.js")]
        ;; First ensure content bridge is loaded
        (js-await (inject-content-script tab-id "content-bridge.js"))
        (js-await (test-logger/log-event! "BRIDGE_INJECTED" {:tab-id tab-id}))
        ;; Wait for bridge to signal readiness via ping response
        (js-await (wait-for-bridge-ready tab-id))
        (js-await (test-logger/log-event! "BRIDGE_READY_CONFIRMED" {:tab-id tab-id}))
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
                               ;; Log test event for E2E tests - return it so Promise.all waits
                               (test-logger/log-event! "SCRIPT_INJECTED"
                                                       {:script-id (:script/id script)
                                                        :script-name (:script/name script)
                                                        :timing (or (:script/run-at script) "document-idle")
                                                        :tab-id tab-id})))))
                scripts))))
        ;; Trigger Scittle to evaluate them
        (js-await (send-tab-message tab-id {:type "inject-script" :url trigger-url})))
      (catch :default err
        (log/error "Background" "Inject" "Userscript injection error:" err)
        (js-await (test-logger/log-event! "EXECUTE_SCRIPTS_ERROR" {:error (.-message err)}))))))

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

(defn ^:async get-auto-connect-settings
  "Get auto-connect REPL settings from storage.
   Returns {:enabled? boolean :ws-port string} or nil if disabled."
  []
  (js/Promise.
   (fn [resolve]
     (js/chrome.storage.local.get
      #js ["autoConnectRepl"]
      (fn [result]
        (let [enabled (.-autoConnectRepl result)]
          (resolve {:enabled? (boolean enabled)})))))))

(defn ^:async get-tab-hostname
  "Get hostname for a specific tab to look up its saved port."
  [tab-id]
  (js/Promise.
   (fn [resolve]
     (js/chrome.tabs.get
      tab-id
      (fn [tab]
        (if js/chrome.runtime.lastError
          (resolve "default")
          (try
            (resolve (.-hostname (js/URL. (.-url tab))))
            (catch :default _ (resolve "default")))))))))

(defn ^:async get-saved-ws-port
  "Get saved WebSocket port for a tab's hostname."
  [tab-id]
  (let [hostname (js-await (get-tab-hostname tab-id))
        key (str "ports_" hostname)]
    (js/Promise.
     (fn [resolve]
       (js/chrome.storage.local.get
        #js [key]
        (fn [result]
          (let [saved (aget result key)]
            (if (and saved (.-wsPort saved))
              (resolve (str (.-wsPort saved)))
              (resolve "1340"))))))))) ; default ws port

(defn ^:async fetch-text!
  [url]
  (let [resp (js-await (js/fetch url))]
    (when-not (.-ok resp)
      (throw (js/Error. (str "Failed to fetch " url " (" (.-status resp) ")"))))
    (js-await (.text resp))))

(defn- allowed-script-origins
  "Get the merged list of allowed origins from config and user storage"
  []
  (concat (or (.-allowedScriptOrigins config) [])
          (storage/get-user-allowed-origins)))

(defn- url-origin-allowed?
  "Check if a URL starts with any allowed origin prefix"
  [url]
  (bg-utils/url-origin-allowed? url (allowed-script-origins)))

(defn ^:async install-userscript!
  "Install a userscript from a URL. Validates that the URL is from an allowed origin.
   Name is normalized for uniqueness and valid filename format.
   Cannot overwrite built-in scripts.
   Extracts run-at timing from code manifest if present."
  [{:keys [script-name site-match script-url description]}]
  (when (or (nil? script-name) (nil? site-match))
    (throw (js/Error. "Missing scriptName or siteMatch")))
  (when (nil? script-url)
    (throw (js/Error. "Missing script URL")))
  (when-not (url-origin-allowed? script-url)
    (throw (js/Error. (str "Script URL not from allowed origin. Allowed: " (vec (allowed-script-origins))))))
  (js-await (ensure-initialized!))
  (let [code (js-await (fetch-text! script-url))
        normalized-name (script-utils/normalize-script-name script-name)
        ;; Extract run-at from code manifest (more reliable than passed manifest)
        code-manifest (try
                        (manifest-parser/extract-manifest code)
                        (catch :default _e nil))
        run-at (or (get code-manifest "run-at")
                   script-utils/default-run-at)
        ;; Check if a script with this ID already exists and is built-in
        existing-script (storage/get-script normalized-name)]
    (when (and existing-script (script-utils/builtin-script? existing-script))
      (throw (js/Error. (str "Cannot overwrite built-in script: " normalized-name))))
    (let [script (cond-> {:script/id normalized-name
                          :script/name normalized-name
                          :script/match [site-match]
                          :script/code code
                          :script/run-at run-at
                          :script/enabled true
                          :script/approved-patterns []}
                   (seq description) (assoc :script/description description))]
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

                    ;; Popup requests active connection info
                    "get-connections"
                    (let [connections (:ws/connections @!state)
                          display-list (bg-utils/connections->display-list connections)]
                      ;; Log for E2E test debugging
                      (test-logger/log-event! "GET_CONNECTIONS_RESPONSE"
                                              {:raw-connection-count (count (keys connections))
                                               :display-list-count (count display-list)
                                               :connections-keys (vec (keys connections))})
                      (send-response (clj->js {:success true
                                               :connections display-list}))
                      false)

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
                    ;; Manifest already parsed by the installer userscript using Scittle
                    ;; scriptUrl is the raw URL to fetch the script from
                    ;; In Squint, keywords work as accessors on JS objects with string keys
                    (let [manifest (.-manifest message)
                          script-url (.-scriptUrl message)]
                      ((^:async fn []
                         (try
                           (let [saved (js-await (install-userscript!
                                                  {:script-name (:script-name manifest)
                                                   :site-match (:site-match manifest)
                                                   :script-url script-url
                                                   :description (:description manifest)}))]
                             (send-response #js {:success true
                                                 :scriptId (:script/id saved)
                                                 :scriptName (:script/name saved)}))
                           (catch :default err
                             (log/error "Background" "Install" "Install failed:" err)
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

                    ;; Popup/Panel - evaluate a userscript in current tab
                    "evaluate-script"
                    (let [target-tab-id (.-tabId message)
                          code (.-code message)]
                      ((^:async fn []
                         (try
                           (js-await (ensure-scittle! target-tab-id))
                           (js-await (execute-scripts! target-tab-id [{:script/id (.-scriptId message)
                                                                       :script/name "popup-eval"
                                                                       :script/code code}]))
                           (send-response #js {:success true})
                           (catch :default err
                             (send-response #js {:success false :error (.-message err)})))))
                      true)  ; Return true to indicate async response

                    ;; Unknown
                    (do (log/info "Background" nil "Unknown message type:" msg-type)
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
      (log/info "Background" "Approval" "Pending approval for" (:script/name script) "on pattern" pattern)))
  ;; Always update badge from source of truth
  (update-badge-for-tab! tab-id))

(defn ^:async process-navigation!
  "Process a navigation event after ensuring initialization is complete.
   Find matching scripts, check approvals, execute or prompt.
   Only processes document-idle scripts - early-timing scripts are handled
   by registered content scripts (see registration.cljs)."
  [tab-id url]
  (let [all-scripts (url-matching/get-matching-scripts url)
        ;; Filter to only document-idle scripts (default for scripts without run-at)
        ;; Early-timing scripts (document-start, document-end) are handled by
        ;; registerContentScripts and should not be injected again here.
        idle-scripts (filter #(= "document-idle"
                                 (or (:script/run-at %) "document-idle"))
                             all-scripts)]
    ;; Log for E2E debugging
    (js-await (test-logger/log-event! "NAVIGATION_PROCESSED"
                                      {:url url
                                       :all-scripts-count (count all-scripts)
                                       :idle-scripts-count (count idle-scripts)}))
    (when (seq idle-scripts)
      (log/info "Background" "Inject" "Found" (count idle-scripts) "document-idle scripts for" url)
      (let [script-contexts (map (fn [script]
                                   (let [pattern (url-matching/get-matching-pattern url script)]
                                     {:script script
                                      :pattern pattern
                                      :approved? (storage/pattern-approved? script pattern)}))
                                 idle-scripts)
            approved (filter :approved? script-contexts)
            unapproved (remove :approved? script-contexts)]
        ;; Log approval status for E2E debugging
        (js-await (test-logger/log-event! "SCRIPTS_APPROVAL_STATUS"
                                          {:approved-count (count approved)
                                           :unapproved-count (count unapproved)
                                           :scripts (mapv #(-> %
                                                               :script
                                                               (select-keys [:script/name :script/approved-patterns]))
                                                          script-contexts)}))
        (when (seq approved)
          (log/info "Background" "Inject" "Executing" (count approved) "approved scripts")
          (js-await (test-logger/log-event! "AUTO_INJECT_START" {:count (count approved)}))
          (try
            (js-await (ensure-scittle! tab-id))
            (js-await (execute-scripts! tab-id (map :script approved)))
            (catch :default err
              (log/error "Background" "Inject" "Failed:" (.-message err))
              (js-await (test-logger/log-event! "AUTO_INJECT_ERROR" {:error (.-message err)})))))
        (doseq [{:keys [script pattern]} unapproved]
          (log/info "Background" "Approval" "Requesting approval for" (:script/name script))
          (request-approval! script pattern tab-id url))))))

(defn ^:async handle-navigation!
  "Handle page navigation by waiting for init then processing.
   Never drops navigation events - always waits for readiness.
   If auto-connect REPL is enabled, also connects the Scittle nREPL."
  [tab-id url]
  (try
    ;; Log test event for E2E debugging
    (js-await (test-logger/log-event! "NAVIGATION_STARTED" {:tab-id tab-id :url url}))
    (js-await (ensure-initialized!))

    ;; Reset icon state on navigation (Scittle is not injected on new page)
    (js-await (update-icon-for-tab! tab-id :disconnected))

    ;; Check auto-connect setting
    (let [{:keys [enabled?]} (js-await (get-auto-connect-settings))]
      (when enabled?
        (log/info "Background" "AutoConnect" "REPL auto-connect enabled, connecting to tab:" tab-id)
        (let [ws-port (js-await (get-saved-ws-port tab-id))]
          (try
            (js-await (connect-tab! tab-id ws-port))
            (log/info "Background" "AutoConnect" "Successfully connected REPL to tab:" tab-id)
            (catch :default err
              (log/warn "Background" "AutoConnect" "Failed to connect REPL:" (.-message err)))))))

    ;; Continue with normal userscript processing
    (js-await (process-navigation! tab-id url))
    (catch :default err
      (log/error "Background" "Inject" "Navigation handler error:" err))))

;; Clean up when tab is closed
(.addListener js/chrome.tabs.onRemoved
              (fn [tab-id _remove-info]
                (log/info "Background" nil "Tab closed, cleaning up:" tab-id)
                (when (get-ws tab-id)
                  (close-ws! tab-id))
                (clear-icon-state! tab-id)))

(.addListener js/chrome.tabs.onActivated
              (fn [active-info]
                (let [tab-id (.-tabId active-info)]
                  ;; Query the tab to check if it's an extension page or empty/new tab
                  (-> (js/chrome.tabs.get tab-id)
                      (.then (fn [tab]
                               (let [url (or (.-url tab) "")]
                                 ;; Only update icon for real web pages
                                 ;; Skip: extension pages, empty tabs, about:blank
                                 (when (and (seq url)
                                            (not (.startsWith url "chrome-extension://"))
                                            (not (.startsWith url "about:")))
                                   (update-icon-now! tab-id)))))
                      (.catch (fn [_err]
                                ;; Tab might be gone, ignore
                                nil))))))

(.addListener js/chrome.webNavigation.onCompleted
              (fn [details]
                ;; Only handle main frame (not iframes)
                ;; Skip extension pages, about:blank, etc.
                (let [url (.-url details)]
                  (when (and (zero? (.-frameId details))
                             (or (.startsWith url "http://")
                                 (.startsWith url "https://")))
                    (handle-navigation! (.-tabId details) url)))))

;; ============================================================
;; Lifecycle Events
;; ============================================================

;; These ensure initialization happens on browser/extension lifecycle events.
;; The ensure-initialized! pattern guarantees we only init once even if
;; multiple events fire.

;; Sync registrations when scripts change
(.addListener js/chrome.storage.onChanged
              (fn [changes area]
                (when (and (= area "local") (.-scripts changes))
                  (log/info "Background" nil "Scripts changed, syncing registrations")
                  ((^:async fn []
                     (js-await (ensure-initialized!))
                     (js-await (registration/sync-registrations!)))))))

(.addListener js/chrome.runtime.onInstalled
              (fn [details]
                (log/info "Background" nil "onInstalled:" (.-reason details))
                (ensure-initialized!)))

(.addListener js/chrome.runtime.onStartup
              (fn []
                (log/info "Background" nil "onStartup")
                (ensure-initialized!)))

;; Start initialization immediately for service worker wake scenarios
(ensure-initialized!)

(log/info "Background" nil "Listeners registered")
