(ns panel-test
  "E2E tests for DevTools panel - user journey style.

   Strategy: Load panel.html directly as extension page, inject mock
   chrome.devtools APIs via addInitScript before panel.js loads.

   Tests are structured as user journeys - one browser context, multiple
   sequential operations that build on each other, like a real user session.

   What this doesn't test (use bb test:repl-e2e instead):
   - Actual code evaluation in inspected page
   - Real chrome.devtools.inspectedWindow.eval behavior"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              clear-storage sleep]]))

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
