(ns popup
  "Browser Jack-in extension popup - built with Squint + Reagami
   Inspired by Replicant tic-tac-toe state management pattern"
  (:require [reagami :as r]))

(defonce !state
  (atom {:ports/nrepl "1339"
         :ports/ws "1340"
         :ui/status nil
         :ui/copy-feedback nil
         :ui/has-connected false  ; Track if we've connected at least once
         :browser/brave? false
         :scripts/list []         ; All userscripts
         :scripts/current-url nil ; Current tab URL for matching
         }))

(defn ws-fail-message []
  (str "Failed: WebSocket connection failed. Is the server running?"
       (when (:browser/brave? @!state)
         " Brave Shields may block WebSocket connections.")))

(defn status-class [status]
  (when status
    (cond
      (or (.startsWith status "Failed") (.startsWith status "Error")) "status status-failed"
      (or (.endsWith status "...") (.includes status "not connected")) "status status-pending"
      :else "status")))

(defn generate-server-cmd [{:keys [ports/nrepl ports/ws]}]
  (str "bb -Sdeps '{:deps {io.github.babashka/sci.nrepl {:git/sha \"1042578d5784db07b4d1b6d974f1db7cabf89e3f\"}}}' "
       "-e '(require (quote [sci.nrepl.browser-server :as server])) "
       "(server/start! {:nrepl-port " nrepl " :websocket-port " ws "}) "
       "@(promise)'"))

;; ============================================================
;; URL pattern matching (inline for popup bundle)
;; ============================================================

(defn- escape-regex [s]
  (.replace s (js/RegExp. "[.+?^${}()|[\\]\\\\]" "g") "\\$&"))

(defn- pattern->regex [pattern]
  (if (= pattern "<all_urls>")
    (js/RegExp. "^https?://.*$")
    (let [escaped (escape-regex pattern)
          with-wildcards (.replace escaped (js/RegExp. "\\*" "g") ".*")]
      (js/RegExp. (str "^" with-wildcards "$")))))

(defn- url-matches-pattern? [url pattern]
  (.test (pattern->regex pattern) url))

(defn- script-matches-url? [script url]
  (some #(url-matches-pattern? url %) (:script/match script)))

;; ============================================================
;; Tab and storage helpers
;; ============================================================

(defn get-active-tab []
  (js/Promise.
   (fn [resolve]
     (js/chrome.tabs.query
      #js {:active true :currentWindow true}
      (fn [tabs] (resolve (first tabs)))))))

(defn get-hostname [tab]
  (try
    (.-hostname (js/URL. (.-url tab)))
    (catch :default _ "default")))

(defn storage-key [tab]
  (str "ports_" (get-hostname tab)))

(defn execute-in-page
  "Execute function in page context (MAIN world)."
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
  "Inject a script file into ISOLATED world (content script context)."
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

;; ============================================================
;; Page injection functions (for execute-in-page)
;; These must be JS proper - no Squint runtime dependencies
;; They get serialized and run in page context.
;; ============================================================

(def inject-script-fn
  (js* "function(url, isModule) {
    var script = document.createElement('script');
    if (isModule) {
      script.type = 'module';
    }
    // Handle Trusted Types if required by the page
    if (window.trustedTypes && window.trustedTypes.createPolicy) {
      try {
        var policy = window.trustedTypes.createPolicy('browser-jack-in', {
          createScriptURL: function(s) { return s; }
        });
        script.src = policy.createScriptURL(url);
      } catch(e) {
        // Policy might already exist, try using default or direct assignment
        if (e.name === 'TypeError' && e.message.includes('already exists')) {
          script.src = url;
        } else {
          throw e;
        }
      }
    } else {
      script.src = url;
    }
    document.head.appendChild(script);
    console.log('[Browser Jack-in] Injected:', url, isModule ? '(module)' : '');
    return 'ok';
  }"))

(def set-nrepl-config-fn
  (js* "function(port) {
    window.SCITTLE_NREPL_WEBSOCKET_HOST = 'localhost';
    window.SCITTLE_NREPL_WEBSOCKET_PORT = port;
    console.log('[Browser Jack-in] Set nREPL to ws://localhost:', port);
  }"))

(def check-scittle-fn
  (js* "function() {
    if (window.scittle && window.scittle.core) return {ready: true};
    return {ready: false};
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
      console.log('[Browser Jack-in] Closing existing WebSocket, readyState:', ws.readyState);
      if (ws.readyState === 0 || ws.readyState === 1) {
        ws.close();
      }
      window.ws_nrepl = null;
    }
  }"))

(def reconnect-nrepl-fn
  "Reconnect by creating a new WebSocket - reuses existing scittle.nrepl handlers"
  (js* "function(port) {
    console.log('[Browser Jack-in] Reconnecting nREPL to port:', port);
    // Create new WebSocket - will be intercepted by ws-bridge
    var ws = new WebSocket('ws://localhost:' + port + '/_nrepl');
    // scittle.nrepl sets handlers on window.ws_nrepl which ws-bridge populates
  }"))

(def check-connection-fn
  "Check if WebSocket is connected"
  (js* "function() {
    var ws = window.ws_nrepl;
    if (!ws) return {connected: false, state: -1};
    return {connected: ws.readyState === 1, state: ws.readyState};
  }"))

(defn poll-until [check-fn success? fail? timeout]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)]
       (letfn [(poll []
                 (-> (check-fn)
                     (.then (fn [result]
                              (cond
                                (fail? result) (reject (fail? result))
                                (success? result) (resolve result)
                                (> (- (js/Date.now) start) timeout)
                                (reject (js/Error. "Timeout"))
                                :else (js/setTimeout poll 100))))
                     (.catch reject)))]
         (poll))))))

;; ============================================================
;; Connection step functions
;; Each returns a Promise, composable in sequence
;; ============================================================

(defn ensure-bridge!
  "Inject content bridge and WS bridge if not already present."
  [tab-id has-bridge]
  (if has-bridge
    (js/Promise.resolve nil)
    (let [bridge-url (js/chrome.runtime.getURL "ws-bridge.js")]
      (swap! !state assoc :ui/status "Loading bridge...")
      (-> (inject-content-script tab-id "content-bridge.js")
          (.then (fn [_] (execute-in-page tab-id inject-script-fn bridge-url false)))))))

(defn ensure-scittle!
  "Inject Scittle if not already present, wait for it to load."
  [tab-id has-scittle]
  (if has-scittle
    (js/Promise.resolve nil)
    (let [scittle-url (js/chrome.runtime.getURL "vendor/scittle.js")]
      (swap! !state assoc :ui/status "Loading Scittle...")
      (-> (execute-in-page tab-id inject-script-fn scittle-url false)
          (.then (fn [_]
                   (poll-until
                    (fn [] (execute-in-page tab-id check-scittle-fn))
                    (fn [result] (.-ready result))
                    (constantly nil)
                    5000)))))))

(defn configure-and-connect!
  "Close any existing connection, set nREPL config and connect.
   If scittle.nrepl is already loaded, just reconnect without re-injecting."
  [tab-id port has-scittle-nrepl]
  (let [nrepl-url (js/chrome.runtime.getURL "vendor/scittle.nrepl.js")]
    (swap! !state assoc :ui/status "Connecting...")
    (-> (execute-in-page tab-id close-websocket-fn)
        (.then (fn [_] (execute-in-page tab-id set-nrepl-config-fn port)))
        (.then (fn [_]
                 (if has-scittle-nrepl
                   ;; Already have scittle.nrepl - just create new WebSocket (reuses existing handlers)
                   (execute-in-page tab-id reconnect-nrepl-fn port)
                   ;; First time - inject the script (it auto-connects on load)
                   (execute-in-page tab-id inject-script-fn nrepl-url false))))
        (.then (fn [_]
                 ;; Poll for connection success (up to 3 seconds)
                 (poll-until
                  (fn [] (execute-in-page tab-id check-connection-fn))
                  (fn [result] (.-connected result))
                  (fn [result]
                    (when (= 3 (.-state result))
                      (js/Error. (ws-fail-message))))
                  3000)))
        (.then (fn [_]
                 (swap! !state assoc
                        :ui/status (str "Connected to ws://localhost:" port)
                        :ui/has-connected true)))
        (.catch (fn [err]
                  (swap! !state assoc :ui/status (str "Failed: " (.-message err))))))))

(defn connect-to-tab!
  "Main connection flow for a tab. Injects what's needed (idempotent), then connects."
  [tab-id port]
  (-> (execute-in-page tab-id check-status-fn)
      (.then (fn [status]
               (let [has-bridge (and status (.-hasWsBridge status))
                     has-scittle (and status (.-hasScittle status))
                     has-scittle-nrepl (and status (.-hasScittleNrepl status))]
                 (js/console.log "[Connect] Status:" (js/JSON.stringify status))
                 (-> (ensure-bridge! tab-id has-bridge)
                     (.then (fn [_] (ensure-scittle! tab-id has-scittle)))
                     (.then (fn [_] (configure-and-connect! tab-id port has-scittle-nrepl)))))))
      (.catch (fn [err]
                (swap! !state assoc :ui/status (str "Failed: " (.-message err)))))))

;; ============================================================
;; Script storage helpers
;; ============================================================

(defn- parse-scripts
  "Convert JS scripts array to Clojure with namespaced keys"
  [js-scripts]
  (if js-scripts
    (->> (vec js-scripts)
         (mapv (fn [s]
                 {:script/id (.-id s)
                  :script/name (.-name s)
                  :script/match (vec (or (.-match s) #js []))
                  :script/code (.-code s)
                  :script/enabled (.-enabled s)
                  :script/created (.-created s)
                  :script/modified (.-modified s)
                  :script/approved-patterns (vec (or (.-approvedPatterns s) #js []))})))
    []))

(defn- load-scripts! []
  (js/chrome.storage.local.get
   #js ["scripts"]
   (fn [result]
     (let [scripts (parse-scripts (.-scripts result))]
       (swap! !state assoc :scripts/list scripts)))))

(defn- script->js [script]
  #js {:id (:script/id script)
       :name (:script/name script)
       :match (clj->js (:script/match script))
       :code (:script/code script)
       :enabled (:script/enabled script)
       :created (:script/created script)
       :modified (:script/modified script)
       :approvedPatterns (clj->js (:script/approved-patterns script))})

(defn- get-matching-pattern
  "Find which pattern matches the URL for a script"
  [url script]
  (when url
    (some #(when (url-matches-pattern? url %) %) (:script/match script))))

(defn- pattern-approved?
  "Check if a pattern is in the script's approved list"
  [script pattern]
  (some #(= % pattern) (:script/approved-patterns script)))

(defn- save-scripts! [scripts]
  (js/chrome.storage.local.set
   #js {:scripts (clj->js (mapv script->js scripts))}))

(defn- toggle-script!
  "Toggle enabled state. When disabling, also remove matching pattern from approved list."
  [script-id matching-pattern]
  (swap! !state update :scripts/list
         (fn [scripts]
           (let [updated (mapv (fn [s]
                                 (if (= (:script/id s) script-id)
                                   (let [new-enabled (not (:script/enabled s))]
                                     (if new-enabled
                                       ;; Enabling - just flip the flag (will need approval)
                                       (assoc s :script/enabled true)
                                       ;; Disabling - also remove pattern from approved
                                       (-> s
                                           (assoc :script/enabled false)
                                           (update :script/approved-patterns
                                                   (fn [patterns]
                                                     (filterv #(not= % matching-pattern) (or patterns [])))))))
                                   s))
                               scripts)]
             (save-scripts! updated)
             ;; Notify background to update badge
             (js/chrome.runtime.sendMessage #js {:type "refresh-approvals"})
             updated))))

(defn- approve-script!
  "Approve a pattern for a script and notify background to execute it"
  [script-id pattern]
  (swap! !state update :scripts/list
         (fn [scripts]
           (let [updated (mapv (fn [s]
                                 (if (= (:script/id s) script-id)
                                   (update s :script/approved-patterns
                                           (fn [patterns]
                                             (let [patterns (or patterns [])]
                                               (if (some #(= % pattern) patterns)
                                                 patterns
                                                 (conj patterns pattern)))))
                                   s))
                               scripts)]
             (save-scripts! updated)
             ;; Notify background to clear pending and execute
             (js/chrome.runtime.sendMessage
              #js {:type "pattern-approved"
                   :scriptId script-id
                   :pattern pattern})
             updated))))

(defn- deny-script!
  "Deny a script by disabling it"
  [script-id]
  (swap! !state update :scripts/list
         (fn [scripts]
           (let [updated (mapv (fn [s]
                                 (if (= (:script/id s) script-id)
                                   (assoc s :script/enabled false)
                                   s))
                               scripts)]
             (save-scripts! updated)
             ;; Notify background to clear pending
             (js/chrome.runtime.sendMessage #js {:type "refresh-approvals"})
             updated))))

(defn- delete-script! [script-id]
  (swap! !state update :scripts/list
         (fn [scripts]
           (let [updated (filterv #(not= (:script/id %) script-id) scripts)]
             (save-scripts! updated)
             updated))))

;; ============================================================
;; Dispatch
;; ============================================================

(defn dispatch! [[action & args]]
  (case action
    :set-nrepl-port
    (let [[port] args]
      (swap! !state assoc :ports/nrepl port))

    :set-ws-port
    (let [[port] args]
      (swap! !state assoc :ports/ws port))

    :save-ports
    (-> (get-active-tab)
        (.then (fn [tab]
                 (let [key (storage-key tab)
                       {:keys [ports/nrepl ports/ws]} @!state]
                   (js/chrome.storage.local.set
                    (clj->js {key {:nreplPort nrepl :wsPort ws}}))))))

    :copy-command
    (let [cmd (generate-server-cmd @!state)]
      (-> (js/navigator.clipboard.writeText cmd)
          (.then (fn []
                   (swap! !state assoc :ui/copy-feedback "Copied!")
                   (js/setTimeout (fn [] (swap! !state assoc :ui/copy-feedback nil)) 1500)))))

    :connect
    (let [{:keys [ports/ws]} @!state
          port (js/parseInt ws 10)]
      (when (and (not (js/isNaN port)) (<= 1 port 65535))
        (swap! !state assoc :ui/status "Checking...")
        (-> (get-active-tab)
            (.then (fn [tab] (connect-to-tab! (.-id tab) port))))))

    :check-status
    (-> (get-active-tab)
        (.then (fn [tab]
                 (execute-in-page (.-id tab) check-status-fn)))
        (.then (fn [result]
                 (when result
                   (let [has-scittle (.-hasScittle result)
                         has-bridge (.-hasWsBridge result)
                         ws-port (:ports/ws @!state)]
                     (js/console.log "[Check Status] hasScittle:" has-scittle "hasWsBridge:" has-bridge)
                     (when (and has-scittle has-bridge)
                       (swap! !state assoc
                              :ui/has-connected true
                              :ui/status (str "Connected to ws://localhost:" ws-port))))))))

    :load-saved-ports
    (-> (get-active-tab)
        (.then (fn [tab]
                 (let [key (storage-key tab)]
                   (js/chrome.storage.local.get
                    #js [key]
                    (fn [result]
                      (when-let [saved (aget result key)]
                        (when-let [nrepl (.-nreplPort saved)]
                          (swap! !state assoc :ports/nrepl (str nrepl)))
                        (when-let [ws (.-wsPort saved)]
                          (swap! !state assoc :ports/ws (str ws))))))))))

    :load-scripts
    (load-scripts!)

    :toggle-script
    (let [[script-id matching-pattern] args]
      (toggle-script! script-id matching-pattern))

    :delete-script
    (let [[script-id] args]
      (when (js/confirm "Delete this script?")
        (delete-script! script-id)))

    :load-current-url
    (-> (get-active-tab)
        (.then (fn [tab]
                 (swap! !state assoc :scripts/current-url (.-url tab)))))

    :approve-script
    (let [[script-id pattern] args]
      (approve-script! script-id pattern))

    :deny-script
    (let [[script-id] args]
      (deny-script! script-id))

    (js/console.warn "Unknown action:" action)))

(defn jack-in-icon []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width 36
         :height 36
         :viewBox "0 0 100 100"}
   [:circle {:cx "50"
             :cy "50"
             :r "48"
             :stroke "#4a71c4"
             :stroke-width "4"
             :fill "#4a71c4"}]
   [:path
    {:fill "#ffdc73"
     :transform "translate(50, 50) scale(0.5) translate(-211, -280)"
     :d
       "M224.12 259.93h21.11a5.537 5.537 0 0 1 4.6 8.62l-50.26 85.75a5.536 5.536 0 0 1-7.58 1.88 5.537 5.537 0 0 1-2.56-5.85l7.41-52.61-24.99.43a5.538 5.538 0 0 1-5.61-5.43c0-1.06.28-2.04.78-2.89l49.43-85.71a5.518 5.518 0 0 1 7.56-1.95 5.518 5.518 0 0 1 2.65 5.53l-2.54 52.23z"}]])

(defn port-input [{:keys [id label value on-change]}]
  [:span
   [:label {:for id} label]
   [:input {:type "number"
            :id id
            :value value
            :min "1"
            :max "65535"
            :on-input (fn [e]
                        (on-change (.. e -target -value))
                        (dispatch! [:save-ports]))}]])

(defn command-box [{:keys [command copy-feedback]}]
  [:div.command-box
   [:code command]
   [:button.copy-btn {:on-click #(dispatch! [:copy-command])}
    (or copy-feedback "Copy")]])

(defn script-item [{:keys [script/name script/match script/enabled] :as script}
                   current-url]
  (let [script-id (:script/id script)
        matching-pattern (get-matching-pattern current-url script)
        matches-current (some? matching-pattern)
        needs-approval (and matches-current
                            enabled
                            (not (pattern-approved? script matching-pattern)))
        pattern-display (or matching-pattern (first match))]
    [:div.script-item {:class (str (when matches-current "script-item-active ")
                                   (when needs-approval "script-item-approval"))}
     [:div.script-info
      [:span.script-name name]
      [:span.script-match pattern-display]]
     [:div.script-actions
      ;; Show approval buttons when script matches current URL but pattern not approved
      (when needs-approval
        [:button.approval-allow {:on-click #(dispatch! [:approve-script script-id matching-pattern])}
         "Allow"])
      (when needs-approval
        [:button.approval-deny {:on-click #(dispatch! [:deny-script script-id])}
         "Deny"])
      ;; Always show checkbox and delete
      [:input {:type "checkbox"
               :checked enabled
               :title (if enabled "Enabled" "Disabled")
               :on-change #(dispatch! [:toggle-script script-id matching-pattern])}]
      [:button.script-delete {:on-click #(dispatch! [:delete-script script-id])
                              :title "Delete script"}
       "×"]]]))

(defn scripts-section [{:keys [scripts/list scripts/current-url]}]
  [:div.step.scripts-section
   [:div.step-header "Userscripts"]
   (if (seq list)
     [:div.script-list
      (for [script list]
        ^{:key (:script/id script)}
        [script-item script current-url])]
     [:div.no-scripts "No scripts yet. Create scripts via DevTools console."])])

(defn popup-ui [{:keys [ports/nrepl ports/ws ui/status ui/copy-feedback ui/has-connected] :as state}]
  [:div
   ;; Header with logos
   [:div.header
    [:div.header-left
     [jack-in-icon]
     [:h1 "Browser Jack-in"]]
    [:div.header-right
     [:a.header-tagline {:href "https://github.com/babashka/scittle/tree/main/doc/nrepl"
                         :target "_blank"}
      "Scittle nREPL"]
     [:div.header-logos
      [:img {:src "images/sci.png" :alt "SCI"}]
      [:img {:src "images/clojure.png" :alt "Clojure"}]]]]

   [:div.step
    [:div.step-header "1. Start the browser-nrepl server"]
    [:div.port-row
     [port-input {:id "nrepl-port"
                  :label "nREPL:"
                  :value nrepl
                  :on-change #(dispatch! [:set-nrepl-port %])}]
     [port-input {:id "ws-port"
                  :label "WebSocket:"
                  :value ws
                  :on-change #(dispatch! [:set-ws-port %])}]]
    [command-box {:command (generate-server-cmd state)
                  :copy-feedback copy-feedback}]]

   [:div.step
    [:div.step-header "2. Connect browser to server"]
    [:div.connect-row
     [:span.connect-target (str "ws://localhost:" ws)]
     [:button#connect {:on-click #(dispatch! [:connect])}
      (if has-connected "Reconnect" "Connect")]]
    (when status
      [:div#status {:class (status-class status)} status])]

   [:div.step
    [:div.step-header "3. Connect editor to browser (via server)"]
    [:div.connect-row
     [:span.connect-target (str "nrepl://localhost:" nrepl)]]]

   ;; Userscripts section
   [scripts-section state]])

(defn render! []
  (r/render (js/document.getElementById "app")
            [popup-ui @!state]))

(defn init! []
  (js/console.log "Browser Jack-in popup init!")
  (add-watch !state ::render (fn [_ _ _ _] (render!)))

  ;; Detect browser features
  (swap! !state assoc :browser/brave? (some? (.-brave js/navigator)))

  (render!)
  (dispatch! [:load-saved-ports])
  (dispatch! [:check-status])
  (dispatch! [:load-scripts])
  (dispatch! [:load-current-url]))

;; Start the app when DOM is ready
(js/console.log "Popup script loaded, readyState:" js/document.readyState)
(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
