(ns e2e.fs-write-save-test
  "E2E tests for REPL file system save! operations"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [sleep eval-in-browser unquote-result setup-browser! ensure-builtin-script!]]))

(def ^:private !context (atom nil))

(defn- ^:async wait-for-builtin-script!
  "Wait until a built-in script name is visible via epupp.fs/ls."
  [script-name timeout-ms]
  (let [start (.now js/Date)
        timeout-ms (or timeout-ms 5000)
        ;; Define the atom ONCE before the loop to avoid race condition
        define-result (js-await (eval-in-browser "(def !wait-builtin-present (atom :pending))"))]
    (when-not (.-success define-result)
      (throw (js/Error. (str "Failed to define atom: " (.-error define-result)))))
    (loop []
      ;; Reset to :pending at start of each iteration
      (let [setup-result (js-await (eval-in-browser
                                    (str "(reset! !wait-builtin-present :pending)\n"
                                         "(-> (epupp.fs/ls {:fs/ls-hidden? true})\n"
                                         "    (.then (fn [scripts]\n"
                                         "            (reset! !wait-builtin-present\n"
                                         "                    (some (fn [s] (= (:fs/name s) \"" script-name "\")) scripts)))))\n"
                                         ":setup-done")))]
        (when-not (.-success setup-result)
          (throw (js/Error. (str "Failed to start builtin check: " (.-error setup-result)))))
        (let [check-result (js-await (eval-in-browser "(pr-str @!wait-builtin-present)"))]
          (if (and (.-success check-result)
                   (seq (.-values check-result)))
            (let [result-str (unquote-result (first (.-values check-result)))]
              (if (= result-str "true")
                true
                (if (> (- (.now js/Date) start) timeout-ms)
                  (throw (js/Error. (str "Timeout waiting for built-in script: " script-name)))
                  (do
                    (js-await (sleep 20))
                    (recur)))))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. (str "Timeout waiting for built-in script: " script-name)))
              (do
                (js-await (sleep 20))
                (recur)))))))))

(defn- ^:async wait-for-script-present!
  "Wait until a script name is visible via epupp.fs/ls."
  [script-name timeout-ms]
  (let [start (.now js/Date)
        timeout-ms (or timeout-ms 3000)]
    (loop []
      (let [setup-result (js-await (eval-in-browser
                                    (str "(def !script-present (atom :pending))\n"
                                         " (-> (epupp.fs/ls)\n"
                                         "     (.then (fn [scripts]\n"
                                         "             (reset! !script-present\n"
                                         "                     (some (fn [s] (= (:fs/name s) \"" script-name "\")) scripts)))))\n"
                                         " :setup-done")))]
        (when-not (.-success setup-result)
          (throw (js/Error. (str "Failed to start script presence check: " (.-error setup-result)))))
        (let [check-result (js-await (eval-in-browser "(pr-str @!script-present)"))]
          (if (and (.-success check-result)
                   (seq (.-values check-result)))
            (let [result-str (unquote-result (first (.-values check-result)))]
              (if (= result-str "true")
                true
                (if (> (- (.now js/Date) start) timeout-ms)
                  (throw (js/Error. (str "Timeout waiting for script: " script-name)))
                  (do
                    (js-await (sleep 20))
                    (recur)))))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. (str "Timeout waiting for script: " script-name)))
              (do
                (js-await (sleep 20))
                (recur)))))))))

(defn- ^:async test_save_creates_new_script_from_code_with_manifest []
  (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/save!)"))]
    (-> (expect (.-success fn-check)) (.toBe true))
    (-> (expect (.-values fn-check)) (.toContain "true")))

  (let [test-code "{:epupp/script-name \"test-script-from-repl\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns test-script)\n                                  (js/console.log \"Hello from test script!\")"
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
              (js-await (sleep 20))
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
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_save_with_disabled_creates_disabled_script []
  (let [test-code "{:epupp/script-name \"disabled-by-default\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns disabled-test)\n                                  (js/console.log \"Should be disabled!\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-disabled (atom :pending))\n                                       (-> (epupp.fs/save! " (pr-str test-code) " {:fs/enabled? false :fs/force? true})\n                                         (.then (fn [r] (reset! !save-disabled r))))\n                                       :setup-done")))]
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
              (js-await (sleep 20))
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
                                           "(some (fn [s] (and (= (:fs/name s) \"disabled_by_default.cljs\")\n                                                                                 (false? (:fs/enabled? s))))\n                                                                  @!ls-check-disabled)"))]
              (-> (expect (.-success scripts-check)) (.toBe true))
              (-> (expect (.-values scripts-check)) (.toContain "true"))))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (js-await (eval-in-browser "(epupp.fs/rm! \"disabled_by_default.cljs\")")))

(defn- ^:async test_save_with_vector_returns_map_of_results []
  (let [code1 "{:epupp/script-name \"bulk-save-test-1\"\n                               :epupp/auto-run-match \"https://example.com/*\"}\n                              (ns bulk-save-1)"
        code2 "{:epupp/script-name \"bulk-save-test-2\"\n                               :epupp/auto-run-match \"https://example.com/*\"}\n                              (ns bulk-save-2)"
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
              (-> (expect (.includes result-str ":fs/success true"))
                  (.toBe true))
              (-> (expect (.includes result-str "bulk_save_test_1.cljs"))
                  (.toBe true))
              (-> (expect (.includes result-str "bulk_save_test_2.cljs"))
                  (.toBe true)))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for bulk save! result"))
              (do
                (js-await (sleep 20))
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
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_save_rejects_when_script_already_exists []
  ;; First create a script
  (let [test-code "{:epupp/script-name \"save-collision-test\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns collision-test)"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-first (atom :pending))\n                                     (-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n                                       (.then (fn [r] (reset! !save-first r))))\n                                     :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  ;; Wait for first save
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!save-first] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/name r)) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (unquote-result (first (.-values check-result))))
              (.toBe "true||save_collision_test.cljs"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for initial save"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (js-await (wait-for-script-present! "save_collision_test.cljs" 3000))

  ;; Now try to save again WITHOUT force - should reject
  (let [new-code "{:epupp/script-name \"save-collision-test\"\n                  :epupp/auto-run-match \"https://example.com/*\"}\n                 (ns collision-test-v2)\n                 (js/console.log \"This should not overwrite!\")"
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
            (-> (expect (.includes result-str ":rejected"))
                (.toBe true))
            (-> (expect (.includes result-str "Script already exists: save_collision_test.cljs"))
                (.toBe true (str "Expected collision error, got: " result-str))))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save collision result"))
            (do
              (js-await (sleep 20))
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
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_save_rejects_builtin_script_names []
  (js-await (ensure-builtin-script! @!context))
  (js-await (wait-for-builtin-script! "epupp/gist_installer.cljs" 5000))
  ;; Small delay to let any pending storage operations settle
  (js-await (sleep 50))

  ;; Try to save a script with a built-in name - should reject
  ;; Note: epupp/ prefix is rejected by reserved namespace check (correct behavior)
  (let [test-code "{:epupp/script-name \"epupp/gist_installer.cljs\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns fake-builtin)\n                  (js/console.log \"Trying to impersonate built-in!\")"
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
            ;; Should be rejected by reserved namespace check (epupp/ prefix)
            (-> (expect (.includes result-str ":rejected"))
                (.toBe true))
            (-> (expect (.includes result-str "Cannot create scripts in reserved namespace: epupp/"))
                (.toBe true (str "Expected reserved namespace error, got: " result-str))))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save built-in result"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_save_with_force_rejects_builtin_script_names []
  (js-await (ensure-builtin-script! @!context))
  (js-await (wait-for-builtin-script! "epupp/gist_installer.cljs" 5000))
  ;; Small delay to let any pending storage operations settle
  (js-await (sleep 50))

  ;; Try to save with force - still should reject (reserved namespace)
  (let [test-code "{:epupp/script-name \"epupp/gist_installer.cljs\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns fake-builtin-force)"
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
            ;; Should still be rejected by reserved namespace check even with force
            (-> (expect (.includes result-str ":rejected"))
                (.toBe true))
            (-> (expect (.includes result-str "Cannot create scripts in reserved namespace: epupp/"))
                (.toBe true (str "Expected reserved namespace error, got: " result-str))))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save built-in with force result"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_save_force_update_preserves_script_id []
  ;; Create a script via REPL FS
  (let [test-code-v1 "{:epupp/script-name \"id-preserve-test\"\n                     :epupp/auto-run-match \"https://example.com/*\"}\n                    (ns id-test)\n                    (js/console.log \"Version 1\")"
        setup-result (js-await (eval-in-browser
                                (str "(-> (epupp.fs/save! " (pr-str test-code-v1) " {:fs/force? true})\n"
                                     "  (.then (fn [_] :v1-done)))\n")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  ;; Wait for script to appear
  (js-await (wait-for-script-present! "id_preserve_test.cljs" 3000))

  ;; Get script ID via ls
  (let [id1-result (js-await (eval-in-browser
                              "(-> (epupp.fs/ls)\n                                   (.then (fn [scripts]\n                                            (let [s (first (filter #(= (:fs/name %) \"id_preserve_test.cljs\") scripts))]\n                                              (pr-str (:fs/id s))))))"))]
    (-> (expect (.-success id1-result)) (.toBe true))
    (let [id1 (unquote-result (first (.-values id1-result)))]
      (-> (expect id1) (.not.toBeNull))

      ;; Force-save v2 with same name but different content
      (let [test-code-v2 "{:epupp/script-name \"id-preserve-test\"\n                       :epupp/auto-run-match \"https://example.com/*\"}\n                      (ns id-test)\n                      (js/console.log \"Version 2 - UPDATED\")"
            save2-result (js-await (eval-in-browser
                                    (str "(-> (epupp.fs/save! " (pr-str test-code-v2) " {:fs/force? true})\n"
                                         "  (.then (fn [_] :v2-done)))\n")))]
        (-> (expect (.-success save2-result)) (.toBe true)))

      ;; Wait a moment for storage to sync
      (js-await (sleep 100))

      ;; Get script ID again
      (let [id2-result (js-await (eval-in-browser
                                  "(-> (epupp.fs/ls)\n                                     (.then (fn [scripts]\n                                              (let [s (first (filter #(= (:fs/name %) \"id_preserve_test.cljs\") scripts))]\n                                                (pr-str (:fs/id s))))))"))]
        (-> (expect (.-success id2-result)) (.toBe true))
        (let [id2 (unquote-result (first (.-values id2-result)))]
          ;; IDs MUST be equal - the force save should update, not delete+create
          ;; This assertion should FAIL to expose the bug
          (-> (expect id1) (.toBe id2))))))

  ;; Cleanup
  (let [cleanup-result (js-await (eval-in-browser
                                  "(-> (epupp.fs/rm! \"id_preserve_test.cljs\")\n                                     (.then (fn [_] :cleanup-done))\n                                     (.catch (fn [_] :cleanup-done)))"))]
    (-> (expect (.-success cleanup-result)) (.toBe true))))

(defn- ^:async test_save_rejects_reserved_namespace_with_clear_error []
  (let [test-code "{:epupp/script-name \"epupp/my-script.cljs\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns bad-namespace)"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-reserved-result (atom :pending))\n"
                                     "(-> (epupp.fs/save! " (pr-str test-code) ")\n"
                                     "  (.then (fn [r] (reset! !save-reserved-result {:resolved r})))\n"
                                     "  (.catch (fn [e] (reset! !save-reserved-result {:rejected (.-message e)}))))\n"
                                     ":setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!save-reserved-result)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            (-> (expect (.includes result-str "rejected"))
                (.toBe true (str "Expected rejection, got: " result-str)))
            (-> (expect (.includes result-str "reserved"))
                (.toBe true (str "Expected reserved error, got: " result-str)))
            (-> (expect (.includes result-str "Missing")) (.toBe false)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save reserved namespace result"))
            (do (js-await (sleep 20)) (recur))))))))

(defn- ^:async test_save_rejects_path_traversal_names []
  (let [init-result (js-await (eval-in-browser "(def !save-path-results (atom {}))"))]
    (-> (expect (.-success init-result)) (.toBe true)))

  (doseq [[label name-pattern]
          [["leading slash" "/absolute/path.cljs"]
           ["dot-slash prefix" "./relative.cljs"]
           ["dot-dot-slash prefix" "../parent.cljs"]
           ["dot-dot-slash in middle" "foo/../bar.cljs"]]]
    (let [test-code (str "{:epupp/script-name \"" name-pattern "\"\n"
                         " :epupp/auto-run-match \"https://example.com/*\"}\n"
                         "(ns path-traversal-test)")
          label-key (pr-str label)
          setup-result (js-await (eval-in-browser
                                  (str "(swap! !save-path-results assoc " label-key " :pending)\n"
                                       "(-> (epupp.fs/save! " (pr-str test-code) ")\n"
                                       "  (.then (fn [r] (swap! !save-path-results assoc " label-key " {:resolved r})))\n"
                                       "  (.catch (fn [e] (swap! !save-path-results assoc " label-key " {:rejected (.-message e)}))))\n"
                                       ":setup-done")))]
      (-> (expect (.-success setup-result)) (.toBe true))

      (let [start (.now js/Date)
            timeout-ms 3000]
        (loop []
          (let [check-result (js-await (eval-in-browser (str "(pr-str (get @!save-path-results " label-key "))")))]
            (if (and (.-success check-result)
                     (seq (.-values check-result))
                     (not= (first (.-values check-result)) ":pending"))
              (let [result-str (first (.-values check-result))]
                (-> (expect (.includes result-str "rejected"))
                    (.toBe true (str "Expected rejection for: " label)))
                (let [expected (if (= label "leading slash")
                                 "Script name cannot start with '/'"
                                 "Script name cannot contain './' or '../'")]
                  (-> (expect (.includes result-str expected))
                      (.toBe true (str "Expected '" expected "', got: " result-str)))))
              (if (> (- (.now js/Date) start) timeout-ms)
                (throw (js/Error. (str "Timeout waiting for save path traversal result: " label)))
                (do (js-await (sleep 20)) (recur))))))))))

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
                   test_save_with_force_rejects_builtin_script_names)

             (test "REPL FS: save - rejects reserved namespace with clear error"
                   test_save_rejects_reserved_namespace_with_clear_error)

             (test "REPL FS: save - rejects path traversal names"
                   test_save_rejects_path_traversal_names)

             (test "REPL FS: save - force update preserves script ID"
                   test_save_force_update_preserves_script_id)))
