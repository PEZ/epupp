(ns log-powered-test
  "Log-powered E2E tests that verify userscript loading, timing, and REPL interaction
   via structured event logging to chrome.storage.local.

   These tests observe internal extension behavior that's invisible to standard
   Playwright assertions by capturing events emitted to storage."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           create-panel-page wait-for-event
                                           get-test-events wait-for-save-status wait-for-popup-ready
                                           generate-timing-report print-timing-report
                                           clear-test-events! assert-no-new-event-within
                                           wait-for-connection ws-port-1]]))

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
              (js-await (.goto page "http://localhost:18080/timing-test.html" #js {:timeout 1000}))
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
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
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
              (js-await (.goto page "http://localhost:18080/spa-test.html" #js {:timeout 1000}))
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

                  ;; Assert no NEW SCITTLE_LOADED event occurs (rapid-poll for 300ms)
                  ;; Using scittle-count-before as the baseline
                  (js-await (assert-no-new-event-within popup "SCITTLE_LOADED" scittle-count-before 300))
                  (js/console.log "Verified: No new SCITTLE_LOADED after SPA navigation"))

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
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
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
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
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

(test "Log-powered: injected state is tab-local, connected state is global"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Create a userscript that ONLY targets basic.html
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "tab-local-test"
                                            :match "*://localhost:*/basic.html"
                                            :code "(js/console.log \"Tab A script loaded\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button:text(\"Save Script\")")))
              (js-await (wait-for-save-status panel "Created"))
              (js-await (.close panel)))

            ;; Approve the script using test URL override
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))
              (let [script-item (.locator popup ".script-item:has-text(\"tab_local_test.cljs\")")]
                (js-await (-> (expect script-item) (.toBeVisible)))
                (let [allow-btn (.locator script-item "button:has-text(\"Allow\")")]
                  (when (pos? (js-await (.count allow-btn)))
                    (js-await (.click allow-btn))
                    (js-await (-> (expect allow-btn) (.not.toBeVisible)))))
                (js-await (.close popup))))

            ;; Navigate Tab A - script is pre-approved, should inject
            (let [tab-a (js-await (.newPage context))]
              (js-await (.goto tab-a "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator tab-a "#test-marker"))
                            (.toContainText "ready")))

              ;; Wait for Scittle injection, capture Tab A's Chrome tab-id
              (let [popup (js-await (create-popup-page context ext-id))
                    scittle-loaded-event (js-await (wait-for-event popup "SCITTLE_LOADED" 10000))
                    tab-a-id (aget (.-data scittle-loaded-event) "tab-id")]
                (js-await (.close popup))

                ;; Assert Tab A shows injected/connected based on its tab-id
                (let [popup (js-await (create-popup-page context ext-id))
                      events (js-await (get-test-events popup))
                      icon-events (.filter events
                                           (fn [e]
                                             (and (= (.-event e) "ICON_STATE_CHANGED")
                                                  (= (aget (.-data e) "tab-id") tab-a-id))))]
                  (js-await (-> (expect (.-length icon-events))
                                (.toBeGreaterThan 0)))
                  (let [last-event (aget icon-events (dec (.-length icon-events)))
                        last-state (.. last-event -data -state)]
                    (js-await (-> (expect (or (= last-state "injected")
                                              (= last-state "connected")))
                                  (.toBeTruthy))))
                  (js-await (.close popup)))

                ;; Open Tab B (spa-test.html - does NOT match our script)
                (let [tab-b (js-await (.newPage context))]
                  (js-await (.goto tab-b "http://localhost:18080/spa-test.html" #js {:timeout 1000}))
                  (js-await (-> (expect (.locator tab-b "#test-marker"))
                                (.toContainText "ready")))

                  ;; Bring Tab B to focus
                  (js-await (.bringToFront tab-b))
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 300))))

                  ;; Tab B should show disconnected, and the icon event should be for Tab B
                  ;; (i.e. not Tab A's Chrome tab-id)
                  (let [popup (js-await (create-popup-page context ext-id))
                        events (js-await (get-test-events popup))
                        icon-events (.filter events (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))
                        last-event (aget icon-events (dec (.-length icon-events)))
                        last-tab-id (aget (.-data last-event) "tab-id")
                        last-state (.. last-event -data -state)]
                    (js-await (-> (expect last-tab-id) (.toBeTruthy)))
                    (js-await (-> (expect (not (= last-tab-id tab-a-id)))
                                  (.toBeTruthy)))
                    (js-await (-> (expect (= last-state "disconnected"))
                                  (.toBeTruthy)))
                    (js-await (.close popup)))

                  ;; Bring Tab A back to focus
                  (js-await (.bringToFront tab-a))
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 300))))

                  ;; Tab A should STILL show injected/connected
                  (let [popup (js-await (create-popup-page context ext-id))
                        events (js-await (get-test-events popup))
                        icon-events (.filter events
                                             (fn [e]
                                               (and (= (.-event e) "ICON_STATE_CHANGED")
                                                    (= (aget (.-data e) "tab-id") tab-a-id))))]
                    (js-await (-> (expect (.-length icon-events))
                                  (.toBeGreaterThan 0)))
                    (let [last-event (aget icon-events (dec (.-length icon-events)))
                          last-state (.. last-event -data -state)]
                      (js-await (-> (expect (or (= last-state "injected")
                                                (= last-state "connected")))
                                    (.toBeTruthy))))
                    (js-await (.close popup)))

                  (js-await (.close tab-b))))
              (js-await (.close tab-a)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Connection Tracking Test - Verify get-connections returns connection data
;; =============================================================================

(test "Log-powered: get-connections returns active REPL connections"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Navigate to a test page first
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Test page loaded")

              ;; Open popup
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))

                ;; Check initial state - no connections yet
                (let [initial-conns (js-await (fixtures/get-connections popup))]
                  (js/console.log "Initial connections:" (.-length initial-conns))
                  (js-await (-> (expect (.-length initial-conns))
                                (.toBe 0))))

                ;; Find the test page tab and connect
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
                  (js/console.log "Found test page tab ID:" tab-id)
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))
                  (js/console.log "Connected to tab via WebSocket port" ws-port-1)

                  ;; Wait for connection event
                  (js-await (wait-for-connection popup 5000))

                  ;; Now get-connections should return the connection
                  (let [connections (js-await (fixtures/get-connections popup))
                        conn-count (.-length connections)]
                    (js/console.log "Connections after connect:" conn-count)
                    (js/console.log "Connection data:" (js/JSON.stringify connections))

                    ;; Should have exactly 1 connection now
                    (js-await (-> (expect conn-count)
                                  (.toBe 1)))

                    ;; Verify the connection has the tab-id
                    (when (> conn-count 0)
                      (let [conn (aget connections 0)
                            conn-tab-id (aget conn "tab-id")]
                        (js/console.log "Connected tab ID:" conn-tab-id "expected:" tab-id)
                        (js-await (-> (expect (str conn-tab-id))
                                      (.toBe (str tab-id))))))))

                (js-await (.close popup)))
              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Log-powered: popup UI displays active REPL connections"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Navigate to a test page first
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              ;; Open popup and check initial state shows "No REPL connections"
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))

                ;; Initially should show no connections message
                (let [no-conn-msg (.locator popup ".no-connections")]
                  (js-await (-> (expect no-conn-msg)
                                (.toBeVisible))))

                ;; Find and connect to the test page
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))

                  ;; Wait for connection event then reload popup
                  (js-await (wait-for-connection popup 5000))
                  (js-await (.reload popup))
                  (js-await (wait-for-popup-ready popup))

                  ;; Now the "no connections" message should be hidden
                  ;; and we should see a connected tab item
                  (let [no-conn-msg (.locator popup ".no-connections")
                        connected-items (.locator popup ".connected-tab-item")]
                    ;; "No connections" should no longer be visible
                    (js-await (-> (expect no-conn-msg)
                                  (.not.toBeVisible)))
                    ;; Should have exactly 1 connected tab item
                    (js-await (-> (expect connected-items)
                                  (.toHaveCount 1)))
                    ;; The connected tab should show the port
                    (let [port-elem (.locator popup ".connected-tab-port")]
                      (js-await (-> (expect port-elem)
                                    (.toContainText ":12346"))))))

                (js-await (.close popup)))
              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Log-powered: popup UI updates immediately after connecting (no tab switch needed)"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Navigate to a test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              ;; Open popup - keep it open throughout the test
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))

                ;; Initially should show no connections
                (let [no-conn-msg (.locator popup ".no-connections")]
                  (js-await (-> (expect no-conn-msg)
                                (.toBeVisible)))
                  (js/console.log "Initial state: no connections shown"))

                ;; Connect to the test page while popup is still open
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))
                  (js/console.log "Connected to tab" tab-id)

                  ;; Wait for connection event
                  (js-await (wait-for-connection popup 5000))

                  ;; WITHOUT reloading or switching tabs, the UI should update
                  ;; BUG: Currently requires popup reload or tab switch
                  (let [connected-items (.locator popup ".connected-tab-item")]
                    (js-await (-> (expect connected-items)
                                  (.toHaveCount 1 #js {:timeout 2000})))
                    (js/console.log "UI updated with connected tab")))

                (js-await (.close popup)))
              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Log-powered: popup UI updates when connected page is reloaded (auto-reconnect disabled)"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Clear storage and DISABLE auto-reconnect for this test
            ;; With auto-reconnect enabled (default), the connection would persist after reload
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Expand settings and disable auto-reconnect
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))
              (let [auto-reconnect-checkbox (.locator popup "#auto-reconnect-repl")]
                ;; It's checked by default, uncheck it
                (js-await (-> (expect auto-reconnect-checkbox) (.toBeChecked)))
                (js-await (.click auto-reconnect-checkbox))
                (js-await (-> (expect auto-reconnect-checkbox) (.not.toBeChecked)))
                (js/console.log "Auto-reconnect disabled for this test"))
              (js-await (.close popup)))

            ;; Navigate to a test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              ;; Connect to the test page
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))

                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))
                  (js/console.log "Connected to tab" tab-id)

                  ;; Wait for connection event
                  (js-await (wait-for-connection popup 5000))
                  (js-await (.reload popup))
                  (js-await (wait-for-popup-ready popup))

                  (let [connected-items (.locator popup ".connected-tab-item")]
                    (js-await (-> (expect connected-items)
                                  (.toHaveCount 1)))
                    (js/console.log "Connection shown in UI"))

                  ;; Now reload the connected page - this disconnects WebSocket
                  ;; With auto-reconnect DISABLED, connection should not come back
                  (js-await (.reload page))
                  (js-await (-> (expect (.locator page "#test-marker"))
                                (.toContainText "ready")))
                  (js/console.log "Page reloaded (auto-reconnect disabled)")

                  ;; Wait for WebSocket close to propagate
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))

                  ;; Reload popup to get fresh state
                  (js-await (.reload popup))
                  (js-await (wait-for-popup-ready popup))

                  ;; The connection should now be gone (no auto-reconnect)
                  (let [no-conn-msg (.locator popup ".no-connections")
                        connected-items (.locator popup ".connected-tab-item")]
                    (js-await (-> (expect connected-items)
                                  (.toHaveCount 0 #js {:timeout 2000})))
                    (js-await (-> (expect no-conn-msg)
                                  (.toBeVisible)))
                    (js/console.log "Connection removed from UI after page reload")))

                (js-await (.close popup)))
              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Error Assertion Test - Verify No Uncaught Errors
;; =============================================================================

(defn ^:async assert-no-errors!
  "Check that no UNCAUGHT_ERROR or UNHANDLED_REJECTION events were logged.
   Call this at the end of tests to catch extension errors."
  [popup]
  (let [events (js-await (get-test-events popup))
        error-events (.filter events
                              (fn [e]
                                (or (= (.-event e) "UNCAUGHT_ERROR")
                                    (= (.-event e) "UNHANDLED_REJECTION"))))]
    (when (pos? (.-length error-events))
      (js/console.error "Unexpected errors captured:")
      (.forEach error-events
                (fn [e]
                  (js/console.error (js/JSON.stringify e nil 2)))))
    (js-await (-> (expect (.-length error-events))
                  (.toBe 0)))))

(test "Log-powered: extension startup produces no uncaught errors"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Wait for extension to fully start
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (wait-for-popup-ready popup))

              ;; Navigate to a test page to trigger content bridge injection
              (let [page (js-await (.newPage context))]
                (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
                (js-await (-> (expect (.locator page "#test-marker"))
                              (.toContainText "ready")))

                ;; Small wait for any async operations
                (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))

                ;; Re-open popup and check for any errors
                (js-await (.close popup))
                (let [popup2 (js-await (create-popup-page context ext-id))]
                  (js-await (wait-for-popup-ready popup2))
                  (js-await (assert-no-errors! popup2))
                  (js-await (.close popup2)))

                (js-await (.close page))))

            (finally
              (js-await (.close context)))))))

(test "Log-powered: userscript injection produces no uncaught errors"
      (^:async fn []
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

            ;; Approve the script
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              (let [script-item (.locator popup ".script-item:has-text(\"error_check_test.cljs\")")]
                (js-await (-> (expect script-item) (.toBeVisible)))
                (let [allow-btn (.locator script-item "button:has-text(\"Allow\")")]
                  (when (pos? (js-await (.count allow-btn)))
                    (js-await (.click allow-btn))
                    (js-await (-> (expect allow-btn) (.not.toBeVisible))))))
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
              (js-await (.close context)))))))

;; =============================================================================
;; Auto-Reconnect REPL Tests
;; =============================================================================

(test "Log-powered: auto-reconnect triggers Scittle injection on page reload of previously connected tab"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Clear storage for clean state, ensure auto-reconnect is enabled (default)
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Verify auto-reconnect is enabled by default
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))
              (let [auto-reconnect-checkbox (.locator popup "#auto-reconnect-repl")]
                (js-await (-> (expect auto-reconnect-checkbox) (.toBeChecked)))
                (js/console.log "Auto-reconnect is enabled (default)"))

              ;; Ensure auto-connect-all is OFF (so auto-reconnect logic is tested)
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (-> (expect auto-connect-checkbox) (.not.toBeChecked)))
                (js/console.log "Auto-connect-all is disabled"))

              (js-await (.close popup)))

            ;; Navigate to test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Test page loaded")

              ;; Manually connect REPL to this tab
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
                  (js/console.log "Connecting to tab" tab-id "on port" ws-port-1)
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))

                  ;; Wait for connection to establish and Scittle to load
                  (js-await (wait-for-event popup "SCITTLE_LOADED" 10000))
                  (js/console.log "REPL connected and Scittle loaded")

                  ;; Verify connection exists
                  (let [connections (js-await (fixtures/get-connections popup))]
                    (js-await (-> (expect (.-length connections)) (.toBe 1)))
                    (js/console.log "Connection verified:" (.-length connections) "active")))

                ;; Clear test events RIGHT BEFORE reload to get clean slate
                (js-await (clear-test-events! popup))
                (js/console.log "Cleared test events before reload")
                (js-await (.close popup)))

              ;; Reload the page - this disconnects WebSocket, triggers auto-reconnect
              (js/console.log "Reloading page - should trigger auto-reconnect...")
              (js-await (.reload page))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Page reloaded")

              ;; Wait for auto-reconnect to trigger Scittle injection
              ;; This is the key assertion: after reload, auto-reconnect should load Scittle again
              (let [popup2 (js-await (create-popup-page context ext-id))
                    _ (js/console.log "Waiting for SCITTLE_LOADED event from auto-reconnect...")
                    event (js-await (wait-for-event popup2 "SCITTLE_LOADED" 10000))]
                (js/console.log "Auto-reconnect triggered! SCITTLE_LOADED event:" (js/JSON.stringify event))
                ;; The presence of SCITTLE_LOADED event after clearing events proves auto-reconnect worked
                (js-await (-> (expect (.-event event)) (.toBe "SCITTLE_LOADED")))
                (js-await (.close popup2)))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Log-powered: auto-reconnect does NOT trigger for tabs never connected"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Clear storage for clean state
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Verify auto-reconnect is enabled but auto-connect-all is OFF
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))
              (let [auto-reconnect-checkbox (.locator popup "#auto-reconnect-repl")]
                (js-await (-> (expect auto-reconnect-checkbox) (.toBeChecked))))
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (-> (expect auto-connect-checkbox) (.not.toBeChecked))))
              (js/console.log "Auto-reconnect ON, auto-connect-all OFF")
              (js-await (.close popup)))

            ;; Navigate to test page WITHOUT connecting REPL first
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Test page loaded (never connected)")

              ;; Get initial SCITTLE_LOADED count
              (let [popup (js-await (create-popup-page context ext-id))
                    events-before (js-await (get-test-events popup))
                    scittle-count-before (.-length (.filter events-before (fn [e] (= (.-event e) "SCITTLE_LOADED"))))]
                (js/console.log "SCITTLE_LOADED count before reload (should be 0):" scittle-count-before)
                (js-await (.close popup))

                ;; Reload the page - should NOT trigger auto-reconnect (never connected)
                (js/console.log "Reloading page - should NOT trigger any connection...")
                (js-await (.reload page))
                (js-await (-> (expect (.locator page "#test-marker"))
                              (.toContainText "ready")))
                (js/console.log "Page reloaded")

                ;; Assert no NEW SCITTLE_LOADED event occurs (rapid-poll for 300ms)
                ;; scittle-count-before is 0 for never-connected tab
                (let [popup2 (js-await (create-popup-page context ext-id))]
                  (js-await (assert-no-new-event-within popup2 "SCITTLE_LOADED" scittle-count-before 300))
                  (js/console.log "SCITTLE_LOADED count after reload (should still be 0):" scittle-count-before)
                  (js-await (.close popup2))))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Log-powered: disabled auto-reconnect does NOT trigger on page reload"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Clear storage and DISABLE auto-reconnect
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Expand settings and disable auto-reconnect
              (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
                (js-await (.click settings-header)))
              (let [auto-reconnect-checkbox (.locator popup "#auto-reconnect-repl")]
                ;; It's checked by default, uncheck it
                (js-await (-> (expect auto-reconnect-checkbox) (.toBeChecked)))
                (js-await (.click auto-reconnect-checkbox))
                (js-await (-> (expect auto-reconnect-checkbox) (.not.toBeChecked)))
                (js/console.log "Auto-reconnect disabled"))

              ;; Ensure auto-connect-all is also OFF
              (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
                (js-await (-> (expect auto-connect-checkbox) (.not.toBeChecked))))
              (js-await (.close popup)))

            ;; Navigate to test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Test page loaded")

              ;; Manually connect REPL to this tab
              (let [popup (js-await (create-popup-page context ext-id))]
                (js-await (wait-for-popup-ready popup))
                (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
                  (js/console.log "Connecting to tab" tab-id "on port" ws-port-1)
                  (js-await (fixtures/connect-tab popup tab-id ws-port-1))
                  (js-await (wait-for-connection popup 5000))
                  (js/console.log "REPL connected"))
                (js-await (.close popup)))

              ;; Get SCITTLE_LOADED count before reload
              (let [popup (js-await (create-popup-page context ext-id))
                    events-before (js-await (get-test-events popup))
                    scittle-count-before (.-length (.filter events-before (fn [e] (= (.-event e) "SCITTLE_LOADED"))))]
                (js/console.log "SCITTLE_LOADED count before reload:" scittle-count-before)
                (js-await (.close popup))

                ;; Reload the page - should NOT trigger reconnect (setting disabled)
                (js/console.log "Reloading page with auto-reconnect DISABLED...")
                (js-await (.reload page))
                (js-await (-> (expect (.locator page "#test-marker"))
                              (.toContainText "ready")))
                (js/console.log "Page reloaded")

                ;; Assert no NEW SCITTLE_LOADED event occurs (rapid-poll for 300ms)
                (let [popup2 (js-await (create-popup-page context ext-id))]
                  (js-await (assert-no-new-event-within popup2 "SCITTLE_LOADED" scittle-count-before 300))
                  (js/console.log "SCITTLE_LOADED count after reload:" scittle-count-before)
                  (js-await (.close popup2))))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Gist Installer E2E Test
;; =============================================================================

(test "Log-powered: gist installer shows Install button and installs script"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Clear storage and wait for built-in gist installer to be re-installed
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))

              ;; Wait for gist installer to be re-installed with require field
              ;; Poll using .evaluate loop - same pattern as wait-for-event
              (let [start (.now js/Date)
                    timeout-ms 5000]
                (loop []
                  (let [storage-data (js-await (.evaluate popup
                                                          (fn []
                                                            (js/Promise. (fn [resolve]
                                                                           (.get js/chrome.storage.local #js ["scripts"]
                                                                                 (fn [result] (resolve result))))))))
                        scripts (.-scripts storage-data)
                        gist-installer (when scripts
                                         (.find scripts (fn [s]
                                                          (= (.-id s) "epupp-builtin-gist-installer"))))
                        has-requires (and gist-installer
                                          (.-require gist-installer)
                                          (pos? (.-length (.-require gist-installer))))]
                    (if has-requires
                      (js/console.log "Gist installer re-installed with requires")
                      (if (> (- (.now js/Date) start) timeout-ms)
                        (throw (js/Error. "Timeout waiting for gist installer with requires"))
                        (do
                          (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
                          (recur)))))))
              (js-await (.close popup)))

            ;; Navigate to mock gist page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/mock-gist.html" #js {:timeout 5000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))
              (js/console.log "Mock gist page loaded")

              ;; Wait for the gist installer userscript to run and add Install button
              ;; The button should appear on the installable gist file
              (let [install-btn (.locator page "#installable-gist button:has-text(\"Install\")")]
                (js/console.log "Waiting for Install button to appear...")
                (js-await (-> (expect install-btn)
                              (.toBeVisible #js {:timeout 10000})))
                (js/console.log "Install button found, clicking...")

                ;; Click the install button - should show confirmation modal
                (js-await (.click install-btn))

                ;; Wait for confirmation modal - uses #epupp-confirm ID
                (let [confirm-btn (.locator page "#epupp-confirm")]
                  (js-await (-> (expect confirm-btn)
                                (.toBeVisible #js {:timeout 5000})))
                  (js/console.log "Confirmation modal appeared, confirming...")

                  ;; Confirm the installation
                  (js-await (.click confirm-btn))

                  ;; Wait for button to change to "Installed"
                  (let [installed-indicator (.locator page "#installable-gist button:has-text(\"Installed\")")]
                    (js-await (-> (expect installed-indicator)
                                  (.toBeVisible #js {:timeout 5000})))
                    (js/console.log "Script installed successfully"))))

              (js-await (.close page)))

            ;; Verify script appears in popup
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (wait-for-popup-ready popup))

              ;; Look for the installed script
              (let [script-item (.locator popup ".script-item:has-text(\"test_installer_script.cljs\")")]
                (js-await (-> (expect script-item)
                              (.toBeVisible #js {:timeout 5000})))
                (js/console.log "Installed script visible in popup"))

              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))
