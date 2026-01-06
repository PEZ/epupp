(ns panel-test
  "E2E tests for DevTools panel and popup - user journey style.

   Strategy: Load panel.html directly as extension page, inject mock
   chrome.devtools APIs via addInitScript before panel.js loads.

   Tests are structured as user journeys - one browser context, multiple
   sequential operations that build on each other, like a real user session.

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
            callback('https://test.example.com/some/path', undefined);
          } else if (expr.includes('scittle') && !expr.includes('eval_string')) {
            callback({ hasScittle: true, hasNrepl: false }, undefined);
          } else if (expr.includes('eval_string')) {
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
    (js-await (sleep 1500))
    panel-page))

(defn ^:async create-popup-page
  "Create a popup page"
  [context ext-id]
  (let [popup-page (js-await (.newPage context))
        popup-url (str "chrome-extension://" ext-id "/popup.html")]
    (js-await (.goto popup-page popup-url #js {:timeout 10000}))
    (js-await (sleep 1000))
    popup-page))

(defn ^:async clear-storage
  "Clear extension storage to ensure clean test state"
  [page]
  (js-await (.evaluate page "() => chrome.storage.local.clear()")))

;; =============================================================================
;; Panel User Journey: Code Evaluation Workflow
;; =============================================================================

(test "Panel: code evaluation workflow"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "textarea")
                  eval-btn (.locator panel "button.btn-eval")
                  clear-btn (.locator panel "button.btn-clear")
                  results (.locator panel ".results-area")
                  status (.locator panel ".panel-status")]

              ;; 1. Panel renders with all UI elements
              (js-await (-> (expect textarea) (.toBeVisible)))
              (js-await (-> (expect eval-btn) (.toBeVisible)))
              (js-await (-> (expect clear-btn) (.toBeVisible)))
              (js-await (-> (expect (.locator panel "#script-name")) (.toBeVisible)))
              (js-await (-> (expect (.locator panel "#script-match")) (.toBeVisible)))

              ;; 2. Status shows Ready (mock returns hasScittle: true)
              (js-await (-> (expect status) (.toContainText "Ready")))

              ;; 3. Enter code in textarea
              (js-await (.fill textarea "(+ 1 2 3)"))
              (js-await (-> (expect textarea) (.toHaveValue "(+ 1 2 3)")))

              ;; 4. Click eval - results should show input echo
              (js-await (.click eval-btn))
              (js-await (sleep 500))
              (js-await (-> (expect results) (.toBeVisible)))
              (js-await (-> (expect results) (.toContainText "(+ 1 2 3)")))

              ;; 5. Evaluate more code - results accumulate
              (js-await (.fill textarea "(str \"hello\" \" world\")"))
              (js-await (.click eval-btn))
              (js-await (sleep 500))
              (js-await (-> (expect results) (.toContainText "hello")))

              ;; 6. Clear results - code stays, results go
              (js-await (.click clear-btn))
              (js-await (sleep 300))
              (js-await (-> (expect results) (.not.toContainText "(+ 1 2 3)")))
              (js-await (-> (expect results) (.not.toContainText "hello")))
              ;; Textarea still has last code
              (js-await (-> (expect textarea) (.toHaveValue "(str \"hello\" \" world\")"))))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Panel User Journey: Save Script Workflow
;; =============================================================================

(test "Panel: save script workflow"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "textarea")
                  name-input (.locator panel "#script-name")
                  match-input (.locator panel "#script-match")
                  use-url-btn (.locator panel "button.btn-use-url")
                  save-btn (.locator panel "button.btn-save")]

              ;; Clear storage for clean slate
              (js-await (clear-storage panel))

              ;; 1. Save button should be disabled with empty fields
              (js-await (-> (expect save-btn) (.toBeDisabled)))

              ;; 2. Fill in code
              (js-await (.fill textarea "(println \"My userscript\")"))

              ;; 3. Fill in script name
              (js-await (.fill name-input "Test Userscript"))
              (js-await (-> (expect name-input) (.toHaveValue "Test Userscript")))

              ;; 4. Use URL button fills pattern from mock hostname
              (js-await (.click use-url-btn))
              (js-await (sleep 200))
              (let [match-value (js-await (.inputValue match-input))]
                (js-await (-> (expect (.includes match-value "test.example.com"))
                              (.toBeTruthy))))

              ;; 5. Save button now enabled - click it
              (js-await (-> (expect save-btn) (.toBeEnabled)))
              (js-await (.click save-btn))
              (js-await (sleep 500))

              ;; 6. Success message appears
              (js-await (-> (expect (.locator panel ".save-status"))
                            (.toContainText "Saved")))

              ;; 7. Form fields cleared after save
              (js-await (-> (expect name-input) (.toHaveValue "")))
              (js-await (-> (expect match-input) (.toHaveValue ""))))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Integration: Script Lifecycle (save, view, enable/disable, edit, delete)
;; =============================================================================

(test "Integration: script lifecycle - save, view, toggle, edit, delete"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Start with clean storage
            (let [temp-page (js-await (.newPage context))]
              (js-await (.goto temp-page (str "chrome-extension://" ext-id "/popup.html")))
              (js-await (clear-storage temp-page))
              (js-await (.close temp-page)))

            ;; === PHASE 1: Save script from panel ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "textarea") "(println \"Original code\")"))
              (js-await (.fill (.locator panel "#script-name") "Lifecycle Test"))
              (js-await (.fill (.locator panel "#script-match") "*://lifecycle.test/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 500))
              (js-await (-> (expect (.locator panel ".save-status"))
                            (.toContainText "Saved")))
              (js-await (.close panel)))

            ;; === PHASE 2: Verify script in popup, test enable/disable ===
            (let [popup (js-await (create-popup-page context ext-id))
                  script-item (.locator popup ".script-item")
                  checkbox (.locator script-item "input[type='checkbox']")
                  edit-btn (.locator script-item "button.script-edit")
                  delete-btn (.locator script-item "button.script-delete")]

              ;; Script appears in list with pattern
              (js-await (-> (expect script-item) (.toContainText "Lifecycle Test")))
              (js-await (-> (expect script-item) (.toContainText "*://lifecycle.test/*")))

              ;; Has enable checkbox (unchecked by default)
              (js-await (-> (expect checkbox) (.toBeVisible)))
              (js-await (-> (expect checkbox) (.not.toBeChecked)))

              ;; Has edit and delete buttons
              (js-await (-> (expect edit-btn) (.toBeVisible)))
              (js-await (-> (expect delete-btn) (.toBeVisible)))

              ;; Enable script
              (js-await (.click checkbox))
              (js-await (sleep 300))
              (js-await (-> (expect checkbox) (.toBeChecked)))

              ;; Disable script
              (js-await (.click checkbox))
              (js-await (sleep 300))
              (js-await (-> (expect checkbox) (.not.toBeChecked)))

              (js-await (.close popup)))

            ;; === PHASE 3: Edit script - panel receives it ===
            (let [panel (js-await (create-panel-page context ext-id))
                  popup (js-await (create-popup-page context ext-id))
                  edit-btn (.locator popup "button.script-edit")]

              ;; Click edit in popup
              (js-await (.click edit-btn))
              (js-await (sleep 500))

              ;; Panel should receive the script
              (js-await (-> (expect (.locator panel "textarea"))
                            (.toHaveValue "(println \"Original code\")")))
              (js-await (-> (expect (.locator panel "#script-name"))
                            (.toHaveValue "Lifecycle Test")))

              ;; Modify and save
              (js-await (.fill (.locator panel "textarea") "(println \"Updated code\")"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 500))

              (js-await (.close popup))
              (js-await (.close panel)))

            ;; === PHASE 4: Delete script ===
            (let [popup (js-await (create-popup-page context ext-id))
                  script-item (.locator popup ".script-item")
                  delete-btn (.locator script-item "button.script-delete")]

              ;; Script still exists
              (js-await (-> (expect script-item) (.toContainText "Lifecycle Test")))

              ;; Handle confirm dialog
              (.on popup "dialog" (fn [dialog] (.accept dialog)))

              ;; Delete
              (js-await (.click delete-btn))
              (js-await (sleep 500))

              ;; Script should be gone
              (js-await (-> (expect script-item) (.toHaveCount 0)))

              (js-await (.close popup)))
            (finally
              (js-await (.close context)))))))
