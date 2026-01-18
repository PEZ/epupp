(ns e2e.fs-ui-reactivity-test
  "E2E tests for UI reactivity to REPL file system operations.
   Tests that popup and panel automatically refresh when scripts
   are created/renamed/deleted via epupp.fs/* functions."
  (:require ["@playwright/test" :refer [test expect chromium]]
            ["net" :as net]
            ["path" :as path]
            [fixtures :refer [http-port nrepl-port-1 ws-port-1
                              assert-no-errors! wait-for-script-count
                              wait-for-popup-ready clear-fs-scripts]]))

(def ^:private !context (atom nil))
(def ^:private !ext-id (atom nil))

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

(defn ^:async wait-for-eval-promise
  "Wait for a REPL evaluation result stored in an atom."
  [atom-name timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [check-result (js-await (eval-in-browser (str "(pr-str @" atom-name ")")))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (first (.-values check-result))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. (str "Timeout waiting for " atom-name)))
            (do
              (js-await (sleep 50))
              (recur))))))))

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
    (let [ext-id (js-await (get-extension-id ctx))]
      (reset! !ext-id ext-id)
      ;; Load test page
      (let [test-page (js-await (.newPage ctx))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html")))
        (js-await (.waitForLoadState test-page "domcontentloaded"))
        ;; Connect via popup
        (let [bg-page (js-await (.newPage ctx))]
          (js-await (.goto bg-page
                           (str "chrome-extension://" ext-id "/popup.html")
                           #js {:waitUntil "networkidle"}))
          (js-await (clear-fs-scripts bg-page))
          ;; Enable FS REPL Sync for write tests via runtime message
          (js-await (send-runtime-message bg-page "e2e/set-storage" #js {:key "fsReplSyncEnabled" :value true}))
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
              (js-await (wait-for-script-tag "scittle" 5000)))))))))

(defn- ^:async popup_refreshes_script_list_after_save []
  ;; Open popup and count initial scripts
  (let [popup (js-await (.newPage @!context))]
    (js-await (.goto popup
                     (str "chrome-extension://" @!ext-id "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (wait-for-popup-ready popup))

    ;; Get initial script count
    (let [initial-items (.locator popup ".script-item")
          initial-count (js-await (.count initial-items))]
      (js/console.log "=== Initial script count ===" initial-count)

      ;; Create a new script via REPL fs API
      (let [test-code "{:epupp/script-name \"ui-reactivity-test-script\"
                                           :epupp/site-match \"https://reactivity-test.com/*\"}
                                          (ns ui-reactivity-test)"
            save-code (str "(def !ui-save-result (atom :pending))
                                         (-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})
                                           (.then (fn [r] (reset! !ui-save-result r))))
                                         :setup-done")]
        (let [setup-result (js-await (eval-in-browser save-code))]
          (-> (expect (.-success setup-result)) (.toBe true)))

        ;; Wait for save to complete
        (let [save-result (js-await (wait-for-eval-promise "!ui-save-result" 3000))]
          (js/console.log "=== Save result ===" save-result)
          (-> (expect (.includes save-result ":success true")) (.toBe true))))

      ;; Popup should automatically refresh and show the new script
      ;; Use Playwright's auto-waiting to poll for the new element
      (js-await (-> (expect (.locator popup ".script-item:has-text(\"ui_reactivity_test_script.cljs\")"))
                    (.toBeVisible #js {:timeout 2000})))

      ;; Script count should have increased by 1
      (js-await (wait-for-script-count popup (inc initial-count))))

    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))

(defn- ^:async popup_refreshes_after_rm []
  ;; First create a script to delete
  (let [test-code "{:epupp/script-name \"script-to-delete\"
                                      :epupp/site-match \"https://delete-test.com/*\"}
                                      (ns script-to-delete)"
        save-code (str "(def !delete-setup (atom :pending))
                                     (-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})
                                       (.then (fn [r] (reset! !delete-setup r))))
                                     :setup-done")]
    (let [setup-result (js-await (eval-in-browser save-code))]
      (-> (expect (.-success setup-result)) (.toBe true)))
    (js-await (wait-for-eval-promise "!delete-setup" 3000)))

  ;; Open popup and verify script exists
  (let [popup (js-await (.newPage @!context))]
    (js-await (.goto popup
                     (str "chrome-extension://" @!ext-id "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (wait-for-popup-ready popup))

    ;; Verify script exists
    (js-await (-> (expect (.locator popup ".script-item:has-text(\"script_to_delete.cljs\")"))
                  (.toBeVisible #js {:timeout 2000})))

    (let [initial-count (js-await (.count (.locator popup ".script-item")))]
      ;; Delete the script via REPL
      (let [delete-code "(def !rm-ui-result (atom :pending))
                                  (-> (epupp.fs/rm! \"script_to_delete.cljs\")
                                    (.then (fn [r] (reset! !rm-ui-result r))))
                                  :setup-done"]
        (let [del-result (js-await (eval-in-browser delete-code))]
          (-> (expect (.-success del-result)) (.toBe true)))
        (js-await (wait-for-eval-promise "!rm-ui-result" 3000)))

      ;; Popup should automatically refresh and remove the script
      (js-await (-> (expect (.locator popup ".script-item:has-text(\"script_to_delete.cljs\")"))
                    (.not.toBeVisible #js {:timeout 2000})))

      ;; Script count should have decreased
      (js-await (wait-for-script-count popup (dec initial-count))))

    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))

(defn- ^:async popup_refreshes_after_mv []
  ;; First create a script to rename
  (let [test-code "{:epupp/script-name \"script-to-rename\"
                                       :epupp/site-match \"https://rename-test.com/*\"}
                                      (ns script-to-rename)"
        save-code (str "(def !rename-setup (atom :pending))
                                     (-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})
                                       (.then (fn [r] (reset! !rename-setup r))))
                                     :setup-done")]
    (let [setup-result (js-await (eval-in-browser save-code))]
      (-> (expect (.-success setup-result)) (.toBe true)))
    (js-await (wait-for-eval-promise "!rename-setup" 3000)))

  ;; Open popup and verify script exists
  (let [popup (js-await (.newPage @!context))]
    (js-await (.goto popup
                     (str "chrome-extension://" @!ext-id "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (wait-for-popup-ready popup))

    ;; Verify old name exists
    (js-await (-> (expect (.locator popup ".script-item:has-text(\"script_to_rename.cljs\")"))
                  (.toBeVisible #js {:timeout 2000})))

    ;; Rename the script via REPL
    (let [rename-code "(def !mv-ui-result (atom :pending))
                                  (-> (epupp.fs/mv! \"script_to_rename.cljs\" \"renamed_via_repl.cljs\" {:fs/force? true})
                                    (.then (fn [r] (reset! !mv-ui-result r))))
                                  :setup-done"]
      (let [mv-result (js-await (eval-in-browser rename-code))]
        (-> (expect (.-success mv-result)) (.toBe true)))
      (js-await (wait-for-eval-promise "!mv-ui-result" 3000)))

    ;; Popup should show new name, hide old name
    (js-await (-> (expect (.locator popup ".script-item:has-text(\"renamed_via_repl.cljs\")"))
                  (.toBeVisible #js {:timeout 2000})))
    (js-await (-> (expect (.locator popup ".script-item:has-text(\"script_to_rename.cljs\")"))
                  (.not.toBeVisible)))

    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))

(defn- ^:async failed_fs_operation_rejects_promise []
  ;; Try to delete a non-existent script - should reject
  ;; Ensure the script does not exist (defensive cleanup for parallel runs)
  (let [cleanup-code "(-> (epupp.fs/rm! \"nonexistent_script_12345.cljs\")
                          (.catch (fn [_] nil)))"]
    (js-await (eval-in-browser cleanup-code))
    (js-await (sleep 50)))
  (let [delete-code "(def !rm-nonexistent-result (atom :pending))
                                        (-> (epupp.fs/rm! \"nonexistent_script_12345.cljs\")
                                            (.then (fn [r] (reset! !rm-nonexistent-result {:resolved r})))
                                            (.catch (fn [e] (reset! !rm-nonexistent-result {:rejected (.-message e)}))))
                                        :setup-done"]
    (let [res (js-await (eval-in-browser delete-code))]
      (-> (expect (.-success res)) (.toBe true)))

    (let [out (js-await (wait-for-eval-promise "!rm-nonexistent-result" 3000))]
      (-> (expect (.includes out "rejected")) (.toBe true))
      (-> (expect (or (.includes out "Script not found")
                      (.includes out "not found")
                      (.includes out "does not exist")))
          (.toBe true)))))

(defn- ^:async no_uncaught_errors_during_ui_reactivity_tests []
  (let [popup (js-await (.newPage @!context))]
    (js-await (.goto popup (str "chrome-extension://" @!ext-id "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))

(.describe test "REPL FS UI: reactivity"
           (fn []
             (.beforeAll test (fn [] (setup-browser!)))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS UI: popup refreshes script list after epupp.fs/save"
                   popup_refreshes_script_list_after_save)

             (test "REPL FS UI: popup refreshes after epupp.fs/rm"
                   popup_refreshes_after_rm)

             (test "REPL FS UI: popup refreshes after epupp.fs/mv"
                   popup_refreshes_after_mv)

             (test "REPL FS UI: failed FS operation rejects promise"
                   failed_fs_operation_rejects_promise)

             ;; Final error check
             (test "REPL FS UI: no uncaught errors during UI reactivity tests"
                   no_uncaught_errors_during_ui_reactivity_tests)))
