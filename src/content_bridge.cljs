(ns content-bridge
  "Content script bridge for WebSocket connections.
   Runs in ISOLATED world with extension CSP, bridges to MAIN world via postMessage.")

(js/console.log "[Browser Jack-in Bridge] Content script loaded")

(def !ws (atom nil))

;; Listen for messages from page
(.addEventListener js/window "message"
  (fn [event]
    ;; Only accept messages from same origin
    (when (= (.-source event) js/window)
      (let [msg (.-data event)]
        (when (and msg (= "browser-jack-in-page" (.-source msg)))
          (js/console.log "[Bridge] Received from page:" (.-type msg))

          (case (.-type msg)
            "ws-connect"
            (let [ws-url (str "ws://localhost:" (.-port msg) "/_nrepl")]
              (js/console.log "[Bridge] Connecting to:" ws-url)
              (try
                (let [ws (js/WebSocket. ws-url)]
                  (reset! !ws ws)
                  (set! (.-onopen ws)
                        (fn []
                          (js/console.log "[Bridge] WebSocket connected")
                          (.postMessage js/window
                                        #js {:source "browser-jack-in-bridge"
                                             :type "ws-open"}
                                        "*")))
                  (set! (.-onmessage ws)
                        (fn [event]
                          (.postMessage js/window
                                        #js {:source "browser-jack-in-bridge"
                                             :type "ws-message"
                                             :data (.-data event)}
                                        "*")))
                  (set! (.-onerror ws)
                        (fn [error]
                          (js/console.error "[Bridge] WebSocket error:" error)
                          (.postMessage js/window
                                        #js {:source "browser-jack-in-bridge"
                                             :type "ws-error"
                                             :error (str error)}
                                        "*")))
                  (set! (.-onclose ws)
                        (fn []
                          (js/console.log "[Bridge] WebSocket closed")
                          (.postMessage js/window
                                        #js {:source "browser-jack-in-bridge"
                                             :type "ws-close"}
                                        "*")
                          (reset! !ws nil))))
                (catch :default e
                  (js/console.error "[Bridge] Failed to create WebSocket:" e)
                  (.postMessage js/window
                    #js {:source "browser-jack-in-bridge"
                         :type "ws-error"
                         :error (str e)}
                    "*"))))

            "ws-send"
            (when-let [ws @!ws]
              (when (= 1 (.-readyState ws)) ; OPEN
                (.send ws (.-data msg))))

            nil))))))

;; Notify page that bridge is ready
(.postMessage js/window
  #js {:source "browser-jack-in-bridge"
       :type "bridge-ready"}
  "*")
