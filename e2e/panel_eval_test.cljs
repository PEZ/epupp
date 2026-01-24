(ns panel-eval-test
  "E2E tests for DevTools panel evaluation functionality."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-panel-page
                                           clear-storage wait-for-panel-ready wait-for-save-status
                                           assert-no-errors!]]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata.
   Uses the official manifest keys: :epupp/script-name, :epupp/auto-run-match, etc."
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

(defn- ^:async test_eval_and_save_workflow []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            eval-btn (.locator panel "button.btn-eval")
            clear-btn (.locator panel "button.btn-clear")
            results (.locator panel ".results-area")
            status (.locator panel ".app-header-status")
            save-btn (.locator panel "button.btn-save")
            ;; Property table selectors
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
            match-field (.locator save-section ".property-row:has(th:text('Auto-run')) .property-value")]

        ;; Clear storage for clean slate
        (js-await (clear-storage panel))

        ;; === EVALUATION WORKFLOW ===

        ;; 1. Panel renders with code input and action buttons
        (js-await (-> (expect textarea) (.toBeVisible)))
        (js-await (-> (expect eval-btn) (.toBeVisible)))
        (js-await (-> (expect clear-btn) (.toBeVisible)))

        ;; 2. Status shows Ready (mock returns hasScittle: true)
        (js-await (-> (expect status) (.toContainText "Ready")))

        ;; 3. Enter and evaluate code
        (js-await (.fill textarea "(+ 1 2 3)"))
        (js-await (.click eval-btn))
        ;; Playwright auto-waits for assertion condition
        (js-await (-> (expect results) (.toContainText "(+ 1 2 3)")))

        ;; 4. Clear results
        (js-await (.click clear-btn))
        ;; Playwright auto-waits for the negative assertion too
        (js-await (-> (expect results) (.not.toContainText "(+ 1 2 3)")))

        ;; === SAVE WORKFLOW (Manifest-Driven) ===

        ;; 5. Without manifest, save section shows guidance message
        (js-await (-> (expect (.locator save-section ".no-manifest-message")) (.toBeVisible)))
        (js-await (-> (expect save-btn) (.toBeDisabled)))

        ;; 6. Add code with manifest - metadata displays should appear
        (let [test-code (code-with-manifest {:name "Test Userscript"
                                             :match "*://test.example.com/*"
                                             :code "(println \"My userscript\")"})]
          (js-await (.fill textarea test-code)))

        ;; 7. Verify manifest-driven displays show correct values
        (js-await (-> (expect name-field) (.toContainText "test_userscript.cljs")))
        (js-await (-> (expect match-field) (.toContainText "*://test.example.com/*")))

        ;; 8. Save button should now be enabled
        (js-await (-> (expect save-btn) (.toBeEnabled)))

        ;; 9. Save and verify (first save = Created since it's a new script)
        (js-await (.click save-btn))
        (js-await (fixtures/wait-for-save-status panel "Created"))

        ;; 10. Name field still shows normalized name after save
        (js-await (-> (expect name-field) (.toContainText "test_userscript.cljs")))

        ;; Assert no errors before closing
        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_selection_evaluation []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            results (.locator panel ".results-area")
            eval-btn (.locator panel "button.btn-eval")]
        ;; Clear storage for clean state
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        ;; Enter code with multiple expressions
        ;; "(+ 1 2)\n(* 3 4)\n(- 10 5)"
        ;; Position 0-6: "(+ 1 2)"  Position 7: "\n"  Position 8-14: "(* 3 4)"
        (let [test-code "(+ 1 2)\n(* 3 4)\n(- 10 5)"]
          (js-await (.fill textarea test-code)))

        ;; === TEST 1: Button always evaluates full script ===
        (js-await (.click eval-btn))
        ;; Result should show the full script as input
        (js-await (-> (expect results) (.toContainText "(+ 1 2)")))
        (js-await (-> (expect results) (.toContainText "(* 3 4)")))
        (js-await (-> (expect results) (.toContainText "(- 10 5)")))

        ;; Clear results for next test
        (js-await (.click (.locator panel "button.btn-clear")))

        ;; === TEST 2: Ctrl+Enter with selection evaluates only selection ===
        ;; Re-enter code
        (js-await (.fill textarea "(+ 1 2)\n(* 3 4)\n(- 10 5)"))

        ;; Select only the middle expression (* 3 4) using JavaScript
        ;; This is more reliable than keyboard navigation
        (js-await (.evaluate textarea "el => { el.focus(); el.setSelectionRange(8, 15); }"))

        ;; Trigger selection tracking by dispatching a select event
        (js-await (.dispatchEvent textarea "select"))

        ;; Press Ctrl+Enter to evaluate selection
        ;; Selection state should be ready by the time keypress is processed
        (js-await (.press (.-keyboard panel) "ControlOrMeta+Enter"))

        ;; Result should show only the selected expression "(* 3 4)" as input
        ;; Wait for result to appear
        (js-await (-> (expect results) (.toContainText "(* 3 4)" #js {:timeout 2000})))

        ;; Should NOT show the other expressions as input (they weren't selected)
        ;; Note: We check that the input label shows only selected code
        (let [input-items (.locator results ".result-input")]
          ;; Should have exactly one input (the selection)
          (js-await (-> (expect input-items) (.toHaveCount 1)))
          ;; That input should be the selected code
          (js-await (-> (expect (.first input-items)) (.toContainText "(* 3 4)"))))

        ;; Assert no errors before closing
        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_ctrl_enter_no_selection []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            results (.locator panel ".results-area")]
        ;; Clear storage for clean state
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        ;; Enter code
        (js-await (.fill textarea "(+ 100 200)"))

        ;; Focus textarea without selecting anything
        (js-await (.focus textarea))
        (js-await (.press (.-keyboard panel) "End"))  ; Just position cursor

        ;; Ctrl+Enter should evaluate full script (no selection)
        (js-await (.press (.-keyboard panel) "ControlOrMeta+Enter"))

        ;; Result should show full script
        (js-await (-> (expect results) (.toContainText "(+ 100 200)" #js {:timeout 2000})))

        ;; Assert no errors before closing
        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_eval_button_shows_icon_and_label []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            eval-btn (.locator panel "button.btn-eval")]
        ;; Clear storage for clean state
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        ;; Button should contain "Eval script" text
        (js-await (-> (expect eval-btn) (.toContainText "Eval script")))

        ;; Button should have a play icon (SVG)
        (let [svg (.locator eval-btn "svg")]
          (js-await (-> (expect svg) (.toBeVisible))))

        ;; Assert no errors before closing
        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_shortcut_hint_says_evals_selection []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            hint (.locator panel ".shortcut-hint")]
        ;; Clear storage for clean state
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        ;; Hint should mention selection
        (js-await (-> (expect hint) (.toContainText "evals selection")))

        ;; Assert no errors before closing
        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_empty_results_shows_keyboard_hint []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            results (.locator panel ".results-area")]
        ;; Clear storage for clean state
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        ;; Empty results area should show helpful hints
        (let [empty-results (.locator results ".empty-results")
              shortcut-hint (.locator results ".empty-results-shortcut")]

          ;; Main message
          (js-await (-> (expect empty-results) (.toContainText "Evaluate ClojureScript")))

          ;; Keyboard shortcut with kbd elements
          (js-await (-> (expect shortcut-hint) (.toBeVisible)))
          (js-await (-> (expect shortcut-hint) (.toContainText "Ctrl")))
          (js-await (-> (expect shortcut-hint) (.toContainText "Enter"))))

        ;; After evaluating code, hints should disappear
        (let [textarea (.locator panel "#code-area")
              eval-btn (.locator panel "button.btn-eval")]
          (js-await (.fill textarea "(+ 1 2)"))
          (js-await (.click eval-btn))
          ;; Wait for result
          (js-await (-> (expect results) (.toContainText "(+ 1 2)")))
          ;; Empty hints should be gone
          (js-await (-> (expect (.locator results ".empty-results")) (.not.toBeVisible))))

        ;; Assert no errors before closing
        (js-await (assert-no-errors! panel)))
      (finally
        (js-await (.close context))))))

(.describe test "Panel Eval"
       (fn []
     (test "Panel Eval: evaluation and save workflow"
       test_eval_and_save_workflow)

     (test "Panel Eval: Ctrl+Enter evaluates selection when text is selected"
       test_selection_evaluation)

     (test "Panel Eval: Ctrl+Enter evaluates full script when no selection"
       test_ctrl_enter_no_selection)

     (test "Panel Eval: Eval button shows play icon and 'Eval script' label"
       test_eval_button_shows_icon_and_label)

     (test "Panel Eval: shortcut hint says 'evals selection'"
       test_shortcut_hint_says_evals_selection)

     (test "Panel Eval: empty results shows keyboard shortcut hint"
       test_empty_results_shows_keyboard_hint)))

