(ns panel-test
  "Testing DevTools panel UI with mocked chrome.devtools APIs.

     Strategy: Load panel.html directly as extension page, inject mock
     chrome.devtools APIs via addInitScript before panel.js loads.

     This enables testing:
     - Panel UI rendering and interactions
     - Form inputs (code, script name, URL pattern)
     - Button clicks (Eval, Clear, Save, Use URL)
     - State management and storage integration

     What this doesn't test (use bb test:repl-e2e instead):
     - Actual code evaluation in inspected page
     - Real chrome.devtools.inspectedWindow.eval behavior"
  (:require ["@playwright/test" :refer [test expect chromium]]
              ["path" :as path]
              ["url" :as url]))

(def ^:private __dirname
  (path/dirname (url/fileURLToPath js/import.meta.url)))

(def extension-path
  (path/resolve __dirname ".." ".." "dist" "chrome"))

(defn ^:async sleep [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async get-extension-id [context]
  (let [workers (.serviceWorkers context)]
    (if (pos? (.-length workers))
      (-> (aget workers 0) (.url) (.split "/") (aget 2))
      (let [sw (js-await (.waitForEvent context "serviceworker"))]
        (-> (.url sw) (.split "/") (aget 2))))))

(defn ^:async launch-browser []
  (js-await
   (.launchPersistentContext
    chromium
    ""
    #js {:headless false
         :args #js ["--no-sandbox"
                    (str "--disable-extensions-except=" extension-path)
                    (str "--load-extension=" extension-path)]})))

;; Mock script for chrome.devtools APIs
(def mock-devtools-script "
  // Mock chrome.devtools APIs for panel testing
  window.__mockEvalCalls = [];
  window.__mockHostname = 'test.example.com';

  if (!chrome.devtools) {
    chrome.devtools = {
      inspectedWindow: {
        eval: function(expr, callback) {
          window.__mockEvalCalls.push(expr);
          if (expr.includes('location.hostname')) {
            callback(window.__mockHostname, undefined);
          } else if (expr.includes('location.href')) {
            // Full URL for use-current-url
            callback('https://test.example.com/some/path', undefined);
          } else if (expr.includes('scittle') && !expr.includes('eval_string')) {
            // Scittle status check - report as loaded
            callback({ hasScittle: true, hasNrepl: false }, undefined);
          } else if (expr.includes('eval_string')) {
            // Code evaluation - return mock success
            callback({ value: 'mock-result', error: null }, undefined);
          } else {
            callback(undefined, undefined);
          }
        },
        tabId: 99999
      },
      network: {
        onNavigated: { addListener: function() {} }
      },
      panels: { themeName: 'dark' }
    };
  }
")

(defn ^:async create-panel-page
  "Create a page with mocked chrome.devtools and navigate to panel.html"
  [context ext-id]
  (let [panel-page (js-await (.newPage context))
        panel-url (str "chrome-extension://" ext-id "/panel.html")]
    (js-await (.addInitScript panel-page mock-devtools-script))
    (js-await (.goto panel-page panel-url #js {:timeout 10000}))
    (js-await (sleep 1500))  ;; Let panel initialize
    panel-page))

;; =============================================================================
;; Tests
;; =============================================================================

(test "Panel renders with mocked chrome.devtools"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel-page (js-await (create-panel-page context ext-id))]
              ;; Check essential UI elements rendered
              (js-await (-> (expect (.locator panel-page "textarea"))
                            (.toBeVisible)))
              (js-await (-> (expect (.locator panel-page "button.btn-eval"))
                            (.toBeVisible)))
              (js-await (-> (expect (.locator panel-page "button.btn-clear"))
                            (.toBeVisible)))
              (js-await (-> (expect (.locator panel-page "#script-name"))
                            (.toBeVisible)))
              (js-await (-> (expect (.locator panel-page "#script-match"))
                            (.toBeVisible))))
            (finally
              (js-await (.close context)))))))

(test "Code textarea accepts input"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel-page (js-await (create-panel-page context ext-id))
                  textarea (.locator panel-page "textarea")]
              (js-await (.fill textarea "(+ 1 2 3)"))
              (js-await (-> (expect textarea) (.toHaveValue "(+ 1 2 3)"))))
            (finally
              (js-await (.close context)))))))

(test "Clear button clears results area"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel-page (js-await (create-panel-page context ext-id))
                  textarea (.locator panel-page "textarea")
                  eval-btn (.locator panel-page "button.btn-eval")
                  clear-btn (.locator panel-page "button.btn-clear")]
              ;; Enter code and evaluate to create results
              (js-await (.fill textarea "(+ 1 2)"))
              (js-await (.click eval-btn))
              (js-await (sleep 500))
              ;; Results should contain the evaluated code
              (js-await (-> (expect (.locator panel-page ".results-area"))
                            (.toContainText "(+ 1 2)")))
              ;; Click clear
              (js-await (.click clear-btn))
              (js-await (sleep 300))
              ;; Results should no longer contain the code (shows empty logos instead)
              (js-await (-> (expect (.locator panel-page ".results-area"))
                            (.not.toContainText "(+ 1 2)"))))
            (finally
              (js-await (.close context)))))))

(test "Eval button triggers evaluation"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel-page (js-await (create-panel-page context ext-id))
                  textarea (.locator panel-page "textarea")
                  eval-btn (.locator panel-page "button.btn-eval")]
              ;; Enter code
              (js-await (.fill textarea "(+ 1 2)"))
              ;; Click eval
              (js-await (.click eval-btn))
              (js-await (sleep 500))
              ;; Results area should appear with input echo
              (let [results (.locator panel-page ".results-area")]
                (js-await (-> (expect results) (.toBeVisible)))
                (js-await (-> (expect results) (.toContainText "(+ 1 2)")))))
            (finally
              (js-await (.close context)))))))

(test "Save script form accepts input"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel-page (js-await (create-panel-page context ext-id))
                  name-input (.locator panel-page "#script-name")
                  match-input (.locator panel-page "#script-match")]
              (js-await (.fill name-input "My Test Script"))
              (js-await (.fill match-input "*://example.com/*"))
              (js-await (-> (expect name-input) (.toHaveValue "My Test Script")))
              (js-await (-> (expect match-input) (.toHaveValue "*://example.com/*"))))
            (finally
              (js-await (.close context)))))))

(test "Use URL button fills pattern from mock hostname"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel-page (js-await (create-panel-page context ext-id))
                  match-input (.locator panel-page "#script-match")
                  use-url-btn (.locator panel-page "button.btn-use-url")]
              (js-await (.click use-url-btn))
              (js-await (sleep 200))
              ;; Check the value contains the mock hostname
              (let [value (js-await (.inputValue match-input))]
                (js-await (-> (expect (.includes value "test.example.com"))
                              (.toBeTruthy)))))
            (finally
              (js-await (.close context)))))))

(test "Panel shows Ready status when Scittle detected"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel-page (js-await (create-panel-page context ext-id))
                  status (.locator panel-page ".panel-status")]
              ;; Mock returns hasScittle: true, so status should show Ready
              (js-await (-> (expect status) (.toContainText "Ready"))))
            (finally
              (js-await (.close context)))))))
