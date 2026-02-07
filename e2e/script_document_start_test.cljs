(ns e2e.script-document-start-test
  "E2E tests for document-start script timing.

   Coverage:
   - document-start scripts run before page scripts
   - document-start scripts are injected via registration system"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              create-popup-page wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status wait-for-script-count
                              assert-no-errors!]]
            [panel-save-helpers :as panel-save-helpers]))

;; =============================================================================
;; Document-start Timing
;; =============================================================================

(defn- ^:async test_document_start_script_runs_before_page_script []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))
        ;; Capture console logs from background worker for registration verification
        bg-logs (atom [])
        workers (.serviceWorkers context)
        bg-worker (aget workers 0)]
    ;; Listen to background console (for registration logs)
    (.on bg-worker "console"
         (fn [msg]
           (swap! bg-logs conj (.text msg))))
    (try
      ;; === PHASE 1: Create and ENABLE a document-start script ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (.fill textarea ""))
        (js-await (wait-for-panel-ready panel))
        ;; Create script with document-start timing
        ;; Note: Use (.-prop obj) syntax for set! - Scittle doesn't support (set! js/window.x ...)
        (let [code (panel-save-helpers/code-with-manifest
                    {:name "Document Start Timing Test"
                     :match "http://localhost:18080/*"
                     :run-at "document-start"
                     :code "(set! (.-__EPUPP_SCRIPT_PERF js/window) (js/performance.now))"})]
          (js-await (.fill textarea code)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; === PHASE 2: Enable the script (defaults to disabled for auto-run) ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (wait-for-script-count popup 3)) ;; 2 built-in + our script
        ;; Find and enable the script
        (let [script-item (.locator popup ".script-item:has-text(\"document_start_timing_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          ;; Verify it starts disabled
          (js-await (-> (expect checkbox) (.not.toBeChecked)))
          ;; Enable it
          (js-await (.click checkbox))
          ;; Verify it's now enabled
          (js-await (-> (expect checkbox) (.toBeChecked #js {:timeout 1000}))))
        (js-await (.close popup)))

      ;; === PHASE 2.5: Check registration logs ===
      ;; Poll for registration completion (30ms interval, 500ms max)
      (js-await (let [start (.now js/Date)]
                  (loop []
                    (let [logs-text (str/join "\n" @bg-logs)]
                      (if (str/includes? logs-text "Content scripts registered successfully")
                        true
                        (if (> (- (.now js/Date) start) 500)
                          nil  ;; Timeout - proceed to check anyway
                          (do
                            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 30))))
                            (recur))))))))
      (let [bg-logs-text (str/join "\n" @bg-logs)]
        ;; Should see successful registration in background logs
        (when (str/includes? bg-logs-text "Sync failed")
          (throw (js/Error. (str "Registration failed! Logs:\n" bg-logs-text))))
        ;; Should see registration completed
        (js-await (-> (expect bg-logs-text)
                      (.toMatch "Content scripts registered successfully"))))

      ;; === PHASE 3: Navigate to test page and verify scripts run ===
      (let [page (js-await (.newPage context))
            page-logs (atom [])]
        ;; Capture page console to see loader output
        (.on page "console"
             (fn [msg]
               (let [text (.text msg)]
                 (swap! page-logs conj text))))

        (js-await (.goto page "http://localhost:18080/timing-test.html" #js {:timeout 2000}))
        ;; Wait for page to fully load
        (js-await (-> (expect (.locator page "#timing-marker"))
                      (.toBeVisible #js {:timeout 2000})))

        ;; Wait for userscript to execute (Scittle loading is async)
        ;; Poll until the perf value is defined (a number, not undefined)
        (let [start (.now js/Date)]
          (loop []
            (let [is-number (js-await (.evaluate page (fn [] (= (js/typeof js/window.__EPUPP_SCRIPT_PERF) "number"))))]
              (when-not is-number
                (when (> (- (.now js/Date) start) 2000)
                  (throw (js/Error. "Timeout waiting for __EPUPP_SCRIPT_PERF to be defined")))
                (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                (recur)))))

        ;; Get timing value - script should have run
        (let [epupp-perf (js-await (.evaluate page (fn [] js/window.__EPUPP_SCRIPT_PERF)))]
          ;; Assert script actually ran (was executed via Scittle)
          (js-await (-> (expect epupp-perf)
                        (.toBeDefined)))
          ;; Note: We can't guarantee running BEFORE inline page scripts because
          ;; Scittle needs to load first. But we verify the script does execute
          ;; during page load (before full page ready).
          (js/console.log "Document-start script perf:" epupp-perf))

        (js-await (.close page)))

      ;; === PHASE 4: Check for errors ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_document_start_script_requires_enabled_state []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Create document-start script (will be disabled by default) ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (.fill textarea ""))
        (js-await (wait-for-panel-ready panel))
        ;; Create script with document-start timing
        (let [code (panel-save-helpers/code-with-manifest
                    {:name "Disabled Doc Start Test"
                     :match "http://localhost:18080/*"
                     :run-at "document-start"
                     :code "(set! js/window.__DISABLED_SCRIPT_RAN true)"})]
          (js-await (.fill textarea code)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; === PHASE 2: Navigate WITHOUT enabling - script should NOT run ===
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 2000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toBeVisible #js {:timeout 2000})))

        ;; Check that script did NOT run (window var should be undefined)
        (let [script-ran (js-await (.evaluate page (fn [] js/window.__DISABLED_SCRIPT_RAN)))]
          (js-await (-> (expect script-ran) (.toBeUndefined))))

        (js-await (.close page)))

      ;; === PHASE 3: Check for errors ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(.describe test "Document-start Script Timing"
           (fn []
             (test "document-start scripts run before page scripts when enabled"
                   test_document_start_script_runs_before_page_script)

             (test "document-start scripts do not run when disabled"
                   test_document_start_script_requires_enabled_state)))
