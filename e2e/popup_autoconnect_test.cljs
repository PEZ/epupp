(ns e2e.popup-autoconnect-test
  "E2E tests for popup auto-connect and auto-reconnect REPL functionality."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           clear-storage wait-for-popup-ready
                                           wait-for-checkbox-state
                                           wait-for-connection ws-port-1 assert-no-errors!]]))



;; =============================================================================
;; Popup User Journey: Auto-Connect REPL Setting
;; =============================================================================

(defn- ^:async auto_connect_repl_setting []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Setting appears in Settings section with warning ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-storage popup))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        ;; Expand settings section
        (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")
              settings-content (.locator popup ".settings-content")]
          (js-await (.click settings-header))
          (js-await (-> (expect settings-content) (.toBeVisible))))

        ;; Auto-connect checkbox exists and is unchecked by default
        (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
          (js-await (-> (expect auto-connect-checkbox) (.toBeVisible)))
          (js-await (-> (expect auto-connect-checkbox) (.not.toBeChecked))))

        ;; Warning text is visible
        (js-await (-> (expect (.locator popup ".auto-connect-warning"))
                      (.toContainText "inject the Scittle REPL")))

        (js-await (.close popup)))

      ;; === PHASE 2: Enable setting and verify it persists ===
      (let [popup (js-await (create-popup-page context ext-id))]
        ;; Expand settings
        (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
          (js-await (.click settings-header)))

        ;; Enable auto-connect
        (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
          (js-await (.click auto-connect-checkbox))
          (js-await (wait-for-checkbox-state auto-connect-checkbox true)))

        (js-await (.close popup)))

      ;; === PHASE 3: Setting persists after reload ===
      (let [popup (js-await (create-popup-page context ext-id))]
        ;; Expand settings
        (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
          (js-await (.click settings-header)))

        ;; Verify setting is still enabled
        (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
          (js-await (-> (expect auto-connect-checkbox) (.toBeChecked))))

        ;; Disable it again
        (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
          (js-await (.click auto-connect-checkbox))
          (js-await (wait-for-checkbox-state auto-connect-checkbox false)))

        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Popup User Journey: Auto-Connect and Auto-Reconnect REPL
;; =============================================================================

(defn- ^:async auto_connect_triggers_scittle_injection_on_page_load []
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
              event (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))]
          (js/console.log "SCITTLE_LOADED event:" (js/JSON.stringify event))
          (js-await (-> (expect (.-event event)) (.toBe "SCITTLE_LOADED")))
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async spa_navigation_does_not_trigger_repl_reconnection []
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
              event (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))]
          (js/console.log "Initial SCITTLE_LOADED:" (js/JSON.stringify event))

          ;; Get current event count
          (let [events-before (js-await (fixtures/get-test-events popup))
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
            (js-await (fixtures/assert-no-new-event-within popup "SCITTLE_LOADED" scittle-count-before 300))
            (js/console.log "Verified: No new SCITTLE_LOADED after SPA navigation"))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async auto_reconnect_triggers_scittle_injection_on_page_reload []
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
            (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))
            (js/console.log "REPL connected and Scittle loaded")

            ;; Verify connection exists
            (let [connections (js-await (fixtures/get-connections popup))]
              (js-await (-> (expect (.-length connections)) (.toBe 1)))
              (js/console.log "Connection verified:" (.-length connections) "active")))

          ;; Clear test events RIGHT BEFORE reload to get clean slate
          (js-await (fixtures/clear-test-events! popup))
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
              event (js-await (fixtures/wait-for-event popup2 "SCITTLE_LOADED" 10000))]
          (js/console.log "Auto-reconnect triggered! SCITTLE_LOADED event:" (js/JSON.stringify event))
          ;; The presence of SCITTLE_LOADED event after clearing events proves auto-reconnect worked
          (js-await (-> (expect (.-event event)) (.toBe "SCITTLE_LOADED")))
          (js-await (assert-no-errors! popup2))
          (js-await (.close popup2)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async auto_reconnect_does_not_trigger_for_tabs_never_connected []
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
              events-before (js-await (fixtures/get-test-events popup))
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
            (js-await (fixtures/assert-no-new-event-within popup2 "SCITTLE_LOADED" scittle-count-before 300))
            (js/console.log "SCITTLE_LOADED count after reload (should still be 0):" scittle-count-before)
            (js-await (assert-no-errors! popup2))
            (js-await (.close popup2))))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async disabled_auto_reconnect_does_not_trigger_on_page_reload []
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
              events-before (js-await (fixtures/get-test-events popup))
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
            (js-await (fixtures/assert-no-new-event-within popup2 "SCITTLE_LOADED" scittle-count-before 300))
            (js/console.log "SCITTLE_LOADED count after reload:" scittle-count-before)
            (js-await (assert-no-errors! popup2))
            (js-await (.close popup2))))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Popup: Auto-Connect and Auto-Reconnect REPL"
           (fn []
             (test "auto-connect REPL setting"
                   auto_connect_repl_setting)

             (test "auto-connect REPL triggers Scittle injection on page load"
                   auto_connect_triggers_scittle_injection_on_page_load)

             (test "SPA navigation does NOT trigger REPL reconnection"
                   spa_navigation_does_not_trigger_repl_reconnection)

             (test "auto-reconnect triggers Scittle injection on page reload of previously connected tab"
                   auto_reconnect_triggers_scittle_injection_on_page_reload)

             (test "auto-reconnect does NOT trigger for tabs never connected"
                   auto_reconnect_does_not_trigger_for_tabs_never_connected)

             (test "disabled auto-reconnect does NOT trigger on page reload"
                   disabled_auto_reconnect_does_not_trigger_on_page_reload)))
