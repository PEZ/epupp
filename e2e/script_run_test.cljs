(ns e2e.script-run-test
  "E2E tests for running scripts from popup."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              find-tab-id create-panel-page
                              wait-for-save-status assert-no-errors!
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

          ;; Wait for script injection event (async pipeline: message -> injection -> event)
          (js-await (wait-for-event popup "SCRIPT_INJECTED" 3000))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        ;; === PHASE 4: Verify script executed by checking DOM ===
        ;; Poll for the marker element (script evaluation is async, allow 2s for full pipeline)
        (let [marker (.locator test-page "#play-button-test-marker")]
          (js-await (-> (expect marker) (.toBeVisible #js {:timeout 2000})))
          (js-await (-> (expect marker) (.toHaveText "Script executed!"))))

        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

(test "Script Run: play button evaluates script in current tab"
      test_play_button_evaluates_script_in_current_tab)
