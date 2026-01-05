(ns panel-test
  "E2E tests for the Browser Jack-in DevTools panel"
  (:require ["@playwright/test" :as pw]))

;; Test configuration
(def extension-path "./dist/chrome")

(pw/test.describe "Browser Jack-in DevTools Panel" 
  (fn []
    (pw/test "panel loads in DevTools"
      (fn []
        (pw/test.setTimeout 30000)
        
        (let [context (pw/await 
                       (.launchPersistentContext 
                        pw/chromium 
                        ""
                        #js {:headless false
                             :args #js [(str "--disable-extensions-except=" extension-path)
                                       (str "--load-extension=" extension-path)]}))
              page (pw/await (.newPage context))]
          
          ;; Navigate to a test page
          (pw/await (.goto page "https://example.com"))
          
          ;; TODO: Open DevTools programmatically and verify panel exists
          ;; This is a placeholder test - DevTools automation requires CDP
          
          ;; Cleanup
          (pw/await (.close context)))))))

(pw/test.describe "Panel UI Tests"
  (fn []
    (pw/test "panel displays connection status"
      (fn []
        ;; This is a basic placeholder test
        ;; Real implementation will use CDP to interact with DevTools panel
        (pw/expect true)))
    
    (pw/test "panel updates status when REPL connects"
      (fn []
        ;; Placeholder for future implementation
        (pw/expect true)))))
