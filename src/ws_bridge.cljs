(ns ws-bridge
  "WebSocket bridge wrapper for page context.
   Runs in MAIN world and communicates with content script bridge via postMessage.")

(js/console.log "[Browser Jack-in] Installing WebSocket bridge")

(def !bridge-ready (atom false))

;; Wait for bridge ready signal
(.addEventListener js/window "message"
  (fn [event]
    (when (= (.-source event) js/window)
      (let [msg (.-data event)]
        (when (and msg
                   (= "browser-jack-in-bridge" (.-source msg))
                   (= "bridge-ready" (.-type msg)))
          (js/console.log "[WS Bridge] Bridge is ready")
          (reset! !bridge-ready true))))))

(defn bridged-websocket [url]
  (js/console.log "[WS Bridge] Creating bridged WebSocket for:" url)

  (let [ws-obj (js-obj)
        message-handler (atom nil)]

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
                 "1340")]

      ;; Set up message listener
      (reset! message-handler
        (fn [event]
          (when (= (.-source event) js/window)
            (let [msg (.-data event)]
              (when (and msg (= "browser-jack-in-bridge" (.-source msg)))
                (case (.-type msg)
                  "ws-open"
                  (do
                    (js/console.log "[WS Bridge] WebSocket OPEN")
                    (set! (.-readyState ws-obj) 1) ; OPEN
                    (when-let [onopen (.-onopen ws-obj)]
                      (onopen)))

                  "ws-message"
                  (when-let [onmessage (.-onmessage ws-obj)]
                    (onmessage #js {:data (.-data msg)}))

                  "ws-error"
                  (do
                    (js/console.log "[WS Bridge] WebSocket ERROR")
                    (set! (.-readyState ws-obj) 3) ; CLOSED
                    (when-let [onerror (.-onerror ws-obj)]
                      (onerror (js/Error. (or (.-error msg) "WebSocket error")))))

                  "ws-close"
                  (do
                    (js/console.log "[WS Bridge] WebSocket CLOSED")
                    (set! (.-readyState ws-obj) 3) ; CLOSED
                    (when-let [onclose (.-onclose ws-obj)]
                      (onclose)))

                  nil))))))

      (.addEventListener js/window "message" @message-handler)

      ;; Add send method
      (set! (.-send ws-obj)
        (fn [data]
          (when (= 1 (.-readyState ws-obj)) ; OPEN
            (.postMessage js/window
              #js {:source "browser-jack-in-page"
                   :type "ws-send"
                   :data data}
              "*"))))

      ;; Add close method
      (set! (.-close ws-obj)
        (fn []
          (set! (.-readyState ws-obj) 3) ; CLOSED
          (.removeEventListener js/window "message" @message-handler)
          (when-let [onclose (.-onclose ws-obj)]
            (onclose))))

      ;; Request connection through bridge
      (.postMessage js/window
        #js {:source "browser-jack-in-page"
             :type "ws-connect"
             :port port}
        "*"))

    ws-obj))

;; Store original WebSocket
(set! (.-_OriginalWebSocket js/window) js/WebSocket)

;; Override WebSocket for nREPL URLs only
(set! js/WebSocket
  (fn [url protocols]
    (if (and (string? url) (.includes url "/_nrepl"))
      (do
        (js/console.log "[WS Bridge] Intercepting nREPL WebSocket:" url)
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

(js/console.log "[WS Bridge] WebSocket bridge installed")
