(ns e2e.fs-test
  "E2E tests for REPL file system API.
   Tests epupp.fs/cat, epupp.fs/ls, epupp.fs/save!, epupp.fs/mv!, epupp.fs/rm!

   These test the full pipeline: nREPL client -> browser-nrepl -> extension -> Scittle -> page"
  (:require ["@playwright/test" :refer [test expect chromium]]
            ["net" :as net]
            ["path" :as path]
            [fixtures :refer [http-port nrepl-port-1 ws-port-1
                              assert-no-errors!]]))

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
              ;; Check if response contains "done" status (bencode format)
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
      ;; Load test page
      (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html")))
      (js-await (.waitForLoadState test-page "domcontentloaded"))
      ;; Open popup to send messages to background worker
      (let [bg-page (js-await (.newPage ctx))]
        (js-await (.goto bg-page
                         (str "chrome-extension://" ext-id "/popup.html")
                         #js {:waitUntil "networkidle"}))
        ;; Find test page tab ID
        (let [find-result (js-await (send-runtime-message
                                     bg-page "e2e/find-tab-id"
                                     #js {:urlPattern "http://localhost:*/*"}))]
          (when-not (and find-result (.-success find-result))
            (throw (js/Error. (str "Could not find test tab: " (.-error find-result)))))
          ;; Connect to test page
          (let [connect-result (js-await (send-runtime-message
                                          bg-page "connect-tab"
                                          #js {:tabId (.-tabId find-result)
                                               :wsPort ws-port-1}))]
            (when-not (and connect-result (.-success connect-result))
              (throw (js/Error. (str "Connection failed: " (.-error connect-result)))))
            (js-await (.close bg-page))
            ;; Wait for Scittle to be available
            (js-await (wait-for-script-tag "scittle" 5000))))))))

(.describe test "REPL File System"
           (fn []
             (.beforeAll test (fn [] (setup-browser!)))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "epupp.fs/cat retrieves script code by name"
                   (^:async fn []
                     ;; First verify epupp.fs.fs namespace is available
                     (let [ns-check (js-await (eval-in-browser "(fn? epupp.fs/cat)"))]
                       (js/console.log "=== epupp.fs/cat exists? ===" (js/JSON.stringify ns-check))
                       (-> (expect (.-success ns-check)) (.toBe true))
                       (-> (expect (.-values ns-check)) (.toContain "true")))

                     ;; Call cat and store the promise, then check its result
                     ;; We need to wait for the promise to resolve, so use .then
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !cat-result (atom nil))
                                                    (-> (epupp.fs/cat \"GitHub Gist Installer (Built-in)\")
                                                        (.then (fn [code] (reset! !cat-result code))))
                                                    :pending"))]
                       (js/console.log "=== setup result ===" (js/JSON.stringify setup-result))
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll until the result is available (promise resolved)
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "@!cat-result"))]
                           (js/console.log "=== check result ===" (js/JSON.stringify check-result))
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) "nil"))
                             ;; Success - we have the code
                             (-> (expect (.some (.-values check-result)
                                                (fn [v] (.includes v "epupp/script-name"))))
                                 (.toBe true))
                             ;; Not ready yet - check timeout
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for epupp.fs/cat result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/cat returns nil for non-existent script"
                   (^:async fn []
                     ;; Setup and call cat for non-existent script
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !cat-nil-result (atom :pending))
                                                    (-> (epupp.fs/cat \"does-not-exist.cljs\")
                                                        (.then (fn [code] (reset! !cat-nil-result code))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll until result available
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "@!cat-nil-result"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             ;; Result received - should be nil
                             (-> (expect (.-values check-result)) (.toContain "nil"))
                             ;; Not ready yet
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for epupp.fs/cat nil result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/ls lists all scripts with metadata"
                   (^:async fn []
                     ;; First verify ls function exists
                     (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/ls)"))]
                       (-> (expect (.-success fn-check)) (.toBe true))
                       (-> (expect (.-values fn-check)) (.toContain "true")))

                     ;; Call ls and store promise result
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !ls-result (atom :pending))
                                                    (-> (epupp.fs/ls)
                                                        (.then (fn [scripts] (reset! !ls-result scripts))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll until result available
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!ls-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             ;; Result received - should be a vector with at least the gist installer
                             (let [result-str (first (.-values check-result))]
                               ;; Should contain the built-in gist installer script
                               (-> (expect (.includes result-str "GitHub Gist Installer")) (.toBe true))
                               ;; Should have :name key (within #:fs{...} namespace map syntax)
                               (-> (expect (.includes result-str ":name")) (.toBe true))
                               ;; Should have :enabled key
                               (-> (expect (.includes result-str ":enabled")) (.toBe true))
                               ;; Should have :match key
                               (-> (expect (.includes result-str ":match")) (.toBe true)))
                             ;; Not ready yet
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for epupp.fs/ls result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/save! creates new script from code with manifest"
                   (^:async fn []
                     ;; First verify save! function exists
                     (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/save!)"))]
                       (-> (expect (.-success fn-check)) (.toBe true))
                       (-> (expect (.-values fn-check)) (.toContain "true")))

                     ;; Save a new script
                     (let [test-code "{:epupp/script-name \"test-script-from-repl\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns test-script)
                                      (js/console.log \"Hello from test script!\")"
                           ;; Use pr-str to properly escape the code string
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !save-result (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) ")
                                                             (.then (fn [r] (reset! !save-result r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll until result available
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!save-result)"))]
                           (js/console.log "=== save result ===" (js/JSON.stringify check-result))
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             ;; Result received - should have :success true (within #:fs{...} namespace map syntax)
                             (let [result-str (first (.-values check-result))]
                               (js/console.log "=== result-str ===" result-str)
                               (-> (expect (.includes result-str ":success true")) (.toBe true))
                               (-> (expect (.includes result-str "test_script_from_repl.cljs")) (.toBe true)))
                             ;; Not ready yet
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for epupp.fs/save! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     ;; Verify script appears in ls
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
                             ;; Should now include our test script
                             (-> (expect (.includes (first (.-values check-result)) "test_script_from_repl.cljs"))
                                 (.toBe true))
                             ;; Not ready yet
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for ls after save"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/mv! renames a script"
                   (^:async fn []
                     ;; First verify mv! function exists
                     (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/mv!)"))]
                       (-> (expect (.-success fn-check)) (.toBe true))
                       (-> (expect (.-values fn-check)) (.toContain "true")))

                     ;; Create a script to rename
                     (let [test-code "{:epupp/script-name \"rename-test-original\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns rename-test)"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !mv-setup (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) ")
                                                             (.then (fn [r] (reset! !mv-setup r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Wait for save to complete
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

                     ;; Now rename the script
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !mv-result (atom :pending))
                                                    (-> (epupp.fs/mv! \"rename_test_original.cljs\" \"renamed_script.cljs\")
                                                        (.then (fn [r] (reset! !mv-result r))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll for result
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!mv-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             ;; Should have success (within #:fs{...} namespace map syntax)
                             (-> (expect (.includes (first (.-values check-result)) ":success true"))
                                 (.toBe true))
                             ;; Not ready yet
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for epupp.fs/mv! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     ;; Verify new name appears in ls and old doesn't
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
                               ;; Should have new name
                               (-> (expect (.includes result-str "renamed_script.cljs")) (.toBe true))
                               ;; Should NOT have old name
                               (-> (expect (.includes result-str "rename_test_original.cljs")) (.toBe false)))
                             ;; Not ready yet
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for ls after mv"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/rm! deletes a script"
                   (^:async fn []
                     ;; First verify rm! function exists
                     (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/rm!)"))]
                       (-> (expect (.-success fn-check)) (.toBe true))
                       (-> (expect (.-values fn-check)) (.toContain "true")))

                     ;; Create a script to delete
                     (let [test-code "{:epupp/script-name \"delete-test-script\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns delete-test)"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !rm-setup (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) ")
                                                             (.then (fn [r] (reset! !rm-setup r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Wait for save to complete
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

                     ;; Verify script exists before delete
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
                             ;; Should have the script
                             (-> (expect (.includes (first (.-values check-result)) "delete_test_script.cljs"))
                                 (.toBe true))
                             ;; Not ready yet
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for ls before rm"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     ;; Delete the script
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !rm-result (atom :pending))
                                                    (-> (epupp.fs/rm! \"delete_test_script.cljs\")
                                                        (.then (fn [r] (reset! !rm-result r))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll for result
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!rm-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             ;; Should have success (within #:fs{...} namespace map syntax)
                             (-> (expect (.includes (first (.-values check-result)) ":success true"))
                                 (.toBe true))
                             ;; Not ready yet
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for epupp.fs/rm! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     ;; Verify script no longer appears in ls
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
                             ;; Should NOT have the deleted script
                             (-> (expect (.includes (first (.-values check-result)) "delete_test_script.cljs"))
                                 (.toBe false))
                             ;; Not ready yet
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for ls after rm"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/rm! rejects deleting built-in scripts"
                   (^:async fn []
                     ;; Try to delete the built-in gist installer
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !rm-builtin-result (atom :pending))
                                                    (-> (epupp.fs/rm! \"GitHub Gist Installer (Built-in)\")
                                                        (.then (fn [r] (reset! !rm-builtin-result r))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll for result
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!rm-builtin-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               ;; Should have success false (within #:fs{...} namespace map syntax)
                               (-> (expect (.includes result-str ":success false"))
                                   (.toBe true))
                               ;; Should have error about built-in
                               (-> (expect (.includes result-str "built-in"))
                                   (.toBe true)))
                             ;; Not ready yet
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for epupp.fs/rm! built-in result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))
             (test "epupp.fs/save! with {:enabled false} creates disabled script"
                   (^:async fn []
                     ;; Save a script with enabled: false option
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

                     ;; Poll until save completes
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

                     ;; Verify script was created with enabled: false
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
                               ;; Find the disabled script and verify it has :fs/enabled false
                               (-> (expect (.includes result-str "disabled_by_default.cljs"))
                                   (.toBe true))
                               ;; The script entry should have :enabled false
                               ;; Note: we can't easily parse the pr-str output, so we check
                               ;; that the pattern matches a disabled state
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

                     ;; Cleanup
                     (js-await (eval-in-browser "(epupp.fs/rm! \"disabled_by_default.cljs\")"))))

             (test "epupp.fs/cat with vector returns map of names to codes"
                   (^:async fn []
                     ;; Test bulk cat: (cat ["name1" "name2"]) returns {"name1" "code" "name2" nil}
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !bulk-cat-result (atom :pending))
                                                    (-> (epupp.fs/cat [\"GitHub Gist Installer (Built-in)\" \"does-not-exist.cljs\"])
                                                        (.then (fn [result] (reset! !bulk-cat-result result))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll until result available
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-cat-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               ;; Should be a map with two entries
                               ;; The built-in script should have code
                               (-> (expect (.includes result-str "GitHub Gist Installer (Built-in)"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "epupp/script-name"))
                                   (.toBe true))
                               ;; The non-existent script should have nil
                               (-> (expect (.includes result-str "does-not-exist.cljs"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "nil"))
                                   (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for bulk cat result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/rm! with vector returns map of per-item results"
                   (^:async fn []
                     ;; Create two test scripts
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

                     ;; Wait for both scripts to be created
                     (js-await (sleep 100))

                     ;; Now bulk delete them
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !bulk-rm-result (atom :pending))
                                                    (-> (epupp.fs/rm! [\"bulk_rm_test_1.cljs\" \"bulk_rm_test_2.cljs\" \"does-not-exist.cljs\"])
                                                        (.then (fn [result] (reset! !bulk-rm-result result))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll for result
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-rm-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               ;; Should be a map with three entries
                               ;; The first two should succeed
                               (-> (expect (.includes result-str "bulk_rm_test_1.cljs"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "bulk_rm_test_2.cljs"))
                                   (.toBe true))
                               ;; Verify at least some success
                               (-> (expect (.includes result-str ":success true"))
                                   (.toBe true))
                               ;; The non-existent script should have an error
                               (-> (expect (.includes result-str "does-not-exist.cljs"))
                                   (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for bulk rm! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     ;; Verify scripts are actually deleted
                     (let [ls-result (js-await (eval-in-browser
                                                "(def !ls-after-bulk-rm (atom :pending))
                                                 (-> (epupp.fs/ls)
                                                     (.then (fn [scripts] (reset! !ls-after-bulk-rm scripts))))
                                                 :setup-done"))]
                       (-> (expect (.-success ls-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!ls-after-bulk-rm)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               ;; Neither deleted script should appear
                               (-> (expect (.includes result-str "bulk_rm_test_1.cljs"))
                                   (.toBe false))
                               (-> (expect (.includes result-str "bulk_rm_test_2.cljs"))
                                   (.toBe false)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for ls after bulk rm"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

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

                     ;; Poll for result
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-save-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               ;; Should be a map with numeric indices (0, 1)
                               (-> (expect (.includes result-str "0"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "1"))
                                   (.toBe true))
                               ;; Both should succeed
                               (-> (expect (.includes result-str ":success true"))
                                   (.toBe true))
                               ;; Should have the script names
                               (-> (expect (.includes result-str "bulk_save_test_1.cljs"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "bulk_save_test_2.cljs"))
                                   (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for bulk save! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     ;; Verify scripts are actually created by checking ls
                     (let [ls-result (js-await (eval-in-browser
                                                "(def !ls-after-bulk-save (atom :pending))
                                                 (-> (epupp.fs/ls)
                                                     (.then (fn [scripts] (reset! !ls-after-bulk-save scripts))))
                                                 :setup-done"))]
                       (-> (expect (.-success ls-result)) (.toBe true)))

                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!ls-after-bulk-save)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               ;; Both scripts should appear
                               (-> (expect (.includes result-str "bulk_save_test_1.cljs"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "bulk_save_test_2.cljs"))
                                   (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for ls after bulk save"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     ;; Clean up
                     (js-await (eval-in-browser
                                "(-> (js/Promise.all #js [(epupp.fs/rm! \"bulk_save_test_1.cljs\")
                                                          (epupp.fs/rm! \"bulk_save_test_2.cljs\")])
                                    (.then (fn [_] :cleaned-up)))"))
                     (js-await (sleep 100))))

             (test "epupp.fs/rm! with {:confirm false} returns result with :fs/name"
                   (^:async fn []
                     ;; Create a test script first
                     (let [test-code "{:epupp/script-name \"confirm-test-rm\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns confirm-test)"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !confirm-rm-setup (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) ")
                                                             (.then (fn [r] (reset! !confirm-rm-setup r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Wait for save to complete
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

                     ;; Delete with :confirm false option
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !confirm-rm-result (atom :pending))
                                                    (-> (epupp.fs/rm! \"confirm_test_rm.cljs\" {:confirm false})
                                                        (.then (fn [r] (reset! !confirm-rm-result r))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll for result
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!confirm-rm-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               ;; Should have :fs/success true
                               (-> (expect (.includes result-str ":success true"))
                                   (.toBe true))
                               ;; Should have :fs/name with the script name
                               (-> (expect (.includes result-str ":name"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "confirm_test_rm.cljs"))
                                   (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for rm! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp.fs/mv! with {:confirm false} returns result with :fs/from-name and :fs/to-name"
                   (^:async fn []
                     ;; Create a test script first
                     (let [test-code "{:epupp/script-name \"confirm-test-mv\"
                                       :epupp/site-match \"https://example.com/*\"}
                                      (ns confirm-test)"
                           setup-result (js-await (eval-in-browser
                                                   (str "(def !confirm-mv-setup (atom :pending))
                                                         (-> (epupp.fs/save! " (pr-str test-code) ")
                                                             (.then (fn [r] (reset! !confirm-mv-setup r))))
                                                         :setup-done")))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Wait for save to complete
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

                     ;; Rename with :confirm false option
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !confirm-mv-result (atom :pending))
                                                    (-> (epupp.fs/mv! \"confirm_test_mv.cljs\" \"mv_renamed.cljs\" {:confirm false})
                                                        (.then (fn [r] (reset! !confirm-mv-result r))))
                                                    :setup-done"))]
                       (-> (expect (.-success setup-result)) (.toBe true)))

                     ;; Poll for result
                     (let [start (.now js/Date)
                           timeout-ms 3000]
                       (loop []
                         (let [check-result (js-await (eval-in-browser "(pr-str @!confirm-mv-result)"))]
                           (if (and (.-success check-result)
                                    (seq (.-values check-result))
                                    (not= (first (.-values check-result)) ":pending"))
                             (let [result-str (first (.-values check-result))]
                               ;; Should have :fs/success true
                               (-> (expect (.includes result-str ":success true"))
                                   (.toBe true))
                               ;; Should have :fs/from-name
                               (-> (expect (.includes result-str ":from-name"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "confirm_test_mv.cljs"))
                                   (.toBe true))
                               ;; Should have :fs/to-name
                               (-> (expect (.includes result-str ":to-name"))
                                   (.toBe true))
                               (-> (expect (.includes result-str "mv_renamed.cljs"))
                                   (.toBe true)))
                             (if (> (- (.now js/Date) start) timeout-ms)
                               (throw (js/Error. "Timeout waiting for mv! result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))

                     ;; Clean up
                     (js-await (eval-in-browser "(epupp.fs/rm! \"mv_renamed.cljs\")"))
                     (js-await (sleep 100))))

             ;; Final error check
             (test "no uncaught errors during fs tests"
                   (^:async fn []
                     (let [ext-id (js-await (get-extension-id @!context))
                           popup (js-await (.newPage @!context))]
                       (js-await (.goto popup (str "chrome-extension://" ext-id "/popup.html")
                                        #js {:waitUntil "networkidle"}))
                       (js-await (assert-no-errors! popup))
                       (js-await (.close popup)))))))
