(ns popup
  "DOM REPL extension popup - built with Squint + Reagami
   Inspired by Replicant tic-tac-toe state management pattern"
  (:require [reagami :as r]))

(defonce !state
  (atom {:ports/nrepl "1339"
         :ports/ws "1340"
         :ui/status nil
         :ui/copy-feedback nil}))

(defn generate-server-cmd [{:keys [ports/nrepl ports/ws]}]
  (str "bb -Sdeps '{:deps {io.github.babashka/sci.nrepl {:git/sha \"1042578d5784db07b4d1b6d974f1db7cabf89e3f\"}}}' "
       "-e \"(require '[sci.nrepl.browser-server :as server]) "
       "(server/start\\! {:nrepl-port " nrepl " :websocket-port " ws "}) "
       "@(promise)\""))

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

(defn execute-in-page [tab-id func & args]
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

;; ============================================================
;; Page injection functions (for execute-in-page)
;; ============================================================
;; Page injection functions (for execute-in-page)
;; These must be JS proper - no Squint runtime dependencies
;; They get serialized and run in page context.
;; ============================================================

(def inject-script-fn
  (js* "function(url) {
    var script = document.createElement('script');
    script.src = url;
    document.head.appendChild(script);
    console.log('[DOM REPL] Injected:', url);
    return 'ok';
  }"))

(def set-nrepl-config-fn
  (js* "function(port) {
    window.SCITTLE_NREPL_WEBSOCKET_HOST = 'localhost';
    window.SCITTLE_NREPL_WEBSOCKET_PORT = port;
    console.log('[DOM REPL] Set nREPL to ws://localhost:', port);
  }"))

(def check-scittle-fn
  (js* "function() {
    if (window.__scittle_csp_error) return {error: 'csp'};
    if (window.scittle && window.scittle.core) return {ready: true};
    try { eval('1'); return {ready: false}; }
    catch(e) { return {error: 'csp'}; }
  }"))

(def check-websocket-fn
  (js* "function() {
    var ws = window.ws_nrepl;
    if (!ws) return {status: 'no-ws'};
    switch(ws.readyState) {
      case 1: return {status: 'connected'};
      case 3: return {status: 'failed'};
      default: return {status: 'connecting'};
    }
  }"))

(def check-status-fn
  (js* "function() {
    return {
      hasScittle: !!(window.scittle && window.scittle.core),
      wsState: window.ws_nrepl ? window.ws_nrepl.readyState : -1,
      wsPort: window.SCITTLE_NREPL_WEBSOCKET_PORT
    };
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
        (swap! !state assoc :ui/status "Loading Scittle...")
        (-> (get-active-tab)
            (.then (fn [tab]
                     (let [tab-id (.-id tab)
                           scittle-url (js/chrome.runtime.getURL "vendor/scittle.js")
                           nrepl-url (js/chrome.runtime.getURL "vendor/scittle.nrepl.js")]
                       (-> (execute-in-page tab-id inject-script-fn scittle-url)
                           (.then (fn [_]
                                    (poll-until
                                     (fn [] (execute-in-page tab-id check-scittle-fn))
                                     (fn [result] (.-ready result))
                                     (fn [result]
                                       (when (= "csp" (.-error result))
                                         (js/Error. "Page blocks eval (CSP). Try a different page.")))
                                     5000)))
                           (.then (fn [_]
                                    (swap! !state assoc :ui/status "Connecting...")
                                    (execute-in-page tab-id set-nrepl-config-fn port)))
                           (.then (fn [_] (execute-in-page tab-id inject-script-fn nrepl-url)))
                           (.then (fn [_]
                                    (poll-until
                                     (fn [] (execute-in-page tab-id check-websocket-fn))
                                     (fn [result] (= "connected" (.-status result)))
                                     (fn [result]
                                       (when (= "failed" (.-status result))
                                         (js/Error. "WebSocket connection failed. Is the server running?")))
                                     5000)))
                           (.then (fn [_] (swap! !state assoc :ui/status (str "Connected to ws://localhost:" port))))
                           (.catch (fn [err] (swap! !state assoc :ui/status (str "Failed: " (.-message err))))))))))))

    :check-status
    (-> (get-active-tab)
        (.then (fn [tab]
                 (execute-in-page (.-id tab) check-status-fn)))
        (.then (fn [result]
                 (when result
                   (let [ws-state (.-wsState result)
                         ws-port (or (.-wsPort result) (:ports/ws @!state))]
                     (swap! !state assoc :ui/status
                            (case ws-state
                              1 (str "Connected to ws://localhost:" ws-port)
                              0 "Connecting..."
                              3 "Failed: WebSocket connection failed. Is the server running?"
                              (when (.-hasScittle result)
                                "Scittle loaded, not connected"))))))))

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

    (js/console.warn "Unknown action:" action)))

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

(defn popup-ui [{:keys [ports/nrepl ports/ws ui/status ui/copy-feedback] :as state}]
  [:div
   ;; Header with logos
   [:div.header
    [:h1 "DOM REPL"]
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
     [:button#connect {:on-click #(dispatch! [:connect])} "Connect"]]
    (when status
         [:div#status.status status])]

   [:div.step
    [:div.step-header "3. Connect editor to browser (via server)"]
    [:div.connect-row
     [:span.connect-target (str "nrepl://localhost:" nrepl)]]]])

(defn render! []
  (r/render (js/document.getElementById "app")
            [popup-ui @!state]))

(defn init! []
  (js/console.log "DOM REPL popup init!")
  (add-watch !state ::render (fn [_ _ _ _] (render!)))

  (js/console.log "About to render, app element:" (js/document.getElementById "app"))
  (render!)
  (js/console.log "Rendered!")

  (dispatch! [:load-saved-ports])
  (dispatch! [:check-status]))

;; Start the app when DOM is ready
(js/console.log "Popup script loaded, readyState:" js/document.readyState)
(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
