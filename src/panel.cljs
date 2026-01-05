(ns panel
  "DevTools panel for Browser Jack-in")

;; State management for panel
(defonce !state
  (atom {:connection/status "Not connected"
         :connection/color "#666"
         :messages/count 0}))

;; Check if we're in a DevTools context
(when (and js/chrome js/chrome.devtools)
  (println "Browser Jack-in DevTools panel loaded")
  
  ;; Get the inspected tab ID
  (def inspected-tab-id js/chrome.devtools.inspectedWindow.tabId)
  
  ;; Listen for messages from background/content scripts
  (when js/chrome.runtime
    (.addListener js/chrome.runtime.onMessage
      (fn [message _sender _sendResponse]
        (let [msg-type (.-type message)]
          (case msg-type
            "ws-open" 
            (swap! !state assoc 
                   :connection/status "Connected"
                   :connection/color "#0a0")
            
            "ws-close"
            (swap! !state assoc
                   :connection/status "Disconnected"
                   :connection/color "#c00")
            
            "ws-message"
            (swap! !state update :messages/count inc)
            
            nil))
        (render-panel!))))
  
  (defn render-panel! []
    "Render the panel UI"
    (let [container (.getElementById js/document "panel-container")
          state @!state]
      (when container
        (set! (.-innerHTML container)
              (str "<div style='padding: 20px; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif;'>
                      <h2 style='margin-top: 0;'>Browser Jack-in DevTools Panel</h2>
                      <p>Monitor and control the Browser Jack-in REPL connection for this tab.</p>
                      <div style='border: 1px solid #ddd; border-radius: 4px; padding: 15px; margin: 20px 0;'>
                        <div style='margin-bottom: 10px;'>
                          <strong>Tab ID:</strong> <code>" inspected-tab-id "</code>
                        </div>
                        <div style='margin-bottom: 10px;'>
                          <strong>Connection Status:</strong> 
                          <span id='status-indicator' style='display: inline-block; width: 10px; height: 10px; border-radius: 50%; background: " (:connection/color state) "; margin: 0 8px;'></span>
                          <span id='status-text'>" (:connection/status state) "</span>
                        </div>
                        <div>
                          <strong>Messages Received:</strong> <span id='message-count'>" (:messages/count state) "</span>
                        </div>
                      </div>
                      <div style='color: #666; font-size: 0.9em;'>
                        <p><strong>Tip:</strong> Use the extension popup to connect the REPL to this tab.</p>
                      </div>
                    </div>")))))
  
  ;; Initial render
  (render-panel!))

