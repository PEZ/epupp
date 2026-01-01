(ns background
  "Background service worker for WebSocket connections.
   Runs in extension context, immune to page CSP.
   Relays WebSocket messages to/from content scripts.")

(js/console.log "[Browser Jack-in Background] Service worker started")

;; Store WebSocket connections per tab
(def !connections (atom {}))

(defn get-ws
  "Get WebSocket for a tab"
  [tab-id]
  (get @!connections tab-id))

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
  (swap! !connections dissoc tab-id))

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
        (swap! !connections assoc tab-id ws)

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
                (js/console.error "[Background] WebSocket error for tab:" tab-id)
                (send-to-tab tab-id {:type "ws-error"
                                     :error (str "WebSocket error connecting to " ws-url)})))

        (set! (.-onclose ws)
              (fn [event]
                (js/console.log "[Background] WebSocket closed for tab:" tab-id)
                (send-to-tab tab-id {:type "ws-close"
                                     :code (.-code event)
                                     :reason (.-reason event)})
                (swap! !connections dissoc tab-id))))
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

;; Listen for messages from content scripts
(.addListener js/chrome.runtime.onMessage
  (fn [message sender _send-response]
    (let [tab-id (.. sender -tab -id)
          msg-type (.-type message)]
      (case msg-type
        "ws-connect" (handle-ws-connect tab-id (.-port message))
        "ws-send" (handle-ws-send tab-id (.-data message))
        "ws-close" (handle-ws-close tab-id)
        "ping" nil
        (js/console.log "[Background] Unknown message type:" msg-type)))
    false))

;; Clean up when tab is closed
(.addListener js/chrome.tabs.onRemoved
  (fn [tab-id _remove-info]
    (when (get-ws tab-id)
      (js/console.log "[Background] Tab closed, cleaning up:" tab-id)
      (close-ws! tab-id))))

(js/console.log "[Browser Jack-in Background] Message listeners registered")
