(ns ws-bridge
  "WebSocket bridge wrapper for page context.
   Runs in MAIN world and communicates with content script bridge via postMessage.")

(defn- bridge-log
  "Send log messages via postMessage to content_bridge for routing through log namespace."
  [level & args]
  (.postMessage js/window
                #js {:source "epupp-page"
                     :type "log"
                     :level level
                     :subsystem "WsBridge"
                     :messages (to-array args)}
                "*"))

;; Centralized state with namespaced keys
(def !state (atom {:bridge/ready? false
                   :ws/message-handler nil}))

(defn- handle-bridge-ready [event]
  (when (= (.-source event) js/window)
    (let [msg (.-data event)]
      (when (and msg
                 (= "epupp-bridge" (.-source msg))
                 (= "bridge-ready" (.-type msg)))
        (bridge-log :debug "Bridge is ready")
        (swap! !state assoc :bridge/ready? true)))))

(defn bridged-websocket [url]
  (bridge-log :debug "Creating bridged WebSocket for:" url)

  ;; Clean up any existing message handler from previous connection
  (when-let [old-handler (:ws/message-handler @!state)]
    (bridge-log :debug "Removing old message handler")
    (.removeEventListener js/window "message" old-handler)
    (swap! !state assoc :ws/message-handler nil))

  (let [ws-obj (js-obj)]

    ;; Set up basic properties
    (set! (.-url ws-obj) url)
    (set! (.-readyState ws-obj) 0) ; CONNECTING
    (set! (.-onopen ws-obj) nil)
    (set! (.-onmessage ws-obj) nil)
    (set! (.-onerror ws-obj) nil)
    (set! (.-onclose ws-obj) nil)

    ;; Expose WebSocket constants on instance
    (set! (.-CONNECTING ws-obj) 0)
    (set! (.-OPEN ws-obj) 1)
    (set! (.-CLOSING ws-obj) 2)
    (set! (.-CLOSED ws-obj) 3)

    ;; Parse port from URL
    (let [port (if-let [match (.match url #":(\d+)/")]
                 (aget match 1)
                 "1340")
          message-handler
          (fn [event]
            (when (= (.-source event) js/window)
              (let [msg (.-data event)]
                (when (and msg (= "epupp-bridge" (.-source msg)))
                  (case (.-type msg)
                    "ws-open"
                    (do
                      (bridge-log :debug "WebSocket OPEN")
                      (set! (.-readyState ws-obj) 1) ; OPEN
                      (when-let [onopen (.-onopen ws-obj)]
                        (onopen)))

                    "ws-message"
                    (when-let [onmessage (.-onmessage ws-obj)]
                      (onmessage #js {:data (.-data msg)}))

                    "ws-error"
                    (do
                      (bridge-log :error "WebSocket ERROR")
                      (set! (.-readyState ws-obj) 3) ; CLOSED
                      (when-let [onerror (.-onerror ws-obj)]
                        (onerror (js/Error. (or (.-error msg) "WebSocket error")))))

                    "ws-close"
                    (do
                      (bridge-log :debug "WebSocket CLOSED")
                      (set! (.-readyState ws-obj) 3) ; CLOSED
                      (when-let [onclose (.-onclose ws-obj)]
                        (onclose)))

                    nil)))))]

      ;; Store and add the new message handler
      (swap! !state assoc :ws/message-handler message-handler)
      (.addEventListener js/window "message" message-handler)

      ;; Add send method
      (set! (.-send ws-obj)
            (fn [data]
              (when (= 1 (.-readyState ws-obj)) ; OPEN
                (.postMessage js/window
                              #js {:source "epupp-page"
                                   :type "ws-send"
                                   :data data}
                              "*"))))

      ;; Add close method
      ;; Note: This only closes the page-side proxy, not the background WebSocket.
      ;; The background WebSocket is cleaned up when:
      ;; - A new connection is requested (handle-ws-connect calls close-ws! first)
      ;; - The tab is closed (onRemoved listener)
      ;; This design avoids extra message round-trips for the common reconnect case.
      (set! (.-close ws-obj)
            (fn []
              (set! (.-readyState ws-obj) 3) ; CLOSED
              (when-let [handler (:ws/message-handler @!state)]
                (.removeEventListener js/window "message" handler)
                (swap! !state assoc :ws/message-handler nil))
              (when-let [onclose (.-onclose ws-obj)]
                (onclose))))

      ;; Request connection through bridge
      (.postMessage js/window
                    #js {:source "epupp-page"
                         :type "ws-connect"
                         :port port}
                    "*"))

    ws-obj))

;; Initialize - guard against multiple injections
(when-not js/window.__browserJackInWSBridge
  (set! js/window.__browserJackInWSBridge true)
  (bridge-log :debug "Installing WebSocket bridge")

  ;; Wait for bridge ready signal
  (.addEventListener js/window "message" handle-bridge-ready)

  ;; Store original WebSocket
  (set! (.-_OriginalWebSocket js/window) js/WebSocket)

  ;; Override WebSocket for nREPL URLs only
  (set! js/WebSocket
        (fn [url protocols]
          (if (and (string? url) (.includes url "/_nrepl"))
            (do
              (bridge-log :debug "Intercepting nREPL WebSocket:" url)
              (let [ws (bridged-websocket url)]
            ;; Store reference for Scittle's usage
                (set! (.-ws_nrepl js/window) ws)
                ws))
            (new (.-_OriginalWebSocket js/window) url protocols))))

  ;; Copy static properties
  (set! (.-CONNECTING js/WebSocket) 0)
  (set! (.-OPEN js/WebSocket) 1)
  (set! (.-CLOSING js/WebSocket) 2)
  (set! (.-CLOSED js/WebSocket) 3)

  (bridge-log :debug "WebSocket bridge installed"))
