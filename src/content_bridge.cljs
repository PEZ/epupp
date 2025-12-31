(ns content-bridge
  "Content script bridge for WebSocket connections.
   Runs in ISOLATED world, relays messages between page (MAIN) and background service worker.")

(js/console.log "[Browser Jack-in Bridge] Content script loaded")

(def !connected (atom false))

(defn same-window?
  "Check if event came from same window, safely handling cross-origin frames."
  [event]
  (try
    (identical? (.-source event) js/window)
    (catch :default _
      false)))

;; Listen for messages from page
(.addEventListener js/window "message"
                   (fn [event]
                     (when (same-window? event)
                       (let [msg (.-data event)]
                         (when (and msg (= "browser-jack-in-page" (.-source msg)))
                           (case (.-type msg)
                             "ws-connect"
                             (do
                               (js/console.log "[Bridge] Requesting connection to port:" (.-port msg))
                               (reset! !connected true)
                               (js/chrome.runtime.sendMessage
                                #js {:type "ws-connect"
                                     :port (.-port msg)}))

                             "ws-send"
                             (when @!connected
                               (js/chrome.runtime.sendMessage
                                #js {:type "ws-send"
                                     :data (.-data msg)}))

                             nil))))))

;; Notify page that bridge is ready
(.addListener js/chrome.runtime.onMessage
              (fn [message _sender _send-response]
                (let [msg-type (.-type message)]
                  (case msg-type
                    "ws-open"
                    (do
                      (js/console.log "[Bridge] WebSocket connected")
                      (.postMessage js/window
                                    #js {:source "browser-jack-in-bridge"
                                         :type "ws-open"}
                                    "*"))

                    "ws-message"
                    (.postMessage js/window
                                  #js {:source "browser-jack-in-bridge"
                                       :type "ws-message"
                                       :data (.-data message)}
                                  "*")

                    "ws-error"
                    (do
                      (js/console.error "[Bridge] WebSocket error:" (.-error message))
                      (reset! !connected false)
                      (.postMessage js/window
                                    #js {:source "browser-jack-in-bridge"
                                         :type "ws-error"
                                         :error (.-error message)}
                                    "*"))

                    "ws-close"
                    (do
                      (js/console.log "[Bridge] WebSocket closed")
                      (reset! !connected false)
                      (.postMessage js/window
                                    #js {:source "browser-jack-in-bridge"
                                         :type "ws-close"}
                                    "*"))

                    nil))
                false))

(.postMessage js/window
              #js {:source "browser-jack-in-bridge"
                   :type "bridge-ready"}
              "*")
