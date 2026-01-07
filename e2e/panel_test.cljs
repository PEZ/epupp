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
                  textarea (.locator panel "#code-area")
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

              ;; 8. Form fields kept after save (normalized name shown)
              (js-await (-> (expect name-input) (.toHaveValue "test_userscript.cljs"))))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Panel User Journey: Script Name Normalization and Overwrite
;; =============================================================================

(test "Panel: script name normalization and overwrite behavior"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Create script with spaces and special chars ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (sleep 300))
              (js-await (.fill (.locator panel "#code-area") "(println \"version 1\")"))
              (js-await (.fill (.locator panel "#script-name") "My Cool Script!"))
              (js-await (.fill (.locator panel "#script-match") "*://example.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 300))
              ;; Verify save status shows normalized name
              (js-await (-> (expect (.locator panel ".save-status"))
                            (.toContainText "my_cool_script.cljs")))
              (js-await (.close panel)))

            ;; === PHASE 2: Verify in popup - name is normalized ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (sleep 500))
              ;; Script appears with normalized name
              (let [script-item (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")")]
                (js-await (-> (expect script-item) (.toBeVisible))))
              ;; Should have only 2 scripts: built-in Gist Installer + our script
              (js-await (-> (expect (.locator popup ".script-item")) (.toHaveCount 2)))
              (js-await (.close popup)))

            ;; === PHASE 3: Save with same name again (should overwrite) ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "#code-area") "(println \"version 2 - UPDATED\")"))
              ;; Use slightly different input that normalizes to same name
              (js-await (.fill (.locator panel "#script-name") "my-cool-script"))
              (js-await (.fill (.locator panel "#script-match") "*://updated.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 300))
              (js-await (.close panel)))

            ;; === PHASE 4: Verify overwrite - still only 1 user script ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (sleep 500))
              ;; Still 2 scripts total (not 3) - overwrite worked
              (js-await (-> (expect (.locator popup ".script-item")) (.toHaveCount 2)))
              ;; Script shows normalized name
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")"))
                            (.toBeVisible)))
              (js-await (.close popup)))

            ;; === PHASE 5: Edit script in panel and verify code was updated ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (sleep 300))
              ;; Click edit to send to panel
              (let [script-item (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")")
                    edit-btn (.locator script-item "button.script-edit")]
                (js-await (.click edit-btn))
                (js-await (sleep 200)))
              (js-await (.close popup)))

            ;; Open panel and verify edited script has updated code
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (sleep 500))
              ;; Code area should have the updated code from version 2
              (let [code-value (js-await (.inputValue (.locator panel "#code-area")))]
                (js-await (-> (expect (.includes code-value "version 2 - UPDATED"))
                              (.toBeTruthy))))
              ;; Match pattern should be updated too
              (let [match-value (js-await (.inputValue (.locator panel "#script-match")))]
                (js-await (-> (expect (.includes match-value "updated.com"))
                              (.toBeTruthy))))
              (js-await (.close panel)))

            (finally
              (js-await (.close context)))))))
