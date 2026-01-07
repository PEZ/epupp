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
            ;; === PHASE 1: Initial state (built-in Gist Installer exists) ===
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (clear-storage popup))
              (js-await (.reload popup))
              (js-await (sleep 500))
              ;; Built-in Gist Installer script is auto-created, so we have 1 script
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"Gist Installer\")"))
                            (.toBeVisible)))
              (js-await (.close popup)))

            ;; === PHASE 2: Create scripts via panel ===
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "#code-area") "(println \"script one\")"))
              (js-await (.fill (.locator panel "#script-name") "Script One"))
              (js-await (.fill (.locator panel "#script-match") "*://example.com/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 300))
              (js-await (.close panel)))

            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "#code-area") "(println \"script two\")"))
              (js-await (.fill (.locator panel "#script-name") "Script Two"))
              (js-await (.fill (.locator panel "#script-match") "*://example.org/*"))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (sleep 300))
              (js-await (.close panel)))

            ;; === PHASE 3: Verify scripts, test enable/disable ===
            ;; Names are normalized: "Script One" -> "script_one.cljs"
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; 3 scripts: built-in Gist Installer + 2 user scripts
              (js-await (-> (expect (.locator popup ".script-item")) (.toHaveCount 3 #js {:timeout 3000})))

              ;; Verify script action buttons exist (edit, run, delete)
              (let [item (.locator popup ".script-item:has-text(\"script_one.cljs\")")]
                (js-await (-> (expect (.locator item "button.script-edit")) (.toBeVisible)))
                (js-await (-> (expect (.locator item "button.script-run")) (.toBeVisible)))
                (js-await (-> (expect (.locator item "button.script-delete")) (.toBeVisible))))

              ;; Toggle disable (using normalized name)
              (let [item (.locator popup ".script-item:has-text(\"script_one.cljs\")")
                    checkbox (.locator item "input[type='checkbox']")]
                (js-await (-> (expect checkbox) (.toBeChecked)))
                (js-await (.click checkbox))
                (js-await (sleep 200))
                (js-await (-> (expect checkbox) (.not.toBeChecked))))

              ;; Delete (using normalized name)
              (.on popup "dialog" (fn [dialog] (.accept dialog)))
              (let [item (.locator popup ".script-item:has-text(\"script_two.cljs\")")
                    delete-btn (.locator item "button.script-delete")]
                (js-await (.click delete-btn))
                (js-await (sleep 300))
                ;; 2 remaining: built-in Gist Installer + script_one.cljs
                (js-await (-> (expect (.locator popup ".script-item")) (.toHaveCount 2))))

              (js-await (.close popup)))

            ;; === PHASE 4: Approval workflow (Allow) ===
            ;; Re-enable and create script for approval test
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "#code-area") "(println \"needs approval\")"))
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

              (let [item (.locator popup ".script-item:has-text(\"approval_test.cljs\")")]
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
              (js-await (.fill (.locator panel "#code-area") "(println \"deny me\")"))
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

              ;; "Deny Test" normalized to "deny_test.cljs"
              (let [item (.locator popup ".script-item:has-text(\"deny_test.cljs\")")
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

;; =============================================================================
;; Popup User Journey: Settings and Origin Management
;; =============================================================================

(test "Popup: settings view and origin management"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Open settings section (now collapsible, not separate view) ===
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (clear-storage popup))
              (js-await (.reload popup))
              (js-await (sleep 300))

              ;; Settings section header visible (collapsed by default)
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (-> (expect settings-header) (.toBeVisible)))

                ;; Click to expand settings
                (js-await (.click settings-header))
                (js-await (sleep 200)))

              ;; Settings content renders with section title
              (js-await (-> (expect (.locator popup ".settings-content"))
                            (.toBeVisible)))
              (js-await (-> (expect (.locator popup ".settings-section-title"))
                            (.toContainText "Allowed Userscript-install Base URLs")))

              ;; Default origins list shows config origins
              (js-await (-> (expect (.locator popup ".origin-item-default"))
                            (.toHaveCount 7))) ;; dev config has 7 origins

              ;; No user origins initially
              (js-await (-> (expect (.locator popup ".no-origins"))
                            (.toContainText "No custom origins")))

              (js-await (.close popup)))

            ;; === PHASE 2: Add custom origin ===
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; Expand settings section
              (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
              (js-await (sleep 200))

              ;; Fill in valid origin and click Add
              (let [input (.locator popup ".add-origin-form input")
                    add-btn (.locator popup "button.add-btn")]
                (js-await (.fill input "https://git.example.com/"))
                (js-await (.click add-btn))
                (js-await (sleep 300)))

              ;; Origin appears in user list
              (let [user-origins (.locator popup ".origins-section:has(.origins-label:text(\"Your custom origins\")) .origin-item")]
                (js-await (-> (expect user-origins) (.toHaveCount 1)))
                (js-await (-> (expect (.first user-origins)) (.toContainText "https://git.example.com/"))))

              ;; Input cleared
              (js-await (-> (expect (.locator popup ".add-origin-form input"))
                            (.toHaveValue "")))

              (js-await (.close popup)))

            ;; === PHASE 3: Invalid origin shows error ===
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
              (js-await (sleep 200))

              ;; Try adding invalid origin (no trailing slash)
              (let [input (.locator popup ".add-origin-form input")
                    add-btn (.locator popup "button.add-btn")]
                (js-await (.fill input "https://invalid.com"))
                (js-await (.click add-btn))
                (js-await (sleep 100)))

              ;; Error message appears
              (js-await (-> (expect (.locator popup ".add-origin-error"))
                            (.toBeVisible)))
              (js-await (-> (expect (.locator popup ".add-origin-error"))
                            (.toContainText "http:// or https://")))

              (js-await (.close popup)))

            ;; === PHASE 4: Remove custom origin ===
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
              (js-await (sleep 200))

              ;; Verify origin still exists from phase 2
              (let [user-origins (.locator popup ".origins-section:has(.origins-label:text(\"Your custom origins\")) .origin-item")]
                (js-await (-> (expect user-origins) (.toHaveCount 1)))

                ;; Click delete button
                (let [delete-btn (.locator (.first user-origins) "button.origin-delete")]
                  (js-await (.click delete-btn))
                  (js-await (sleep 300)))

                ;; Origin removed, shows empty message
                (js-await (-> (expect user-origins) (.toHaveCount 0)))
                (js-await (-> (expect (.locator popup ".no-origins"))
                              (.toBeVisible))))

              (js-await (.close popup)))

            ;; === PHASE 5: Collapse/expand toggle works ===
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; Settings starts collapsed
              (js-await (-> (expect (.locator popup ".settings-content"))
                            (.not.toBeVisible)))

              ;; Expand
              (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
              (js-await (sleep 200))
              (js-await (-> (expect (.locator popup ".settings-content"))
                            (.toBeVisible)))

              ;; Collapse again
              (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
              (js-await (sleep 200))
              (js-await (-> (expect (.locator popup ".settings-content"))
                            (.not.toBeVisible)))

              ;; REPL Connect section is expanded by default
              (js-await (-> (expect (.locator popup "#nrepl-port"))
                            (.toBeVisible)))

              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))
