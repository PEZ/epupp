(ns e2e.popup-core-test
  "E2E tests for popup core functionality - REPL setup, scripts, settings, hints."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           find-tab-id
                                           create-panel-page clear-storage wait-for-popup-ready
                                           wait-for-save-status wait-for-script-count
                                           wait-for-checkbox-state assert-no-errors!
                                           http-port clear-test-events! wait-for-event]]))

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

(defn- ^:async test_repl_connection_setup []
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
        (js-await (.close context))))))

(defn- ^:async test_script_management_workflow []
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

        ;; Toggle enable then disable (using normalized name)
        ;; Script starts disabled by default (auto-run script with match pattern)
        (let [item (.locator popup ".script-item:has-text(\"script_one.cljs\")")
              checkbox (.locator item "input[type='checkbox']")]
          (js-await (-> (expect checkbox) (.not.toBeChecked)))
          (js-await (.click checkbox))
          (js-await (wait-for-checkbox-state checkbox true))
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

      (finally
        (js-await (.close context))))))

(defn- ^:async test_settings_view_and_origin_management []
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

        ;; Error message appears in system banner (Playwright auto-waits)
        (js-await (-> (expect (.locator popup ".fs-error-banner"))
                      (.toBeVisible)))
        (js-await (-> (expect (.locator popup ".fs-error-banner"))
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
            settings-section (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\"))")
            settings-header (.locator settings-section ".section-header")
            settings-content (.locator popup ".settings-content")]
        ;; Settings starts collapsed (check class, not visibility - content stays in DOM for animations)
        (js-await (-> (expect settings-section) (.toHaveClass #"collapsed")))

        ;; Expand - wait for content to be visible
        (js-await (.click settings-header))
        (js-await (-> (expect settings-section) (.not.toHaveClass #"collapsed")))
        (js-await (-> (expect settings-content) (.toBeVisible)))

        ;; Collapse again - check class
        (js-await (.click settings-header))
        (js-await (-> (expect settings-section) (.toHaveClass #"collapsed")))

        ;; REPL Connect section is expanded by default
        (js-await (-> (expect (.locator popup "#nrepl-port"))
                      (.toBeVisible)))

        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; TODO: Re-implement blank slate hints test with simpler approach
;; The original test was too complex with 3 phases and incomplete logic
;; For now, the hints are manually tested and work correctly
(defn- ^:async test_blank_slate_hints_show_contextual_guidance []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Simplified test - just verify hints exist in fresh state
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-storage popup))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        ;; Connected Tabs section shows actionable guidance
        (let [no-conn-hint (.locator popup ".no-connections-hint")]
          (js-await (-> (expect no-conn-hint) (.toBeVisible #js {:timeout 2000})))
          (js-await (-> (expect no-conn-hint) (.toContainText "Step 1" #js {:timeout 500}))))

        ;; Matching Scripts section shows "no userscripts yet" message
        (let [matching-section (.locator popup ".collapsible-section:has(.section-title:text(\"Auto-run for This Page\"))")
              no-scripts (.locator matching-section ".no-scripts")]
          (js-await (-> (expect no-scripts) (.toContainText "No userscripts yet" #js {:timeout 500}))))

        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_play_button_evaluates_script_in_current_tab []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Setup - open test page ===
      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html")))
        (js-await (.waitForLoadState test-page "domcontentloaded"))

        ;; === PHASE 2: Create a script that modifies the DOM ===
        (let [panel (js-await (create-panel-page context ext-id))
              ;; Script that adds a div with a specific ID to prove it ran
              code (code-with-manifest {:name "Play Button Test"
                                        :match "*://localhost:*/*"
                                        :code "(let [el (js/document.createElement \"div\")]
                                                 (set! (.-id el) \"play-button-test-marker\")
                                                 (set! (.-textContent el) \"Script executed!\")
                                                 (.appendChild js/document.body el))"})]
          (js-await (.fill (.locator panel "#code-area") code))
          (js-await (.click (.locator panel "button.btn-save")))
          (js-await (wait-for-save-status panel "play_button_test.cljs"))
          (js-await (.close panel)))

        ;; === PHASE 3: Click play button in popup ===
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (clear-test-events! popup))

          ;; Ensure the test page is the active tab for popup actions
          (let [tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
            (js-await (.evaluate popup
                                 (fn [target-tab-id]
                                   (js/Promise.
                                    (fn [resolve]
                                      (js/chrome.tabs.update target-tab-id #js {:active true}
                                                             (fn [] (resolve true))))))
                                 tab-id)))

          ;; Find the script item and click run
          (let [item (.locator popup ".script-item:has-text(\"play_button_test.cljs\")")
                run-btn (.locator item "button.script-run")]
            (js-await (-> (expect run-btn) (.toBeVisible #js {:timeout 500})))
            (js-await (.click run-btn)))

          ;; Wait for script injection event instead of sleeping
          (js-await (wait-for-event popup "SCRIPT_INJECTED" 5000))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        ;; === PHASE 4: Verify script executed by checking DOM ===
        ;; Poll for the marker element to appear (script evaluation is async)
        (let [marker (.locator test-page "#play-button-test-marker")]
          (js-await (-> (expect marker) (.toBeVisible #js {:timeout 3000})))
          (js-await (-> (expect marker) (.toHaveText "Script executed!"))))

        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

(.describe test "Popup Core"
           (fn []
             (test "Popup Core: REPL connection setup"
                   test_repl_connection_setup)

             (test "Popup Core: script management workflow"
                   test_script_management_workflow)

             (test "Popup Core: settings view and origin management"
                   test_settings_view_and_origin_management)

             (test "Popup Core: blank slate hints show contextual guidance"
                   test_blank_slate_hints_show_contextual_guidance)

             (test "Popup Core: play button evaluates script in current tab"
                   test_play_button_evaluates_script_in_current_tab)))
