(ns e2e.fs-write-rm-test
  "E2E tests for REPL file system rm! operations"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [assert-no-errors! get-extension-id]]
            [fs-write-helpers :refer [sleep eval-in-browser unquote-result setup-browser!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_rm_deletes_a_script []
  (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/rm!)"))]
    (-> (expect (.-success fn-check)) (.toBe true))
    (-> (expect (.-values fn-check)) (.toContain "true")))

  (let [test-code "{:epupp/script-name \"delete-test-script\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns delete-test)"
        setup-result (js-await (eval-in-browser
                                (str "(def !rm-setup (atom :pending))\n                                       (-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n                                         (.then (fn [r] (reset! !rm-setup r))))\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!rm-setup)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          true
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save setup"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !ls-before-rm (atom :pending))\n                                                (-> (epupp.fs/ls)\n                                                    (.then (fn [scripts] (reset! !ls-before-rm scripts))))\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!ls-before-rm)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (.includes (first (.-values check-result)) "delete_test_script.cljs"))
              (.toBe true))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls before rm"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !rm-result (atom :pending))\n                                (-> (epupp.fs/rm! \"delete_test_script.cljs\")\n                                  (.then (fn [r] (reset! !rm-result r))))\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!rm-result] (cond (= r :pending) :pending (map? r) (:fs/success r) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (first (.-values check-result)))
              (.toBe "true"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for epupp.fs/rm! result"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !ls-after-rm (atom :pending))\n                                                (-> (epupp.fs/ls)\n                                                    (.then (fn [scripts] (reset! !ls-after-rm scripts))))\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!ls-after-rm)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (.includes (first (.-values check-result)) "delete_test_script.cljs"))
              (.toBe false))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls after rm"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_rm_rejects_deleting_builtin_scripts []
  (let [setup-result (js-await (eval-in-browser
                                "(def !rm-builtin-result (atom :pending))\n                                (-> (epupp.fs/rm! \"GitHub Gist Installer (Built-in)\")\n                                  (.then (fn [r] (reset! !rm-builtin-result {:resolved r})))\n                                  (.catch (fn [e] (reset! !rm-builtin-result {:rejected (.-message e)}))))\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser
                                    "(let [r @!rm-builtin-result]
                                       (cond
                                         (= r :pending) :pending
                                         (:rejected r) (str \"rejected||\" (:rejected r))
                                         (:resolved r) (str \"resolved||\" (:resolved r))
                                         :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (unquote-result (first (.-values check-result)))]
            (-> (expect (or (.startsWith result-str "rejected||")
                            (.startsWith result-str "resolved||")))
                (.toBe true))
            (-> (expect (or (.includes result-str "built-in")
                            (.includes result-str "Cannot delete built-in scripts")))
                (.toBe true))
            (-> (expect (or (.startsWith result-str "rejected||")
                            (.includes result-str ":success false")))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for epupp.fs/rm! built-in result"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_rm_with_vector_rejects_when_any_missing []
  (let [code1 "{:epupp/script-name \"bulk-rm-test-1\"\n                               :epupp/auto-run-match \"https://example.com/*\"}\n                              (ns bulk-rm-1)"
        code2 "{:epupp/script-name \"bulk-rm-test-2\"\n                               :epupp/auto-run-match \"https://example.com/*\"}\n                              (ns bulk-rm-2)"
        ;; Setup: save two test scripts
        setup-result (js-await (eval-in-browser
                                (str "(def !bulk-rm-setup (atom :pending))\n                                      (-> (js/Promise.all #js [(epupp.fs/save! " (pr-str code1) " {:fs/force? true})\n                                                               (epupp.fs/save! " (pr-str code2) " {:fs/force? true})])\n                                          (.then (fn [r] (reset! !bulk-rm-setup {:resolved r})))\n                                          (.catch (fn [e] (reset! !bulk-rm-setup {:rejected (.-message e)}))))\n                                      :setup-started")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  ;; Wait for setup to complete
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-rm-setup)"))
            result-str (unquote-result (first (.-values check-result)))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= result-str ":pending"))
          (do
            (js/console.log "=== Bulk rm setup result ===" result-str)
            (-> (expect (.includes result-str "resolved")) (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for bulk rm setup"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  ;; Delete two existing scripts and one non-existent - should reject
  (let [setup-result (js-await (eval-in-browser
                                "(def !bulk-rm-result (atom :pending))\n                                (-> (epupp.fs/rm! [\"bulk_rm_test_1.cljs\" \"bulk_rm_test_2.cljs\" \"does-not-exist.cljs\"])\n                                  (.then (fn [result] (reset! !bulk-rm-result {:resolved result})))\n                                  (.catch (fn [e] (reset! !bulk-rm-result {:rejected (.-message e)}))))\n                                :delete-started"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-rm-result)"))
            result-str (unquote-result (first (.-values check-result)))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= result-str ":pending"))
          (do
            (js/console.log "=== Bulk rm result ===" result-str)
            (-> (expect (.includes result-str "rejected"))
                (.toBe true))
            (-> (expect (.includes result-str "does-not-exist.cljs"))
                (.toBe true))
            (-> (expect (or (.includes result-str "Script not found")
                            (.includes result-str "not found")
                            (.includes result-str "does not exist")))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for bulk rm! result"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !bulk-rm-after (atom :pending))\n                                                (-> (epupp.fs/ls)\n                                                    (.then (fn [scripts] (reset! !bulk-rm-after scripts))))\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-rm-after)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            (-> (expect (.includes result-str "bulk_rm_test_1.cljs"))
                (.toBe false))
            (-> (expect (.includes result-str "bulk_rm_test_2.cljs"))
                (.toBe false)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls after bulk rm"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  )

(defn- ^:async test_rm_returns_existed_flag []
  (let [unique-name (str "existed-test-rm-" (.now js/Date))
        normalized-name (-> unique-name
                            (.toLowerCase)
                            (.replace (js/RegExp. "[\\s.-]+" "g") "_")
                            (.replace (js/RegExp. "[^a-z0-9_/]" "g") "")
                            (str ".cljs"))
        test-code (str "{:epupp/script-name \"" unique-name "\"\n"
                       " :epupp/auto-run-match \"https://example.com/*\"}\n"
                       "(ns existed-test)")
        setup-result (js-await (eval-in-browser
                                (str "(def !existed-rm-result (atom {:save :pending :rm :pending}))\n"
                                     "(-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n"
                                     "    (.then (fn [r] (swap! !existed-rm-result assoc :save r)\n"
                                     "                   (epupp.fs/rm! \"" normalized-name "\")))\n"
                                     "    (.then (fn [r] (swap! !existed-rm-result assoc :rm r)))\n"
                                     "    (.catch (fn [e] (swap! !existed-rm-result assoc :rm {:rejected (.-message e)}))))\n"
                                     ":setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!existed-rm-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not (.includes (first (.-values check-result)) ":rm :pending")))
          (let [result-str (first (.-values check-result))]
            (-> (expect (.includes result-str "rejected"))
                (.toBe false))
            (-> (expect (.includes result-str ":fs/success true"))
                (.toBe true))
            (-> (expect (.includes result-str ":fs/name"))
                (.toBe true))
            (-> (expect (.includes result-str ":fs/existed? true"))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for rm! result"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_no_uncaught_errors_during_fs_tests []
  (let [ext-id (js-await (get-extension-id @!context))
        popup (js-await (.newPage @!context))]
    (js-await (.goto popup (str "chrome-extension://" ext-id "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))

(.describe test "REPL FS: rm operations"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (reset! !context (js-await (setup-browser!)))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: rm - deletes a script"
                   test_rm_deletes_a_script)

             (test "REPL FS: rm - rejects deleting built-in scripts"
                   test_rm_rejects_deleting_builtin_scripts)

             (test "REPL FS: rm - vector rejects when any missing"
                   test_rm_with_vector_rejects_when_any_missing)

             (test "REPL FS: rm - returns result with :fs/existed? flag"
                   test_rm_returns_existed_flag)

             (test "REPL FS: rm - no uncaught errors"
                   test_no_uncaught_errors_during_fs_tests)))
