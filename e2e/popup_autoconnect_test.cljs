(ns e2e.popup-autoconnect-test
  "E2E tests for popup auto-connect level functionality."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           clear-storage wait-for-popup-ready
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

        ;; Auto-connect select exists and defaults to "off"
        (let [auto-connect-select (.locator popup "#auto-connect-level")]
          (js-await (-> (expect auto-connect-select) (.toBeVisible)))
          (js-await (-> (expect auto-connect-select) (.toHaveValue "off"))))

        ;; Description text is visible
        (js-await (-> (expect (.locator popup ".setting:has(#auto-connect-level) .description.warning"))
                      (.toBeVisible)))

        (js-await (.close popup)))

      ;; === PHASE 2: Change setting to all-pages and verify it persists ===
      (let [popup (js-await (create-popup-page context ext-id))]
        ;; Set auto-connect to all-pages
        (let [auto-connect-select (.locator popup "#auto-connect-level")]
          (js-await (.selectOption auto-connect-select "all-pages"))
          (js-await (-> (expect auto-connect-select) (.toHaveValue "all-pages"))))

        (js-await (.close popup)))

      ;; === PHASE 3: Setting persists after reload ===
      (let [popup (js-await (create-popup-page context ext-id))]
        ;; Verify setting is still all-pages
        (let [auto-connect-select (.locator popup "#auto-connect-level")]
          (js-await (-> (expect auto-connect-select) (.toHaveValue "all-pages"))))

        ;; Set back to off
        (let [auto-connect-select (.locator popup "#auto-connect-level")]
          (js-await (.selectOption auto-connect-select "off"))
          (js-await (-> (expect auto-connect-select) (.toHaveValue "off"))))

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

        ;; Set auto-connect to all-pages
        (let [auto-connect-select (.locator popup "#auto-connect-level")]
          (js-await (-> (expect auto-connect-select) (.toBeVisible)))
          (js-await (.selectOption auto-connect-select "all-pages"))
          (js-await (-> (expect auto-connect-select) (.toHaveValue "all-pages"))))

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
             (test "Popup Auto-Connect: auto-connect level setting"
                   auto_connect_repl_setting)

             (test "Popup Auto-Connect: auto-connect REPL triggers Scittle injection on page load"
                   auto_connect_triggers_scittle_injection_on_page_load)))
