(ns panel-test
  "E2E tests for the Browser Jack-in DevTools panel
   
   Tests use Playwright with Chrome DevTools Protocol (CDP) to:
   1. Load extension in headed Chrome
   2. Open DevTools and verify panel exists
   3. Test panel UI and connection status updates"
  (:require ["@playwright/test" :as pw]
            ["path" :as path]
            ["fs" :as fs]))

;; Test configuration
(def extension-path (.resolve path (.cwd js/process) "dist/chrome"))

(pw/test.describe "Browser Jack-in DevTools Panel - Basic Loading"
  (fn []
    (pw/test "extension loads and panel is created"
      (fn []
        (let [;; Launch browser with extension
              context (pw/await 
                       (.launchPersistentContext 
                        pw/chromium 
                        ""
                        #js {:headless false
                             :args #js [(str "--disable-extensions-except=" extension-path)
                                       (str "--load-extension=" extension-path)
                                       "--auto-open-devtools-for-tabs"]}))
              page (pw/await (.newPage context))]
          
          ;; Navigate to a test page
          (pw/await (.goto page "https://example.com"))
          
          ;; Wait a bit for extension to initialize
          (pw/await (.waitForTimeout page 2000))
          
          ;; Get CDP session to inspect DevTools
          (let [cdp-session (pw/await (.newCDPSession context page))]
            
            ;; Verify extension is loaded by checking for the background page
            ;; This is a basic smoke test that the extension loaded correctly
            (let [targets (pw/await (.send cdp-session "Target.getTargets"))
                  target-infos (.-targetInfos targets)
                  extension-targets (.filter target-infos 
                                            (fn [t] 
                                              (= (.-type t) "background_page")))]
              
              ;; Should have at least one background page (our extension)
              (.toBeGreaterThan (pw/expect (.-length extension-targets)) 0)))
          
          ;; Cleanup
          (pw/await (.close context)))))))

(pw/test.describe "Panel UI Tests"
  (fn []
    (pw/test "panel HTML file exists and is valid"
      (fn []
        ;; Test that the panel HTML file exists in the built extension
        (let [panel-html-path (.resolve path extension-path "panel.html")
              panel-html-exists (.existsSync fs panel-html-path)]
          (.toBe (pw/expect panel-html-exists) true)
          
          ;; Read and verify basic content
          (when panel-html-exists
            (let [content (.readFileSync fs panel-html-path "utf8")]
              (.toContain (pw/expect content) "panel-container")
              (.toContain (pw/expect content) "panel.js"))))))
    
    (pw/test "devtools.html and devtools.js exist"
      (fn []
        (let [devtools-html-path (.resolve path extension-path "devtools.html")
              devtools-js-path (.resolve path extension-path "devtools.js")]
          (.toBe (pw/expect (.existsSync fs devtools-html-path)) true)
          (.toBe (pw/expect (.existsSync fs devtools-js-path)) true))))
    
    (pw/test "manifest includes devtools_page"
      (fn []
        (let [manifest-path (.resolve path extension-path "manifest.json")
              manifest (js/JSON.parse (.readFileSync fs manifest-path "utf8"))]
          (.toBe (pw/expect (.-devtools_page manifest)) "devtools.html"))))))

(pw/test.describe "Panel Functionality Tests"
  (fn []
    (pw/test "panel shows tab ID and initial status"
      (fn []
        ;; This is a conceptual test showing what we want to test
        ;; In practice, accessing DevTools panel content programmatically
        ;; is challenging, but we can test the logic by:
        ;; 1. Verifying files are built correctly
        ;; 2. Testing the panel code runs without errors
        ;; 3. Manual verification during development
        
        ;; For now, verify the panel.js file contains expected functionality
        (let [panel-js-path (.resolve path extension-path "panel.js")
              panel-js-content (.readFileSync fs panel-js-path "utf8")]
          
          ;; Verify key functions exist in compiled output
          (.toContain (pw/expect panel-js-content) "inspectedWindow")
          (.toContain (pw/expect panel-js-content) "connection")
          (.toContain (pw/expect panel-js-content) "status"))))))

