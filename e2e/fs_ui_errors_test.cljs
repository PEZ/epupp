(ns e2e.fs-ui-errors-test
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [assert-no-errors!]]
            [fs-ui-reactivity-helpers :as helpers]
            [fs-write-helpers :refer [sleep eval-in-browser unquote-result]]))

(defn- ^:async failed_fs_operation_rejects_promise []
  ;; Try to delete a non-existent script - should reject
  ;; Cleanup: ensure the script doesn't exist (defensive for parallel runs)
  ;; Use atom-based polling instead of fixed sleep
  (let [cleanup-code "(def !cleanup-result (atom :pending))
                      (-> (epupp.fs/rm! \"nonexistent_script_12345.cljs\")
                          (.then (fn [_] (reset! !cleanup-result :done)))
                          (.catch (fn [_] (reset! !cleanup-result :done))))
                      :cleanup-started"]
    (js-await (helpers/eval-in-browser cleanup-code))
    (js-await (helpers/wait-for-eval-promise "!cleanup-result" 500)))
  (let [delete-code "(def !rm-nonexistent-result (atom :pending))\n                                        (-> (epupp.fs/rm! \"nonexistent_script_12345.cljs\")\n                                            (.then (fn [r] (reset! !rm-nonexistent-result {:resolved r})))\n                                            (.catch (fn [e] (reset! !rm-nonexistent-result {:rejected (.-message e)}))))\n                                        :setup-done"
        res (js-await (helpers/eval-in-browser delete-code))]
    (-> (expect (.-success res)) (.toBe true))

    ;; Poll @!rm-nonexistent-result until not :pending
    (let [poll-code "(let [r @!rm-nonexistent-result] (cond (= r :pending) :pending (:rejected r) (:rejected r) :else r))"
          timeout 3000
          interval 20
          start (.now js/Date)]
      (loop []
        (when (> (- (.now js/Date) start) timeout)
          (throw (js/Error. "Timeout waiting for !rm-nonexistent-result")))
        (let [check-result (js-await (eval-in-browser poll-code))]
          (if (and (.-success check-result) (seq (.-values check-result)))
            (let [result-str (unquote-result (first (.-values check-result)))]
              (if (= ":pending" result-str)
                (do
                  (js-await (sleep interval))
                  (recur))
                (let [error-msg result-str]
                  (-> (expect error-msg)
                      (.toBe "Not deleting non-existent file: nonexistent_script_12345.cljs")))))
            (do
              (js-await (sleep interval))
              (recur))))))))

(defn- ^:async no_uncaught_errors_during_ui_reactivity_tests []
  (let [popup (js-await (.newPage (helpers/get-context)))]
    (js-await (.goto popup (str "chrome-extension://" (helpers/get-ext-id) "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))

(.describe test "REPL FS UI: error handling"
           (fn []
             (.beforeAll test (fn [] (helpers/setup-browser!)))

             (.afterAll test
                        (fn []
                          (helpers/close-browser!)))

             (test "REPL FS UI: failed FS operation rejects promise"
                   failed_fs_operation_rejects_promise)

             ;; Final error check
             (test "REPL FS UI: no uncaught errors during UI reactivity tests"
                   no_uncaught_errors_during_ui_reactivity_tests)))
