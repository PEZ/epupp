(ns bg-ws
  "WebSocket connection management for browser-nrepl relay.
   Handles connection lifecycle, message routing, and cleanup."
  (:require [log :as log]
            [background-utils :as bg-utils]
            [test-logger :as test-logger]
            [bg-icon :as bg-icon]))

;; ============================================================
;; WebSocket Access
;; ============================================================

(defn get-ws
  "Get WebSocket for a tab from connections map."
  [connections tab-id]
  (get-in connections [tab-id :ws/socket]))

;; ============================================================
;; Connection Broadcasting
;; ============================================================

(defn broadcast-connections-changed!
  "Notify popup/panel that connections have changed.
   Sends message to all extension pages listening for connection updates."
  [connections]
  (let [display-list (bg-utils/connections->display-list connections)]
    ;; Send to all extension pages (popup, panel)
    ;; This is a fire-and-forget - some pages may not be open
    (js/chrome.runtime.sendMessage
     #js {:type "connections-changed"
          :connections (clj->js display-list)}
     (fn [_response]
       ;; Ignore errors - expected when no popup/panel is open
       (when js/chrome.runtime.lastError nil)))))

;; ============================================================
;; WebSocket Lifecycle
;; ============================================================

(defn close-ws!
  "Close and remove WebSocket for a tab. Does not send ws-close event.
   Requires dispatch! function to dispatch unregister action."
  [connections dispatch! tab-id]
  (when-let [ws (get-ws connections tab-id)]
    ;; Clear onclose to prevent sending ws-close when deliberately closing
    (set! (.-onclose ws) nil)
    (try
      (.close ws)
      (catch :default e
        (log/error "Background" "WS" "Error closing WebSocket:" e))))
  (dispatch! [[:ws/ax.unregister tab-id]])
  ;; Update icon to disconnected (since we cleared onclose, we must do it here)
  (bg-icon/update-icon-for-tab! dispatch! tab-id :disconnected))

(defn send-to-tab
  "Send message to content script in a tab.
   Fire-and-forget: ignores errors when the content bridge isn't present."
  [tab-id message]
  (js/chrome.tabs.sendMessage
   tab-id
   (clj->js message)
   (fn [_response]
     ;; Ignore errors - expected when no content bridge/content script is present.
     (when js/chrome.runtime.lastError nil))))

(defn handle-ws-connect
  "Create WebSocket connection for a tab.
   Closes existing connection for this tab, AND any other tab on the same port
   (browser-nrepl only supports one client per port).

   Parameters:
   - connections: Current connections map
   - dispatch!: Dispatch function for actions
   - tab-id: Target tab ID
   - port: WebSocket port number"
  [connections dispatch! tab-id port]
  ;; Close existing connection for THIS tab
  (close-ws! connections dispatch! tab-id)

  ;; Close any OTHER tab using the same port (browser-nrepl limitation)
  (when-let [other-tab-id (bg-utils/find-tab-on-port connections port tab-id)]
    (log/info "Background" "WS" "Disconnecting tab" other-tab-id "- port" port "claimed by tab" tab-id)
    (close-ws! connections dispatch! other-tab-id)
    ;; Update icon for the disconnected tab
    (bg-icon/update-icon-for-tab! dispatch! other-tab-id :disconnected))

  ;; Fetch tab title and favicon for display
  (js/chrome.tabs.get
   tab-id
   (fn [tab]
     (let [tab-title (if js/chrome.runtime.lastError
                       "Unknown"
                       (or (.-title tab) "Unknown"))
           tab-favicon (when-not js/chrome.runtime.lastError
                         (.-favIconUrl tab))
           tab-url (when-not js/chrome.runtime.lastError
                     (.-url tab))
           ws-url (str "ws://localhost:" port "/_nrepl")]
       (log/info "Background" "WS" "Connecting to:" ws-url "for tab:" tab-id)
       (try
         (let [ws (js/WebSocket. ws-url)]
           (dispatch! [[:ws/ax.register tab-id
                        {:ws/socket ws
                         :ws/port port
                         :ws/tab-title tab-title
                         :ws/tab-favicon tab-favicon
                         :ws/tab-url tab-url}]])
           ;; Track this tab in connection history for auto-reconnect
           (dispatch! [[:history/ax.track tab-id port]])

           (set! (.-onopen ws)
                 (fn []
                   (log/info "Background" "WS" "Connected for tab:" tab-id)
                   (test-logger/log-event! "WS_CONNECTED" {:tab-id tab-id :port port})
                   (bg-icon/update-icon-for-tab! dispatch! tab-id :connected)
                   (send-to-tab tab-id {:type "ws-open"})
                   (dispatch! [[:ws/ax.broadcast]])))

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
                   (dispatch! [[:ws/ax.unregister tab-id]])
                   ;; When WS closes, set to disconnected (we no longer have an intermediate injected state)
                   (bg-icon/update-icon-for-tab! dispatch! tab-id :disconnected))))
         (catch :default e
           (log/error "Background" "WS" "Failed to create WebSocket:" e)
           (send-to-tab tab-id {:type "ws-error"
                                :error (str e)})))))))

(defn handle-ws-send
  "Send data through WebSocket for a tab."
  [connections tab-id data]
  (when-let [ws (get-ws connections tab-id)]
    (when (= 1 (.-readyState ws))
      (.send ws data))))

(defn handle-ws-close
  "Close WebSocket for a tab."
  [connections dispatch! tab-id]
  (close-ws! connections dispatch! tab-id))
