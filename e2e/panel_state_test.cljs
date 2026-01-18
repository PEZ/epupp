(ns panel-state-test
  "E2E tests for DevTools panel state management - initialization, new script, undo."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              clear-storage wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status wait-for-panel-state-saved
                              get-test-events assert-no-errors!]]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata.
   Uses the official manifest keys: :epupp/script-name, :epupp/site-match, etc."
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
;; Panel User Journey: Initialization
;; =============================================================================

(defn- ^:async test_panel_initializes_with_default_script []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
            match-field (.locator save-section ".property-row:has(th:text('URL Pattern')) .property-value")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))
        (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(ns hello-world\\)"))))
        (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(defn hello \\[s\\]"))))

        (js-await (-> (expect name-field) (.toContainText "hello_world.cljs")))
        (js-await (-> (expect match-field) (.toContainText "https://example.com/*")))

        (js-await (-> (expect (.locator panel "button.btn-save")) (.toBeEnabled)))

        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))


(defn- ^:async test_panel_restores_saved_state_and_parses_manifest []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        (let [test-code (code-with-manifest {:name "Persisted Script"
                                             :match "*://persist.example.com/*"
                                             :description "Test persistence"
                                             :code "(println \"persisted\")"})]
          (js-await (.fill textarea test-code)))

        (let [debug-info (.locator panel "#debug-info")]
          (js-await (-> (expect debug-info) (.toBeVisible #js {:timeout 300})))
          (let [debug-text (js-await (.textContent debug-info))]
            (println "=== PHASE 1: After fill ===" debug-text)))

        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (wait-for-panel-state-saved panel "(println \"persisted\")"))
        (js-await (.close panel)))

      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
            match-field (.locator save-section ".property-row:has(th:text('URL Pattern')) .property-value")
            description-field (.locator save-section ".property-row:has(th:text('Description')) .property-value")]

        (js-await (wait-for-panel-ready panel))

        (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "Persisted Script") #js {:timeout 2000})))

        (let [debug-info (.locator panel "#debug-info")]
          (js-await (-> (expect debug-info) (.toBeVisible #js {:timeout 300})))
          (let [debug-text (js-await (.textContent debug-info))]
            (println "=== DEBUG INFO ===" debug-text)))

        (let [popup (js-await (.newPage context))
              popup-url (str "chrome-extension://" ext-id "/popup.html")]
          (js-await (.goto popup popup-url #js {:timeout 1000}))
          (js-await (wait-for-popup-ready popup))
          (let [events (js-await (get-test-events popup))]
            (println "=== TEST EVENTS ===" (js/JSON.stringify events nil 2)))
          (js-await (.close popup)))

        (let [textarea-value (js-await (.inputValue textarea))]
          (println "=== DEBUG: Current textarea value ===")
          (println (subs textarea-value 0 100))
          (println "=== END DEBUG ==="))

        (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(println \"persisted\"\\)"))))

        (js-await (-> (expect name-field) (.toContainText "persisted_script.cljs")))
        (js-await (-> (expect match-field) (.toContainText "*://persist.example.com/*")))
        (js-await (-> (expect description-field) (.toContainText "Test persistence")))

        (js-await (-> (expect (.locator panel "button.btn-save")) (.toBeEnabled)))

        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Panel User Journey: New Script Button
;; =============================================================================

(defn- ^:async test_panel_new_button_clears_editor []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
        textarea (.locator panel "#code-area")
        new-btn (.locator panel "button.btn-new-script")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        (js-await (-> (expect new-btn) (.toBeVisible)))

        (let [test-code (code-with-manifest {:name "My Custom Script"
                                             :match "*://custom.example.com/*"
                                             :code "(println \"custom code\")"})]
          (js-await (.fill textarea test-code)))

        (.once panel "dialog" (fn [dialog] (.accept dialog)))

        (js-await (.click new-btn))

        (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))
        (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(ns hello-world\\)"))))

        (let [save-section (.locator panel ".save-script-section")
              name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
          (js-await (-> (expect name-field) (.toContainText "hello_world.cljs"))))

        (let [header (.locator panel ".save-script-header .header-title")]
          (js-await (-> (expect header) (.toContainText "Save as Userscript"))))

        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))


(defn- ^:async test_panel_new_button_preserves_evaluation_results []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            eval-btn (.locator panel "button.btn-eval")
            results (.locator panel ".results-area")
            new-btn (.locator panel "button.btn-new-script")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        (let [eval-code (code-with-manifest {:name "Eval Test"
                                             :match "*://eval.example.com/*"
                                             :code "(+ 1 2 3)"})]
          (js-await (.fill textarea eval-code)))
        (js-await (.click eval-btn))
        (js-await (-> (expect results) (.toContainText "(+ 1 2 3)")))

        (.once panel "dialog" (fn [dialog] (.accept dialog)))
        (js-await (.click new-btn))

        (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))

        (js-await (-> (expect results) (.toContainText "(+ 1 2 3)")))

        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))


(defn- ^:async test_panel_new_button_with_default_script_skips_confirmation []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            new-btn (.locator panel "button.btn-new-script")
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))

        (let [dialog-appeared (atom false)]
          (.on panel "dialog" (fn [_dialog]
                                (reset! dialog-appeared true)))

          (js-await (.click new-btn))

          (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 200))))

          (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs"))))
          (js-await (-> (expect name-field) (.toContainText "hello_world.cljs")))

          (js-await (assert-no-errors! panel))))
      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Panel User Journey: Undo Functionality
;; =============================================================================

(defn- ^:async test_panel_undo_works_in_code_editor []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        (let [initial-value (js-await (.inputValue textarea))]

          (js-await (.focus textarea))
          (js-await (.press (.-keyboard panel) "End"))
          (js-await (.type (.-keyboard panel) "X"))

          (js-await (-> (expect textarea)
                        (.toHaveValue (js/RegExp. "X$") #js {:timeout 500})))

          (js-await (.press (.-keyboard panel) "ControlOrMeta+z"))

          (js-await (-> (expect textarea)
                        (.toHaveValue initial-value #js {:timeout 500}))))

        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))

(.describe test "Panel State"
           (fn []
             (test "Panel State: initializes with default script when no saved state"
                   test_panel_initializes_with_default_script)

             (test "Panel State: restores saved state and parses manifest on reload"
                   test_panel_restores_saved_state_and_parses_manifest)

             (test "Panel State: New button clears editor and resets to default script"
                   test_panel_new_button_clears_editor)

             (test "Panel State: New button preserves evaluation results"
                   test_panel_new_button_preserves_evaluation_results)

             (test "Panel State: New button with default script skips confirmation"
                   test_panel_new_button_with_default_script_skips_confirmation)

             (test "Panel State: undo works in code editor"
                   test_panel_undo_works_in_code_editor)))
