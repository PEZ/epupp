(ns e2e.fs-write-test
  "E2E tests for REPL file system write operations: epupp.fs/save!, epupp.fs/mv!, epupp.fs/rm!"
  (:require ["@playwright/test" :refer [test expect chromium]]
            ["net" :as net]
            ["path" :as path]
            [fixtures :refer [http-port nrepl-port-1 ws-port-1 assert-no-errors!]]))

(def ^:private !context (atom nil))

(defn ^:async sleep [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async get-extension-id [context]
  (let [workers (.serviceWorkers context)]
    (if (pos? (.-length workers))
      (-> (aget workers 0) (.url) (.split "/") (aget 2))
      (let [sw (js-await (.waitForEvent context "serviceworker"))]
        (-> (.url sw) (.split "/") (aget 2))))))

(defn ^:async send-runtime-message [page msg-type data]
  (.evaluate page
             (fn [opts]
               (js/Promise.
                (fn [resolve]
                  (js/chrome.runtime.sendMessage
                   (js/Object.assign #js {:type (.-type opts)} (.-data opts))
                   resolve))))
             #js {:type msg-type :data (or data #js {})}))

(defn ^:async eval-in-browser
  "Evaluate code via nREPL on server 1. Returns {:success bool :values [...] :error str}"
  [code]
  (js/Promise.
   (fn [resolve]
     (let [client (.createConnection net #js {:port nrepl-port-1 :host "localhost"})
           !response (atom "")]
       (.on client "data"
            (fn [data]
              (swap! !response str (.toString data))
              (when (.includes @!response "4:done")
                (.destroy client)
                (let [response @!response
                      values (atom [])
                      value-regex (js/RegExp. "5:value(\\d+):" "g")]
                  (loop [match (.exec value-regex response)]
                    (when match
                      (let [len (js/parseInt (aget match 1))
                            start-idx (+ (.-index match) (.-length (aget match 0)))
                            value (.substring response start-idx (+ start-idx len))]
                        (swap! values conj value))
                      (recur (.exec value-regex response))))
                  (let [success (not (or (.includes response "2:ex")
                                         (.includes response "3:err")))
                        error (when-not success
                                (let [err-match (.match response (js/RegExp. "3:err(\\d+):"))]
                                  (if err-match
                                    (let [err-len (js/parseInt (aget err-match 1))
                                          err-start (+ (.-index err-match) (.-length (aget err-match 0)))]
                                      (.substring response err-start (+ err-start err-len)))
                                    "Unknown error")))]
                    (resolve #js {:success success
                                  :values @values
                                  :error error}))))))
       (.on client "error"
            (fn [err]
              (resolve #js {:success false :error (.-message err)})))
       (let [msg (str "d2:op4:eval4:code" (.-length code) ":" code "e")]
         (.write client msg))))))

(defn ^:async wait-for-script-tag
  "Poll the page via nREPL until a script tag matching the pattern appears."
  [pattern timeout-ms]
  (let [start (.now js/Date)
        poll-interval 30
        check-code (str "(pos? (.-length (js/document.querySelectorAll \"script[src*='" pattern "']\")))")
        check-fn (fn check []
                   (js/Promise.
                    (fn [resolve reject]
                      (-> (eval-in-browser check-code)
                          (.then (fn [result]
                                   (if (and (.-success result)
                                            (= (first (.-values result)) "true"))
                                     (resolve true)
                                     (if (> (- (.now js/Date) start) timeout-ms)
                                       (reject (js/Error. (str "Timeout waiting for script: " pattern)))
                                       (-> (sleep poll-interval)
                                           (.then #(resolve (check))))))))
                          (.catch reject)))))]
    (js-await (check-fn))))

(defn ^:async setup-browser! []
  (let [extension-path (.resolve path "dist/chrome")
        ctx (js-await (.launchPersistentContext
                       chromium ""
                       #js {:headless false
                            :args #js ["--no-sandbox"
                                       "--allow-file-access-from-files"
                                       "--enable-features=ExtensionsManifestV3Only"
                                       (str "--disable-extensions-except=" extension-path)
                                       (str "--load-extension=" extension-path)]}))]
    (reset! !context ctx)
    (let [ext-id (js-await (get-extension-id ctx))
          test-page (js-await (.newPage ctx))]
      (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html")))
      (js-await (.waitForLoadState test-page "domcontentloaded"))
      (let [bg-page (js-await (.newPage ctx))]
        (js-await (.goto bg-page
                         (str "chrome-extension://" ext-id "/popup.html")
                         #js {:waitUntil "networkidle"}))
        (let [find-result (js-await (send-runtime-message
                                     bg-page "e2e/find-tab-id"
                                     #js {:urlPattern "http://localhost:*/*"}))]
          (when-not (and find-result (.-success find-result))
            (throw (js/Error. (str "Could not find test tab: " (.-error find-result)))))
          (let [connect-result (js-await (send-runtime-message
                                          bg-page "connect-tab"
                                          #js {:tabId (.-tabId find-result)
                                               :wsPort ws-port-1}))]
            (when-not (and connect-result (.-success connect-result))
              (throw (js/Error. (str "Connection failed: " (.-error connect-result)))))
            (js-await (.close bg-page))
            (js-await (wait-for-script-tag "scittle" 5000))))))))

(.describe test "REPL File System - Write Operations"
           (fn []
             (.beforeAll test (fn [] (setup-browser!)))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             ;; ============== save! tests ==============

             (test "epupp.fs/save! creates new script from code with manifest"
                   (^:async fn []
                     (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/save!)"))]
                       (-> (expect (.-success fn-check)) (.toBe true))
                       (-> (expect (.-values fn-check)) (.toContain "true")))

                     (let [test-code "{:epupp/script-name \"test-script-from-repl\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns test-script)
                                      (js/console.log \"Hello from test script!\")"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !save-result (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) ")
                                                             (.then (fn [r] (reset! !save-result r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!save-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               (-> (expect (.includes result-str ":success true")) (.toBe true))
                               (-> (expect (.includes result-str "test_script_from_repl.cljs")) (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for epupp.fs/save! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !ls-after-save (atom :pending))
                                                    (-> (epupp.fs/ls)
                                                        (.then (fn [scripts] (reset! !ls-after-save scripts))))
                                                    :setup-done"))]
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
                                 (recur)))))))))

             (test "epupp.fs/save! with {:enabled false} creates disabled script"
                   (^:async fn []
                     (let [test-code "{:epupp/script-name \"disabled-by-default\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns disabled-test)
                                      (js/console.log \"Should be disabled!\")"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !save-disabled (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) " {:enabled false})
                                                             (.then (fn [r] (reset! !save-disabled r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!save-disabled)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (-> (expect (.includes (first (.-values check-result)) ":success true"))
                                 (.toBe true))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for save"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !ls-check-disabled (atom :pending))
                                                    (-> (epupp.fs/ls)
                                                        (.then (fn [scripts] (reset! !ls-check-disabled scripts))))
                                                    :setup-done"))]
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
                                                              "(some (fn [s] (and (= (:fs/name s) \"disabled_by_default.cljs\")
                                                                                   (false? (:fs/enabled s))))
                                                                     @!ls-check-disabled)"))]
                                 (-> (expect (.-success scripts-check)) (.toBe true))
                                 (-> (expect (.-values scripts-check)) (.toContain "true"))))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for ls"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     (js-await (eval-in-browser "(epupp.fs/rm! \"disabled_by_default.cljs\")"))))

             (test "epupp.fs/save! with vector returns map of per-item results"
                   (^:async fn []
                     (let [code1 "{:epupp/script-name \"bulk-save-test-1\"
                                   :epupp/site-match \"https://example.com/*\"}
                                  (ns bulk-save-1)"
                           code2 "{:epupp/script-name \"bulk-save-test-2\"
                                   :epupp/site-match \"https://example.com/*\"}
                                  (ns bulk-save-2)"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !bulk-save-result (atom :pending))
                                                         (-> (epupp.fs/save! [" (pr-str code1) " " (pr-str code2) "])
                                                             (.then (fn [result] (reset! !bulk-save-result result))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-save-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
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

                     (js-await (eval-in-browser
                                "(-> (js/Promise.all #js [(epupp.fs/rm! \"bulk_save_test_1.cljs\")
                                                          (epupp.fs/rm! \"bulk_save_test_2.cljs\")])
                                    (.then (fn [_] :cleaned-up)))"))
                     (js-await (sleep 100))))

             ;; ============== mv! tests ==============

             (test "epupp.fs/mv! renames a script"
                   (^:async fn []
                     (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/mv!)"))]
                       (-> (expect (.-success fn-check)) (.toBe true))
                       (-> (expect (.-values fn-check)) (.toContain "true")))

                     (let [test-code "{:epupp/script-name \"rename-test-original\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns rename-test)"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !mv-setup (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) ")
                                                             (.then (fn [r] (reset! !mv-setup r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!mv-setup)"))]
                           (when (or (not (.-success check-result))
                                     (empty? (.-values check-result))
                                     (= (first (.-values check-result)) ":pending"))
                             (when (< (- (.now js/Date) start) timeout-ms)
                               (js-await (sleep 50))
                               (recur))))))

                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !mv-result (atom :pending))
                                                    (-> (epupp.fs/mv! \"rename_test_original.cljs\" \"renamed_script.cljs\")
                                                        (.then (fn [r] (reset! !mv-result r))))
                                                    :setup-done"))]
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
                                 (js-await (sleep 50))
                                 (recur)))))))

                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !ls-after-mv (atom :pending))
                                                    (-> (epupp.fs/ls)
                                                        (.then (fn [scripts] (reset! !ls-after-mv scripts))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!ls-after-mv)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               (-> (expect (.includes result-str "renamed_script.cljs")) (.toBe true))
                               (-> (expect (.includes result-str "rename_test_original.cljs")) (.toBe false)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for ls after mv"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/mv! with {:confirm false} returns result with :fs/from-name and :fs/to-name"
                   (^:async fn []
                     (let [test-code "{:epupp/script-name \"confirm-test-mv\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns confirm-test)"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !confirm-mv-setup (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) ")
                                                             (.then (fn [r] (reset! !confirm-mv-setup r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!confirm-mv-setup)"))]
                           (when (or (not (.-success check-result))
                                     (empty? (.-values check-result))
                                     (= (first (.-values check-result)) ":pending"))
                             (when (< (- (.now js/Date) start) timeout-ms)
                               (js-await (sleep 50))
                               (recur))))))

                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !confirm-mv-result (atom :pending))
                                                    (-> (epupp.fs/mv! \"confirm_test_mv.cljs\" \"mv_renamed.cljs\" {:confirm false})
                                                        (.then (fn [r] (reset! !confirm-mv-result r))))
                                                    :setup-done"))]
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
                               (-> (expect (.includes result-str "confirm_test_mv.cljs"))
                                   (.toBe true))
                               (-> (expect (.includes result-str ":to-name"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "mv_renamed.cljs"))
                                   (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for mv! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     (js-await (eval-in-browser "(epupp.fs/rm! \"mv_renamed.cljs\")"))
                     (js-await (sleep 100))))

             ;; ============== rm! tests ==============

             (test "epupp.fs/rm! deletes a script"
                   (^:async fn []
                     (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/rm!)"))]
                       (-> (expect (.-success fn-check)) (.toBe true))
                       (-> (expect (.-values fn-check)) (.toContain "true")))

                     (let [test-code "{:epupp/script-name \"delete-test-script\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns delete-test)"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !rm-setup (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) ")
                                                             (.then (fn [r] (reset! !rm-setup r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!rm-setup)"))]
                           (when (or (not (.-success check-result))
                                     (empty? (.-values check-result))
                                     (= (first (.-values check-result)) ":pending"))
                             (when (< (- (.now js/Date) start) timeout-ms)
                               (js-await (sleep 50))
                               (recur))))))

                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !ls-before-rm (atom :pending))
                                                    (-> (epupp.fs/ls)
                                                        (.then (fn [scripts] (reset! !ls-before-rm scripts))))
                                                    :setup-done"))]
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
                                 (js-await (sleep 50))
                                 (recur)))))))

                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !rm-result (atom :pending))
                                                    (-> (epupp.fs/rm! \"delete_test_script.cljs\")
                                                        (.then (fn [r] (reset! !rm-result r))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!rm-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (-> (expect (.includes (first (.-values check-result)) ":success true"))
                                 (.toBe true))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for epupp.fs/rm! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !ls-after-rm (atom :pending))
                                                    (-> (epupp.fs/ls)
                                                        (.then (fn [scripts] (reset! !ls-after-rm scripts))))
                                                    :setup-done"))]
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
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/rm! rejects deleting built-in scripts"
                   (^:async fn []
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !rm-builtin-result (atom :pending))
                                                    (-> (epupp.fs/rm! \"GitHub Gist Installer (Built-in)\")
                                                        (.then (fn [r] (reset! !rm-builtin-result r))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!rm-builtin-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               (-> (expect (.includes result-str ":success false"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "built-in"))
                                   (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for epupp.fs/rm! built-in result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/rm! with vector returns map of per-item results"
                   (^:async fn []
                     (let [code1 "{:epupp/script-name \"bulk-rm-test-1\"
                                   :epupp/site-match \"https://example.com/*\"}
                                  (ns bulk-rm-1)"
                           code2 "{:epupp/script-name \"bulk-rm-test-2\"
                                   :epupp/site-match \"https://example.com/*\"}
                                  (ns bulk-rm-2)"
                           setup-result (js-await (eval-in-browser
                                                   (str "(-> (js/Promise.all #js [(epupp.fs/save! " (pr-str code1) ")
                                                                                  (epupp.fs/save! " (pr-str code2) ")])
                                                           (.then (fn [_] :done)))")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (js-await (sleep 100))

                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !bulk-rm-result (atom :pending))
                                                    (-> (epupp.fs/rm! [\"bulk_rm_test_1.cljs\" \"bulk_rm_test_2.cljs\" \"does-not-exist.cljs\"])
                                                        (.then (fn [result] (reset! !bulk-rm-result result))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-rm-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               (-> (expect (.includes result-str "bulk_rm_test_1.cljs"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "bulk_rm_test_2.cljs"))
                                   (.toBe true))
                               (-> (expect (.includes result-str ":success true"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "does-not-exist.cljs"))
                                   (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for bulk rm! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/rm! with {:confirm false} returns result with :fs/name"
                   (^:async fn []
                     (let [test-code "{:epupp/script-name \"confirm-test-rm\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns confirm-test)"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !confirm-rm-setup (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) ")
                                                             (.then (fn [r] (reset! !confirm-rm-setup r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!confirm-rm-setup)"))]
                           (when (or (not (.-success check-result))
                                     (empty? (.-values check-result))
                                     (= (first (.-values check-result)) ":pending"))
                             (when (< (- (.now js/Date) start) timeout-ms)
                               (js-await (sleep 50))
                               (recur))))))

                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !confirm-rm-result (atom :pending))
                                                    (-> (epupp.fs/rm! \"confirm_test_rm.cljs\" {:confirm false})
                                                        (.then (fn [r] (reset! !confirm-rm-result r))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!confirm-rm-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               (-> (expect (.includes result-str ":success true"))
                                   (.toBe true))
                               (-> (expect (.includes result-str ":name"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "confirm_test_rm.cljs"))
                                   (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for rm! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             ;; ============== Final error check ==============

             (test "no uncaught errors during fs tests"
                   (^:async fn []
                     (let [ext-id (js-await (get-extension-id @!context))
                           popup (js-await (.newPage @!context))]
                       (js-await (.goto popup (str "chrome-extension://" ext-id "/popup.html")
                                        #js {:waitUntil "networkidle"}))
                       (js-await (assert-no-errors! popup))
                       (js-await (.close popup)))))))
