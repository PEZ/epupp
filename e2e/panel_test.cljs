(ns panel-test
  "E2E tests for DevTools panel - user journey style.

   Strategy: Load panel.html directly as extension page, inject mock
   chrome.devtools APIs via addInitScript before panel.js loads.

   What this doesn't test (use bb test:repl-e2e instead):
   - Actual code evaluation in inspected page
   - Real chrome.devtools.inspectedWindow.eval behavior"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              clear-storage sleep]]))

;; =============================================================================
;; Panel User Journey: Evaluation and Save
;; =============================================================================

(test "Panel: evaluation and save workflow"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "textarea")
                  eval-btn (.locator panel "button.btn-eval")
                  clear-btn (.locator panel "button.btn-clear")
                  results (.locator panel ".results-area")
                  status (.locator panel ".panel-status")
                  name-input (.locator panel "#script-name")
                  match-input (.locator panel "#script-match")
                  use-url-btn (.locator panel "button.btn-use-url")
                  save-btn (.locator panel "button.btn-save")]

              ;; Clear storage for clean slate
              (js-await (clear-storage panel))

              ;; === EVALUATION WORKFLOW ===

              ;; 1. Panel renders with all UI elements
              (js-await (-> (expect textarea) (.toBeVisible)))
              (js-await (-> (expect eval-btn) (.toBeVisible)))
              (js-await (-> (expect clear-btn) (.toBeVisible)))
              (js-await (-> (expect name-input) (.toBeVisible)))
              (js-await (-> (expect match-input) (.toBeVisible)))

              ;; 2. Status shows Ready (mock returns hasScittle: true)
              (js-await (-> (expect status) (.toContainText "Ready")))

              ;; 3. Enter and evaluate code
              (js-await (.fill textarea "(+ 1 2 3)"))
              (js-await (.click eval-btn))
              (js-await (sleep 300))
              (js-await (-> (expect results) (.toContainText "(+ 1 2 3)")))

              ;; 4. Clear results
              (js-await (.click clear-btn))
              (js-await (sleep 200))
              (js-await (-> (expect results) (.not.toContainText "(+ 1 2 3)")))

              ;; === SAVE WORKFLOW ===

              ;; 5. Save button disabled with empty name/pattern
              (js-await (-> (expect save-btn) (.toBeDisabled)))

              ;; 6. Fill save form
              (js-await (.fill textarea "(println \"My userscript\")"))
              (js-await (.fill name-input "Test Userscript"))
              (js-await (.click use-url-btn))
              (js-await (sleep 200))
              (let [match-value (js-await (.inputValue match-input))]
                (js-await (-> (expect (.includes match-value "test.example.com"))
                              (.toBeTruthy))))

              ;; 7. Save and verify
              (js-await (-> (expect save-btn) (.toBeEnabled)))
              (js-await (.click save-btn))
              (js-await (sleep 300))
              (js-await (-> (expect (.locator panel ".save-status"))
                            (.toContainText "Saved")))

              ;; 8. Form fields cleared after save
              (js-await (-> (expect name-input) (.toHaveValue "")))
              (js-await (-> (expect match-input) (.toHaveValue ""))))
            (finally
              (js-await (.close context)))))))
