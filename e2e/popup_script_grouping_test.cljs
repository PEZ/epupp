(ns e2e.popup-script-grouping-test
  "E2E tests for popup 3-way script grouping (manual / matching / other)."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [builtin-script-count launch-browser get-extension-id
                              create-popup-page create-panel-page clear-storage
                              wait-for-popup-ready wait-for-save-status
                              wait-for-script-count assert-no-errors!]]))

(defn- code-with-manifest [{:keys [name match description code]
                            :or {code "(println \"Test\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\"")))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

(defn- ^:async test_scripts_grouped_in_correct_sections []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Clean state ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-storage popup))
        (js-await (.close popup)))

      ;; === PHASE 2: Create scripts via panel ===
      ;; Manual script (no match pattern)
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "manual_test"
                                      :code "(println \"manual\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "manual_test.cljs"))
        (js-await (.close panel)))

      ;; Auto-run script 1 (won't match popup URL)
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "autorun_one"
                                      :match "https://example.com/*"
                                      :code "(println \"autorun one\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "autorun_one.cljs"))
        (js-await (.close panel)))

      ;; Auto-run script 2 (also won't match popup URL)
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "autorun_two"
                                      :match "https://nomatch.example.org/*"
                                      :code "(println \"autorun two\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "autorun_two.cljs"))
        (js-await (.close panel)))

      ;; === PHASE 3: Verify grouping in popup ===
      (let [popup (js-await (create-popup-page context ext-id))
            _ (js-await (wait-for-popup-ready popup))
            manual-section (.locator popup "[data-e2e-section=\"manual-scripts\"]")
            matching-section (.locator popup "[data-e2e-section=\"matching-scripts\"]")
            other-section (.locator popup "[data-e2e-section=\"other-scripts\"]")]

        ;; Wait for all scripts to load (builtin + 3 user scripts)
        (js-await (wait-for-script-count popup (+ builtin-script-count 3)))

        ;; Manual script appears in manual-scripts section
        (js-await (-> (expect (.locator manual-section ".script-item[data-script-name=\"manual_test.cljs\"]"))
                      (.toBeVisible #js {:timeout 500})))

        ;; Auto-run scripts appear in other-scripts section (popup has no real page URL)
        (js-await (-> (expect (.locator other-section ".script-item[data-script-name=\"autorun_one.cljs\"]"))
                      (.toBeVisible #js {:timeout 500})))
        (js-await (-> (expect (.locator other-section ".script-item[data-script-name=\"autorun_two.cljs\"]"))
                      (.toBeVisible #js {:timeout 500})))

        ;; Manual script is NOT in other or matching sections
        (js-await (-> (expect (.locator other-section ".script-item[data-script-name=\"manual_test.cljs\"]"))
                      (.not.toBeVisible)))
        (js-await (-> (expect (.locator matching-section ".script-item[data-script-name=\"manual_test.cljs\"]"))
                      (.not.toBeVisible)))

        ;; Auto-run scripts are NOT in manual section
        (js-await (-> (expect (.locator manual-section ".script-item[data-script-name=\"autorun_one.cljs\"]"))
                      (.not.toBeVisible)))

        ;; Matching-scripts section shows blank slate (popup has no real page URL)
        (js-await (-> (expect (.locator matching-section ".no-scripts"))
                      (.toContainText "No scripts auto-run for this page" #js {:timeout 500})))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(.describe test "Popup Script Grouping"
           (fn []
             (test "Popup Script Grouping: scripts appear in correct sections"
                   test_scripts_grouped_in_correct_sections)))
