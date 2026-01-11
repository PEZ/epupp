(ns z-final-error-check-test
  "Final test that runs after all others to catch any accumulated errors.
   Named with 'z_' prefix to ensure alphabetical ordering puts it last."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [create-extension-context get-extension-id
                              create-popup-page wait-for-popup-ready
                              get-test-events]]))

(defn ^:async assert-no-errors!
  "Assert that no UNCAUGHT_ERROR or UNHANDLED_REJECTION events were logged.
   Call this at the end of the test suite to catch any errors from all tests."
  [ext-page]
  (let [events (js-await (get-test-events ext-page))
        errors (.filter events
                        (fn [e]
                          (or (= (.-event e) "UNCAUGHT_ERROR")
                              (= (.-event e) "UNHANDLED_REJECTION"))))]
    (when (pos? (.-length errors))
      (js/console.error "Found errors:" (js/JSON.stringify errors nil 2)))
    (js-await (-> (expect (.-length errors))
                  (.toBe 0)))))

(test "Final check: no uncaught errors across all tests"
      (^:async fn []
        (let [context (js-await (create-extension-context))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (wait-for-popup-ready popup))
              (js-await (assert-no-errors! popup))
              (js-await (.close popup)))
            (finally
              (js-await (.close context)))))))
