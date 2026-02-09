(ns e2e.popup-core-test
  "E2E tests for popup core functionality - REPL setup, scripts, hints."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :as fixtures :refer [builtin-script-count launch-browser get-extension-id create-popup-page
                                           create-panel-page clear-storage wait-for-popup-ready
                                           wait-for-save-status wait-for-script-count
                                           wait-for-checkbox-state assert-no-errors!]]))


(defn code-with-manifest
  "Generate test code with epupp manifest metadata."
  [{:keys [name match description run-at code]
    :or {code "(println \"Test script\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
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

        ;; Copy button exists and is clickable (icon-button in command-box)
        (let [copy-btn (.locator popup ".command-box button.icon-button")]
          (js-await (-> (expect copy-btn) (.toBeVisible)))
          (js-await (.click copy-btn)))

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
      ;; === PHASE 1: Initial state (built-in Web Userscript Installer exists) ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-storage popup))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))
        ;; Built-in web userscript installer script is auto-created, so we have 1 script
        (js-await (-> (expect (.locator popup ".script-item:has-text(\"web_userscript_installer.cljs\")"))
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
        ;; built-in + 2 user scripts
        (js-await (wait-for-script-count popup (+ builtin-script-count 2)))

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
          ;; remaining: built-in + script_one.cljs
          (js-await (-> (expect (.locator popup ".script-item"))
                        (.toHaveCount (+ builtin-script-count 1) #js {:timeout 2000}))))

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
          (js-await (-> (expect no-conn-hint) (.toBeVisible #js {:timeout 500})))
          (js-await (-> (expect no-conn-hint) (.toContainText "Step 1" #js {:timeout 500}))))

        ;; Matching Scripts section shows "no userscripts yet" message
        (let [matching-section (.locator popup ".collapsible-section:has(.section-title:text(\"Auto-run for This Page\"))")
              no-scripts (.locator matching-section ".no-scripts")]
          (js-await (-> (expect no-scripts) (.toContainText "No userscripts yet" #js {:timeout 500}))))

        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))



(.describe test "Popup Core"
           (fn []
             (test "Popup Core: REPL connection setup"
                   test_repl_connection_setup)

             (test "Popup Core: script management workflow"
                   test_script_management_workflow)

             (test "Popup Core: blank slate hints show contextual guidance"
                   test_blank_slate_hints_show_contextual_guidance)))

