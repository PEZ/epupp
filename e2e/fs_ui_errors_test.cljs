(ns e2e.fs-ui-errors-test
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [assert-no-errors!]]
            [fs-ui-reactivity-helpers :as helpers]))

(defn- ^:async failed_fs_operation_rejects_promise []
  ;; Try to delete a non-existent script - should reject
  ;; Ensure the script does not exist (defensive cleanup for parallel runs)
  (let [cleanup-code "(-> (epupp.fs/rm! \"nonexistent_script_12345.cljs\")\n                          (.catch (fn [_] nil)))"]
    (js-await (helpers/eval-in-browser cleanup-code))
    (js-await (helpers/sleep 50)))
  (let [delete-code "(def !rm-nonexistent-result (atom :pending))\n                                        (-> (epupp.fs/rm! \"nonexistent_script_12345.cljs\")\n                                            (.then (fn [r] (reset! !rm-nonexistent-result {:resolved r})))\n                                            (.catch (fn [e] (reset! !rm-nonexistent-result {:rejected (.-message e)}))))\n                                        :setup-done"
        res (js-await (helpers/eval-in-browser delete-code))]
    (-> (expect (.-success res)) (.toBe true))

    (let [out (js-await (helpers/wait-for-eval-promise "!rm-nonexistent-result" 3000))]
      (-> (expect (.includes out "rejected")) (.toBe true))
      (-> (expect (or (.includes out "Script not found")
                      (.includes out "not found")
                      (.includes out "does not exist")
                      (.includes out "non-existent")))
          (.toBe true)))))

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
