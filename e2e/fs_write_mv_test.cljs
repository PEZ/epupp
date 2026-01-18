(ns e2e.fs-write-mv-test
  "E2E tests for REPL file system mv! operations"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [sleep eval-in-browser unquote-result setup-browser!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_mv_renames_a_script []
  (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/mv!)"))]
    (-> (expect (.-success fn-check)) (.toBe true))
    (-> (expect (.-values fn-check)) (.toContain "true")))

  (let [test-code "{:epupp/script-name \"mv-rename-test-original\"\n                                   :epupp/site-match \"https://example.com/*\"}\n                                  (ns rename-test)"
        setup-result (js-await (eval-in-browser
                                (str "(def !mv-setup (atom :pending))\n                                       (-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n                                         (.then (fn [r] (reset! !mv-setup r))))\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!mv-setup)"))]
        (when (or (not (.-success check-result))
                  (empty? (.-values check-result))
                  (= (first (.-values check-result)) ":pending"))
          (when (< (- (.now js/Date) start) timeout-ms)
            (js-await (sleep 20))
            (recur))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !mv-ls (atom :pending))\n                                                (-> (epupp.fs/ls)\n                                                    (.then (fn [scripts] (reset! !mv-ls scripts))))\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!mv-ls)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (.includes (first (.-values check-result)) "mv_rename_test_original.cljs"))
              (.toBe true))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls before mv"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !mv-result (atom :pending))\n                                                (-> (epupp.fs/mv! \"mv_rename_test_original.cljs\" \"mv_renamed_script.cljs\" {:fs/force? true})\n                                                    (.then (fn [r] (reset! !mv-result r))))\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!mv-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (.includes (first (.-values check-result)) ":success true"))
              (.toBe true))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for epupp.fs/mv! result"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !ls-after-mv (atom :pending))\n                                                (-> (epupp.fs/ls)\n                                                    (.then (fn [scripts] (reset! !ls-after-mv scripts))))\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!ls-after-mv)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            (-> (expect (.includes result-str "mv_renamed_script.cljs")) (.toBe true))
            (-> (expect (.includes result-str "mv_rename_test_original.cljs")) (.toBe false)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls after mv"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_mv_with_force_returns_from_and_to_names []
  (let [test-code "{:epupp/script-name \"mv-force-confirm\"\n                                   :epupp/site-match \"https://example.com/*\"}\n                                  (ns mv-force-confirm)"
        setup-result (js-await (eval-in-browser
                                (str "(def !confirm-mv-setup (atom :pending))\n                                       (-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n                                         (.then (fn [r] (reset! !confirm-mv-setup r))))\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!confirm-mv-setup)"))]
        (when (or (not (.-success check-result))
                  (empty? (.-values check-result))
                  (= (first (.-values check-result)) ":pending"))
          (when (< (- (.now js/Date) start) timeout-ms)
            (js-await (sleep 20))
            (recur))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !confirm-mv-ls (atom :pending))\n                                (-> (epupp.fs/ls)\n                                  (.then (fn [scripts] (reset! !confirm-mv-ls scripts))))\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!confirm-mv-ls)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (.includes (first (.-values check-result)) "mv_force_confirm.cljs"))
              (.toBe true))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls before mv"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !confirm-mv-result (atom :pending))\n                                (-> (epupp.fs/mv! \"mv_force_confirm.cljs\" \"mv_force_renamed.cljs\" {:fs/force? true})\n                                  (.then (fn [r] (reset! !confirm-mv-result r))))\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!confirm-mv-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
            (let [result-str (first (.-values check-result))]
            (-> (expect (.includes result-str ":success true"))
                (.toBe true))
            (-> (expect (.includes result-str ":from-name"))
                (.toBe true))
            (-> (expect (.includes result-str "mv_force_confirm.cljs"))
                (.toBe true))
            (-> (expect (.includes result-str ":to-name"))
                (.toBe true))
            (-> (expect (.includes result-str "mv_force_renamed.cljs"))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for mv! result"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [cleanup-result (js-await (eval-in-browser
                                  "(def !mv-force-cleanup (atom :pending))\n                                 (-> (epupp.fs/rm! \"mv_force_renamed.cljs\")\n                                   (.then (fn [_] (reset! !mv-force-cleanup :done)))\n                                   (.catch (fn [_] (reset! !mv-force-cleanup :done))))\n                                 :cleanup-started"))]
    (-> (expect (.-success cleanup-result)) (.toBe true)))
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!mv-force-cleanup)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          true
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for mv force cleanup"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_mv_rejects_when_target_name_exists []
  ;; Create two scripts with different names - save sequentially to avoid races
  (let [code1 "{:epupp/script-name \"mv-collision-source\"\n               :epupp/site-match \"https://example.com/*\"}\n              (ns collision-source)"
        save1-result (js-await (eval-in-browser
                                (str "(def !save1 (atom :pending))\n                                     (-> (epupp.fs/save! " (pr-str code1) " {:fs/force? true})\n                                       (.then (fn [r] (reset! !save1 (pr-str r))))\n                                       (.catch (fn [e] (reset! !save1 (str \"ERROR: \" (.-message e))))))\n                                     :started")))]
    (-> (expect (.-success save1-result)) (.toBe true)))

  ;; Wait for first save
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!save1)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not (or (= (first (.-values check-result)) ":pending")
                          (= (first (.-values check-result)) "\":pending\""))))
          (let [result-str (first (.-values check-result))]
            (when (.includes result-str "ERROR:")
              (throw (js/Error. (str "First save failed: " result-str))))
            (-> (expect (.includes result-str "mv_collision_source.cljs")) (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for first save"))
            (do (js-await (sleep 20)) (recur)))))))

  ;; Save second script
  (let [code2 "{:epupp/script-name \"mv-collision-target\"\n               :epupp/site-match \"https://example.com/*\"}\n              (ns collision-target)"
        save2-result (js-await (eval-in-browser
                                (str "(def !save2 (atom :pending))\n                                     (-> (epupp.fs/save! " (pr-str code2) " {:fs/force? true})\n                                       (.then (fn [r] (reset! !save2 (pr-str r))))\n                                       (.catch (fn [e] (reset! !save2 (str \"ERROR: \" (.-message e))))))\n                                     :started")))]
    (-> (expect (.-success save2-result)) (.toBe true)))

  ;; Wait for second save
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!save2)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not (or (= (first (.-values check-result)) ":pending")
                          (= (first (.-values check-result)) "\":pending\""))))
          (let [result-str (first (.-values check-result))]
            (when (.includes result-str "ERROR:")
              (throw (js/Error. (str "Second save failed: " result-str))))
            (-> (expect (.includes result-str "mv_collision_target.cljs")) (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for second save"))
            (do (js-await (sleep 20)) (recur)))))))

  ;; Ensure both scripts are visible before mv
  (let [setup-result (js-await (eval-in-browser
                                "(def !collision-ls-before (atom :pending))\n                                (-> (epupp.fs/ls)\n                                  (.then (fn [scripts] (reset! !collision-ls-before scripts))))\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!collision-ls-before)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            (if (and (.includes result-str "mv_collision_source.cljs")
                     (.includes result-str "mv_collision_target.cljs"))
              true
              (if (> (- (.now js/Date) start) timeout-ms)
                (throw (js/Error. "Timeout waiting for ls before collision mv"))
                (do (js-await (sleep 20)) (recur)))))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls before collision mv"))
            (do (js-await (sleep 20)) (recur)))))))

  ;; Now try to rename source to target - should fail since target exists
  (let [setup-result (js-await (eval-in-browser
                                "(def !collision-mv-result (atom :pending))\n                                (-> (epupp.fs/mv! \"mv_collision_source.cljs\" \"mv_collision_target.cljs\")\n                                  (.then (fn [r] (reset! !collision-mv-result {:resolved r})))\n                                  (.catch (fn [e] (reset! !collision-mv-result {:rejected (.-message e)}))))\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!collision-mv-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            ;; Should be rejected because target already exists
            (-> (expect (.includes result-str "rejected"))
                (.toBe true))
            (-> (expect (or (.includes result-str "already exists")
                            (.includes result-str "Script already exists")))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for collision mv! result"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  ;; Verify both scripts still exist (no data corruption)
  (let [setup-result (js-await (eval-in-browser
                                "(def !collision-ls (atom :pending))\n                                (-> (epupp.fs/ls)\n                                  (.then (fn [scripts] (reset! !collision-ls scripts))))\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!collision-ls)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            ;; Both scripts should still exist
            (-> (expect (.includes result-str "mv_collision_source.cljs"))
                (.toBe true))
            (-> (expect (.includes result-str "mv_collision_target.cljs"))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls after collision test"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  ;; Cleanup
  (let [cleanup-result (js-await (eval-in-browser
                                  "(def !mv-collision-cleanup (atom :pending))\n                                     (-> (js/Promise.all #js [(epupp.fs/rm! \"mv_collision_source.cljs\")\n                                                              (epupp.fs/rm! \"mv_collision_target.cljs\")])\n                                       (.then (fn [_] (reset! !mv-collision-cleanup :done)))\n                                       (.catch (fn [_] (reset! !mv-collision-cleanup :done))))\n                                     :cleanup-started"))]
    (-> (expect (.-success cleanup-result)) (.toBe true)))
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!mv-collision-cleanup)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          true
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for mv collision cleanup"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_mv_rejects_renaming_builtin_scripts []
  ;; Try to rename a built-in script - should reject
  (let [setup-result (js-await (eval-in-browser
                                "(def !mv-builtin-ls (atom :pending))\n                                (-> (epupp.fs/ls {:fs/ls-hidden? true})\n                                  (.then (fn [scripts] (reset! !mv-builtin-ls scripts))))\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!mv-builtin-ls)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            (if (.includes result-str "GitHub Gist Installer (Built-in)")
              (-> (expect true) (.toBe true))
              (if (> (- (.now js/Date) start) timeout-ms)
                (throw (js/Error. "Timeout waiting for ls before mv"))
                (do
                  (js-await (sleep 20))
                  (recur)))))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls before mv"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !mv-builtin-result (atom :pending))\n                                (-> (epupp.fs/mv! \"GitHub Gist Installer (Built-in)\" \"renamed-builtin.cljs\")\n                                  (.then (fn [r] (reset! !mv-builtin-result {:resolved r})))\n                                  (.catch (fn [e] (reset! !mv-builtin-result {:rejected (.-message e)}))))\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!mv-builtin-result] (cond (= r :pending) :pending (:rejected r) (str \"rejected||\" (:rejected r)) (:resolved r) (str \"resolved||\" (:resolved r)) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (unquote-result (first (.-values check-result)))]
            ;; Should be rejected because it's a built-in script
            (-> (expect (.startsWith result-str "rejected||"))
                (.toBe true))
            (-> (expect (or (.includes result-str "built-in")
                            (.includes result-str "Cannot rename built-in scripts")))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for mv! built-in result"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(.describe test "REPL FS: mv operations"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (reset! !context (js-await (setup-browser!)))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: mv - renames a script"
                   test_mv_renames_a_script)

             (test "REPL FS: mv - with {:fs/force? true} returns result with :fs/from-name and :fs/to-name"
                   test_mv_with_force_returns_from_and_to_names)

             (test "REPL FS: mv - rejects rename when target name already exists"
                   test_mv_rejects_when_target_name_exists)

             (test "REPL FS: mv - rejects renaming built-in scripts"
                   test_mv_rejects_renaming_builtin_scripts)))
