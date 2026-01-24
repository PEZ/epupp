(ns e2e.panel-save-create-test
  "E2E tests for DevTools panel save create functionality."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              clear-storage wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status wait-for-script-count wait-for-edit-hint
                              wait-for-property-value assert-no-errors!]]
            [panel-save-helpers :as panel-save-helpers]))

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
        (let [initial-code (panel-save-helpers/code-with-manifest {:name "My Cool Script"
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
        (let [updated-code (panel-save-helpers/code-with-manifest {:name "my_cool_script.cljs"
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
        (let [new-name-code (panel-save-helpers/code-with-manifest {:name "New Script Name"
                                                                    :match "*://example.com/*"
                                                                    :code "(println \"version 2 - UPDATED\")"})]
          (js-await (.fill textarea new-name-code)))
        ;; Wait for manifest parsing to propagate new name
        (js-await (wait-for-property-value panel "name" "new_script_name.cljs"))
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

(.describe test "Panel Save"
           (fn []
             (test "Panel Save: create new script when name changed, save when unchanged"
                   test_create_new_script_when_name_changed_save_when_unchanged)))
