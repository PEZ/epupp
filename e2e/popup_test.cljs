(ns popup-test
  "E2E tests for the popup UI - user journey style.

   Coverage:
   - REPL connection setup (ports, command generation)
   - Script management with approval workflow"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           create-panel-page clear-storage wait-for-popup-ready
                                           wait-for-save-status wait-for-script-count
                                           wait-for-checkbox-state wait-for-panel-ready
                                           wait-for-connection ws-port-1]]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata."
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
                (js-await (-> (expect copy-btn) (.toContainText "Copied" #js {:timeout 500}))))

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
              (js-await (wait-for-popup-ready popup))
              ;; Built-in Gist Installer script is auto-created, so we have 1 script
              (js-await (-> (expect (.locator popup ".script-item:has-text(\"Gist Installer\")"))
                            (.toBeVisible)))
              (js-await (.close popup)))

            ;; === PHASE 2: Create scripts via panel ===
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "Script One"
                                            :match "*://example.com/*"
                                            :code "(println \"script one\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "script_one.cljs"))
              (js-await (.close panel)))

            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "Script Two"
                                            :match "*://example.org/*"
                                            :code "(println \"script two\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "script_two.cljs"))
              (js-await (.close panel)))

            ;; === PHASE 3: Verify scripts, test enable/disable ===
            ;; Names are normalized: "Script One" -> "script_one.cljs"
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; 3 scripts: built-in Gist Installer + 2 user scripts
              (js-await (wait-for-script-count popup 3))

              ;; Verify script action buttons exist (inspect, run, delete)
              (let [item (.locator popup ".script-item:has-text(\"script_one.cljs\")")]
                (js-await (-> (expect (.locator item "button.script-inspect")) (.toBeVisible)))
                (js-await (-> (expect (.locator item "button.script-run")) (.toBeVisible)))
                (js-await (-> (expect (.locator item "button.script-delete")) (.toBeVisible))))

              ;; Toggle disable (using normalized name)
              (let [item (.locator popup ".script-item:has-text(\"script_one.cljs\")")
                    checkbox (.locator item "input[type='checkbox']")]
                (js-await (-> (expect checkbox) (.toBeChecked)))
                (js-await (.click checkbox))
                (js-await (wait-for-checkbox-state checkbox false)))

              ;; Delete (using normalized name)
              (.on popup "dialog" (fn [dialog] (.accept dialog)))
              (let [item (.locator popup ".script-item:has-text(\"script_two.cljs\")")
                    delete-btn (.locator item "button.script-delete")]
                (js-await (.click delete-btn))
                ;; 2 remaining: built-in Gist Installer + script_one.cljs
                (js-await (wait-for-script-count popup 2)))

              (js-await (.close popup)))

            ;; === PHASE 4: Approval workflow (Allow) ===
            ;; Re-enable and create script for approval test
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "Approval Test"
                                            :match "*://approval.test/*"
                                            :code "(println \"needs approval\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "approval_test.cljs"))
              (js-await (.close panel)))

            ;; Open popup with test URL override
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'https://approval.test/page';"))
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))

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
                  ;; Buttons disappear, approval class removed (Playwright auto-waits)
                  (js-await (-> (expect allow-btn) (.not.toBeVisible)))
                  (js-await (-> (expect item) (.not.toHaveClass (js/RegExp. "script-item-approval"))))))
              (js-await (.close popup)))

            ;; === PHASE 5: Approval workflow (Deny) ===
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "Deny Test"
                                            :match "*://deny.test/*"
                                            :code "(println \"deny me\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "deny_test.cljs"))
              (js-await (.close panel)))

            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'https://deny.test/';"))
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))

              ;; "Deny Test" normalized to "deny_test.cljs"
              (let [item (.locator popup ".script-item:has-text(\"deny_test.cljs\")")
                    deny-btn (.locator item "button.approval-deny")
                    checkbox (.locator item "input[type='checkbox']")]
                (js-await (.click deny-btn))
                ;; Deny disables the script (Playwright auto-waits for assertions)
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
              (js-await (wait-for-popup-ready popup))

              ;; Settings section header visible (collapsed by default)
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")
                    settings-content (.locator popup ".settings-content")]
                (js-await (-> (expect settings-header) (.toBeVisible)))

                ;; Click to expand settings and wait for content
                (js-await (.click settings-header))
                (js-await (-> (expect settings-content) (.toBeVisible))))

              ;; Settings content renders with section titles (2 sections now)
              (js-await (-> (expect (.locator popup ".settings-section-title:text(\"Allowed Userscript-install Base URLs\")"))
                            (.toBeVisible)))

              ;; Default origins list shows config origins
              (js-await (-> (expect (.locator popup ".origin-item-default"))
                            (.toHaveCount 7))) ;; dev config has 7 origins

              ;; No user origins initially
              (js-await (-> (expect (.locator popup ".no-origins"))
                            (.toContainText "No custom origins")))

              (js-await (.close popup)))

            ;; === PHASE 2: Add custom origin ===
            (let [popup (js-await (create-popup-page context ext-id))
                  settings-content (.locator popup ".settings-content")]
              ;; Expand settings section
              (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
              (js-await (-> (expect settings-content) (.toBeVisible)))

              ;; Fill in valid origin and click Add
              (let [input (.locator popup ".add-origin-form input")
                    add-btn (.locator popup "button.add-btn")
                    user-origins (.locator popup ".origins-section:has(.origins-label:text(\"Your custom origins\")) .origin-item")]
                (js-await (.fill input "https://git.example.com/"))
                (js-await (.click add-btn))
                ;; Wait for origin to appear in user list
                (js-await (-> (expect user-origins) (.toHaveCount 1)))
                (js-await (-> (expect (.first user-origins)) (.toContainText "https://git.example.com/"))))

              ;; Input cleared
              (js-await (-> (expect (.locator popup ".add-origin-form input"))
                            (.toHaveValue "")))

              (js-await (.close popup)))

            ;; === PHASE 3: Invalid origin shows error ===
            (let [popup (js-await (create-popup-page context ext-id))
                  settings-content (.locator popup ".settings-content")]
              (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
              (js-await (-> (expect settings-content) (.toBeVisible)))

              ;; Try adding invalid origin (no trailing slash)
              (let [input (.locator popup ".add-origin-form input")
                    add-btn (.locator popup "button.add-btn")]
                (js-await (.fill input "https://invalid.com"))
                (js-await (.click add-btn)))

              ;; Error message appears (Playwright auto-waits)
              (js-await (-> (expect (.locator popup ".add-origin-error"))
                            (.toBeVisible)))
              (js-await (-> (expect (.locator popup ".add-origin-error"))
                            (.toContainText "http:// or https://")))

              (js-await (.close popup)))

            ;; === PHASE 4: Remove custom origin ===
            (let [popup (js-await (create-popup-page context ext-id))
                  settings-content (.locator popup ".settings-content")]
              (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
              (js-await (-> (expect settings-content) (.toBeVisible)))

              ;; Verify origin still exists from phase 2
              (let [user-origins (.locator popup ".origins-section:has(.origins-label:text(\"Your custom origins\")) .origin-item")]
                (js-await (-> (expect user-origins) (.toHaveCount 1)))

                ;; Click delete button
                (let [delete-btn (.locator (.first user-origins) "button.origin-delete")]
                  (js-await (.click delete-btn)))

                ;; Origin removed, shows empty message (Playwright auto-waits)
                (js-await (-> (expect user-origins) (.toHaveCount 0)))
                (js-await (-> (expect (.locator popup ".no-origins"))
                              (.toBeVisible))))

              (js-await (.close popup)))

            ;; === PHASE 5: Collapse/expand toggle works ===
            (let [popup (js-await (create-popup-page context ext-id))
                  settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")
                  settings-content (.locator popup ".settings-content")]
              ;; Settings starts collapsed
              (js-await (-> (expect settings-content) (.not.toBeVisible)))

              ;; Expand - wait for visibility
              (js-await (.click settings-header))
              (js-await (-> (expect settings-content) (.toBeVisible)))

              ;; Collapse again - wait for hidden
              (js-await (.click settings-header))
              (js-await (-> (expect settings-content) (.not.toBeVisible)))

              ;; REPL Connect section is expanded by default
              (js-await (-> (expect (.locator popup "#nrepl-port"))
                            (.toBeVisible)))

              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Popup User Journey: Auto-Connect REPL Setting
;; =============================================================================

(test "Popup: auto-connect REPL setting"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Setting appears in Settings section with warning ===
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (clear-storage popup))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Expand settings section
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")
                    settings-content (.locator popup ".settings-content")]
                (js-await (.click settings-header))
                (js-await (-> (expect settings-content) (.toBeVisible))))

              ;; Auto-connect checkbox exists and is unchecked by default
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (-> (expect auto-connect-checkbox) (.toBeVisible)))
                (js-await (-> (expect auto-connect-checkbox) (.not.toBeChecked))))

              ;; Warning text is visible
              (js-await (-> (expect (.locator popup ".auto-connect-warning"))
                            (.toContainText "inject the Scittle REPL")))

              (js-await (.close popup)))

            ;; === PHASE 2: Enable setting and verify it persists ===
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; Expand settings
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))

              ;; Enable auto-connect
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (.click auto-connect-checkbox))
                (js-await (wait-for-checkbox-state auto-connect-checkbox true)))

              (js-await (.close popup)))

            ;; === PHASE 3: Setting persists after reload ===
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; Expand settings
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))

              ;; Verify setting is still enabled
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (-> (expect auto-connect-checkbox) (.toBeChecked))))

              ;; Disable it again
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (.click auto-connect-checkbox))
                (js-await (wait-for-checkbox-state auto-connect-checkbox false)))

              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Popup User Journey: Connection Tracking and Management
;; =============================================================================


(test "Popup: connection tracking displays connected tabs with reveal buttons"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Navigate to a test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              ;; Open popup and connect
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))

                ;; Initially no connections
                (let [no-conn-msg (.locator popup ".no-connections")]
                  (js-await (-> (expect no-conn-msg)
                                (.toBeVisible))))

                ;; Find and connect the page
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))]
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))

                  ;; Wait for connection event then reload popup
                  (js-await (wait-for-connection popup 5000))
                  (js-await (.reload popup))
                  (js-await (wait-for-popup-ready popup))

                  ;; Should now show 1 connected tab
                  (let [connected-items (.locator popup ".connected-tab-item")]
                    (js-await (-> (expect connected-items)
                                  (.toHaveCount 1))))

                  ;; Connected tab should show port number
                  (let [port-elem (.locator popup ".connected-tab-port")]
                    (js-await (-> (expect port-elem)
                                  (.toContainText ":12346"))))

                  ;; Tab should have a reveal or disconnect button
                  (let [action-btns (.locator popup ".reveal-tab-btn, .disconnect-tab-btn")]
                    (js-await (-> (expect action-btns)
                                  (.toBeVisible)))))

                (js-await (.close popup)))
              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Popup User Journey: Connection Status Feedback
;; =============================================================================

(test "Popup: connection failure shows error status near Connect button"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Navigate to a test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))

              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))

                ;; Click Connect - will fail (permissions issue with UI-based connect)
                (let [connect-btn (.locator popup "#connect")]
                  (js-await (.click connect-btn)))

                ;; Should show failure status with appropriate styling
                (let [status-elem (.locator popup ".connect-status")]
                  (js-await (-> (expect status-elem)
                                (.toBeVisible)))
                  (js-await (-> (expect status-elem)
                                (.toContainText "Failed")))
                  ;; Failed status should have failed class
                  (js-await (-> (expect status-elem)
                                (.toHaveClass #"status-failed"))))

                (js-await (.close popup)))
              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Popup: successful connection via API updates UI correctly"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))

                ;; Initially no connections
                (let [no-conn-msg (.locator popup ".no-connections")]
                  (js-await (-> (expect no-conn-msg)
                                (.toBeVisible))))

                ;; Connect via direct API (bypasses UI button permission issues)
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))]
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))

                  ;; Wait for connection event then reload popup
                  (js-await (wait-for-connection popup 5000))
                  (js-await (.reload popup))
                  (js-await (wait-for-popup-ready popup))

                  ;; Should now show connection in UI
                  (let [connected-items (.locator popup ".connected-tab-item")]
                    (js-await (-> (expect connected-items)
                                  (.toHaveCount 1)))))

                (js-await (.close popup)))
              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))
