(ns panel
  "DevTools panel for Browser Jack-in")

;; Check if we're in a DevTools context
(when (and js/chrome js/chrome.devtools)
  (println "Browser Jack-in DevTools panel loaded")
  
  ;; Create a simple panel UI
  (let [container (.getElementById js/document "panel-container")]
    (when container
      (set! (.-innerHTML container)
            "<div style='padding: 20px; font-family: monospace;'>
               <h2>Browser Jack-in DevTools Panel</h2>
               <p>This panel helps you inspect and control the Browser Jack-in REPL connection.</p>
               <div id='connection-status'>
                 <strong>Status:</strong> <span id='status-text'>Not connected</span>
               </div>
             </div>"))))
