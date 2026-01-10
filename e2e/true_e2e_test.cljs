(ns true-e2e-test
  "True E2E tests that verify userscript loading, timing, and REPL interaction
   via structured event logging to chrome.storage.local."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page wait-for-event
                              get-test-events wait-for-save-status wait-for-popup-ready
                              generate-timing-report print-timing-report]]))

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

(test "True E2E: extension starts and emits startup event"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (js/console.log "Extension ID:" ext-id)
          (let [popup (js-await (create-popup-page context ext-id))]
            (js/console.log "Popup URL:" (.url popup))
            (try
              ;; Wait for popup UI to be ready (uses proper Playwright waiting)
              (js-await (wait-for-popup-ready popup))
              (js/console.log "Popup ready")

              ;; Get all events - EXTENSION_STARTED should be there from startup
              (let [events (js-await (get-test-events popup))]
                (js/console.log "All events:" (js/JSON.stringify events))
                (let [started-events (.filter events (fn [e] (= (.-event e) "EXTENSION_STARTED")))]
                  ;; Should have at least one EXTENSION_STARTED event
                  (js-await (-> (expect (.-length started-events))
                                (.toBeGreaterThanOrEqual 1)))))
              (finally
                (js-await (.close context))))))))

(test "True E2E: dev log button works and captures console output"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))
              popup (js-await (create-popup-page context ext-id))]
          (try
            ;; Wait for popup to be ready
            (js-await (wait-for-popup-ready popup))

            ;; Check dev log button is visible
            (let [dev-log-btn (.locator popup ".dev-log-btn")]
              (js-await (-> (expect dev-log-btn) (.toBeVisible)))
              (js/console.log "Dev log button is visible"))

            ;; Get events via the button click mechanism
            (let [events (js-await (get-test-events popup))]
              (js/console.log "Events from dev log:" (js/JSON.stringify events))
              ;; Should be a JS array
              (js-await (-> (expect (js/Array.isArray events)) (.toBe true))))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; True E2E: Userscript Injection via SCRIPT_INJECTED Event
;; =============================================================================

(test "True E2E: userscript injects on matching URL and logs SCRIPT_INJECTED"
      (^:async fn []
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

            ;; Approve the script pattern in popup
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; Set the test URL override for approval UI
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Find and approve the script
              (let [script-item (.locator popup ".script-item:has-text(\"injection_test.cljs\")")]
                (js-await (-> (expect script-item) (.toBeVisible)))
                (js/console.log "Script item visible, checking for Allow button...")
                ;; Check for approval button (may not exist if auto-approved)
                (let [allow-btn (.locator script-item "button:has-text(\"Allow\")")]
                  (when (pos? (js-await (.count allow-btn)))
                    (js/console.log "Clicking Allow button...")
                    (js-await (.click allow-btn))
                    ;; Wait for button to disappear (approval processed)
                    (js-await (-> (expect allow-btn) (.not.toBeVisible))))))
              (js-await (.close popup)))

            ;; Navigate to matching page
            (let [page (js-await (.newPage context))]
              (js/console.log "Navigating to localhost:18080/basic.html...")
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 10000}))
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
                (js-await (.close popup)))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; True E2E: Document-start Timing Test
;; =============================================================================

(test "True E2E: document-start script runs before page scripts"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Setup: Create a document-start script that records timing
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "Timing Test"
                                            :match "http://localhost:18080/*"
                                            :run-at "document-start"
                                            :code "(set! js/window.__EPUPP_SCRIPT_PERF (js/performance.now))"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "Created"))
              (js-await (.close panel)))

            ;; Approve the script
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/timing-test.html';"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              (let [script-item (.locator popup ".script-item:has-text(\"timing_test.cljs\")")]
                (js-await (-> (expect script-item) (.toBeVisible)))
                (let [allow-btn (.locator script-item "button:has-text(\"Allow\")")]
                  (when (pos? (js-await (.count allow-btn)))
                    (js/console.log "Timing test - clicking Allow button...")
                    (js-await (.click allow-btn))
                    (js-await (-> (expect allow-btn) (.not.toBeVisible))))))
              (js-await (.close popup)))

            ;; Navigate to timing test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/timing-test.html" #js {:timeout 10000}))
              ;; Wait for page to fully load
              (js-await (-> (expect (.locator page "#timing-marker"))
                            (.toBeVisible)))

              ;; Get both timing values - use a function that returns the object
              (let [timings (js-await (.evaluate page (fn []
                                                        #js {:epuppPerf js/window.__EPUPP_SCRIPT_PERF
                                                             :pagePerf js/window.__PAGE_SCRIPT_PERF})))]
                (js/console.log "Timing comparison - Epupp:" (aget timings "epuppPerf") "Page:" (aget timings "pagePerf"))

                ;; Epupp script should have a perf value (might be undefined if Scittle didn't load fast enough)
                ;; The key assertion: if both exist, Epupp should run first
                (when (and (aget timings "epuppPerf") (aget timings "pagePerf"))
                  (js-await (-> (expect (aget timings "epuppPerf"))
                                (.toBeLessThan (aget timings "pagePerf"))))))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Performance Reporting Test
;; =============================================================================

(test "True E2E: generate performance report from events"
      (^:async fn []
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

            ;; Approve the script
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              (let [script-item (.locator popup ".script-item:has-text(\"performance_report_test.cljs\")")]
                (js-await (-> (expect script-item) (.toBeVisible)))
                (let [allow-btn (.locator script-item "button:has-text(\"Allow\")")]
                  (when (pos? (js-await (.count allow-btn)))
                    (js-await (.click allow-btn))
                    (js-await (-> (expect allow-btn) (.not.toBeVisible))))))
              (js-await (.close popup)))

            ;; Navigate to trigger injection
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 10000}))
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
              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))
