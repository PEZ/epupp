(ns e2e.popup-autoconnect-test
  "E2E tests for popup auto-connect REPL functionality."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           clear-storage wait-for-popup-ready
                                           wait-for-checkbox-state
                                           assert-no-errors!]]))



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
        (js-await (-> (expect (.locator popup ".setting:has(#auto-connect-repl) .description.warning"))
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









(.describe test "Popup Auto-Connect"
           (fn []
             (test "Popup Auto-Connect: auto-connect REPL setting"
                   auto_connect_repl_setting)

             (test "Popup Auto-Connect: auto-connect REPL triggers Scittle injection on page load"
                   auto_connect_triggers_scittle_injection_on_page_load)))
