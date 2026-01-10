(ns panel-test
  "E2E tests for DevTools panel - user journey style.

   Strategy: Load panel.html directly as extension page, inject mock
   chrome.devtools APIs via addInitScript before panel.js loads.

   What this doesn't test (use bb test:repl-e2e instead):
   - Actual code evaluation in inspected page
   - Real chrome.devtools.inspectedWindow.eval behavior"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              clear-storage wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status wait-for-script-count wait-for-edit-hint]]))

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
                  status (.locator panel ".app-header-status")
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
              ;; Playwright auto-waits for assertion condition
              (js-await (-> (expect results) (.toContainText "(+ 1 2 3)")))

              ;; 4. Clear results
              (js-await (.click clear-btn))
              ;; Playwright auto-waits for the negative assertion too
              (js-await (-> (expect results) (.not.toContainText "(+ 1 2 3)")))

              ;; === SAVE WORKFLOW ===

              ;; 5. Save button disabled with empty name/pattern
              (js-await (-> (expect save-btn) (.toBeDisabled)))

              ;; 6. Fill save form
              (js-await (.fill textarea "(println \"My userscript\")"))
              (js-await (.fill name-input "Test Userscript"))
              (js-await (.click use-url-btn))
              ;; Wait for input to be populated with pattern
              (js-await (-> (expect match-input)
                            (.toHaveValue #"test\.example\.com")))
              (let [match-value (js-await (.inputValue match-input))]
                (js-await (-> (expect (.includes match-value "test.example.com"))
                              (.toBeTruthy))))

              ;; 7. Save and verify (first save = Created since it's a new script)
              (js-await (-> (expect save-btn) (.toBeEnabled)))
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "Created"))

              ;; 8. Form fields kept after save (normalized name shown)
              (js-await (-> (expect name-input) (.toHaveValue "test_userscript.cljs"))))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Panel User Journey: Create vs Save Behavior
;; =============================================================================

(test "Panel: create new script when name changed, save when unchanged"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Create initial script ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))
              (js-await (.fill (.locator panel "#code-area") "(println \"version 1\")"))
              (js-await (.fill (.locator panel "#script-name") "My Cool Script"))
              (js-await (.fill (.locator panel "#script-match") "*://example.com/*"))
              ;; Button should say "Save Script" for new script
              (js-await (-> (expect (.locator panel "button.btn-save"))
                            (.toContainText "Save Script")))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "my_cool_script.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 2: Edit script from popup ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              ;; Script appears with normalized name - click inspect
              (let [script-item (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            ;; === PHASE 3: Update code WITHOUT changing name - should save over existing ===
            (let [panel (js-await (create-panel-page context ext-id))]
              ;; Wait for script to load - check that name field is populated
              (js-await (-> (expect (.locator panel "#script-name"))
                            (.toHaveValue "my_cool_script.cljs")))
              ;; Script loaded for editing - button says "Save Script"
              (js-await (-> (expect (.locator panel "button.btn-save"))
                            (.toContainText "Save Script")))
              ;; Update just the code
              (js-await (.fill (.locator panel "#code-area") "(println \"version 2 - UPDATED\")"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "Saved"))
              (js-await (.close panel)))

            ;; === PHASE 4: Verify still only 1 user script (overwrite worked) ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              ;; Still 2 scripts total (built-in + our script)
              (js-await (wait-for-script-count popup 2))
              ;; Inspect again for next phase
              (let [script-item (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            ;; === PHASE 5: Change name while editing - should show "Create Script" ===
            (let [panel (js-await (create-panel-page context ext-id))]
              ;; Wait for script to load
              (js-await (-> (expect (.locator panel "#script-name"))
                            (.toHaveValue "my_cool_script.cljs")))
              ;; Initially "Save Script" since name matches
              (js-await (-> (expect (.locator panel "button.btn-save"))
                            (.toContainText "Save Script")))
              ;; Change the name
              (js-await (.fill (.locator panel "#script-name") "New Script Name"))
              ;; Button should change to "Create Script"
              (js-await (-> (expect (.locator panel "button.btn-save"))
                            (.toContainText "Create Script")))
              ;; Rename button should appear
              (js-await (-> (expect (.locator panel "button.btn-rename"))
                            (.toBeVisible)))
              ;; Click Create to make a new script
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "Created"))
              (js-await (.close panel)))

            ;; === PHASE 6: Verify both scripts exist (original + new) ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              ;; Now 3 scripts: built-in + original + new
              (js-await (wait-for-script-count popup 3))
              ;; Both user scripts visible
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")"))
                            (.toBeVisible)))
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"new_script_name.cljs\")"))
                            (.toBeVisible)))
              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Panel User Journey: Rename Behavior
;; =============================================================================

(test "Panel: rename script does not create duplicate"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Create initial script ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))
              (js-await (.fill (.locator panel "#code-area") "(println \"version 1\")"))
              (js-await (.fill (.locator panel "#script-name") "My Cool Script"))
              (js-await (.fill (.locator panel "#script-match") "*://example.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "my_cool_script.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 2: Edit script from popup ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              ;; Script appears with normalized name - click inspect
              (let [script-item (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            ;; === PHASE 3: Rename script in panel ===
            (let [panel (js-await (create-panel-page context ext-id))]
              ;; Wait for script to load
              (js-await (-> (expect (.locator panel "#script-name"))
                            (.toHaveValue "my_cool_script.cljs")))
              ;; Change the name
              (js-await (.fill (.locator panel "#script-name") "Renamed Script"))
              ;; Click Rename
              (js-await (.click (.locator panel "button.btn-rename")))
              (js-await (wait-for-save-status panel "Renamed"))
              (js-await (.close panel)))

            ;; === PHASE 4: Verify still only 2 scripts (built-in + renamed) ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              ;; Only 2 scripts: built-in + renamed
              (js-await (wait-for-script-count popup 2))
              ;; Renamed script visible
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"renamed_script.cljs\")"))
                            (.toBeVisible)))
              ;; Old name should NOT be present
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")"))
                            (.not.toBeVisible)))
              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Panel User Journey: Rename with Multiple Scripts
;; =============================================================================

(test "Panel: rename does not affect other scripts or trigger approvals"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Create first script ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))
              (js-await (.fill (.locator panel "#code-area") "(println \"script 1\")"))
              (js-await (.fill (.locator panel "#script-name") "First Script"))
              (js-await (.fill (.locator panel "#script-match") "*://example.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "first_script.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 2: Create second script ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (wait-for-panel-ready panel))
              (js-await (.fill (.locator panel "#code-area") "(println \"script 2\")"))
              (js-await (.fill (.locator panel "#script-name") "Second Script"))
              (js-await (.fill (.locator panel "#script-match") "*://github.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "second_script.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 3: Verify both scripts exist and are enabled ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              ;; 3 scripts: built-in + script1 + script2
              (js-await (wait-for-script-count popup 3))
              ;; No approval buttons visible (all scripts already approved by saving)
              (js-await (-> (expect (.locator popup "button:has-text(\"Allow\")")) (.toHaveCount 0)))
              (js-await (.close popup)))

            ;; === PHASE 4: Edit and rename first script ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              ;; Inspect first script
              (let [script-item (.locator popup ".script-item:has-text(\"first_script.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            (let [panel (js-await (create-panel-page context ext-id))]
              ;; Wait for script to load
              (js-await (-> (expect (.locator panel "#script-name"))
                            (.toHaveValue "first_script.cljs")))
              ;; Rename it
              (js-await (.fill (.locator panel "#script-name") "Renamed First Script"))
              (js-await (.click (.locator panel "button.btn-rename")))
              (js-await (wait-for-save-status panel "Renamed"))
              (js-await (.close panel)))

            ;; === PHASE 5: Verify rename worked and other scripts unaffected ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              ;; Still 3 scripts total (no duplicates)
              (js-await (wait-for-script-count popup 3))
              ;; Renamed script visible (use exact text match on .script-name)
              (js-await (-> (expect (.locator popup ".script-name" #js {:hasText "renamed_first_script.cljs"}))
                            (.toBeVisible)))
              ;; Second script still there
              (js-await (-> (expect (.locator popup ".script-name" #js {:hasText "second_script.cljs"}))
                            (.toBeVisible)))
              ;; Old name gone - verify exact name doesn't exist
              ;; Note: :has-text is substring match, so we check .script-name text content directly
              (let [script-names (js-await (.allTextContents (.locator popup ".script-item .script-name")))]
                (js-await (-> (expect (some #(= % "first_script.cljs") script-names)) (.toBeFalsy))))
              ;; CRITICAL: No approval buttons should appear (other scripts should be unaffected)
              (js-await (-> (expect (.locator popup "button:has-text(\"Allow\")")) (.toHaveCount 0)))
              (js-await (-> (expect (.locator popup "button:has-text(\"Allow\")")) (.toHaveCount 0)))
              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))

(test "Panel: multiple renames do not create duplicates"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Create initial script ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))
              (js-await (.fill (.locator panel "#code-area") "(println \"original\")"))
              (js-await (.fill (.locator panel "#script-name") "Original Script"))
              (js-await (.fill (.locator panel "#script-match") "*://example.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "original_script.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 2: First rename ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              (let [script-item (.locator popup ".script-item:has-text(\"original_script.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            (let [panel (js-await (create-panel-page context ext-id))]
              ;; Wait for script to load
              (js-await (-> (expect (.locator panel "#script-name"))
                            (.toHaveValue "original_script.cljs")))
              (js-await (.fill (.locator panel "#script-name") "First Rename"))
              (js-await (.click (.locator panel "button.btn-rename")))
              (js-await (wait-for-save-status panel "Renamed"))
              (js-await (.close panel)))

            ;; === PHASE 3: Verify only 2 scripts (built-in + renamed) ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              (js-await (wait-for-script-count popup 2))
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"first_rename.cljs\")"))
                            (.toBeVisible)))
              (js-await (.close popup)))

            ;; === PHASE 4: Second rename ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              (let [script-item (.locator popup ".script-item:has-text(\"first_rename.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            (let [panel (js-await (create-panel-page context ext-id))]
              ;; Wait for script to load
              (js-await (-> (expect (.locator panel "#script-name"))
                            (.toHaveValue "first_rename.cljs")))
              (js-await (.fill (.locator panel "#script-name") "Second Rename"))
              (js-await (.click (.locator panel "button.btn-rename")))
              (js-await (wait-for-save-status panel "Renamed"))
              (js-await (.close panel)))

            ;; === PHASE 5: Verify still only 2 scripts ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (wait-for-popup-ready popup))
              ;; CRITICAL: Still only 2 scripts (no duplicates from multiple renames)
              (js-await (wait-for-script-count popup 2))
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"second_rename.cljs\")"))
                            (.toBeVisible)))
              ;; Old names should not exist
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"first_rename.cljs\")"))
                            (.not.toBeVisible)))
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"original_script.cljs\")"))
                            (.not.toBeVisible)))
              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))
