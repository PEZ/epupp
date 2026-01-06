(ns popup-test
  "E2E tests for the popup UI - user journey style.

   Tests are structured as user journeys - one browser context, multiple
   sequential operations that build on each other, like a real user session.

   Coverage:
   - Port configuration and persistence
   - Copy command functionality
   - Scripts section: empty state, list rendering
   - Script management: enable/disable, delete
   - Approval workflow: Allow/Deny for matching scripts
   - URL matching visual indicators"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page clear-storage sleep]]))

;; =============================================================================
;; Popup User Journey: Port Configuration
;; =============================================================================

(test "Popup: port configuration and command generation"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; 1. Port inputs render with defaults
              (js-await (-> (expect (.locator popup "#nrepl-port")) (.toBeVisible)))
              (js-await (-> (expect (.locator popup "#ws-port")) (.toBeVisible)))

              ;; 2. Copy command button exists
              (let [copy-btn (.locator popup "button.copy-btn")]
                (js-await (-> (expect copy-btn) (.toBeVisible)))
                (js-await (.click copy-btn))
                ;; Button text changes to "Copied!" temporarily - wait for it
                (js-await (-> (expect copy-btn) (.toContainText "Copied" #js {:timeout 3000}))))

              ;; 3. Connect button exists
              (js-await (-> (expect (.locator popup "button:has-text(\"Connect\")")) (.toBeVisible)))

              ;; 4. Can change port values
              (js-await (.fill popup "#nrepl-port" "9999"))
              (js-await (-> (expect (.locator popup "#nrepl-port")) (.toHaveValue "9999")))

              (js-await (.fill popup "#ws-port" "8888"))
              (js-await (-> (expect (.locator popup "#ws-port")) (.toHaveValue "8888")))

              ;; 5. Command box updates with new port values
              (let [cmd-box (.locator popup ".command-box code")]
                (js-await (-> (expect cmd-box) (.toContainText "9999")))
                (js-await (-> (expect cmd-box) (.toContainText "8888"))))

              (js-await (.close popup)))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Popup User Journey: Scripts Section and Management
;; =============================================================================

(test "Popup: scripts section - empty state, list, enable/disable, delete"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Empty state ===
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (clear-storage popup))
              ;; Reload to see empty state
              (js-await (.reload popup))
              (js-await (sleep 1000))

              ;; Shows "No scripts yet" message
              (js-await (-> (expect (.locator popup ".no-scripts"))
                            (.toContainText "No scripts yet")))
              (js-await (.close popup)))

            ;; === PHASE 2: Create scripts via panel (the real user flow) ===
            ;; First script
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "textarea") "(println \"enabled script\")"))
              (js-await (.fill (.locator panel "#script-name") "Enabled Script"))
              (js-await (.fill (.locator panel "#script-match") "*://test.example.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 500))
              (js-await (-> (expect (.locator panel ".save-status"))
                            (.toContainText "Saved")))
              (js-await (.close panel)))

            ;; Second script
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "textarea") "(println \"other script\")"))
              (js-await (.fill (.locator panel "#script-name") "Other Script"))
              (js-await (.fill (.locator panel "#script-match") "*://other.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 500))
              (js-await (-> (expect (.locator panel ".save-status"))
                            (.toContainText "Saved")))
              (js-await (.close panel)))

            ;; === PHASE 3: Verify scripts in popup ===
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; Both scripts appear
              (let [items (.locator popup ".script-item")]
                (js-await (-> (expect items) (.toHaveCount 2 #js {:timeout 5000}))))

              ;; Script names visible
              (js-await (-> (expect (.locator popup ".script-name:has-text(\"Enabled Script\")"))
                            (.toBeVisible)))
              (js-await (-> (expect (.locator popup ".script-name:has-text(\"Other Script\")"))
                            (.toBeVisible)))

              ;; Both scripts enabled by default (saved scripts are enabled)
              (let [enabled-item (.locator popup ".script-item:has-text(\"Enabled Script\")")
                    checkbox (.locator enabled-item "input[type='checkbox']")]
                (js-await (-> (expect checkbox) (.toBeChecked))))

              (let [other-item (.locator popup ".script-item:has-text(\"Other Script\")")
                    checkbox (.locator other-item "input[type='checkbox']")]
                (js-await (-> (expect checkbox) (.toBeChecked))))

              ;; === PHASE 4: Toggle enabled -> disabled ===
              (let [enabled-item (.locator popup ".script-item:has-text(\"Enabled Script\")")
                    checkbox (.locator enabled-item "input[type='checkbox']")]
                (js-await (.click checkbox))
                (js-await (sleep 300))
                (js-await (-> (expect checkbox) (.not.toBeChecked))))

              ;; === PHASE 5: Delete script ===
              ;; Handle confirm dialog
              (.on popup "dialog" (fn [dialog] (.accept dialog)))

              (let [other-item (.locator popup ".script-item:has-text(\"Other Script\")")
                    delete-btn (.locator other-item "button.script-delete")]
                (js-await (.click delete-btn))
                (js-await (sleep 500))
                (js-await (-> (expect (.locator popup ".script-item")) (.toHaveCount 1)))
                (js-await (-> (expect (.locator popup ".script-name:has-text(\"Enabled Script\")"))
                              (.toBeVisible)))
                (js-await (-> (expect (.locator popup ".script-name:has-text(\"Other Script\")"))
                              (.not.toBeVisible))))

              (js-await (.close popup)))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Popup User Journey: Approval Workflow
;; =============================================================================

(test "Popup: approval workflow - Allow and Deny buttons"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Start fresh
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (clear-storage popup))
              (js-await (.close popup)))

            ;; Create script matching example.com via panel
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "textarea") "(println \"approval test\")"))
              (js-await (.fill (.locator panel "#script-name") "Needs Approval"))
              (js-await (.fill (.locator panel "#script-match") "*://example.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 500))
              (js-await (-> (expect (.locator panel ".save-status"))
                            (.toContainText "Saved")))
              (js-await (.close panel)))

            ;; === Test Allow flow ===
            ;; Open popup with test URL override (so popup thinks we're on example.com)
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              ;; Set test URL BEFORE navigating - popup reads URL on init
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'https://example.com/some/page';"))
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (sleep 1000))

              (let [script-item (.locator popup ".script-item:has-text(\"Needs Approval\")")]
                ;; Wait for script item
                (js-await (-> (expect script-item) (.toBeVisible #js {:timeout 5000})))

                ;; Script should show approval class (amber border) because pattern not approved
                (js-await (-> (expect script-item) (.toHaveClass (js/RegExp. "script-item-approval"))))

                ;; Allow and Deny buttons should be visible
                (let [allow-btn (.locator script-item "button.approval-allow")
                      deny-btn (.locator script-item "button.approval-deny")]
                  (js-await (-> (expect allow-btn) (.toBeVisible)))
                  (js-await (-> (expect deny-btn) (.toBeVisible)))

                  ;; Click Allow
                  (js-await (.click allow-btn))
                  (js-await (sleep 500))

                  ;; After allow: approval buttons should disappear
                  (js-await (-> (expect allow-btn) (.not.toBeVisible)))
                  (js-await (-> (expect deny-btn) (.not.toBeVisible)))

                  ;; Script item should no longer have approval class
                  (js-await (-> (expect script-item)
                                (.not.toHaveClass (js/RegExp. "script-item-approval"))))))
              (js-await (.close popup)))

            ;; === Test Deny flow (create fresh script) ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "textarea") "(println \"deny test\")"))
              (js-await (.fill (.locator panel "#script-name") "Deny Test"))
              (js-await (.fill (.locator panel "#script-match") "*://example.org/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 500))
              (js-await (.close panel)))

            ;; Open popup with test URL for example.org
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'https://example.org/';"))
              (js-await (.goto popup popup-url #js {:timeout 10000}))
              (js-await (sleep 1000))

              (let [script-item (.locator popup ".script-item:has-text(\"Deny Test\")")
                    deny-btn (.locator script-item "button.approval-deny")
                    checkbox (.locator script-item "input[type='checkbox']")]
                ;; Wait for script
                (js-await (-> (expect script-item) (.toBeVisible #js {:timeout 5000})))

                ;; Denial button should be visible
                (js-await (-> (expect deny-btn) (.toBeVisible)))

                ;; Click Deny
                (js-await (.click deny-btn))
                (js-await (sleep 500))

                ;; Script should be disabled (deny disables the script)
                (js-await (-> (expect checkbox) (.not.toBeChecked)))

                ;; Approval buttons should disappear
                (js-await (-> (expect (.locator script-item "button.approval-allow")) (.not.toBeVisible)))
                (js-await (-> (expect (.locator script-item "button.approval-deny")) (.not.toBeVisible))))
              (js-await (.close popup)))
            (finally
              (js-await (.close context)))))))
