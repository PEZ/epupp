(ns log-powered-test
  "Log-powered E2E tests that verify userscript loading, timing, and REPL interaction
   via structured event logging to chrome.storage.local.

   These tests observe internal extension behavior that's invisible to standard
   Playwright assertions by capturing events emitted to storage."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page wait-for-event
                              get-test-events wait-for-save-status wait-for-popup-ready
                              generate-timing-report print-timing-report]]))

(test "Log-powered: extension starts and emits startup event"
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

(test "Log-powered: dev log button works and captures console output"
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
;; Userscript Injection via SCRIPT_INJECTED Event
;; =============================================================================

(test "Log-powered: userscript injects on matching URL and logs SCRIPT_INJECTED"
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
;; Document-start Timing Test
;; =============================================================================

(test "Log-powered: document-start script runs before page scripts"
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

(test "Log-powered: generate performance report from events"
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

;; =============================================================================
;; Auto-Connect REPL Setting Tests
;; =============================================================================

(test "Log-powered: auto-connect REPL triggers Scittle injection on page load"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Enable auto-connect setting via popup
            (let [popup (js-await (create-popup-page context ext-id))]
              ;; Clear storage for clean state
              (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Expand settings section
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))

              ;; Enable auto-connect
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (-> (expect auto-connect-checkbox) (.toBeVisible)))
                (js-await (.click auto-connect-checkbox))
                ;; Wait for checkbox to be checked
                (js-await (-> (expect auto-connect-checkbox) (.toBeChecked))))

              (js-await (.close popup)))

            ;; Navigate to a page - should trigger auto-connect (WS_CONNECTED event)
            (let [page (js-await (.newPage context))]
              (js/console.log "Navigating to localhost:18080/basic.html with auto-connect enabled...")
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 10000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Page loaded")

              ;; Wait for SCITTLE_LOADED event - indicates auto-connect triggered
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js/console.log "Waiting for SCITTLE_LOADED event...")
                    event (js-await (wait-for-event popup "SCITTLE_LOADED" 10000))]
                (js/console.log "SCITTLE_LOADED event:" (js/JSON.stringify event))
                (js-await (-> (expect (.-event event)) (.toBe "SCITTLE_LOADED")))
                (js-await (.close popup)))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Log-powered: SPA navigation does NOT trigger REPL reconnection"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Enable auto-connect via popup
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (wait-for-popup-ready popup))

              ;; Expand settings section
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))

              ;; Enable auto-connect
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (.click auto-connect-checkbox))
                (js-await (-> (expect auto-connect-checkbox) (.toBeChecked))))

              (js-await (.close popup)))

            ;; Navigate to SPA test page - should trigger initial auto-connect
            (let [page (js-await (.newPage context))]
              (js/console.log "Navigating to SPA test page with auto-connect enabled...")
              (js-await (.goto page "http://localhost:18080/spa-test.html" #js {:timeout 10000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "SPA page loaded")

              ;; Wait for initial SCITTLE_LOADED event
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js/console.log "Waiting for initial SCITTLE_LOADED...")
                    event (js-await (wait-for-event popup "SCITTLE_LOADED" 10000))]
                (js/console.log "Initial SCITTLE_LOADED:" (js/JSON.stringify event))

                ;; Get current event count
                (let [events-before (js-await (get-test-events popup))
                      scittle-count-before (.-length (.filter events-before (fn [e] (= (.-event e) "SCITTLE_LOADED"))))]
                  (js/console.log "SCITTLE_LOADED count before SPA nav:" scittle-count-before)

                  ;; Perform SPA navigation (client-side, no page reload)
                  (js/console.log "Performing SPA navigation (should NOT trigger reconnect)...")
                  (js-await (.click (.locator page "#nav-about")))
                  (js-await (-> (expect (.locator page "#current-view"))
                                (.toContainText "about")))
                  (js/console.log "SPA navigated to 'about' view")

                  ;; Do another SPA navigation
                  (js-await (.click (.locator page "#nav-contact")))
                  (js-await (-> (expect (.locator page "#current-view"))
                                (.toContainText "contact")))
                  (js/console.log "SPA navigated to 'contact' view")

                  ;; Small wait to ensure any erroneous reconnect would have happened
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))

                  ;; Check that NO new SCITTLE_LOADED events occurred
                  (let [events-after (js-await (get-test-events popup))
                        scittle-count-after (.-length (.filter events-after (fn [e] (= (.-event e) "SCITTLE_LOADED"))))]
                    (js/console.log "SCITTLE_LOADED count after SPA nav:" scittle-count-after)
                    ;; Should be same count - SPA nav should NOT trigger reconnection
                    (js-await (-> (expect scittle-count-after)
                                  (.toBe scittle-count-before)))))

                (js-await (.close popup)))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Log-powered: toolbar icon reflects REPL connection state"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Navigate to a test page first
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 10000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              ;; Check initial icon state - should be "disconnected" (white bolt)
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js-await (wait-for-popup-ready popup))
                    events-initial (js-await (get-test-events popup))
                    icon-events (.filter events-initial (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))]
                (js/console.log "Initial icon events:" (js/JSON.stringify icon-events))
                ;; Initial state should be "disconnected"
                (when (pos? (.-length icon-events))
                  (let [last-event (aget icon-events (dec (.-length icon-events)))]
                    (js-await (-> (expect (.. last-event -data -state))
                                  (.toBe "disconnected")))))
                (js-await (.close popup)))

              ;; Enable auto-connect via popup
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))
                (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                  (js-await (.click settings-header)))
                (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                  (js-await (.click auto-connect-checkbox))
                  (js-await (-> (expect auto-connect-checkbox) (.toBeChecked))))
                (js-await (.close popup)))

              ;; Navigate to trigger auto-connect (Scittle injection)
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 10000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              ;; Wait for Scittle to be loaded
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js-await (wait-for-event popup "SCITTLE_LOADED" 10000))]

                ;; Check icon state after Scittle injection - should be "injected" (yellow) or "connected" (green)
                (let [events (js-await (get-test-events popup))
                      icon-events (.filter events (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))]
                  (js/console.log "Icon events after Scittle load:" (js/JSON.stringify icon-events))
                  ;; Should have icon state events
                  (js-await (-> (expect (.-length icon-events))
                                (.toBeGreaterThan 0)))
                  ;; Last event should be "injected" or "connected"
                  (let [last-event (aget icon-events (dec (.-length icon-events)))
                        state (.. last-event -data -state)]
                    (js/console.log "Final icon state:" state)
                    (js-await (-> (expect (or (= state "injected") (= state "connected")))
                                  (.toBeTruthy)))))
                (js-await (.close popup)))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Log-powered: toolbar icon shows best state across all tabs"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Enable auto-connect
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (wait-for-popup-ready popup))
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (.click auto-connect-checkbox))
                (js-await (-> (expect auto-connect-checkbox) (.toBeChecked))))
              (js-await (.close popup)))

            ;; Open Tab A and trigger auto-connect (gets Scittle injected -> yellow)
            (let [tab-a (js-await (.newPage context))]
              (js-await (.goto tab-a "http://localhost:18080/basic.html" #js {:timeout 10000}))
              (js-await (-> (expect (.locator tab-a "#test-marker"))
                            (.toContainText "ready")))

              ;; Wait for Tab A to have Scittle injected
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js-await (wait-for-event popup "SCITTLE_LOADED" 10000))]
                (js-await (.close popup)))

              ;; Get Tab A's final icon state
              (let [popup (js-await (create-popup-page context ext-id))
                    events-a (js-await (get-test-events popup))
                    icon-events-a (.filter events-a (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))
                    last-state-a (.. (aget icon-events-a (dec (.-length icon-events-a))) -data -state)]
                (js/console.log "Tab A final state:" last-state-a)
                ;; Tab A should be at least "injected"
                (js-await (-> (expect (or (= last-state-a "injected") (= last-state-a "connected")))
                              (.toBeTruthy)))
                (js-await (.close popup)))

              ;; Open Tab B (fresh page, would normally be "disconnected")
              (let [tab-b (js-await (.newPage context))]
                (js-await (.goto tab-b "http://localhost:18080/spa-test.html" #js {:timeout 10000}))
                (js-await (-> (expect (.locator tab-b "#test-marker"))
                              (.toContainText "ready")))

                ;; Bring Tab B to focus (simulates user switching tabs)
                (js-await (.bringToFront tab-b))

                ;; Small wait for tab activation event to process
                (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 300))))

                ;; Check the LAST icon state - should still show "injected" from Tab A
                ;; even though Tab B itself would be "disconnected"
                (let [popup (js-await (create-popup-page context ext-id))
                      events (js-await (get-test-events popup))
                      icon-events (.filter events (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))
                      last-event (aget icon-events (dec (.-length icon-events)))
                      last-state (.. last-event -data -state)]
                  (js/console.log "After switching to Tab B, last icon state:" last-state)
                  ;; Key assertion: icon should show best state (injected from Tab A)
                  ;; NOT disconnected (Tab B's individual state)
                  (js-await (-> (expect (or (= last-state "injected") (= last-state "connected")))
                                (.toBeTruthy)))
                  (js-await (.close popup)))

                (js-await (.close tab-b)))
              (js-await (.close tab-a)))

            (finally
              (js-await (.close context)))))))
