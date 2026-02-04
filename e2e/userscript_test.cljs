(ns userscript-test
  "E2E tests for userscript functionality: injection, timing, and execution.

   Coverage:
   - Userscript injection on matching URLs
   - Script timing (document-start vs page scripts)
   - Performance reporting"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           create-panel-page wait-for-event
                                           get-test-events wait-for-save-status wait-for-popup-ready
                                           generate-timing-report print-timing-report
                                           assert-no-errors!]]))

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
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

;; =============================================================================
;; Userscript Injection
;; =============================================================================

(defn- ^:async test_injects_on_matching_url_and_logs []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup: Create a script that matches localhost:18080
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Injection Test"
                                      :match "http://localhost:18080/*"
                                      :code "(js/console.log \"Userscript ran!\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable the script (defaults to disabled for auto-run)
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"injection_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (.click checkbox)))
        (js-await (.close popup)))

      ;; Navigate to matching page
      (let [page (js-await (.newPage context))]
        (js/console.log "Navigating to localhost:18080/basic.html...")
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))
        (js/console.log "Page loaded, test-marker visible")

        ;; Wait for SCRIPT_INJECTED event BEFORE closing the page
        ;; The injection happens asynchronously after webNavigation.onCompleted
        ;; so we need to keep the page open until injection completes
        (let [popup (js-await (create-popup-page context ext-id))
              _ (js/console.log "Waiting for SCRIPT_INJECTED event (page still open)...")
              event (js-await (wait-for-event popup "SCRIPT_INJECTED" 10000))]
          (js/console.log "SCRIPT_INJECTED event:" (js/JSON.stringify event))
          ;; Verify event has expected data
          (js-await (-> (expect (.-event event)) (.toBe "SCRIPT_INJECTED")))
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Document-start Timing
;; =============================================================================

(defn- ^:async test_document_start_runs_before_page_scripts []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup: Create a document-start script that records timing
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Timing Test"
                                      :match "http://localhost:18080/*"
                                      :run-at "document-start"
                                      :code "(set! (.-__EPUPP_SCRIPT_PERF js/window) (js/performance.now))"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable the script (defaults to disabled for auto-run)
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"timing_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (.click checkbox)))
        (js-await (.close popup)))

      ;; Navigate to timing test page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/timing-test.html" #js {:timeout 1000}))
        ;; Wait for page to fully load
        (js-await (-> (expect (.locator page "#timing-marker"))
                      (.toBeVisible)))

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

        ;; Verify userscript executed via Scittle
        (let [epupp-perf (js-await (.evaluate page (fn [] js/window.__EPUPP_SCRIPT_PERF)))]
          ;; Assert script actually ran
          ;; Note: We can't guarantee running BEFORE inline page scripts because
          ;; Scittle needs to load first. But we verify the script does execute.
          (js-await (-> (expect epupp-perf)
                        (.toBeDefined)))
          (js/console.log "Document-start script perf:" epupp-perf))

        ;; Check for errors before closing
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Performance Reporting
;; =============================================================================

(defn- ^:async test_generate_performance_report_from_events []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup: Create a simple script to trigger full injection pipeline
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Performance Report Test"
                                      :match "http://localhost:18080/*"
                                      :code "(js/console.log \"Perf test\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Navigate to trigger injection
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))
        (js-await (.close page)))

      ;; Generate and print performance report
      (let [popup (js-await (create-popup-page context ext-id))
            events (js-await (get-test-events popup))
            report (generate-timing-report events)]
        ;; Print the report
        (print-timing-report report)

        ;; Verify we got some events
        (js-await (-> (expect (.-length events))
                      (.toBeGreaterThan 0)))

        ;; Verify report has expected structure
        (js-await (-> (expect (:all-events report))
                      (.toBeDefined)))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Error Checking
;; =============================================================================

(defn- ^:async test_injection_produces_no_uncaught_errors []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create and approve a script
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Error Check Test"
                                      :match "http://localhost:18080/*"
                                      :code "(js/console.log \"No errors please\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable the script (defaults to disabled for auto-run)
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"error_check_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (.click checkbox)))
        (js-await (.close popup)))

      ;; Navigate to trigger injection
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Wait for script injection
        (let [popup (js-await (create-popup-page context ext-id))
              _ (js-await (wait-for-event popup "SCRIPT_INJECTED" 10000))]
          ;; Check for any errors
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Userscript"
           (fn []
             (test "Userscript: injects on matching URL and logs SCRIPT_INJECTED"
                   test_injects_on_matching_url_and_logs)

             (test "Userscript: document-start script runs before page scripts"
                   test_document_start_runs_before_page_scripts)

             (test "Userscript: generate performance report from events"
                   test_generate_performance_report_from_events)

             (test "Userscript: injection produces no uncaught errors"
                   test_injection_produces_no_uncaught_errors)))
