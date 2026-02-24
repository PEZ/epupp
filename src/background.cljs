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
            [log :as log]
            [event-handler :as event-handler]
            [background-actions :as bg-actions]
            [bg-fs-dispatch :as fs-dispatch]
            [bg-icon :as bg-icon]
            [bg-ws :as bg-ws]
            [bg-inject :as bg-inject]))

(def ^:private config js/EXTENSION_CONFIG)

;; Note: Use def (not defonce) for state that should reset on script wake.
;; WebSocket connections don't survive script termination anyway.

(def !state (atom {:init/promise nil
                   :ws/connections {}
                   :icon/states {}
                   :connected-tabs/history {}
                   :fs/sync-tab-id nil}))  ; tab-id -> {:port ws-port} - tracks intentionally connected tabs

;; Ephemeral tracking - NOT Uniflow state. Tracks which tabs have had the
;; web installer injected to avoid redundant re-injection.
(def ^:private !installer-injected-tabs (atom #{}))

;; ============================================================
;; Initialization Promise - single source of truth for readiness
;; ============================================================

;; Use a mutable variable (not defonce) so each script wake gets fresh state.
;; The :init/promise key ensures all operations wait for storage to load.

(defn ^:async ensure-initialized!
  "Returns a promise that resolves when initialization is complete.
   Safe to call multiple times - only initializes once per script lifetime.
   Works by dispatching an action that handles both 'already initialized'
   and 'needs initialization' cases. dispatch! returns a promise via
   the :uf/await effect chain."
  [dispatch!]
  (js-await (dispatch! [[:init/ax.ensure-initialized]])))



;; ============================================================
;; Auto-Injection: Run userscripts on page load
;; ============================================================
;; Injection functions extracted to bg-inject module

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

;; Page-context function for injecting scripts (used for ws-bridge.js)
(def inject-script-fn
  (js* "function(url, isModule) {
    var script = document.createElement('script');
    if (isModule) script.type = 'module';
    if (window.trustedTypes && window.trustedTypes.createPolicy) {
      try {
        var policy = window.trustedTypes.defaultPolicy;
        if (!policy) {
          policy = window.trustedTypes.createPolicy('default', {
            createHTML: function(s) { return s; },
            createScript: function(s) { return s; },
            createScriptURL: function(s) { return s; }
          });
        }
        script.src = policy.createScriptURL(url);
      } catch(e) {
        console.warn('[Epupp] TrustedTypes policy creation failed, using direct assignment:', e.message);
        script.src = url;
      }
    } else {
      script.src = url;
    }
    document.head.appendChild(script);
    return 'ok';
  }"))

(def ^:private epupp-api-files
  [{:id "epupp-repl"
    :path "bundled/epupp/repl.cljs"}
   {:id "epupp-fs"
    :path "bundled/epupp/fs.cljs"}])

(defn ^:async fetch-text!
  [url]
  (let [resp (js-await (js/fetch url))]
    (when-not (.-ok resp)
      (throw (js/Error. (str "Failed to fetch " url " (" (.-status resp) ")"))))
    (js-await (.text resp))))

(def close-websocket-fn
  (js* "function() {
    var ws = window.ws_nrepl;
    if (ws) {
      window.__savedNreplOnMessage = ws.onmessage || null;
      if (ws.readyState === 0 || ws.readyState === 1) {
        ws.close();
      }
      window.ws_nrepl = null;
    }
  }"))

(def reconnect-nrepl-fn
  (js* "function(port) {
    // Create new WebSocket - will be intercepted by ws-bridge
    new WebSocket('ws://localhost:' + port + '/_nrepl');
    // Restore Scittle's onmessage handler onto the new proxy
    if (window.__savedNreplOnMessage && window.ws_nrepl) {
      window.ws_nrepl.onmessage = window.__savedNreplOnMessage;
    }
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
                 (-> (bg-inject/execute-in-page tab-id check-connection-fn)
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
  (when-not (and status (.-hasContentBridge status))
    (js-await (bg-inject/inject-content-script tab-id "content-bridge.js")))
  (when-not (and status (.-hasWsBridge status))
    (let [bridge-url (js/chrome.runtime.getURL "ws-bridge.js")]
      (js-await (bg-inject/execute-in-page tab-id inject-script-fn bridge-url false))))
  (js-await (bg-inject/wait-for-bridge-ready tab-id))
  true)

(defn ^:async ensure-scittle-nrepl!
  "Ensure scittle.nrepl is loaded and connected."
  [tab-id ws-port status]
  (let [nrepl-url (js/chrome.runtime.getURL "vendor/scittle.nrepl.js")]
    (js-await (bg-inject/execute-in-page tab-id close-websocket-fn))
    (js-await (bg-inject/execute-in-page tab-id set-nrepl-config-fn ws-port))
    (if (and status (.-hasScittleNrepl status))
      (js-await (bg-inject/execute-in-page tab-id reconnect-nrepl-fn ws-port))
      (js-await (bg-inject/execute-in-page tab-id inject-script-fn nrepl-url false)))
    (js-await (poll-until-connection tab-id 3000))
    true))

(defn ^:async inject-epupp-api!
  "Inject Epupp REPL API namespaces from bundled Scittle source files."
  [tab-id]
  (try
    (let [trigger-url (js/chrome.runtime.getURL "trigger-scittle.js")]
      (loop [remaining epupp-api-files]
        (when (seq remaining)
          (let [{:keys [id path]} (first remaining)
                url (js/chrome.runtime.getURL path)
                code (js-await (fetch-text! url))]
            (js-await (bg-inject/send-tab-message tab-id {:type "inject-userscript"
                                                :id id
                                                :code code}))
            (recur (rest remaining)))))
      (js-await (bg-inject/send-tab-message tab-id {:type "inject-script" :url trigger-url}))
      (js-await (test-logger/log-event! "EPUPP_API_INJECTED"
                                        {:tab-id tab-id
                                         :files (vec (map :path epupp-api-files))}))
      (log/info "Background:REPL" "Injected Epupp API into tab:" tab-id)
      true)
    (catch :default err
      (log/error "Background:REPL" "Failed to inject Epupp API:" err)
      (js-await (test-logger/log-event! "EPUPP_API_INJECT_ERROR"
                                        {:tab-id tab-id
                                         :error (.-message err)}))
      false)))

(defn ^:async connect-tab!
  "End-to-end connect flow for a specific tab.
   Ensures bridge + Scittle + scittle.nrepl and waits for connection.
   Also injects the Epupp API for manifest! support."
  [dispatch! tab-id ws-port icon-state]
  (when-not (and tab-id ws-port)
    (throw (js/Error. "connect-tab: Missing tab-id or ws-port")))
  (let [status (js-await (bg-inject/execute-in-page tab-id check-status-fn))]
    (js-await (ensure-bridge! tab-id status))
    (js-await (bg-inject/ensure-scittle! dispatch! tab-id icon-state))
    (let [status2 (js-await (bg-inject/execute-in-page tab-id check-status-fn))]
      (js-await (ensure-scittle-nrepl! tab-id ws-port status2)))
    ;; Inject Epupp API for manifest! support
    (js-await (inject-epupp-api! tab-id))
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

(defn ^:async get-auto-reconnect-setting
  "Get auto-reconnect REPL setting from storage.
   Returns true if enabled (defaults to true if not set)."
  []
  (js/Promise.
   (fn [resolve]
     (js/chrome.storage.local.get
      #js ["autoReconnectRepl"]
      (fn [result]
        (let [value (.-autoReconnectRepl result)]
          ;; Default to true if not set
          (resolve (if (some? value) value true))))))))

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
  "Get saved WebSocket port for a tab's hostname.
   Falls back to user-configured default port, then to 1340."
  [tab-id]
  (let [hostname (js-await (get-tab-hostname tab-id))
        key (str "ports_" hostname)]
    (js/Promise.
     (fn [resolve]
       (js/chrome.storage.local.get
        #js [key "defaultWsPort"]
        (fn [result]
          (let [saved (aget result key)]
            (if (and saved (.-wsPort saved))
              (resolve (str (.-wsPort saved)))
              (let [default-port (aget result "defaultWsPort")]
                (resolve (str (or default-port "1340"))))))))))))
 ; default ws port

(defn ^:async process-navigation!
  "Process a navigation event after ensuring initialization is complete.
   Find matching scripts and execute them.
   Only processes document-idle scripts - early-timing scripts are handled
   by registered content scripts (see registration.cljs)."
  [dispatch! tab-id url icon-state]
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
      (log/debug "Background:Inject" "Found" (count idle-scripts) "document-idle scripts for" url)
      (log/debug "Background:Inject" "Executing" (count idle-scripts) "scripts")
      (js-await (test-logger/log-event! "AUTO_INJECT_START" {:count (count idle-scripts)}))
      (when (some #(= bg-utils/sponsor-script-id (:script/id %)) idle-scripts)
        (dispatch! [[:sponsor/ax.set-pending tab-id]]))
      (try
        (js-await (bg-inject/ensure-scittle! dispatch! tab-id icon-state))
        (js-await (bg-inject/execute-scripts! tab-id idle-scripts))
        (catch :default err
          (log/error "Background:Inject" "Failed:" (.-message err))
          (js-await (test-logger/log-event! "AUTO_INJECT_ERROR" {:error (.-message err)})))))))

(defn- handle-ws-connect [message tab-id dispatch!]
  (dispatch! [[:ws/ax.handle-connect tab-id (.-port message)]])
  false)

(defn- handle-ws-send [message tab-id dispatch!]
  (dispatch! [[:ws/ax.handle-send tab-id (.-data message)]])
  false)

(defn- handle-ws-close [tab-id dispatch!]
  (dispatch! [[:ws/ax.handle-close tab-id]])
  false)

(defn- handle-list-scripts [message tab-id dispatch! send-response]
  (let [include-hidden? (.-lsHidden message)]
    (dispatch! [[:fs/ax.guard-list-scripts tab-id send-response include-hidden?]])
    true))

(defn- handle-save-script [message tab-id dispatch! send-response]
  (let [script-source (.-scriptSource message)
        web-install? (and script-source
                          (or (.startsWith script-source "http://")
                              (.startsWith script-source "https://")))
        raw-data {:code (.-code message)
                  :enabled (if (some? (.-enabled message)) (.-enabled message) true)
                  :force? (.-force message)
                  :bulk-id (.-bulkId message)
                  :bulk-index (.-bulkIndex message)
                  :bulk-count (.-bulkCount message)
                  :script-source script-source}]
    (dispatch! [[:fs/ax.guard-save-script tab-id send-response raw-data web-install?]])
    true))

(defn- handle-panel-save-script [message send-response]
  (let [js-script (.-script message)
        script (cond-> {:script/name (.-name js-script)
                        :script/description (.-description js-script)
                        :script/match (vec (.-match js-script))
                        :script/code (.-code js-script)
                        :script/enabled (.-enabled js-script)
                        :script/run-at (script-utils/normalize-run-at (.-runAt js-script))
                        :script/inject (if (.-inject js-script)
                                         (vec (.-inject js-script))
                                         [])}
                 (.-id js-script) (assoc :script/id (.-id js-script))
                 (.-force js-script) (assoc :script/force? true))]
    (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.save-script script]))
  false)

(defn- handle-panel-rename-script [message send-response]
  (let [from-name (.-from message)
        to-name (.-to message)]
    (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.rename-script from-name to-name]))
  false)

(defn- handle-rename-script [message tab-id dispatch! send-response]
  (let [from-name (.-from message)
        to-name (.-to message)]
    (dispatch! [[:fs/ax.guard-rename-script tab-id send-response from-name to-name]])
    true))

(defn- handle-delete-script [message tab-id dispatch! send-response]
  (let [script-name (.-name message)
        bulk-id (.-bulkId message)
        bulk-index (.-bulkIndex message)
        bulk-count (.-bulkCount message)]
    (dispatch! [[:fs/ax.guard-delete-script tab-id send-response
                 {:script-name script-name
                  :bulk-id bulk-id
                  :bulk-index bulk-index
                  :bulk-count bulk-count}]])
    true))

(defn- handle-get-script [message tab-id dispatch! send-response]
  (let [script-name (.-name message)]
    (dispatch! [[:fs/ax.guard-get-script tab-id send-response script-name]])
    true))

(defn- handle-load-manifest [message tab-id dispatch! send-response]
  (let [manifest (.-manifest message)]
    (dispatch! [[:msg/ax.load-manifest send-response tab-id manifest]])
    true))

(defn- handle-get-connections [dispatch! send-response]
  (dispatch! [[:msg/ax.get-connections send-response]])
  false)

(defn- handle-connect-tab [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)
        ws-port (.-wsPort message)]
    (dispatch! [[:msg/ax.connect-tab send-response target-tab-id ws-port]])
    true))

(defn- handle-check-status [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.check-status send-response target-tab-id]])
    true))

(defn- handle-disconnect-tab [message dispatch!]
  (let [target-tab-id (.-tabId message)]
    (dispatch! [[:ws/ax.handle-close target-tab-id]]))
  false)

(defn- handle-e2e-find-tab-id [message dispatch! send-response]
  (let [url-pattern (.-urlPattern message)]
    (dispatch! [[:msg/ax.e2e-find-tab-id send-response url-pattern]])
    true))

(defn- handle-e2e-get-test-events [dispatch! send-response]
  (dispatch! [[:msg/ax.e2e-get-test-events send-response]])
  true)

(defn- handle-e2e-get-storage [message dispatch! send-response]
  (let [key (.-key message)]
    (dispatch! [[:msg/ax.e2e-get-storage send-response key]])
    true))

(defn- handle-e2e-set-storage [message dispatch! send-response]
  (let [key (.-key message)
        value (.-value message)]
    (dispatch! [[:msg/ax.e2e-set-storage send-response key value]])
    true))

(defn- handle-e2e-activate-tab [message send-response]
  (let [tab-id (.-tabId message)]
    (js/chrome.tabs.update tab-id #js {:active true}
                           (fn [tab]
                             (if js/chrome.runtime.lastError
                               (send-response #js {:success false :error (.-message js/chrome.runtime.lastError)})
                               (js/chrome.windows.update (.-windowId tab) #js {:focused true}
                                                         (fn [_win]
                                                           (if js/chrome.runtime.lastError
                                                             (send-response #js {:success false :error (.-message js/chrome.runtime.lastError)})
                                                             (send-response #js {:success true :tabId tab-id})))))))
    true))

(defn- handle-e2e-update-icon [message dispatch! send-response]
  (let [tab-id (.-tabId message)]
    ((^:async fn []
       (try
         (js-await (dispatch! [[:icon/ax.refresh-toolbar tab-id]]))
         (send-response #js {:success true :tabId tab-id})
         (catch :default err
           (send-response #js {:success false :error (.-message err)})))))
    true))

(defn- handle-e2e-get-icon-display-state [message dispatch! send-response]
  (let [tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.e2e-get-icon-display-state send-response tab-id]])
    true))

(defn- handle-e2e-ensure-builtin [dispatch! send-response]
  ((^:async fn []
     (try
       (js-await (ensure-initialized! dispatch!))
       (js-await (storage/sync-builtin-scripts!))
       (send-response #js {:success true})
       (catch :default err
         (send-response #js {:success false :error (.-message err)})))))
  true)

(defn- handle-ensure-scittle [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.ensure-scittle send-response target-tab-id]])
    true))

(defn- handle-inject-libs [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)
        libs (when (.-libs message)
               (vec (.-libs message)))]
    (dispatch! [[:msg/ax.inject-libs send-response target-tab-id libs]])
    true))

(defn- handle-evaluate-script [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)
        code (.-code message)
        libs (when (.-inject message)
               (vec (.-inject message)))]
    (dispatch! [[:msg/ax.evaluate-script send-response target-tab-id code libs (.-scriptId message)]])
    true))

(defn- handle-sponsor-status [_message sender dispatch! send-response]
  (let [tab-id (when (.-tab sender) (.. sender -tab -id))
        tab-url (when (.-tab sender) (.. sender -tab -url))]
    (dispatch! [[:sponsor/ax.consume-pending tab-id tab-url send-response]]))
  true)



(defn- ^:async update-sponsor-script-match!
  "Rewrite the sponsor script's auto-run-match URL to use the given username.
   This updates the source of truth (the code itself), so derive-script-fields
   naturally picks up the new match pattern on save."
  [username]
  (let [sponsor-script (storage/get-script "epupp-builtin-sponsor-check")]
    (when sponsor-script
      (let [old-code (:script/code sponsor-script)
            new-code (.replace old-code
                               (js/RegExp. "https://github\\.com/sponsors/[^\"*]+" "g")
                               (str "https://github.com/sponsors/" username))
            updated (assoc sponsor-script :script/code new-code)]
        (when (not= old-code new-code)
          (js-await (storage/save-script! updated))
          (log/info "Background" "Updated sponsor script match to:" username))))))
(defn- handle-get-sponsored-username [_message send-response]
  ((^:async fn []
     (let [storage-result (js-await (js/chrome.storage.local.get #js ["sponsor/sponsored-username"]))
           username (or (aget storage-result "sponsor/sponsored-username") "PEZ")]
       (send-response #js {:success true :username username}))))
  true)
(defn- handle-check-script-exists [message _dispatch! send-response]
  (let [script-name (.-name message)
        code (.-code message)
        script (storage/get-script-by-name script-name)]
    (if script
      (send-response #js {:success true
                          :exists true
                          :identical (= code (:script/code script))})
      (send-response #js {:success true
                          :exists false}))
    false))

(defn- handle-web-installer-save-script [message sender _dispatch! send-response]
  (if-not (bg-utils/web-installer-origin-allowed? sender)
    (do (send-response #js {:success false :error "Domain not allowed for web installation"})
        false)
    (try
      (let [code (.-code message)
            manifest (manifest-parser/extract-manifest code)
            raw-name (or (when manifest (aget manifest "raw-script-name"))
                         (when manifest (aget manifest "script-name")))]
        (if-not raw-name
          (do (send-response #js {:success false :error "No script name in manifest"})
              false)
          (let [auto-run-match (when manifest (aget manifest "auto-run-match"))
                injects (when manifest (aget manifest "inject"))
                run-at (when manifest (aget manifest "run-at"))
                script-source (.. sender -tab -url)
                script-id (str (.now js/Date))
                script {:script/id script-id
                        :script/name raw-name
                        :script/code code
                        :script/match (if (vector? auto-run-match)
                                        auto-run-match
                                        [auto-run-match])
                        :script/inject (or injects [])
                        :script/enabled true
                        :script/run-at (or run-at "document-idle")
                        :script/force? true
                        :script/source script-source}]
            (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.save-script script])
            false)))
      (catch :default err
        (send-response #js {:success false :error (str "Parse error: " (.-message err))})
        false))))

(defn- handle-unknown-message [msg-type]
  (log/debug "Background" "Unknown message type:" msg-type)
  false)

(defn- add-on-message-handler [dispatch!]
  (.addListener js/chrome.runtime.onMessage
                (fn [message sender send-response]
                  (let [tab-id (when (.-tab sender) (.. sender -tab -id))
                        msg-type (.-type message)]
                    (case msg-type
                      "ws-connect" (handle-ws-connect message tab-id dispatch!)
                      "ws-send" (handle-ws-send message tab-id dispatch!)
                      "ws-close" (handle-ws-close tab-id dispatch!)
                      "ping" false
                      "list-scripts" (handle-list-scripts message tab-id dispatch! send-response)
                      "save-script" (handle-save-script message tab-id dispatch! send-response)
                      "panel-save-script" (handle-panel-save-script message send-response)
                      "panel-rename-script" (handle-panel-rename-script message send-response)
                      "rename-script" (handle-rename-script message tab-id dispatch! send-response)
                      "delete-script" (handle-delete-script message tab-id dispatch! send-response)
                      "get-script" (handle-get-script message tab-id dispatch! send-response)
                      "check-script-exists" (handle-check-script-exists message dispatch! send-response)
                      "web-installer-save-script" (handle-web-installer-save-script message sender dispatch! send-response)
                      "load-manifest" (handle-load-manifest message tab-id dispatch! send-response)
                      "get-connections" (handle-get-connections dispatch! send-response)
                      "connect-tab" (handle-connect-tab message dispatch! send-response)
                      "check-status" (handle-check-status message dispatch! send-response)
                      "disconnect-tab" (handle-disconnect-tab message dispatch!)
                      "toggle-fs-sync"
                      (do (dispatch! [[:fs/ax.toggle-sync (.-tabId message) (.-enabled message) send-response]])
                          true)
                      "get-fs-sync-status"
                      (do (dispatch! [[:fs/ax.get-sync-status send-response]])
                          true)
                      "e2e/find-tab-id" (if (.-dev config)
                                          (handle-e2e-find-tab-id message dispatch! send-response)
                                          (do (send-response #js {:success false :error "Not available"})
                                              false))
                      "e2e/get-test-events" (if (.-dev config)
                                              (handle-e2e-get-test-events dispatch! send-response)
                                              (do (send-response #js {:success false :error "Not available"})
                                                  false))
                      "e2e/get-storage" (if (.-dev config)
                                          (handle-e2e-get-storage message dispatch! send-response)
                                          (do (send-response #js {:success false :error "Not available"})
                                              false))
                      "e2e/set-storage" (if (.-dev config)
                                          (handle-e2e-set-storage message dispatch! send-response)
                                          (do (send-response #js {:success false :error "Not available"})
                                              false))
                      "e2e/activate-tab" (if (.-dev config)
                                           (handle-e2e-activate-tab message send-response)
                                           (do (send-response #js {:success false :error "Not available"})
                                               false))
                      "e2e/update-icon" (if (.-dev config)
                                          (handle-e2e-update-icon message dispatch! send-response)
                                          (do (send-response #js {:success false :error "Not available"})
                                              false))
                      "e2e/get-icon-display-state" (if (.-dev config)
                                                     (handle-e2e-get-icon-display-state message dispatch! send-response)
                                                     (do (send-response #js {:success false :error "Not available"})
                                                         false))
                      "e2e/ensure-builtin" (if (.-dev config)
                                             (handle-e2e-ensure-builtin dispatch! send-response)
                                             (do (send-response #js {:success false :error "Not available"})
                                                 false))
                      "ensure-scittle" (handle-ensure-scittle message dispatch! send-response)
                      "inject-libs" (handle-inject-libs message dispatch! send-response)
                      "evaluate-script" (handle-evaluate-script message dispatch! send-response)
                      "sponsor-status" (handle-sponsor-status message sender dispatch! send-response)
                      "get-sponsored-username" (handle-get-sponsored-username message send-response)
                      (handle-unknown-message msg-type))))))

(defn- ^:async maybe-inject-installer!
  "Scan a tab for userscript blocks and inject the installer if found.
   Only scans on whitelisted origins. Skips if installer is disabled or already
   injected on this tab. Uses bounded retry delays to catch DOM elements that
   appear after page load (e.g. GitLab .file-holder elements)."
  [dispatch! tab-id url]
  (try
    (when (bg-utils/should-scan-for-installer? url @!installer-injected-tabs tab-id)
      (js-await (ensure-initialized! dispatch!))
      (let [installer (storage/get-script-by-name "epupp/web_userscript_installer.cljs")]
        (when (and installer (:script/enabled installer))
          (let [delays bg-utils/installer-scan-delays
                found? (loop [remaining delays]
                         (when (seq remaining)
                           (let [delay-ms (first remaining)]
                             (when (pos? delay-ms)
                               (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve delay-ms)))))
                             (let [result (js-await (bg-inject/execute-in-isolated tab-id bg-inject/scan-for-userscripts-fn))]
                               (if (and result (.-found result))
                                 true
                                 (recur (rest remaining)))))))]
            (when found?
              (js-await (bg-inject/inject-installer! dispatch! tab-id installer))
              (swap! !installer-injected-tabs conj tab-id))))))
    (catch :default err
      (log/warn "Background" "Installer scan failed for tab" tab-id ":" (.-message err)))))

(defn- ^:async activate!
  [dispatch!]

  (log/info "Background" "Service worker started")

  ;; Install global error handlers early so we catch all errors in test mode
  (test-logger/install-global-error-handlers! "background" js/self)

  ;; Prune stale icon states from previous session on service worker wake
  (js-await (bg-icon/prune-icon-states! dispatch!))

  ;; ============================================================
  ;; Message Handlers
  ;; ============================================================

  (add-on-message-handler dispatch!)

  ;; Clean up when tab is closed
  (.addListener js/chrome.tabs.onRemoved
                (fn [tab-id _remove-info]
                  (log/debug "Background" "Tab closed, cleaning up:" tab-id)
                  (swap! !installer-injected-tabs disj tab-id)
                  (dispatch! [[:tab/ax.handle-removed tab-id]])))

  (.addListener js/chrome.tabs.onActivated
                (fn [active-info]
                  (let [tab-id (.-tabId active-info)]
                    (-> (js/chrome.tabs.get tab-id)
                        (.then (fn [tab]
                                 (let [url (or (.-url tab) "")]
                                   (when (and (seq url)
                                              (not (.startsWith url "chrome-extension://"))
                                              (not (.startsWith url "about:")))
                                     (dispatch! [[:icon/ax.refresh-toolbar tab-id]])))))
                        (.catch (fn [_] nil))))))

  ;; Close WebSocket when page starts navigating (reload, navigation to new URL)
  ;; This ensures the connection list updates immediately when a page reloads
  (.addListener js/chrome.webNavigation.onBeforeNavigate
                (fn [details]
                  (when (zero? (.-frameId details))
                    (let [tab-id (.-tabId details)]
                      (swap! !installer-injected-tabs disj tab-id)
                      (dispatch! [[:nav/ax.handle-before-navigate tab-id]])))))

  (.addListener js/chrome.webNavigation.onCompleted
                (fn [details]
                  ;; Only handle main frame (not iframes)
                  ;; Skip non-scriptable pages (extension pages, about:blank, etc.)
                  (let [url (.-url details)]
                    (when (and (zero? (.-frameId details))
                               (:scriptable? (script-utils/check-page-scriptability
                                              url (script-utils/detect-browser-type))))
                      ;; Dispatch navigation action (gather-then-decide pattern)
                      (dispatch! [[:nav/ax.handle-navigation (.-tabId details) url]])
                      ;; Scan for userscript blocks and conditionally inject installer
                      (maybe-inject-installer! dispatch! (.-tabId details) url)))))

  (.addListener js/chrome.webNavigation.onHistoryStateUpdated
                (fn [details]
                  (when (zero? (.-frameId details))
                    (maybe-inject-installer! dispatch! (.-tabId details)
                                             (.-url details)))))

  ;; ============================================================
  ;; Lifecycle Events
  ;; ============================================================

  ;; These ensure initialization happens on browser/extension lifecycle events.
  ;; The ensure-initialized! pattern guarantees we only init once even if
  ;; multiple events fire.

  ;; Sync registrations when scripts change, and update debug-logging setting
  (.addListener js/chrome.storage.onChanged
                (fn [changes area]
                  (when (= area "local")
                    (when (.-scripts changes)
                      (log/debug "Background" "Scripts changed, syncing registrations")
                      ((^:async fn []
                         (js-await (ensure-initialized! dispatch!))
                         (js-await (registration/sync-registrations!)))))
                    (when (aget changes "settings/debug-logging")
                      (let [change (aget changes "settings/debug-logging")
                            enabled (boolean (.-newValue change))]
                        (log/set-debug-enabled! enabled)))
                    (when (aget changes "sponsor/sponsored-username")
                      (let [change (aget changes "sponsor/sponsored-username")
                            new-username (or (.-newValue change) "PEZ")]
                        ((^:async fn []
                           (js-await (ensure-initialized! dispatch!))
                           (js-await (update-sponsor-script-match! new-username)))))))))

  (.addListener js/chrome.runtime.onInstalled
                (fn [details]
                  (log/info "Background" "onInstalled:" (.-reason details))
                  ;; Reset dev sponsor username on install/update so it defaults back to PEZ
                  (js/chrome.storage.local.remove #js ["sponsor/sponsored-username"])
                  (ensure-initialized! dispatch!)))

  (.addListener js/chrome.runtime.onStartup
                (fn []
                  (log/info "Background" "onStartup")
                  (ensure-initialized! dispatch!)))

  ;; Start initialization immediately for service worker wake scenarios
  (ensure-initialized! dispatch!)

  (log/info "Background" "Listeners registered"))

;; ============================================================
;; Uniflow Dispatch - placed here after all helpers
;; ============================================================

(defn ^:async perform-effect! [dispatch! [effect & args]]
  (case effect
    :ws/fx.broadcast-connections-changed!
    (let [[connections] args]
      (bg-ws/broadcast-connections-changed! connections))

    :ws/fx.handle-connect
    (let [[connections tab-id port] args]
      (bg-ws/handle-ws-connect connections dispatch! tab-id port))

    :ws/fx.handle-send
    (let [[connections tab-id data] args]
      (bg-ws/handle-ws-send connections tab-id data))

    :ws/fx.handle-close
    (let [[connections tab-id] args]
      (bg-ws/handle-ws-close connections dispatch! tab-id))

    :icon/fx.update-toolbar!
    (let [[tab-id display-state] args]
      (js-await (bg-icon/update-icon-with-state! tab-id display-state)))

    :init/fx.await-promise
    (let [[promise] args]
      (js-await promise))

    :init/fx.initialize
    (let [[resolve reject] args]
      ((^:async fn []
         (try
           (js-await (test-logger/init-test-mode!))
           (js-await (storage/init!))
           ;; Load debug logging setting and apply it
           (js-await (js/Promise.
                      (fn [res]
                        (js/chrome.storage.local.get
                         #js ["settings/debug-logging"]
                         (fn [result]
                           (let [enabled (boolean (aget result "settings/debug-logging"))]
                             (log/set-debug-enabled! enabled)
                             (res true)))))))
           (js-await (registration/sync-registrations!))
           (log/info "Background" "Initialization complete")
           (js-await (test-logger/log-event! "EXTENSION_STARTED"
                                             {:version (.-version (.getManifest js/chrome.runtime))}))
           (resolve true)
           (catch :default err
             (log/error "Background" "Initialization failed:" err)
             (dispatch! [[:init/ax.clear-promise]])
             (reject err))))))

    :repl/fx.connect-tab
    (let [[tab-id ws-port icon-state] args]
      (try
        (js-await (connect-tab! dispatch! tab-id ws-port icon-state))
        {:success true}
        (catch :default err
          {:success false :error (.-message err)})))

    :page/fx.check-status
    (let [[tab-id] args]
      (try
        (let [status (js-await (bg-inject/execute-in-page tab-id check-status-fn))]
          {:success true :status status})
        (catch :default err
          {:success false :error (.-message err)})))

    :msg/fx.ensure-scittle
    (let [[send-response tab-id icon-state] args]
      ((^:async fn []
         (try
           (js-await (bg-inject/ensure-scittle! dispatch! tab-id icon-state))
           (dispatch! [[:msg/ax.ensure-scittle-result send-response {:ok? true}]])
           (catch :default err
             (dispatch! [[:msg/ax.ensure-scittle-result send-response {:ok? false
                                                                      :error (.-message err)}]]))))))

    :script/fx.evaluate
    (let [[tab-id script icon-state] args]
      (when (= bg-utils/sponsor-script-id (:script/id script))
        (dispatch! [[:sponsor/ax.set-pending tab-id]]))
      (try
        (js-await (bg-inject/ensure-scittle! dispatch! tab-id icon-state))
        (js-await (bg-inject/execute-scripts! tab-id [script]))
        {:success true}
        (catch :default err
          {:success false :error (.-message err)})))

    :msg/fx.list-scripts
    (let [[send-response include-hidden?] args
          scripts (storage/get-scripts)]
      (dispatch! [[:msg/ax.list-scripts-result send-response {:include-hidden? include-hidden?
                                                            :scripts scripts}]]))

    :msg/fx.inject-bridge
    (let [[tab-id] args]
      (bg-inject/inject-content-script tab-id "content-bridge.js"))

    :msg/fx.wait-bridge-ready
    (let [[tab-id] args]
      (bg-inject/wait-for-bridge-ready tab-id))

    :msg/fx.inject-lib-file
    (let [[tab-id file] args]
      (bg-inject/inject-libs-sequentially! tab-id [file]))

    :msg/fx.send-response
    (let [[send-response response-data] args]
      (send-response (clj->js response-data)))

    :msg/fx.get-script
    (let [[send-response script-name] args
          script (storage/get-script-by-name script-name)]
      (dispatch! [[:msg/ax.get-script-result send-response {:script-name script-name
                                                          :script script}]]))

    :msg/fx.get-connections
    (let [[send-response connections] args
          display-list (bg-utils/connections->display-list connections)]
      (test-logger/log-event! "GET_CONNECTIONS_RESPONSE"
                              {:raw-connection-count (count (keys connections))
                               :display-list-count (count display-list)
                               :connections-keys (vec (keys connections))})
      (send-response (clj->js {:success true
                               :connections display-list})))

    :tabs/fx.find-by-url-pattern
    (let [[url-pattern] args]
      (try
        (let [found-tab-id (js-await (find-tab-id-by-url-pattern! url-pattern))]
          (if found-tab-id
            {:success true :tabId found-tab-id}
            {:success false :error "No tab found"}))
        (catch :default err
          {:success false :error (.-message err)})))

    :msg/fx.e2e-get-test-events
    (let [[send-response] args]
      ((^:async fn []
         (let [events (js-await (test-logger/get-test-events))]
           (send-response #js {:success true :events events})))))

    :storage/fx.get-local-storage
    (let [[key] args]
      (try
        (let [result (js-await (js/chrome.storage.local.get #js [key]))]
          {:success true :key key :value (aget result key)})
        (catch :default err
          {:success false :key key :error (.-message err)})))

    :storage/fx.set-local-storage
    (let [[key value] args]
      (try
        (js-await (js/Promise.
                   (fn [resolve]
                     (js/chrome.storage.local.set
                      (js-obj key value)
                      resolve))))
        {:success true :key key :value value}
        (catch :default err
          {:success false :error (.-message err)})))

    ;; Navigation effects for gather-then-decide pattern

    :icon/fx.update-icon-disconnected
    (let [[tab-id] args]
      (bg-icon/update-icon-for-tab! dispatch! tab-id :disconnected))

    :nav/fx.gather-auto-connect-context
    (let [[tab-id url history] args]
      (js-await (ensure-initialized! dispatch!))
      (js-await (test-logger/log-event! "NAVIGATION_STARTED" {:tab-id tab-id :url url}))
      (let [{:keys [enabled?]} (js-await (get-auto-connect-settings))
            auto-reconnect? (js-await (get-auto-reconnect-setting))
            saved-port (js-await (get-saved-ws-port tab-id))
            in-history? (bg-utils/tab-in-history? history tab-id)
            history-port (when in-history? (bg-utils/get-history-port history tab-id))]
        {:nav/tab-id tab-id
         :nav/url url
         :nav/auto-connect-enabled? (boolean enabled?)
         :nav/auto-reconnect-enabled? (boolean auto-reconnect?)
         :nav/in-history? in-history?
         :nav/history-port history-port
         :nav/saved-port saved-port}))

    :nav/fx.connect
    ;; Connect REPL to a tab
    (let [[tab-id port icon-state] args]
      (try
        (js-await (test-logger/log-event! "NAV_AUTO_CONNECT"
                                          {:tab-id tab-id
                                           :port port}))
        (js-await (connect-tab! dispatch! tab-id port icon-state))
        (log/info "Background:AutoConnect" "Successfully connected REPL to tab:" tab-id)
        {:success true}
        (catch :default err
          (log/warn "Background:AutoConnect" "Failed to connect REPL:" (.-message err))
          {:success false :error (.-message err)})))

    :nav/fx.process-navigation
    ;; Process userscripts for a navigation event
    (let [[tab-id url icon-state] args]
      (js-await (process-navigation! dispatch! tab-id url icon-state)))

    :sponsor/fx.handle-status-result
    (let [[{:keys [pending? tab-url send-response]}] args]
      ((^:async fn []
         (try
           (let [storage-result (js-await (js/chrome.storage.local.get #js ["sponsor/sponsored-username"]))
                 username (or (aget storage-result "sponsor/sponsored-username") "PEZ")]
             (if (and pending?
                      (bg-utils/sponsor-url-matches? tab-url username))
               (do (swap! storage/!db assoc
                          :sponsor/status true
                          :sponsor/checked-at (js/Date.now))
                   (js-await (storage/persist!))
                   (send-response #js {:success true}))
               (send-response #js {:success false
                                   :error (if pending? "URL mismatch" "No pending sponsor check")})))
           (catch :default err
             (send-response #js {:success false :error (.-message err)}))))))

    :fs/fx.broadcast-sync-status!
    (let [[sync-tab-id] args]
      (js/chrome.runtime.sendMessage
       #js {:type "fs-sync-status-changed"
            :fsSyncTabId sync-tab-id}
       (fn [_response]
         (when js/chrome.runtime.lastError nil))))

    :fs/fx.parse-and-save
    (let [[send-response raw-data] args
          {:keys [code enabled force? bulk-id bulk-index bulk-count script-source]} raw-data]
      (try
        (let [manifest (manifest-parser/extract-manifest code)
              raw-name (or (when manifest (aget manifest "raw-script-name"))
                           (when manifest (aget manifest "script-name")))
              name-error (script-utils/validate-script-name raw-name)
              auto-run-match (when manifest (aget manifest "auto-run-match"))
              injects (when manifest (aget manifest "inject"))
              run-at (script-utils/normalize-run-at (when manifest (aget manifest "run-at")))]
          (cond
            (nil? raw-name)
            (send-response #js {:success false :error "Missing :epupp/script-name in manifest"})

            name-error
            (send-response #js {:success false :error name-error})

            :else
            (let [crypto (.-crypto js/globalThis)
                  script-id (if (and crypto (.-randomUUID crypto))
                              (str "script-" (.randomUUID crypto))
                              (str "script-" (.now js/Date) "-" (.random js/Math)))
                  script (cond-> {:script/id script-id
                                  :script/name raw-name
                                  :script/code code
                                  :script/match (if (vector? auto-run-match) auto-run-match [auto-run-match])
                                  :script/inject (or injects [])
                                  :script/enabled enabled
                                  :script/run-at run-at
                                  :script/force? force?}
                           (some? script-source) (assoc :script/source script-source)
                           (some? bulk-id) (assoc :script/bulk-id bulk-id)
                           (some? bulk-index) (assoc :script/bulk-index bulk-index)
                           (some? bulk-count) (assoc :script/bulk-count bulk-count))]
              (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.save-script script]))))
        (catch :default err
          (send-response #js {:success false :error (str "Parse error: " (.-message err))}))))

    :fs/fx.dispatch-action
    (let [[send-response action] args]
      (fs-dispatch/dispatch-fs-action! send-response action))

    :banner/fx.broadcast-system
    (let [[event] args]
      (bg-icon/broadcast-system-banner! event))

    :uf/unhandled-fx))

(defn dispatch!
  "Dispatch background actions through Uniflow."
  [actions]
  (event-handler/dispatch! !state bg-actions/handle-action perform-effect! actions))

(activate! dispatch!)