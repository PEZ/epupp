(ns integration-test
  "E2E integration tests for cross-component flows.

   These tests verify real chrome.storage interactions between:
   - Panel (DevTools): saving scripts
   - Popup: viewing, enabling/disabling, editing, deleting scripts

   User journey style: one browser context, multiple sequential operations
   that build on each other, like a real user session."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              create-popup-page clear-storage sleep]]))

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

              ;; Has enable checkbox (checked by default - scripts save as enabled)
              (js-await (-> (expect checkbox) (.toBeVisible)))
              (js-await (-> (expect checkbox) (.toBeChecked)))

              ;; Has edit and delete buttons
              (js-await (-> (expect edit-btn) (.toBeVisible)))
              (js-await (-> (expect delete-btn) (.toBeVisible)))

              ;; Disable script (it starts enabled)
              (js-await (.click checkbox))
              (js-await (sleep 300))
              (js-await (-> (expect checkbox) (.not.toBeChecked)))

              ;; Re-enable script
              (js-await (.click checkbox))
              (js-await (sleep 300))
              (js-await (-> (expect checkbox) (.toBeChecked)))

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

;; =============================================================================
;; Integration: Edit hint message appears in popup
;; =============================================================================

(test "Integration: edit button shows hint message in popup"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Start with clean storage
            (let [temp-page (js-await (.newPage context))]
              (js-await (.goto temp-page (str "chrome-extension://" ext-id "/popup.html")))
              (js-await (clear-storage temp-page))
              (js-await (.close temp-page)))

            ;; Create script via panel (the working approach)
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "textarea") "(println \"hint test\")"))
              (js-await (.fill (.locator panel "#script-name") "Hint Test"))
              (js-await (.fill (.locator panel "#script-match") "*://hint.test/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 500))
              (js-await (.close panel)))

            ;; Open popup and test edit hint
            (let [popup (js-await (create-popup-page context ext-id))
                  edit-btn (.locator popup "button.script-edit")
                  hint (.locator popup ".script-edit-hint")]

              ;; Wait for script to appear
              (js-await (-> (expect edit-btn) (.toBeVisible #js {:timeout 5000})))

              ;; Hint not visible initially
              (js-await (-> (expect hint) (.toHaveCount 0)))

              ;; Click edit
              (js-await (.click edit-btn))

              ;; Hint appears with DevTools message
              (js-await (-> (expect hint) (.toBeVisible #js {:timeout 2000})))
              (js-await (-> (expect hint) (.toContainText "Developer Tools")))

              (js-await (.close popup)))
            (finally
              (js-await (.close context)))))))
