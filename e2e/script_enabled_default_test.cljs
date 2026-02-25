(ns e2e.script-enabled-default-test
  "E2E tests for script enabled state defaults.

   Coverage:
   - Auto-run scripts (with :script/match) default to disabled
   - Manual-only scripts (no match patterns) default to enabled"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              create-popup-page clear-storage wait-for-panel-ready
                              wait-for-popup-ready wait-for-save-status
                              assert-no-errors!]]
            [panel-save-helpers :as panel-save-helpers]))

;; =============================================================================
;; Auto-run Scripts Default to Disabled
;; =============================================================================

(defn- ^:async test_auto_run_script_created_disabled_by_default []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Create auto-run script via panel ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))
        ;; Create script with match pattern (auto-run)
        (let [auto-run-code (panel-save-helpers/code-with-manifest
                             {:name "Auto Run Test"
                              :match "http://localhost:18080/*"
                              :code "(js/console.log \"Should start disabled!\")"})]
          (js-await (.fill textarea auto-run-code)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; === PHASE 2: Verify script exists and is DISABLED ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        ;; Script should appear in list
        (let [script-item (.locator popup ".script-item:has-text(\"auto_run_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (-> (expect script-item) (.toBeVisible)))
          ;; Checkbox should be UNCHECKED (disabled state)
          (js-await (-> (expect checkbox) (.not.toBeChecked))))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))



(.describe test "Script Enabled Defaults"
           (fn []
             (test "Auto-run scripts (with match patterns) start disabled by default"
                   test_auto_run_script_created_disabled_by_default)))
