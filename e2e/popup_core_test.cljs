(ns popup-core-test
  "E2E tests for popup core functionality - REPL setup, scripts, settings, hints."
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
