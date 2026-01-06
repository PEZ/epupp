(ns popup-test
  "E2E tests for the popup UI - user journey style.

   Coverage:
   - REPL connection setup (ports, command generation)
   - Script management with approval workflow"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page clear-storage sleep]]))

;; =============================================================================
;; Popup User Journey: REPL Connection Setup
;; =============================================================================

(test "Popup: REPL connection setup"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; Port inputs render
              (js-await (-> (expect (.locator popup "#nrepl-port")) (.toBeVisible)))
              (js-await (-> (expect (.locator popup "#ws-port")) (.toBeVisible)))

              ;; Copy command works
              (let [copy-btn (.locator popup "button.copy-btn")]
                (js-await (.click copy-btn))
                (js-await (-> (expect copy-btn) (.toContainText "Copied" #js {:timeout 2000}))))

              ;; Connect button exists
              (js-await (-> (expect (.locator popup "button:has-text(\"Connect\")")) (.toBeVisible)))

              ;; Port changes update command
              (js-await (.fill popup "#nrepl-port" "9999"))
              (js-await (.fill popup "#ws-port" "8888"))
              (let [cmd-box (.locator popup ".command-box code")]
                (js-await (-> (expect cmd-box) (.toContainText "9999")))
                (js-await (-> (expect cmd-box) (.toContainText "8888"))))

              (js-await (.close popup)))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Popup User Journey: Script Management and Approval
;; =============================================================================

(test "Popup: script management and approval workflow"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Empty state ===
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (clear-storage popup))
              (js-await (.reload popup))
              (js-await (sleep 500))
              (js-await (-> (expect (.locator popup ".no-scripts"))
                            (.toContainText "No scripts yet")))
              (js-await (.close popup)))

            ;; === PHASE 2: Create scripts via panel ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "textarea") "(println \"script one\")"))
              (js-await (.fill (.locator panel "#script-name") "Script One"))
              (js-await (.fill (.locator panel "#script-match") "*://example.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 300))
              (js-await (.close panel)))

            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "textarea") "(println \"script two\")"))
              (js-await (.fill (.locator panel "#script-name") "Script Two"))
              (js-await (.fill (.locator panel "#script-match") "*://example.org/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 300))
              (js-await (.close panel)))

            ;; === PHASE 3: Verify scripts, test enable/disable ===
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (-> (expect (.locator popup ".script-item")) (.toHaveCount 2 #js {:timeout 3000})))

              ;; Toggle disable
              (let [item (.locator popup ".script-item:has-text(\"Script One\")")
                    checkbox (.locator item "input[type='checkbox']")]
                (js-await (-> (expect checkbox) (.toBeChecked)))
                (js-await (.click checkbox))
                (js-await (sleep 200))
                (js-await (-> (expect checkbox) (.not.toBeChecked))))

              ;; Delete
              (.on popup "dialog" (fn [dialog] (.accept dialog)))
              (let [item (.locator popup ".script-item:has-text(\"Script Two\")")
                    delete-btn (.locator item "button.script-delete")]
                (js-await (.click delete-btn))
                (js-await (sleep 300))
                (js-await (-> (expect (.locator popup ".script-item")) (.toHaveCount 1))))

              (js-await (.close popup)))

            ;; === PHASE 4: Approval workflow (Allow) ===
            ;; Re-enable and create script for approval test
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "textarea") "(println \"needs approval\")"))
              (js-await (.fill (.locator panel "#script-name") "Approval Test"))
              (js-await (.fill (.locator panel "#script-match") "*://approval.test/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 300))
              (js-await (.close panel)))

            ;; Open popup with test URL override
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'https://approval.test/page';"))
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (sleep 500))

              (let [item (.locator popup ".script-item:has-text(\"Approval Test\")")]
                ;; Shows approval state (amber border)
                (js-await (-> (expect item) (.toHaveClass (js/RegExp. "script-item-approval"))))

                ;; Allow/Deny buttons visible
                (let [allow-btn (.locator item "button.approval-allow")
                      deny-btn (.locator item "button.approval-deny")]
                  (js-await (-> (expect allow-btn) (.toBeVisible)))
                  (js-await (-> (expect deny-btn) (.toBeVisible)))

                  ;; Click Allow
                  (js-await (.click allow-btn))
                  (js-await (sleep 300))

                  ;; Buttons disappear, approval class removed
                  (js-await (-> (expect allow-btn) (.not.toBeVisible)))
                  (js-await (-> (expect item) (.not.toHaveClass (js/RegExp. "script-item-approval"))))))
              (js-await (.close popup)))

            ;; === PHASE 5: Approval workflow (Deny) ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "textarea") "(println \"deny me\")"))
              (js-await (.fill (.locator panel "#script-name") "Deny Test"))
              (js-await (.fill (.locator panel "#script-match") "*://deny.test/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 300))
              (js-await (.close panel)))

            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'https://deny.test/';"))
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (sleep 500))

              (let [item (.locator popup ".script-item:has-text(\"Deny Test\")")
                    deny-btn (.locator item "button.approval-deny")
                    checkbox (.locator item "input[type='checkbox']")]
                (js-await (.click deny-btn))
                (js-await (sleep 300))
                ;; Deny disables the script
                (js-await (-> (expect checkbox) (.not.toBeChecked)))
                (js-await (-> (expect deny-btn) (.not.toBeVisible))))
              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))
