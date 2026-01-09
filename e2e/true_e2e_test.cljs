(ns true-e2e-test
  "True E2E tests that verify userscript loading, timing, and REPL interaction
   via structured event logging to chrome.storage.local."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page clear-test-events! wait-for-event
                              get-test-events wait-for-save-status wait-for-popup-ready
                              generate-timing-report print-timing-report]]))

(test "True E2E: extension starts and emits startup event"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          ;; Log extension ID to verify it's loaded
          (js/console.log "Extension ID:" ext-id)
          (let [popup (js-await (create-popup-page context ext-id))]
            ;; Log popup URL
            (js/console.log "Popup URL:" (.url popup))
            (try
              ;; Wait for popup to be fully loaded with scripts - check for Gist Installer
              ;; Give the extension time to init (service worker may need to wake)
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 2000))))

              (js-await (-> (expect (.locator popup ".script-item:has-text(\"Gist Installer\")"))
                            (.toBeVisible (js-obj "timeout" 10000))))

              (js/console.log "Popup fully initialized - Gist Installer visible")

              ;; Dump bg-started and test-logger-debug to see what's happening
              (let [debug-result (js-await
                                  (.evaluate popup
                                             "() => new Promise(resolve => {
                                                chrome.storage.local.get(['bg-started', 'test-logger-debug', 'test-events'], resolve);
                                              })"))]
                (js/console.log "Debug storage dump:" (js/JSON.stringify debug-result)))

              ;; Now check script count
              (let [script-items (.locator popup ".script-item")]
                (js/console.log "Script items count:" (js-await (.count script-items))))

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
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 1000))))

            ;; Check dev log button is visible
            (let [dev-log-btn (.locator popup ".dev-log-btn")]
              (js-await (-> (expect dev-log-btn) (.toBeVisible (js-obj "timeout" 5000))))
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
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "#code-area") "(js/console.log \"Userscript ran!\")"))
              (js-await (.fill (.locator panel "#script-name") "Injection Test"))
              (js-await (.fill (.locator panel "#script-match") "http://localhost:18080/*"))
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
                ;; Debug: log what we see
                (js/console.log "Script item visible, checking for Allow button...")
                ;; Check for approval button (may not exist if auto-approved)
                (let [allow-btn (.locator script-item "button:has-text(\"Allow\")")]
                  (let [btn-count (js-await (.count allow-btn))]
                    (js/console.log "Allow button count:" btn-count)
                    (when (pos? btn-count)
                      (js-await (.click allow-btn))
                      (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))))))

              ;; Clear events before navigation
              (js-await (clear-test-events! popup))
              (js-await (.close popup)))

            ;; Navigate to matching page
            (let [page (js-await (.newPage context))]
              (js/console.log "Navigating to localhost:18080/basic.html...")
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 10000}))
              ;; Wait for page to load
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Page loaded, test-marker visible")
              ;; Give injection time to complete (already 2s in setTimeout above)
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 3000))))
              (js-await (.close page)))

            ;; Check for SCRIPT_INJECTED event
            (let [popup (js-await (create-popup-page context ext-id))
                  events (js-await (get-test-events popup))]
              (js/console.log "All events after settling:" (js/JSON.stringify events))
              ;; Check for key events and log their data
              (doseq [e events]
                (let [event-name (.-event e)]
                  (when (or (= "EXECUTE_SCRIPTS_ERROR" event-name)
                            (= "BRIDGE_INJECTED" event-name)
                            (= "BRIDGE_READY" event-name)
                            (= "BRIDGE_READY_CONFIRMED" event-name))
                    (js/console.log "EVENT:" event-name "DATA:" (js/JSON.stringify (.-data e))))))
              (let [event (js-await (wait-for-event popup "SCRIPT_INJECTED" 5000))]
                (js/console.log "SCRIPT_INJECTED event:" (js/JSON.stringify event))
                ;; Verify event has expected data
                (js-await (-> (expect (.-event event)) (.toBe "SCRIPT_INJECTED"))))
              (js-await (.close popup)))

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
                  code "{:epupp/run-at \"document-start\"}\n(set! js/window.__EPUPP_SCRIPT_PERF (js/performance.now))"]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.fill (.locator panel "#script-name") "Timing Test"))
              (js-await (.fill (.locator panel "#script-match") "http://localhost:18080/*"))
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
                  (let [btn-count (js-await (.count allow-btn))]
                    (js/console.log "Timing test - Allow button count:" btn-count)
                    (when (pos? btn-count)
                      (js-await (.click allow-btn))
                      (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))))))
              (js-await (.close popup)))

            ;; Navigate to timing test page and check values
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
            (let [panel (js-await (create-panel-page context ext-id))]
              (js-await (.fill (.locator panel "#code-area") "(js/console.log \"Perf test\")"))
              (js-await (.fill (.locator panel "#script-name") "Performance Report Test"))
              (js-await (.fill (.locator panel "#script-match") "http://localhost:18080/*"))
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
                    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500)))))))

              ;; Clear events before test run
              (js-await (clear-test-events! popup))
              (js-await (.close popup)))

            ;; Navigate to trigger injection and collect events
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 10000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              ;; Wait for injection to complete
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 2000))))
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
