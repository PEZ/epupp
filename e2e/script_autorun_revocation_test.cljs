(ns e2e.script-autorun-revocation-test
  "E2E tests for Phase 6: Auto-Run Revocation
   Verifies that removing :epupp/auto-run-match from a script's manifest
   converts it to a manual-only script with no auto-run UI."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page clear-storage
                              wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status
                              assert-no-errors! get-script-item]]
            [fs-write-helpers :refer [setup-browser! eval-in-browser sleep]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- code-with-manifest
  "Generate test code with epupp manifest metadata."
  [{:keys [name match description run-at code]
    :or {code "(println \"Test script\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\"")))
        meta-block (when (seq meta-parts)
                     (str "^{" (str/join "\n  " meta-parts) "}\n"))]
    (str meta-block code)))

(defn- ^:async assert-script-is-manual-only
  "Assert that a script appears in popup but has no auto-run UI.

   Manual-only signals:
   - Script item exists
   - No pattern checkbox in script-row-pattern
   - Script-match shows 'No auto-run (manual only)'"
  [popup script-name]
  (let [script-item (get-script-item popup script-name)
        pattern-row (.locator script-item ".script-row-pattern")
        checkbox (.locator pattern-row ".pattern-checkbox")
        match-span (.locator pattern-row ".script-match")]
    ;; Script exists
    (js-await (-> (expect script-item) (.toBeVisible #js {:timeout 500})))
    ;; No checkbox for auto-run toggle
    (js-await (-> (expect checkbox) (.toHaveCount 0)))
    ;; Shows manual-only text
    (js-await (-> (expect match-span) (.toHaveText "No auto-run (manual only)")))))

(defn- ^:async assert-script-has-autorun
  "Assert that a script has auto-run UI (checkbox visible)."
  [popup script-name]
  (let [script-item (get-script-item popup script-name)
        pattern-row (.locator script-item ".script-row-pattern")
        checkbox (.locator pattern-row ".pattern-checkbox")]
    (js-await (-> (expect script-item) (.toBeVisible #js {:timeout 500})))
    (js-await (-> (expect checkbox) (.toBeVisible #js {:timeout 500})))))

;; =============================================================================
;; Test: Panel save removes auto-run
;; =============================================================================

(defn- ^:async test_panel_save_removes_autorun_converts_to_manual []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Create script WITH auto-run-match via panel ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))
        (let [code-with-match (code-with-manifest {:name "Revoke Test Script"
                                                   :match "*://example.com/*"
                                                   :code "(ns revoke-test)\n(println \"v1 with match\")"})]
          (js-await (.fill textarea code-with-match)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "revoke_test_script.cljs"))
        (js-await (.close panel)))

      ;; === PHASE 2: Verify popup shows script with auto-run UI ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-script-has-autorun popup "revoke_test_script.cljs"))
        (js-await (.close popup)))

      ;; === PHASE 3: Edit script to remove :epupp/auto-run-match ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        ;; Click inspect to load script into panel
        (let [script-item (get-script-item popup "revoke_test_script.cljs")
              inspect-btn (.locator script-item "button.script-inspect")]
          (js-await (.click inspect-btn)))
        (js-await (.close popup)))

      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
        ;; Wait for script to load
        (js-await (-> (expect name-field) (.toContainText "revoke_test_script.cljs" #js {:timeout 2000})))
        ;; Update code WITHOUT auto-run-match (keep name same to update, not create)
        (let [code-without-match (code-with-manifest {:name "revoke_test_script.cljs"
                                                      :code "(ns revoke-test)\n(println \"v2 no match - manual only\")"})]
          (js-await (.fill textarea code-without-match)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "Saved"))
        (js-await (.close panel)))

      ;; === PHASE 4: Verify popup now shows script as manual-only ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-script-is-manual-only popup "revoke_test_script.cljs"))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: REPL save removes auto-run
;; =============================================================================

(defn- ^:async test_repl_save_removes_autorun_converts_to_manual []
  (let [context (js-await (setup-browser!))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Create script WITH auto-run-match via REPL ===
      (let [code-with-match "^{:epupp/script-name \"repl-revoke-test\"\n  :epupp/auto-run-match \"https://example.com/*\"}\n(ns repl-revoke-test)\n(println \"v1 with match\")"
            setup-result (js-await (eval-in-browser
                                    (str "(def !save1 (atom :pending))\n"
                                         "(-> (epupp.fs/save! " (pr-str code-with-match) " {:fs/force? true})\n"
                                         "    (.then (fn [r] (reset! !save1 r))))\n"
                                         ":setup-done")))]
        (js-await (-> (expect (.-success setup-result)) (.toBe true)))
        ;; Wait for save to complete
        (let [start (.now js/Date)]
          (loop []
            (let [check (js-await (eval-in-browser "(let [r @!save1] (if (map? r) (:fs/success r) :pending))"))]
              (if (and (.-success check) (= (first (.-values check)) "true"))
                true
                (if (> (- (.now js/Date) start) 3000)
                  (throw (js/Error. "Timeout waiting for REPL save"))
                  (do (js-await (sleep 20)) (recur))))))))

      ;; === PHASE 2: Verify popup shows script with auto-run UI ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-script-has-autorun popup "repl_revoke_test.cljs"))
        (js-await (.close popup)))

      ;; === PHASE 3: Update script via REPL WITHOUT auto-run-match ===
      (let [code-without-match "^{:epupp/script-name \"repl_revoke_test.cljs\"}\n(ns repl-revoke-test)\n(println \"v2 no match - manual only\")"
            update-result (js-await (eval-in-browser
                                     (str "(def !save2 (atom :pending))\n"
                                          "(-> (epupp.fs/save! " (pr-str code-without-match) " {:fs/force? true})\n"
                                          "    (.then (fn [r] (reset! !save2 r))))\n"
                                          ":setup-done")))]
        (js-await (-> (expect (.-success update-result)) (.toBe true)))
        ;; Wait for save to complete
        (let [start (.now js/Date)]
          (loop []
            (let [check (js-await (eval-in-browser "(let [r @!save2] (if (map? r) (:fs/success r) :pending))"))]
              (if (and (.-success check) (= (first (.-values check)) "true"))
                true
                (if (> (- (.now js/Date) start) 3000)
                  (throw (js/Error. "Timeout waiting for REPL update save"))
                  (do (js-await (sleep 20)) (recur))))))))

      ;; === PHASE 4: Verify popup now shows script as manual-only ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-script-is-manual-only popup "repl_revoke_test.cljs"))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test Registration
;; =============================================================================

(.describe test "Auto-Run Revocation"
           (fn []
             (test "Auto-Run Revocation: panel save removing match converts to manual-only"
                   test_panel_save_removes_autorun_converts_to_manual)

             (test "Auto-Run Revocation: REPL save removing match converts to manual-only"
                   test_repl_save_removes_autorun_converts_to_manual)))
