(ns e2e.fs-write-save-test
  "E2E tests for REPL file system save! operations"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [sleep eval-in-browser unquote-result setup-browser!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_save_creates_new_script_from_code_with_manifest []
  (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/save!)"))]
    (-> (expect (.-success fn-check)) (.toBe true))
    (-> (expect (.-values fn-check)) (.toContain "true")))

  (let [test-code "{:epupp/script-name \"test-script-from-repl\"\n                                   :epupp/site-match \"https://example.com/*\"}\n                                  (ns test-script)\n                                  (js/console.log \"Hello from test script!\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-result (atom :pending))\n                                       (-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n                                         (.then (fn [r] (reset! !save-result r))))\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!save-result] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/name r)) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (unquote-result (first (.-values check-result))))
              (.toBe "true||test_script_from_repl.cljs"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for epupp.fs/save! result"))
            (do
              (js-await (sleep 50))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !ls-after-save (atom :pending))\n                                                (-> (epupp.fs/ls)\n                                                    (.then (fn [scripts] (reset! !ls-after-save scripts))))\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!ls-after-save)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (.includes (first (.-values check-result)) "test_script_from_repl.cljs"))
              (.toBe true))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls after save"))
            (do
              (js-await (sleep 50))
              (recur))))))))

(defn- ^:async test_save_with_disabled_creates_disabled_script []
  (let [test-code "{:epupp/script-name \"disabled-by-default\"\n                                   :epupp/site-match \"https://example.com/*\"}\n                                  (ns disabled-test)\n                                  (js/console.log \"Should be disabled!\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-disabled (atom :pending))\n                                       (-> (epupp.fs/save! " (pr-str test-code) " {:fs/enabled false :fs/force? true})\n                                         (.then (fn [r] (reset! !save-disabled r))))\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!save-disabled] (cond (= r :pending) :pending (map? r) (:fs/success r) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (first (.-values check-result)))
              (.toBe "true"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save"))
            (do
              (js-await (sleep 50))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !ls-check-disabled (atom :pending))\n                                                (-> (epupp.fs/ls)\n                                                    (.then (fn [scripts] (reset! !ls-check-disabled scripts))))\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!ls-check-disabled)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            (-> (expect (.includes result-str "disabled_by_default.cljs"))
                (.toBe true))
            (let [scripts-check (js-await (eval-in-browser
                                           "(some (fn [s] (and (= (:fs/name s) \"disabled_by_default.cljs\")\n                                                                                 (false? (:fs/enabled s))))\n                                                                  @!ls-check-disabled)"))]
              (-> (expect (.-success scripts-check)) (.toBe true))
              (-> (expect (.-values scripts-check)) (.toContain "true"))))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls"))
            (do
              (js-await (sleep 50))
              (recur)))))))

  (js-await (eval-in-browser "(epupp.fs/rm! \"disabled_by_default.cljs\")")))

(defn- ^:async test_save_with_vector_returns_map_of_results []
  (let [code1 "{:epupp/script-name \"bulk-save-test-1\"\n                               :epupp/site-match \"https://example.com/*\"}\n                              (ns bulk-save-1)"
        code2 "{:epupp/script-name \"bulk-save-test-2\"\n                               :epupp/site-match \"https://example.com/*\"}\n                              (ns bulk-save-2)"
        setup-result (js-await (eval-in-browser
                                (str "(def !bulk-save-result (atom :pending))\n                                       (-> (epupp.fs/save! [" (pr-str code1) " " (pr-str code2) "] {:fs/force? true})\n                                         (.then (fn [result] (reset! !bulk-save-result {:resolved result})))\n                                         (.catch (fn [e] (reset! !bulk-save-result {:rejected (.-message e)}))))\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
        (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-save-result)"))
              result-str (unquote-result (first (.-values check-result)))]
          (if (and (.-success check-result)
                   (seq (.-values check-result))
                   (not= result-str ":pending"))
            (do
              (js/console.log "=== Bulk save result ===" result-str)
              (-> (expect (.includes result-str "resolved"))
                  (.toBe true))
              (-> (expect (.includes result-str "0"))
                  (.toBe true))
              (-> (expect (.includes result-str "1"))
                  (.toBe true))
              (-> (expect (.includes result-str ":success true"))
                  (.toBe true))
              (-> (expect (.includes result-str "bulk_save_test_1.cljs"))
                  (.toBe true))
              (-> (expect (.includes result-str "bulk_save_test_2.cljs"))
                  (.toBe true)))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for bulk save! result"))
              (do
                (js-await (sleep 50))
                (recur)))))))

  (let [cleanup-result (js-await (eval-in-browser
                                  "(def !bulk-save-cleanup (atom :pending))\n                                 (-> (js/Promise.all #js [(epupp.fs/rm! \"bulk_save_test_1.cljs\")\n                                                           (epupp.fs/rm! \"bulk_save_test_2.cljs\")])\n                                   (.then (fn [_] (reset! !bulk-save-cleanup :done)))\n                                   (.catch (fn [_] (reset! !bulk-save-cleanup :done))))\n                                 :cleanup-started"))]
    (-> (expect (.-success cleanup-result)) (.toBe true)))
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-save-cleanup)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          true
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for bulk save cleanup"))
            (do
              (js-await (sleep 50))
              (recur))))))))

(defn- ^:async test_save_rejects_when_script_already_exists []
  ;; First create a script
  (let [test-code "{:epupp/script-name \"save-collision-test\"\n                   :epupp/site-match \"https://example.com/*\"}\n                  (ns collision-test)"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-first (atom :pending))\n                                     (-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n                                       (.then (fn [r] (reset! !save-first r))))\n                                     :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  ;; Wait for first save
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!save-first)"))]
        (when (or (not (.-success check-result))
                  (empty? (.-values check-result))
                  (= (first (.-values check-result)) ":pending"))
          (when (< (- (.now js/Date) start) timeout-ms)
            (js-await (sleep 50))
            (recur))))))

  ;; Now try to save again WITHOUT force - should reject
  (let [new-code "{:epupp/script-name \"save-collision-test\"\n                  :epupp/site-match \"https://example.com/*\"}\n                 (ns collision-test-v2)\n                 (js/console.log \"This should not overwrite!\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-collision-result (atom :pending))\n                                     (-> (epupp.fs/save! " (pr-str new-code) ")\n                                       (.then (fn [r] (reset! !save-collision-result {:resolved r})))\n                                       (.catch (fn [e] (reset! !save-collision-result {:rejected (.-message e)}))))\n                                     :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!save-collision-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            ;; Should be rejected because script already exists
            (-> (expect (.includes result-str "rejected"))
                (.toBe true))
            (-> (expect (or (.includes result-str "already exists")
                            (.includes result-str "Script already exists")))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save collision result"))
            (do
              (js-await (sleep 50))
              (recur)))))))

  ;; Cleanup
  (let [cleanup-result (js-await (eval-in-browser
                                  "(def !save-collision-cleanup (atom :pending))\n                                     (-> (epupp.fs/rm! \"save_collision_test.cljs\")\n                                       (.then (fn [_] (reset! !save-collision-cleanup :done)))\n                                       (.catch (fn [_] (reset! !save-collision-cleanup :done))))\n                                     :cleanup-started"))]
    (-> (expect (.-success cleanup-result)) (.toBe true)))
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!save-collision-cleanup)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          true
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save collision cleanup"))
            (do
              (js-await (sleep 50))
              (recur))))))))

(defn- ^:async test_save_rejects_builtin_script_names []
  ;; Try to save a script with a built-in name - should reject
  (let [test-code "{:epupp/script-name \"GitHub Gist Installer (Built-in)\"\n                   :epupp/site-match \"https://example.com/*\"}\n                  (ns fake-builtin)\n                  (js/console.log \"Trying to impersonate built-in!\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-builtin-result (atom :pending))\n                                     (-> (epupp.fs/save! " (pr-str test-code) ")\n                                       (.then (fn [r] (reset! !save-builtin-result {:resolved r})))\n                                       (.catch (fn [e] (reset! !save-builtin-result {:rejected (.-message e)}))))\n                                     :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!save-builtin-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            ;; Should be rejected because it's a built-in name
            (-> (expect (.includes result-str "rejected"))
                (.toBe true))
            (-> (expect (or (.includes result-str "built-in")
                            (.includes result-str "Cannot save built-in scripts")
                            (.includes result-str "Cannot overwrite built-in scripts")))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save built-in result"))
            (do
              (js-await (sleep 50))
              (recur))))))))

(defn- ^:async test_save_with_force_rejects_builtin_script_names []
  ;; Try to save with force - still should reject for built-in
  (let [test-code "{:epupp/script-name \"GitHub Gist Installer (Built-in)\"\n                   :epupp/site-match \"https://example.com/*\"}\n                  (ns fake-builtin-force)"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-builtin-force-result (atom :pending))\n                                     (-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n                                       (.then (fn [r] (reset! !save-builtin-force-result {:resolved r})))\n                                       (.catch (fn [e] (reset! !save-builtin-force-result {:rejected (.-message e)}))))\n                                     :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!save-builtin-force-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            ;; Should still be rejected even with force
            (-> (expect (.includes result-str "rejected"))
                (.toBe true))
            (-> (expect (or (.includes result-str "built-in")
                            (.includes result-str "Cannot save built-in scripts")
                            (.includes result-str "Cannot overwrite built-in scripts")))
                (.toBe true)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save built-in with force result"))
            (do
              (js-await (sleep 50))
              (recur))))))))

(.describe test "REPL FS: save operations"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (reset! !context (js-await (setup-browser!)))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: save - creates new script from code with manifest"
                   test_save_creates_new_script_from_code_with_manifest)

             (test "REPL FS: save - with {:fs/enabled false} creates disabled script"
                   test_save_with_disabled_creates_disabled_script)

             (test "REPL FS: save - vector returns map of per-item results"
                   test_save_with_vector_returns_map_of_results)

             (test "REPL FS: save - rejects when script with same name already exists"
                   test_save_rejects_when_script_already_exists)

             (test "REPL FS: save - rejects built-in script names"
                   test_save_rejects_builtin_script_names)

             (test "REPL FS: save - with {:fs/force? true} still rejects built-in script names"
                   test_save_with_force_rejects_builtin_script_names)))
