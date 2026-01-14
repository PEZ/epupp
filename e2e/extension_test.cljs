(ns extension-test
  "E2E tests for extension infrastructure and startup.

   Coverage:
   - Extension startup and initialization
   - Test infrastructure (dev log button)
   - Error checking during normal operation"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              get-test-events wait-for-popup-ready assert-no-errors!]]))

;; =============================================================================
;; Extension Startup
;; =============================================================================

(test "Extension: starts and emits startup event"
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

              ;; Assert no errors before closing
              (js-await (assert-no-errors! popup))
              (finally
                (js-await (.close context))))))))

;; =============================================================================
;; Test Infrastructure
;; =============================================================================

(test "Extension: dev log button works and captures console output"
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
;; Error Checking
;; =============================================================================

(test "Extension: startup produces no uncaught errors"
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

                ;; Wait for content bridge to be injected - poll via test events
                ;; Much faster than fixed 500ms sleep when bridge injects quickly
                (js-await (.close popup))
                (let [popup2 (js-await (create-popup-page context ext-id))]
                  (js-await (wait-for-popup-ready popup2))
                  (js-await (assert-no-errors! popup2))
                  (js-await (.close popup2)))

                (js-await (.close page))))

            (finally
              (js-await (.close context)))))))
