(ns e2e.namespace-reservation-test
  "E2E tests for epupp/ namespace reservation"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [eval-in-browser setup-browser!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_rejects_epupp_prefix_script []
  ;; Try to save a script with epupp/ prefix - should reject
  (let [test-code "{:epupp/script-name \"epupp/test.cljs\"
                   :epupp/auto-run-match \"https://example.com/*\"}
                  (ns fake-system)
                  (js/console.log \"Trying to use reserved namespace!\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !reserved-ns-result (atom :pending))
                                     (defn ^:async do-it []
                                       (try
                                         (let [r (await (epupp.fs/save! " (pr-str test-code) "))]
                                           (reset! !reserved-ns-result {:resolved r}))
                                         (catch :default e
                                           (reset! !reserved-ns-result {:rejected (.-message e)}))))
                                     (do-it)
                                     :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 500]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!reserved-ns-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            ;; Should be rejected with reserved namespace error
            (-> (expect (.includes result-str "rejected"))
                (.toBe true))
            (-> (expect (.includes result-str "reserved namespace"))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save rejection"))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
              (recur))))))))

(defn- ^:async test_rejects_epupp_deep_nested_prefix []
  ;; Try to save a script with epupp/built-in/ prefix (deep nesting) - should reject
  (let [test-code "{:epupp/script-name \"epupp/built-in/fake.cljs\"
                   :epupp/auto-run-match \"https://example.com/*\"}
                  (ns deep-fake)
                  (js/console.log \"Trying to impersonate built-in!\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !deep-nested-result (atom :pending))
                                     (defn ^:async do-it []
                                       (try
                                         (let [r (await (epupp.fs/save! " (pr-str test-code) "))]
                                           (reset! !deep-nested-result {:resolved r}))
                                         (catch :default e
                                           (reset! !deep-nested-result {:rejected (.-message e)}))))
                                     (do-it)
                                     :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 500]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!deep-nested-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            ;; Should be rejected with reserved namespace error
            (-> (expect (.includes result-str "rejected"))
                (.toBe true))
            (-> (expect (.includes result-str "reserved namespace"))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for deep nested save rejection"))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
              (recur))))))))

(defn- ^:async test_rejects_epupp_prefix_even_with_force []
  ;; Try with force flag - should still reject
  (let [test-code "{:epupp/script-name \"epupp/hacked.cljs\"
                   :epupp/auto-run-match \"https://example.com/*\"}
                  (ns force-hack)"
        setup-result (js-await (eval-in-browser
                                (str "(def !force-reserved-result (atom :pending))
                                     (defn ^:async do-it []
                                       (try
                                         (let [r (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))]
                                           (reset! !force-reserved-result {:resolved r}))
                                         (catch :default e
                                           (reset! !force-reserved-result {:rejected (.-message e)}))))
                                     (do-it)
                                     :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 500]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!force-reserved-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            ;; Should still be rejected even with force
            (-> (expect (.includes result-str "rejected"))
                (.toBe true))
            (-> (expect (.includes result-str "reserved namespace"))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for force save rejection"))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
              (recur))))))))

(.describe test "REPL FS: namespace reservation"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (reset! !context (js-await (setup-browser!)))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: save - rejects epupp/ prefix script names"
                   test_rejects_epupp_prefix_script)

             (test "REPL FS: save - rejects epupp/built-in/ prefix (deep nesting)"
                   test_rejects_epupp_deep_nested_prefix)

             (test "REPL FS: save - rejects epupp/ prefix even with force flag"
                   test_rejects_epupp_prefix_even_with_force)))
