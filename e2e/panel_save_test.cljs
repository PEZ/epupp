(ns e2e.panel-save-test
  "E2E tests for DevTools panel save and rename functionality."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              clear-storage wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status wait-for-script-count wait-for-edit-hint
                              assert-no-errors!]]))

(defn- code-with-manifest
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

;; =============================================================================
;; Panel User Journey: Create vs Save Behavior
;; =============================================================================

(defn- ^:async test_create_new_script_when_name_changed_save_when_unchanged []
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
        ;; Assert no errors before closing
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Panel User Journey: Rename Behavior
;; =============================================================================

(defn- ^:async test_rename_script_does_not_create_duplicate []
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
        ;; Assert no errors before closing
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Panel User Journey: Rename with Multiple Scripts
;; =============================================================================

(defn- ^:async test_rename_does_not_affect_other_scripts_or_trigger_approvals []
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
        ;; Assert no errors before closing
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))


(defn- ^:async test_multiple_renames_do_not_create_duplicates []
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
        ;; Assert no errors before closing
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_panel_rename_triggers_popup_flash []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Create initial script ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))
        (let [initial-code (code-with-manifest {:name "Flash Rename Script"
                                                :match "*://example.com/*"
                                                :code "(println \"v1\")"})]
          (js-await (.fill textarea initial-code)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "flash_rename_script.cljs"))
        (js-await (.close panel)))

      ;; === PHASE 2: Open popup and ensure no flash class ===
      (let [popup (js-await (.newPage context))
            popup-url (str "chrome-extension://" ext-id "/popup.html")]
        (js-await (.goto popup popup-url #js {:timeout 1000}))
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"flash_rename_script.cljs\")")
              inspect-btn (.locator script-item "button.script-inspect")]
          (js-await (-> (expect script-item) (.toBeVisible #js {:timeout 2000})))
          (js-await (-> (expect script-item) (.not.toHaveClass (js/RegExp. "script-item-fs-modified"))))
          (js-await (.click inspect-btn))
          (js-await (wait-for-edit-hint popup)))

        ;; === PHASE 3: Rename in panel while popup stays open ===
        (let [panel (js-await (create-panel-page context ext-id))
              textarea (.locator panel "#code-area")
              rename-btn (.locator panel "button.btn-rename")
              save-section (.locator panel ".save-script-section")
              name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
          (js-await (-> (expect name-field) (.toContainText "flash_rename_script.cljs")))
          (let [renamed-code (code-with-manifest {:name "Flash Renamed Script"
                                                  :match "*://example.com/*"
                                                  :code "(println \"v1\")"})]
            (js-await (.fill textarea renamed-code)))
          (js-await (.click rename-btn))
          (js-await (wait-for-save-status panel "Renamed"))
          (js-await (.close panel)))

        ;; === PHASE 4: Popup should flash renamed item ===
        (let [renamed-item (.locator popup ".script-item:has-text(\"flash_renamed_script.cljs\")")]
          (js-await (-> (expect renamed-item)
                        (.toHaveClass (js/RegExp. "script-item-fs-modified")
                                      #js {:timeout 2000}))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(.describe test "Panel Save"
           (fn []
             (test "Panel Save: create new script when name changed, save when unchanged"
                   test_create_new_script_when_name_changed_save_when_unchanged)

             (test "Panel Save: rename script does not create duplicate"
                   test_rename_script_does_not_create_duplicate)

             (test "Panel Save: rename does not affect other scripts or trigger approvals"
                   test_rename_does_not_affect_other_scripts_or_trigger_approvals)

             (test "Panel Save: rename triggers popup flash"
                   test_panel_rename_triggers_popup_flash)

             (test "Panel Save: multiple renames do not create duplicates"
                   test_multiple_renames_do_not_create_duplicates)))
