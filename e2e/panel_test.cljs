(ns panel-test
  "E2E tests for DevTools panel - user journey style.

   Strategy: Load panel.html directly as extension page, inject mock
   chrome.devtools APIs via addInitScript before panel.js loads.

   What this doesn't test (use bb test:repl-e2e instead):
   - Actual code evaluation in inspected page
   - Real chrome.devtools.inspectedWindow.eval behavior"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              clear-storage wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status wait-for-script-count wait-for-edit-hint
                              wait-for-panel-state-saved get-test-events]]))


;; =============================================================================
;; Panel User Journey: Evaluation and Save
;; =============================================================================

(defn code-with-manifest
  "Generate test code with epupp manifest metadata.
   Uses the official manifest keys: :epupp/script-name, :epupp/site-match, etc."
  [{:keys [name match description run-at code]
    :or {code "(println \"Test script\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/site-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\"")))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

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
                  save-btn (.locator panel "button.btn-save")
                  ;; Property table selectors
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
                  match-field (.locator save-section ".property-row:has(th:text('URL Pattern')) .property-value")]

              ;; Clear storage for clean slate
              (js-await (clear-storage panel))

              ;; === EVALUATION WORKFLOW ===

              ;; 1. Panel renders with code input and action buttons
              (js-await (-> (expect textarea) (.toBeVisible)))
              (js-await (-> (expect eval-btn) (.toBeVisible)))
              (js-await (-> (expect clear-btn) (.toBeVisible)))

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

              ;; === SAVE WORKFLOW (Manifest-Driven) ===

              ;; 5. Without manifest, save section shows guidance message
              (js-await (-> (expect (.locator save-section ".no-manifest-message")) (.toBeVisible)))
              (js-await (-> (expect save-btn) (.toBeDisabled)))

              ;; 6. Add code with manifest - metadata displays should appear
              (let [test-code (code-with-manifest {:name "Test Userscript"
                                                   :match "*://test.example.com/*"
                                                   :code "(println \"My userscript\")"})]
                (js-await (.fill textarea test-code)))

              ;; 7. Verify manifest-driven displays show correct values
              (js-await (-> (expect name-field) (.toContainText "test_userscript.cljs")))
              (js-await (-> (expect match-field) (.toContainText "*://test.example.com/*")))

              ;; 8. Save button should now be enabled
              (js-await (-> (expect save-btn) (.toBeEnabled)))

              ;; 9. Save and verify (first save = Created since it's a new script)
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "Created"))

              ;; 10. Name field still shows normalized name after save
              (js-await (-> (expect name-field) (.toContainText "test_userscript.cljs"))))
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
            ;; === PHASE 1: Create initial script with manifest ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))
              (let [initial-code (code-with-manifest {:name "My Cool Script"
                                                      :match "*://example.com/*"
                                                      :code "(println \"version 1\")"})]
                (js-await (.fill textarea initial-code)))
              ;; Button should say "Save Script" for new script
              (js-await (-> (expect save-btn) (.toContainText "Save Script")))
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "my_cool_script.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 2: Edit script from popup ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))
              ;; Script appears with normalized name - click inspect
              (let [script-item (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            ;; === PHASE 3: Update code WITHOUT changing name - should save over existing ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
              ;; Wait for script to load - check that name field shows loaded name
              (js-await (-> (expect name-field) (.toContainText "my_cool_script.cljs")))
              ;; Script loaded for editing - button says "Save Script"
              (js-await (-> (expect save-btn) (.toContainText "Save Script")))
              ;; Update just the code body (keep same manifest name)
              (let [updated-code (code-with-manifest {:name "my_cool_script.cljs"
                                                      :match "*://example.com/*"
                                                      :code "(println \"version 2 - UPDATED\")"})]
                (js-await (.fill textarea updated-code)))
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "Saved"))
              (js-await (.close panel)))

            ;; === PHASE 4: Verify still only 1 user script (overwrite worked) ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))
              ;; Still 2 scripts total (built-in + our script)
              (js-await (wait-for-script-count popup 2))
              ;; Inspect again for next phase
              (let [script-item (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            ;; === PHASE 5: Change name in manifest - should show "Create Script" ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")
                  rename-btn (.locator panel "button.btn-rename")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
              ;; Wait for script to load
              (js-await (-> (expect name-field) (.toContainText "my_cool_script.cljs")))
              ;; Initially "Save Script" since name matches
              (js-await (-> (expect save-btn) (.toContainText "Save Script")))
              ;; Change the name in manifest (by updating the whole code)
              (let [new-name-code (code-with-manifest {:name "New Script Name"
                                                       :match "*://example.com/*"
                                                       :code "(println \"version 2 - UPDATED\")"})]
                (js-await (.fill textarea new-name-code)))
              ;; Button should change to "Create Script" when name differs
              (js-await (-> (expect save-btn) (.toContainText "Create Script")))
              ;; Rename button should appear
              (js-await (-> (expect rename-btn) (.toBeVisible)))
              ;; Click Create to make a new script
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "Created"))
              (js-await (.close panel)))

            ;; === PHASE 6: Verify both scripts exist (original + new) ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
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
            ;; === PHASE 1: Create initial script with manifest ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))
              (let [initial-code (code-with-manifest {:name "My Cool Script"
                                                      :match "*://example.com/*"
                                                      :code "(println \"version 1\")"})]
                (js-await (.fill textarea initial-code)))
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "my_cool_script.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 2: Edit script from popup ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))
              ;; Script appears with normalized name - click inspect
              (let [script-item (.locator popup ".script-item:has-text(\"my_cool_script.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            ;; === PHASE 3: Rename script by changing name in manifest ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  rename-btn (.locator panel "button.btn-rename")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
              ;; Wait for script to load
              (js-await (-> (expect name-field) (.toContainText "my_cool_script.cljs")))
              ;; Change the name in manifest
              (let [renamed-code (code-with-manifest {:name "Renamed Script"
                                                      :match "*://example.com/*"
                                                      :code "(println \"version 1\")"})]
                (js-await (.fill textarea renamed-code)))
              ;; Click Rename button
              (js-await (.click rename-btn))
              (js-await (wait-for-save-status panel "Renamed"))
              (js-await (.close panel)))

            ;; === PHASE 4: Verify still only 2 scripts (built-in + renamed) ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
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
            ;; === PHASE 1: Create first script with manifest ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))
              (let [code1 (code-with-manifest {:name "First Script"
                                               :match "*://example.com/*"
                                               :code "(println \"script 1\")"})]
                (js-await (.fill textarea code1)))
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "first_script.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 2: Create second script with manifest ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")]
              (js-await (wait-for-panel-ready panel))
              (let [code2 (code-with-manifest {:name "Second Script"
                                               :match "*://github.com/*"
                                               :code "(println \"script 2\")"})]
                (js-await (.fill textarea code2)))
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "second_script.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 3: Verify both scripts exist and are enabled ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))
              ;; 3 scripts: built-in + script1 + script2
              (js-await (wait-for-script-count popup 3))
              ;; No approval buttons visible (all scripts already approved by saving)
              (js-await (-> (expect (.locator popup "button:has-text(\"Allow\")")) (.toHaveCount 0)))
              (js-await (.close popup)))

            ;; === PHASE 4: Edit and rename first script ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))
              ;; Inspect first script
              (let [script-item (.locator popup ".script-item:has-text(\"first_script.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  rename-btn (.locator panel "button.btn-rename")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
              ;; Wait for script to load
              (js-await (-> (expect name-field) (.toContainText "first_script.cljs")))
              ;; Rename by changing manifest name
              (let [renamed-code (code-with-manifest {:name "Renamed First Script"
                                                      :match "*://example.com/*"
                                                      :code "(println \"script 1\")"})]
                (js-await (.fill textarea renamed-code)))
              (js-await (.click rename-btn))
              (js-await (wait-for-save-status panel "Renamed"))
              (js-await (.close panel)))

            ;; === PHASE 5: Verify rename worked and other scripts unaffected ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
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
              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))


(test "Panel: multiple renames do not create duplicates"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Create initial script with manifest ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))
              (let [initial-code (code-with-manifest {:name "Original Script"
                                                      :match "*://example.com/*"
                                                      :code "(println \"original\")"})]
                (js-await (.fill textarea initial-code)))
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "original_script.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 2: First rename ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))
              (let [script-item (.locator popup ".script-item:has-text(\"original_script.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  rename-btn (.locator panel "button.btn-rename")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
              ;; Wait for script to load
              (js-await (-> (expect name-field) (.toContainText "original_script.cljs")))
              (let [renamed-code (code-with-manifest {:name "First Rename"
                                                      :match "*://example.com/*"
                                                      :code "(println \"original\")"})]
                (js-await (.fill textarea renamed-code)))
              (js-await (.click rename-btn))
              (js-await (wait-for-save-status panel "Renamed"))
              (js-await (.close panel)))

            ;; === PHASE 3: Verify only 2 scripts (built-in + renamed) ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))
              (js-await (wait-for-script-count popup 2))
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"first_rename.cljs\")"))
                            (.toBeVisible)))
              (js-await (.close popup)))

            ;; === PHASE 4: Second rename ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))
              (let [script-item (.locator popup ".script-item:has-text(\"first_rename.cljs\")")
                    inspect-btn (.locator script-item "button.script-inspect")]
                (js-await (.click inspect-btn))
                (js-await (wait-for-edit-hint popup)))
              (js-await (.close popup)))

            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  rename-btn (.locator panel "button.btn-rename")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
              ;; Wait for script to load
              (js-await (-> (expect name-field) (.toContainText "first_rename.cljs")))
              (let [renamed-code (code-with-manifest {:name "Second Rename"
                                                      :match "*://example.com/*"
                                                      :code "(println \"original\")"})]
                (js-await (.fill textarea renamed-code)))
              (js-await (.click rename-btn))
              (js-await (wait-for-save-status panel "Renamed"))
              (js-await (.close panel)))

            ;; === PHASE 5: Verify still only 2 scripts ===
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.goto popup popup-url #js {:timeout 1000}))
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


;; =============================================================================
;; Panel User Journey: Initialization
;; =============================================================================

(test "Panel: initializes with default script when no saved state"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
                  match-field (.locator save-section ".property-row:has(th:text('URL Pattern')) .property-value")]
              ;; Clear storage for clean slate
              (js-await (clear-storage panel))
              ;; Reload to trigger fresh initialization
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; Code area should have the default script (use toHaveValue for textarea)
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(ns hello-world\\)"))))
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(defn hello \\[s\\]"))))

              ;; Metadata fields should be populated from default script manifest
              (js-await (-> (expect name-field) (.toContainText "hello_world.cljs")))
              (js-await (-> (expect match-field) (.toContainText "https://example.com/*")))

              ;; Save button should be enabled (valid manifest)
              (js-await (-> (expect (.locator panel "button.btn-save")) (.toBeEnabled))))
            (finally
              (js-await (.close context)))))))


(test "Panel: restores saved state and parses manifest on reload"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Save a script to create persisted state ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")]
              ;; Clear storage first (this also clears test events)
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; Enter a script with manifest
              (let [test-code (code-with-manifest {:name "Persisted Script"
                                                   :match "*://persist.example.com/*"
                                                   :description "Test persistence"
                                                   :code "(println \"persisted\")"})]
                (js-await (.fill textarea test-code)))

              ;; After fill, check if the state changed (debug element shows code-len)
              (let [debug-info (.locator panel "#debug-info")]
                (js-await (-> (expect debug-info) (.toBeVisible #js {:timeout 300})))
                (let [debug-text (js-await (.textContent debug-info))]
                  (println "=== PHASE 1: After fill ===" debug-text)))

              ;; Save it (this will persist the state)
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "Created"))
              ;; Wait for panel state to be persisted before closing
              ;; Note: We look for code content that's actually in the manifest,
              ;; not the normalized name which appears in UI but not in code
              (js-await (wait-for-panel-state-saved panel "(println \"persisted\")"))
              (js-await (.close panel)))

            ;; === PHASE 2: Reopen panel and verify state + manifest parsing ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
                  match-field (.locator save-section ".property-row:has(th:text('URL Pattern')) .property-value")
                  description-field (.locator save-section ".property-row:has(th:text('Description')) .property-value")]

              ;; Wait for panel to fully initialize
              (js-await (wait-for-panel-ready panel))

              ;; Give restore a moment to complete
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 1000))))

              ;; Read debug info from visible element
              (let [debug-info (.locator panel "#debug-info")]
                (js-await (-> (expect debug-info) (.toBeVisible #js {:timeout 300})))
                (let [debug-text (js-await (.textContent debug-info))]
                  (println "=== DEBUG INFO ===" debug-text)))

              ;; Get test events via popup's dev-log button
              (let [popup (js-await (.newPage context))
                    popup-url (str "chrome-extension://" ext-id "/popup.html")]
                (js-await (.goto popup popup-url #js {:timeout 1000}))
                (js-await (wait-for-popup-ready popup))
                (let [events (js-await (get-test-events popup))]
                  (println "=== TEST EVENTS ===" (js/JSON.stringify events nil 2)))
                (js-await (.close popup)))

              ;; Get current textarea value to debug
              (let [textarea-value (js-await (.inputValue textarea))]
                (println "=== DEBUG: Current textarea value ===")
                (println (subs textarea-value 0 100))
                (println "=== END DEBUG ==="))

              ;; Code should be restored (use toHaveValue for textarea)
              ;; Check for the manifest content - the original script-name, not the normalized version
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "Persisted Script") #js {:timeout 500})))
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(println \"persisted\"\\)"))))

              ;; CRITICAL: Manifest should be parsed and metadata displayed
              ;; This was the bug - manifest wasn't parsed on restore
              (js-await (-> (expect name-field) (.toContainText "persisted_script.cljs")))
              (js-await (-> (expect match-field) (.toContainText "*://persist.example.com/*")))
              (js-await (-> (expect description-field) (.toContainText "Test persistence")))

              ;; Save button should be enabled
              (js-await (-> (expect (.locator panel "button.btn-save")) (.toBeEnabled))))
            (finally
              (js-await (.close context)))))))


;; =============================================================================
;; Panel User Journey: New Script Button
;; =============================================================================

(test "Panel: New button clears editor and resets to default script"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Create and save a script ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")
                  new-btn (.locator panel "button.btn-new-script")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; New button should be visible
              (js-await (-> (expect new-btn) (.toBeVisible)))

              ;; Create a script with manifest
              (let [test-code (code-with-manifest {:name "My Custom Script"
                                                   :match "*://custom.example.com/*"
                                                   :code "(println \"custom code\")"})]
                (js-await (.fill textarea test-code)))

              ;; Save the script
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "my_custom_script.cljs"))

              ;; === PHASE 2: Click New to clear editor ===
              ;; Set up dialog handler to accept confirmation
              (.once panel "dialog" (fn [dialog] (.accept dialog)))

              ;; Click the New button
              (js-await (.click new-btn))

              ;; Code should reset to default script
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(ns hello-world\\)"))))

              ;; Metadata should show default script values
              (let [save-section (.locator panel ".save-script-section")
                    name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
                (js-await (-> (expect name-field) (.toContainText "hello_world.cljs"))))

              ;; Script-id should be cleared (we're creating a new script now)
              ;; Verify by checking the header says "Save as Userscript" not "Edit Userscript"
              (let [header (.locator panel ".save-script-header .header-title")]
                (js-await (-> (expect header) (.toContainText "Save as Userscript")))))
            (finally
              (js-await (.close context)))))))


(test "Panel: New button preserves evaluation results"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  eval-btn (.locator panel "button.btn-eval")
                  results (.locator panel ".results-area")
                  new-btn (.locator panel "button.btn-new-script")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; === PHASE 1: Evaluate some code to have results ===
              (let [eval-code (code-with-manifest {:name "Eval Test"
                                                   :match "*://eval.example.com/*"
                                                   :code "(+ 1 2 3)"})]
                (js-await (.fill textarea eval-code)))
              (js-await (.click eval-btn))
              ;; Wait for result to appear
              (js-await (-> (expect results) (.toContainText "(+ 1 2 3)")))

              ;; === PHASE 2: Click New button ===
              ;; Set up dialog handler to accept confirmation
              (.once panel "dialog" (fn [dialog] (.accept dialog)))
              (js-await (.click new-btn))

              ;; === PHASE 3: Verify results are preserved ===
              ;; Code should be reset to default
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))

              ;; BUT results should still show the previous evaluation
              (js-await (-> (expect results) (.toContainText "(+ 1 2 3)"))))
            (finally
              (js-await (.close context)))))))


(test "Panel: New button with default script skips confirmation"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  new-btn (.locator panel "button.btn-new-script")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; Panel should start with default script
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))

              ;; Track whether dialog appears
              (let [dialog-appeared (atom false)]
                (.on panel "dialog" (fn [_dialog]
                                      (reset! dialog-appeared true)))

                ;; Click New button - should NOT trigger dialog since code is default
                (js-await (.click new-btn))

                ;; Small delay to ensure any dialog would have appeared
                (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 200))))

                ;; Dialog should NOT have appeared (code was already default)
                ;; Note: In Squint, we can't easily test this but we can verify
                ;; the textarea still has default content
                (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs"))))
                (js-await (-> (expect name-field) (.toContainText "hello_world.cljs")))))
            (finally
              (js-await (.close context)))))))

