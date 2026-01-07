(ns integration-test
  "E2E integration tests for cross-component flows.

   Verifies real chrome.storage interactions between:
   - Panel (DevTools): saving scripts
   - Popup: viewing, enabling/disabling, editing, deleting scripts"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              create-popup-page clear-storage sleep]]))

;; =============================================================================
;; Integration: Script Lifecycle (panel -> popup -> panel -> popup)
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
            ;; Input: "Lifecycle Test" -> Normalized: "lifecycle_test.cljs"
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "#code-area") "(println \"Original code\")"))
              (js-await (.fill (.locator panel "#script-name") "Lifecycle Test"))
              (js-await (.fill (.locator panel "#script-match") "*://lifecycle.test/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 300))
              ;; First save of new script shows "Created"
              (js-await (-> (expect (.locator panel ".save-status"))
                            (.toContainText "Created")))
              (js-await (.close panel)))

            ;; === PHASE 2: Verify in popup, toggle, check edit hint ===
            ;; Script name is normalized to "lifecycle_test.cljs"
            (let [popup (js-await (create-popup-page context ext-id))
                  ;; Use specific locator for normalized name
                  script-item (.locator popup ".script-item:has-text(\"lifecycle_test.cljs\")")
                  checkbox (.locator script-item "input[type='checkbox']")
                  edit-btn (.locator script-item "button.script-edit")
                  hint (.locator popup ".script-edit-hint")]

              ;; Script appears with normalized name and pattern
              (js-await (-> (expect script-item) (.toContainText "lifecycle_test.cljs")))
              (js-await (-> (expect script-item) (.toContainText "*://lifecycle.test/*")))

              ;; Toggle enable/disable
              (js-await (-> (expect checkbox) (.toBeChecked)))
              (js-await (.click checkbox))
              (js-await (sleep 200))
              (js-await (-> (expect checkbox) (.not.toBeChecked)))
              (js-await (.click checkbox))
              (js-await (sleep 200))
              (js-await (-> (expect checkbox) (.toBeChecked)))

              ;; Edit hint appears on click
              (js-await (-> (expect hint) (.toHaveCount 0)))
              (js-await (.click edit-btn))
              (js-await (-> (expect hint) (.toBeVisible #js {:timeout 2000})))
              (js-await (-> (expect hint) (.toContainText "Developer Tools")))

              (js-await (.close popup)))

            ;; === PHASE 3: Edit script - panel receives it ===
            (let [panel (js-await (create-panel-page context ext-id))
                  popup (js-await (create-popup-page context ext-id))
                  ;; Use normalized name
                  script-item (.locator popup ".script-item:has-text(\"lifecycle_test.cljs\")")
                  edit-btn (.locator script-item "button.script-edit")]

              ;; Click edit in popup
              (js-await (.click edit-btn))
              (js-await (sleep 300))

              ;; Panel should receive the script (name field shows normalized name)
              (js-await (-> (expect (.locator panel "#code-area"))
                            (.toHaveValue "(println \"Original code\")")))
              (js-await (-> (expect (.locator panel "#script-name"))
                            (.toHaveValue "lifecycle_test.cljs")))

              ;; Modify and save
              (js-await (.fill (.locator panel "#code-area") "(println \"Updated code\")"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 300))

              (js-await (.close popup))
              (js-await (.close panel)))

            ;; === PHASE 4: Delete script ===
            (let [popup (js-await (create-popup-page context ext-id))
                  ;; Use normalized name
                  script-item (.locator popup ".script-item:has-text(\"lifecycle_test.cljs\")")
                  delete-btn (.locator script-item "button.script-delete")]

              (.on popup "dialog" (fn [dialog] (.accept dialog)))
              (js-await (.click delete-btn))
              (js-await (sleep 300))
              ;; Only our test script should be gone, built-in Gist Installer remains
              (js-await (-> (expect script-item) (.toHaveCount 0)))

              (js-await (.close popup)))
            (finally
              (js-await (.close context)))))))
