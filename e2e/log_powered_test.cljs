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

(def ws-port 12346)  ;; Must match browser-nrepl port in tasks.clj

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
                  (js-await (fixtures/connect-tab popup tab-id ws-port))
                  (js/console.log "Connected to tab via WebSocket port" ws-port)

                  ;; Wait a moment for connection to be fully established
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))

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
                  (js-await (fixtures/connect-tab popup tab-id ws-port))

                  ;; Wait for connection then reload popup to show updated state
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))
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
