(ns true-e2e-test
  "True E2E tests that verify userscript loading, timing, and REPL interaction
   via structured event logging to chrome.storage.local."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              clear-test-events! wait-for-event get-test-events]]))

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
