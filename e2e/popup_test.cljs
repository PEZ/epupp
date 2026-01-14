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
                                           wait-for-connection ws-port-1 assert-no-errors!]]))

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

              (js-await (assert-no-errors! popup))
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

                (js-await (assert-no-errors! popup))
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

                (js-await (assert-no-errors! popup))
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

                (js-await (assert-no-errors! popup))
                (js-await (.close popup)))
              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Popup User Journey: Blank Slate Hints
;; =============================================================================

(test "Popup: blank slate hints show contextual guidance"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Fresh state shows guidance for creating first script ===
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (clear-storage popup))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Connected Tabs section shows actionable guidance
              (let [no-conn-hint (.locator popup ".no-connections-hint")]
                (js-await (-> (expect no-conn-hint) (.toBeVisible)))
                (js-await (-> (expect no-conn-hint) (.toContainText "Step 1")))
                (js-await (-> (expect no-conn-hint) (.toContainText "Step 2"))))

              ;; Matching Scripts section shows "no userscripts yet" message
              ;; (Built-in Gist Installer exists but no user scripts)
              ;; Note: Gist Installer only matches gist.github.com, not test URLs
              (let [no-scripts (.locator popup ".script-list .no-scripts")
                    no-scripts-hint (.locator popup ".script-list .no-scripts-hint")]
                ;; Should show guidance to create first script
                (js-await (-> (expect no-scripts) (.toContainText "No userscripts yet")))
                (js-await (-> (expect no-scripts-hint) (.toContainText "DevTools")))
                (js-await (-> (expect no-scripts-hint) (.toContainText "Epupp"))))

              (js-await (.close popup)))

            ;; === PHASE 2: With scripts, shows "no match" with pattern hint ===
            ;; Create a script that doesn't match the test URL
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "GitHub Script"
                                            :match "*://github.com/*"
                                            :code "(println \"github\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "github_script.cljs"))
              (js-await (.close panel)))

            ;; Open popup with a test URL that doesn't match any scripts
            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              ;; Set test URL to localhost (which doesn't match github.com pattern)
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:8080/test';"))
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))

              ;; Matching Scripts section should show "no match" with hostname hint
              (let [matching-section (.locator popup ".collapsible-section:has(.section-title:text(\"Matching Scripts\"))")
                    no-scripts (.locator matching-section ".no-scripts")
                    no-scripts-hint (.locator matching-section ".no-scripts-hint")]
                (js-await (-> (expect no-scripts) (.toContainText "No scripts match")))
                ;; Hint should show URL pattern example with the current hostname
                (js-await (-> (expect no-scripts-hint) (.toContainText "localhost"))))

              ;; Other Scripts section should have our github script
              ;; Let's verify the "other scripts" hint appears when that section is empty
              ;; First, we need to check what's in the other scripts section
              (js-await (.close popup)))

            ;; === PHASE 3: Other Scripts hint shows when section is empty ===
            ;; Create a script that matches the test URL (so "other" is empty)
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "Localhost Script"
                                            :match "*://localhost:8080/*"
                                            :code "(println \"localhost\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "localhost_script.cljs"))
              (js-await (.close panel)))

            (let [popup (js-await (.newPage context))
                  popup-url (str "chrome-extension://" ext-id "/popup.html")]
              ;; Set URL so localhost script matches, github script doesn't
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:8080/test';"))
              (js-await (.goto popup popup-url #js {:timeout 1000}))
              (js-await (wait-for-popup-ready popup))

              ;; Expand "Other Scripts" section to see the hint
              (let [other-section-header (.locator popup ".collapsible-section:has(.section-title:text(\"Other Scripts\")) .section-header")]
                (js-await (.click other-section-header)))

              ;; Other Scripts hint should explain what appears there
              ;; Note: GitHub script will be in "other" so hint won't show
              ;; We need a different approach - delete all non-matching scripts

              (js-await (assert-no-errors! popup))
              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Popup User Journey: Auto-Connect and Auto-Reconnect REPL
;; =============================================================================

(test "Popup: auto-connect REPL triggers Scittle injection on page load"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Enable auto-connect setting via popup
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; Clear storage for clean state
              (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Expand settings section
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))

              ;; Enable auto-connect
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (-> (expect auto-connect-checkbox) (.toBeVisible)))
                (js-await (.click auto-connect-checkbox))
                ;; Wait for checkbox to be checked
                (js-await (-> (expect auto-connect-checkbox) (.toBeChecked))))

              (js-await (.close popup)))

            ;; Navigate to a page - should trigger auto-connect (WS_CONNECTED event)
            (let [page (js-await (.newPage context))]
              (js/console.log "Navigating to localhost:18080/basic.html with auto-connect enabled...")
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Page loaded")

              ;; Wait for SCITTLE_LOADED event - indicates auto-connect triggered
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js/console.log "Waiting for SCITTLE_LOADED event...")
                    event (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))]
                (js/console.log "SCITTLE_LOADED event:" (js/JSON.stringify event))
                (js-await (-> (expect (.-event event)) (.toBe "SCITTLE_LOADED")))
                (js-await (assert-no-errors! popup))
                (js-await (.close popup)))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Popup: SPA navigation does NOT trigger REPL reconnection"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Enable auto-connect via popup
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (wait-for-popup-ready popup))

              ;; Expand settings section
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))

              ;; Enable auto-connect
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (.click auto-connect-checkbox))
                (js-await (-> (expect auto-connect-checkbox) (.toBeChecked))))

              (js-await (.close popup)))

            ;; Navigate to SPA test page - should trigger initial auto-connect
            (let [page (js-await (.newPage context))]
              (js/console.log "Navigating to SPA test page with auto-connect enabled...")
              (js-await (.goto page "http://localhost:18080/spa-test.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "SPA page loaded")

              ;; Wait for initial SCITTLE_LOADED event
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js/console.log "Waiting for initial SCITTLE_LOADED...")
                    event (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))]
                (js/console.log "Initial SCITTLE_LOADED:" (js/JSON.stringify event))

                ;; Get current event count
                (let [events-before (js-await (fixtures/get-test-events popup))
                      scittle-count-before (.-length (.filter events-before (fn [e] (= (.-event e) "SCITTLE_LOADED"))))]
                  (js/console.log "SCITTLE_LOADED count before SPA nav:" scittle-count-before)

                  ;; Perform SPA navigation (client-side, no page reload)
                  (js/console.log "Performing SPA navigation (should NOT trigger reconnect)...")
                  (js-await (.click (.locator page "#nav-about")))
                  (js-await (-> (expect (.locator page "#current-view"))
                                (.toContainText "about")))
                  (js/console.log "SPA navigated to 'about' view")

                  ;; Do another SPA navigation
                  (js-await (.click (.locator page "#nav-contact")))
                  (js-await (-> (expect (.locator page "#current-view"))
                                (.toContainText "contact")))
                  (js/console.log "SPA navigated to 'contact' view")

                  ;; Assert no NEW SCITTLE_LOADED event occurs (rapid-poll for 300ms)
                  ;; Using scittle-count-before as the baseline
                  (js-await (fixtures/assert-no-new-event-within popup "SCITTLE_LOADED" scittle-count-before 300))
                  (js/console.log "Verified: No new SCITTLE_LOADED after SPA navigation"))

                (js-await (assert-no-errors! popup))
                (js-await (.close popup)))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Popup: auto-reconnect triggers Scittle injection on page reload of previously connected tab"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Clear storage for clean state, ensure auto-reconnect is enabled (default)
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Verify auto-reconnect is enabled by default
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))
              (let [auto-reconnect-checkbox (.locator popup "#auto-reconnect-repl")]
                (js-await (-> (expect auto-reconnect-checkbox) (.toBeChecked)))
                (js/console.log "Auto-reconnect is enabled (default)"))

              ;; Ensure auto-connect-all is OFF (so auto-reconnect logic is tested)
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (-> (expect auto-connect-checkbox) (.not.toBeChecked)))
                (js/console.log "Auto-connect-all is disabled"))

              (js-await (.close popup)))

            ;; Navigate to test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Test page loaded")

              ;; Manually connect REPL to this tab
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
                  (js/console.log "Connecting to tab" tab-id "on port" ws-port-1)
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))

                  ;; Wait for connection to establish and Scittle to load
                  (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))
                  (js/console.log "REPL connected and Scittle loaded")

                  ;; Verify connection exists
                  (let [connections (js-await (fixtures/get-connections popup))]
                    (js-await (-> (expect (.-length connections)) (.toBe 1)))
                    (js/console.log "Connection verified:" (.-length connections) "active")))

                ;; Clear test events RIGHT BEFORE reload to get clean slate
                (js-await (fixtures/clear-test-events! popup))
                (js/console.log "Cleared test events before reload")
                (js-await (.close popup)))

              ;; Reload the page - this disconnects WebSocket, triggers auto-reconnect
              (js/console.log "Reloading page - should trigger auto-reconnect...")
              (js-await (.reload page))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Page reloaded")

              ;; Wait for auto-reconnect to trigger Scittle injection
              ;; This is the key assertion: after reload, auto-reconnect should load Scittle again
              (let [popup2 (js-await (create-popup-page context ext-id))
                    _ (js/console.log "Waiting for SCITTLE_LOADED event from auto-reconnect...")
                    event (js-await (fixtures/wait-for-event popup2 "SCITTLE_LOADED" 10000))]
                (js/console.log "Auto-reconnect triggered! SCITTLE_LOADED event:" (js/JSON.stringify event))
                ;; The presence of SCITTLE_LOADED event after clearing events proves auto-reconnect worked
                (js-await (-> (expect (.-event event)) (.toBe "SCITTLE_LOADED")))
                (js-await (assert-no-errors! popup2))
                (js-await (.close popup2)))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Popup: auto-reconnect does NOT trigger for tabs never connected"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Clear storage for clean state
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Verify auto-reconnect is enabled but auto-connect-all is OFF
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))
              (let [auto-reconnect-checkbox (.locator popup "#auto-reconnect-repl")]
                (js-await (-> (expect auto-reconnect-checkbox) (.toBeChecked))))
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (-> (expect auto-connect-checkbox) (.not.toBeChecked))))
              (js/console.log "Auto-reconnect ON, auto-connect-all OFF")
              (js-await (.close popup)))

            ;; Navigate to test page WITHOUT connecting REPL first
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Test page loaded (never connected)")

              ;; Get initial SCITTLE_LOADED count
              (let [popup (js-await (create-popup-page context ext-id))
                    events-before (js-await (fixtures/get-test-events popup))
                    scittle-count-before (.-length (.filter events-before (fn [e] (= (.-event e) "SCITTLE_LOADED"))))]
                (js/console.log "SCITTLE_LOADED count before reload (should be 0):" scittle-count-before)
                (js-await (.close popup))

                ;; Reload the page - should NOT trigger auto-reconnect (never connected)
                (js/console.log "Reloading page - should NOT trigger any connection...")
                (js-await (.reload page))
                (js-await (-> (expect (.locator page "#test-marker"))
                              (.toContainText "ready")))
                (js/console.log "Page reloaded")

                ;; Assert no NEW SCITTLE_LOADED event occurs (rapid-poll for 300ms)
                ;; scittle-count-before is 0 for never-connected tab
                (let [popup2 (js-await (create-popup-page context ext-id))]
                  (js-await (fixtures/assert-no-new-event-within popup2 "SCITTLE_LOADED" scittle-count-before 300))
                  (js/console.log "SCITTLE_LOADED count after reload (should still be 0):" scittle-count-before)
                  (js-await (assert-no-errors! popup2))
                  (js-await (.close popup2))))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Popup: disabled auto-reconnect does NOT trigger on page reload"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Clear storage and DISABLE auto-reconnect
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Expand settings and disable auto-reconnect
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))
              (let [auto-reconnect-checkbox (.locator popup "#auto-reconnect-repl")]
                ;; It's checked by default, uncheck it
                (js-await (-> (expect auto-reconnect-checkbox) (.toBeChecked)))
                (js-await (.click auto-reconnect-checkbox))
                (js-await (-> (expect auto-reconnect-checkbox) (.not.toBeChecked)))
                (js/console.log "Auto-reconnect disabled"))

              ;; Ensure auto-connect-all is also OFF
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (-> (expect auto-connect-checkbox) (.not.toBeChecked))))
              (js-await (.close popup)))

            ;; Navigate to test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Test page loaded")

              ;; Manually connect REPL to this tab
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
                  (js/console.log "Connecting to tab" tab-id "on port" ws-port-1)
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))
                  (js-await (wait-for-connection popup 5000))
                  (js/console.log "REPL connected"))
                (js-await (.close popup)))

              ;; Get SCITTLE_LOADED count before reload
              (let [popup (js-await (create-popup-page context ext-id))
                    events-before (js-await (fixtures/get-test-events popup))
                    scittle-count-before (.-length (.filter events-before (fn [e] (= (.-event e) "SCITTLE_LOADED"))))]
                (js/console.log "SCITTLE_LOADED count before reload:" scittle-count-before)
                (js-await (.close popup))

                ;; Reload the page - should NOT trigger reconnect (setting disabled)
                (js/console.log "Reloading page with auto-reconnect DISABLED...")
                (js-await (.reload page))
                (js-await (-> (expect (.locator page "#test-marker"))
                              (.toContainText "ready")))
                (js/console.log "Page reloaded")

                ;; Assert no NEW SCITTLE_LOADED event occurs (rapid-poll for 300ms)
                (let [popup2 (js-await (create-popup-page context ext-id))]
                  (js-await (fixtures/assert-no-new-event-within popup2 "SCITTLE_LOADED" scittle-count-before 300))
                  (js/console.log "SCITTLE_LOADED count after reload:" scittle-count-before)
                  (js-await (assert-no-errors! popup2))
                  (js-await (.close popup2))))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Popup User Journey: Toolbar Icon State
;; =============================================================================

(test "Popup: toolbar icon reflects REPL connection state"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Navigate to a test page first
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              ;; Check initial icon state - should be "disconnected" (white bolt)
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js-await (wait-for-popup-ready popup))
                    events-initial (js-await (fixtures/get-test-events popup))
                    icon-events (.filter events-initial (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))]
                (js/console.log "Initial icon events:" (js/JSON.stringify icon-events))
                ;; Initial state should be "disconnected"
                (when (pos? (.-length icon-events))
                  (let [last-event (aget icon-events (dec (.-length icon-events)))]
                    (js-await (-> (expect (.. last-event -data -state))
                                  (.toBe "disconnected")))))
                (js-await (.close popup)))

              ;; Enable auto-connect via popup
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))
                (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                  (js-await (.click settings-header)))
                (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                  (js-await (.click auto-connect-checkbox))
                  (js-await (-> (expect auto-connect-checkbox) (.toBeChecked))))
                (js-await (.close popup)))

              ;; Navigate to trigger auto-connect (Scittle injection)
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              ;; Wait for Scittle to be loaded
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))]

                ;; Check icon state after Scittle injection - should be "injected" (yellow) or "connected" (green)
                (let [events (js-await (fixtures/get-test-events popup))
                      icon-events (.filter events (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))]
                  (js/console.log "Icon events after Scittle load:" (js/JSON.stringify icon-events))
                  ;; Should have icon state events
                  (js-await (-> (expect (.-length icon-events))
                                (.toBeGreaterThan 0)))
                  ;; Last event should be "injected" or "connected"
                  (let [last-event (aget icon-events (dec (.-length icon-events)))
                        state (.. last-event -data -state)]
                    (js/console.log "Final icon state:" state)
                    (js-await (-> (expect (or (= state "injected") (= state "connected")))
                                  (.toBeTruthy)))))
                (js-await (assert-no-errors! popup))
                (js-await (.close popup)))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Popup: injected state is tab-local, connected state is global"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Create a userscript that ONLY targets basic.html
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "tab-local-test"
                                            :match "*://localhost:*/basic.html"
                                            :code "(js/console.log \"Tab A script loaded\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button:text(\"Save Script\")")))
              (js-await (wait-for-save-status panel "Created"))
              (js-await (.close panel)))

            ;; Approve the script using test URL override
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))
              (let [script-item (.locator popup ".script-item:has-text(\"tab_local_test.cljs\")")]
                (js-await (-> (expect script-item) (.toBeVisible)))
                (let [allow-btn (.locator script-item "button:has-text(\"Allow\")")]
                  (when (pos? (js-await (.count allow-btn)))
                    (js-await (.click allow-btn))
                    (js-await (-> (expect allow-btn) (.not.toBeVisible)))))
                (js-await (.close popup))))

            ;; Navigate Tab A - script is pre-approved, should inject
            (let [tab-a (js-await (.newPage context))]
              (js-await (.goto tab-a "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator tab-a "#test-marker"))
                            (.toContainText "ready")))

              ;; Wait for Scittle injection, capture Tab A's Chrome tab-id
              (let [popup (js-await (create-popup-page context ext-id))
                    scittle-loaded-event (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))
                    tab-a-id (aget (.-data scittle-loaded-event) "tab-id")]
                (js-await (.close popup))

                ;; Assert Tab A shows injected/connected based on its tab-id
                (let [popup (js-await (create-popup-page context ext-id))
                      events (js-await (fixtures/get-test-events popup))
                      icon-events (.filter events
                                           (fn [e]
                                             (and (= (.-event e) "ICON_STATE_CHANGED")
                                                  (= (aget (.-data e) "tab-id") tab-a-id))))]
                  (js-await (-> (expect (.-length icon-events))
                                (.toBeGreaterThan 0)))
                  (let [last-event (aget icon-events (dec (.-length icon-events)))
                        last-state (.. last-event -data -state)]
                    (js-await (-> (expect (or (= last-state "injected")
                                              (= last-state "connected")))
                                  (.toBeTruthy))))
                  (js-await (.close popup)))

                ;; Open Tab B (spa-test.html - does NOT match our script)
                (let [tab-b (js-await (.newPage context))]
                  (js-await (.goto tab-b "http://localhost:18080/spa-test.html" #js {:timeout 1000}))
                  (js-await (-> (expect (.locator tab-b "#test-marker"))
                                (.toContainText "ready")))

                  ;; Bring Tab B to focus
                  (js-await (.bringToFront tab-b))
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))

                  ;; Tab B should show disconnected, and the icon event should be for Tab B
                  ;; (i.e. not Tab A's Chrome tab-id)
                  (let [popup (js-await (create-popup-page context ext-id))
                        events (js-await (fixtures/get-test-events popup))
                        icon-events (.filter events (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))
                        last-event (aget icon-events (dec (.-length icon-events)))
                        last-tab-id (aget (.-data last-event) "tab-id")
                        last-state (.. last-event -data -state)]
                    (js-await (-> (expect last-tab-id) (.toBeTruthy)))
                    (js-await (-> (expect (not (= last-tab-id tab-a-id)))
                                  (.toBeTruthy)))
                    (js-await (-> (expect (= last-state "disconnected"))
                                  (.toBeTruthy)))
                    (js-await (.close popup)))

                  ;; Bring Tab A back to focus
                  (js-await (.bringToFront tab-a))
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))

                  ;; Tab A should STILL show injected/connected
                  (let [popup (js-await (create-popup-page context ext-id))
                        events (js-await (fixtures/get-test-events popup))
                        icon-events (.filter events
                                             (fn [e]
                                               (and (= (.-event e) "ICON_STATE_CHANGED")
                                                    (= (aget (.-data e) "tab-id") tab-a-id))))]
                    (js-await (-> (expect (.-length icon-events))
                                  (.toBeGreaterThan 0)))
                    (let [last-event (aget icon-events (dec (.-length icon-events)))
                          last-state (.. last-event -data -state)]
                      (js-await (-> (expect (or (= last-state "injected")
                                                (= last-state "connected")))
                                    (.toBeTruthy))))
                    (js-await (assert-no-errors! popup))
                    (js-await (.close popup)))

                  (js-await (.close tab-b))))
              (js-await (.close tab-a)))

            (finally
              (js-await (.close context)))))))

(test "Popup: get-connections API returns active REPL connections"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Navigate to a test page first
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Test page loaded")

              ;; Open popup
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))

                ;; Check initial state - no connections yet
                (let [initial-conns (js-await (fixtures/get-connections popup))]
                  (js/console.log "Initial connections:" (.-length initial-conns))
                  (js-await (-> (expect (.-length initial-conns))
                                (.toBe 0))))

                ;; Find the test page tab and connect
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
                  (js/console.log "Found test page tab ID:" tab-id)
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))
                  (js/console.log "Connected to tab via WebSocket port" ws-port-1)

                  ;; Wait for connection event
                  (js-await (wait-for-connection popup 5000))

                  ;; Now get-connections should return the connection
                  (let [connections (js-await (fixtures/get-connections popup))
                        conn-count (.-length connections)]
                    (js/console.log "Connections after connect:" conn-count)
                    (js/console.log "Connection data:" (js/JSON.stringify connections))

                    ;; Should have exactly 1 connection now
                    (js-await (-> (expect conn-count)
                                  (.toBe 1)))

                    ;; Verify the connection has the tab-id
                    (when (> conn-count 0)
                      (let [conn (aget connections 0)
                            conn-tab-id (aget conn "tab-id")]
                        (js/console.log "Connected tab ID:" conn-tab-id "expected:" tab-id)
                        (js-await (-> (expect (str conn-tab-id))
                                      (.toBe (str tab-id))))))))

                (js-await (assert-no-errors! popup))
                (js-await (.close popup)))
              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Popup: UI updates immediately after connecting (no tab switch needed)"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Navigate to a test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              ;; Open popup - keep it open throughout the test
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))

                ;; Initially should show no connections
                (let [no-conn-msg (.locator popup ".no-connections")]
                  (js-await (-> (expect no-conn-msg)
                                (.toBeVisible)))
                  (js/console.log "Initial state: no connections shown"))

                ;; Connect to the test page while popup is still open
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))
                  (js/console.log "Connected to tab" tab-id)

                  ;; Wait for connection event
                  (js-await (wait-for-connection popup 5000))

                  ;; WITHOUT reloading or switching tabs, the UI should update
                  ;; BUG: Currently requires popup reload or tab switch
                  (let [connected-items (.locator popup ".connected-tab-item")]
                    (js-await (-> (expect connected-items)
                                  (.toHaveCount 1 #js {:timeout 2000})))
                    (js/console.log "UI updated with connected tab")))

                (js-await (assert-no-errors! popup))
                (js-await (.close popup)))
              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))
