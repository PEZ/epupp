(ns e2e.fs-ui-reactivity-test
  "E2E tests for UI reactivity to REPL file system operations.
   Tests that popup and panel automatically refresh when scripts
   are created/renamed/deleted via epupp.fs/* functions."
  (:require ["@playwright/test" :refer [test expect chromium]]
            ["net" :as net]
            ["path" :as path]
            [fixtures :refer [http-port nrepl-port-1 ws-port-1
                              assert-no-errors! wait-for-script-count
                              wait-for-popup-ready]]))

(def ^:private !context (atom nil))
(def ^:private !ext-id (atom nil))

(defn unique-basename [prefix]
  (let [ts (.now js/Date)
        rnd (js/Math.floor (* (js/Math.random) 1000000))
        raw (str prefix "_" ts "_" rnd)]
    (-> raw
        (.toLowerCase)
        (.replace (js/RegExp. "[^a-z0-9_]" "g") "_"))))

(defn script-file [base]
  (str base ".cljs"))

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

(.describe test "REPL FS UI Reactivity"
           (fn []
             (.beforeAll test (fn [] (setup-browser!)))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "popup refreshes script list after epupp.fs/save!"
                   (^:async fn []
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
                       (js-await (.close popup)))))

             (test "popup refreshes after epupp.fs/rm!"
                   (^:async fn []
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
                                  (-> (epupp.fs/rm! \"script_to_delete.cljs\" {:fs/force? true})
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
                       (js-await (.close popup)))))

             (test "popup shows confirmation UI for non-force epupp.fs/save!"
                   (^:async fn []
                     ;; Trigger a save without {:fs/force? true} so we get a pending confirmation
                     (let [test-code "{:epupp/script-name \"fs-confirmation-ui-test\"\n                                      :epupp/site-match \"https://confirmation-test.com/*\"}\n                                     (ns fs-confirmation-ui-test)"
                           save-code (str "(def !fs-confirmation-ui-result (atom :pending))\n"
                                          "(-> (epupp.fs/save! " (pr-str test-code) ")\n"
                                          "  (.then (fn [r] (reset! !fs-confirmation-ui-result r))))\n"
                                          ":setup-done")]
                       (let [setup-result (js-await (eval-in-browser save-code))]
                         (-> (expect (.-success setup-result)) (.toBe true)))

                       (let [save-result (js-await (wait-for-eval-promise "!fs-confirmation-ui-result" 3000))]
                         (-> (expect (.includes save-result "pending-confirmation true")) (.toBe true))))

                     ;; Open popup and verify confirmation UI is visible
                     (let [popup (js-await (.newPage @!context))]
                       (js-await (.goto popup
                                        (str "chrome-extension://" @!ext-id "/popup.html")
                                        #js {:waitUntil "networkidle"}))
                       (js-await (wait-for-popup-ready popup))

                       ;; Debug: confirm background returns pending confirmations
                       (let [resp (js-await (send-runtime-message popup "get-fs-confirmations" nil))
                             count-confirmations (when resp (.-confirmations resp))]
                         (-> (expect (and resp (.-success resp))) (.toBe true))
                         (-> (expect (and count-confirmations (> (.-length count-confirmations) 0))) (.toBe true)))

                       (js-await (-> (expect (.locator popup ".fs-confirmations-section"))
                                     (.toBeVisible #js {:timeout 2000})))

                       (js-await (-> (expect (.locator popup ".confirmation-item:has-text(\"fs_confirmation_ui_test.cljs\")"))
                                     (.toBeVisible #js {:timeout 2000})))

                       (js-await (-> (expect (.locator popup ".confirmation-confirm"))
                                     (.toBeVisible #js {:timeout 2000})))
                       (js-await (-> (expect (.locator popup ".confirmation-cancel"))
                                     (.toBeVisible #js {:timeout 2000})))

                       ;; Confirm and verify script shows up in list
                       (js-await (.click (.locator popup ".confirmation-confirm")))
                       (js-await (-> (expect (.locator popup ".script-item:has-text(\"fs_confirmation_ui_test.cljs\")"))
                                     (.toBeVisible #js {:timeout 2000})))

                       (js-await (assert-no-errors! popup))
                       (js-await (.close popup)))))

             (test "popup updates pending confirmation to latest"
                   (^:async fn []
                     (let [from-base (unique-basename "fs_overwrite_src")
                           from-name (script-file from-base)
                           to1 (script-file (unique-basename "fs_overwrite_to1"))
                           to2 (script-file (unique-basename "fs_overwrite_to2"))]
                       ;; Force-save script using manifest :epupp/script-name set to from-base and (ns <from-base>)
                       (let [test-code (str "{:epupp/script-name \"" from-base "\"\n"
                                            "                                      :epupp/site-match \"https://overwrite-test.com/*\"}\n"
                                            "                                     (ns " from-base ")")
                             save-code (str "(def !fs-pending-latest-setup (atom :pending))\n"
                                            "(-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n"
                                            "  (.then (fn [r] (reset! !fs-pending-latest-setup r))))\n"
                                            ":setup-done")]
                         (let [setup-result (js-await (eval-in-browser save-code))]
                           (-> (expect (.-success setup-result)) (.toBe true)))
                         (js-await (wait-for-eval-promise "!fs-pending-latest-setup" 3000)))

                       ;; Trigger non-force mv from from-name -> to1, should produce a pending confirmation
                       (let [mv-code (str "(def !fs-pending-latest-mv-1 (atom :pending))\n"
                                          "(-> (epupp.fs/mv! " (pr-str from-name) " " (pr-str to1) ")\n"
                                          "  (.then (fn [r] (reset! !fs-pending-latest-mv-1 r))))\n"
                                          ":setup-done")]
                         (let [mv-result (js-await (eval-in-browser mv-code))]
                           (-> (expect (.-success mv-result)) (.toBe true)))
                         (let [mv-out (js-await (wait-for-eval-promise "!fs-pending-latest-mv-1" 3000))]
                           (-> (expect (.includes mv-out "pending-confirmation true")) (.toBe true))))

                       ;; Open popup and verify confirmation UI
                       (let [popup (js-await (.newPage @!context))]
                         (js-await (.goto popup
                                          (str "chrome-extension://" @!ext-id "/popup.html")
                                          #js {:waitUntil "networkidle"}))
                         (js-await (wait-for-popup-ready popup))

                         (js-await (-> (expect (.locator popup ".fs-confirmations-section"))
                                       (.toBeVisible #js {:timeout 2000})))

                         ;; Confirmation item shows source and destination
                         (js-await (-> (expect (.locator popup (str ".confirmation-item:has-text(\"" from-name "\")")))
                                       (.toBeVisible #js {:timeout 2000})))
                         (js-await (-> (expect (.locator popup (str ".confirmation-item:has-text(\"" from-name "\"):has-text(\"" to1 "\")")))
                                       (.toBeVisible #js {:timeout 2000})))

                         ;; Trigger another non-force mv updating destination to to2
                         (let [mv-code (str "(def !fs-pending-latest-mv-2 (atom :pending))\n"
                                            "(-> (epupp.fs/mv! " (pr-str from-name) " " (pr-str to2) ")\n"
                                            "  (.then (fn [r] (reset! !fs-pending-latest-mv-2 r))))\n"
                                            ":setup-done")]
                           (let [mv-result (js-await (eval-in-browser mv-code))]
                             (-> (expect (.-success mv-result)) (.toBe true)))
                           (let [mv-out (js-await (wait-for-eval-promise "!fs-pending-latest-mv-2" 3000))]
                             (-> (expect (.includes mv-out "pending-confirmation true")) (.toBe true))))

                         ;; Popup should update via broadcast: destination becomes to2 (and no longer to1)
                         (js-await (-> (expect (.locator popup (str ".confirmation-item:has-text(\"" from-name "\"):has-text(\"" to2 "\")")))
                                       (.toBeVisible #js {:timeout 2000})))
                         (js-await (-> (expect (.locator popup (str ".confirmation-item:has-text(\"" from-name "\"):has-text(\"" to1 "\")")))
                                       (.not.toBeVisible #js {:timeout 2000})))

                         ;; Click confirm within the specific confirmation item
                         (let [item (.locator popup (str ".confirmation-item:has-text(\"" from-name "\")"))]
                           (js-await (.click (.locator item ".confirmation-confirm"))))

                         ;; Verify script list shows to2 and does NOT show from-name
                         (js-await (-> (expect (.locator popup (str ".script-item:has-text(\"" to2 "\")")))
                                       (.toBeVisible #js {:timeout 2000})))
                         (js-await (-> (expect (.locator popup (str ".script-item:has-text(\"" from-name "\")")))
                                       (.not.toBeVisible #js {:timeout 2000})))

                         (js-await (assert-no-errors! popup))
                         (js-await (.close popup))))))

             (test "popup shows multiple pending confirmations"
                   (^:async fn []
                     (let [from-base-a (unique-basename "fs_multi_src_a")
                           from-name-a (script-file from-base-a)
                           to-a (script-file (unique-basename "fs_multi_to_a"))
                           from-base-b (unique-basename "fs_multi_src_b")
                           from-name-b (script-file from-base-b)
                           to-b (script-file (unique-basename "fs_multi_to_b"))]
                       ;; Create two scripts (force-save) so they exist before mv!
                       (let [test-code-a (str "{:epupp/script-name \"" from-base-a "\"\n"
                                              "                                      :epupp/site-match \"https://multi-confirmation-test.com/*\"}\n"
                                              "                                     (ns " from-base-a ")")
                             save-code-a (str "(def !fs-multi-setup-a (atom :pending))\n"
                                              "(-> (epupp.fs/save! " (pr-str test-code-a) " {:fs/force? true})\n"
                                              "  (.then (fn [r] (reset! !fs-multi-setup-a r))))\n"
                                              ":setup-done")
                             test-code-b (str "{:epupp/script-name \"" from-base-b "\"\n"
                                              "                                      :epupp/site-match \"https://multi-confirmation-test.com/*\"}\n"
                                              "                                     (ns " from-base-b ")")
                             save-code-b (str "(def !fs-multi-setup-b (atom :pending))\n"
                                              "(-> (epupp.fs/save! " (pr-str test-code-b) " {:fs/force? true})\n"
                                              "  (.then (fn [r] (reset! !fs-multi-setup-b r))))\n"
                                              ":setup-done")]
                         (let [setup-a (js-await (eval-in-browser save-code-a))]
                           (-> (expect (.-success setup-a)) (.toBe true)))
                         (js-await (wait-for-eval-promise "!fs-multi-setup-a" 3000))
                         (let [setup-b (js-await (eval-in-browser save-code-b))]
                           (-> (expect (.-success setup-b)) (.toBe true)))
                         (js-await (wait-for-eval-promise "!fs-multi-setup-b" 3000)))

                       ;; Trigger two non-force mv confirmations
                       (let [mv-code-a (str "(def !fs-multi-mv-a (atom :pending))\n"
                                            "(-> (epupp.fs/mv! " (pr-str from-name-a) " " (pr-str to-a) ")\n"
                                            "  (.then (fn [r] (reset! !fs-multi-mv-a r))))\n"
                                            ":setup-done")
                             mv-code-b (str "(def !fs-multi-mv-b (atom :pending))\n"
                                            "(-> (epupp.fs/mv! " (pr-str from-name-b) " " (pr-str to-b) ")\n"
                                            "  (.then (fn [r] (reset! !fs-multi-mv-b r))))\n"
                                            ":setup-done")]
                         (let [mv-a (js-await (eval-in-browser mv-code-a))]
                           (-> (expect (.-success mv-a)) (.toBe true)))
                         (let [mv-out-a (js-await (wait-for-eval-promise "!fs-multi-mv-a" 3000))]
                           (-> (expect (.includes mv-out-a "pending-confirmation true")) (.toBe true)))
                         (let [mv-b (js-await (eval-in-browser mv-code-b))]
                           (-> (expect (.-success mv-b)) (.toBe true)))
                         (let [mv-out-b (js-await (wait-for-eval-promise "!fs-multi-mv-b" 3000))]
                           (-> (expect (.includes mv-out-b "pending-confirmation true")) (.toBe true))))

                       ;; Open popup and verify both confirmations are shown
                       (let [popup (js-await (.newPage @!context))]
                         (js-await (.goto popup
                                          (str "chrome-extension://" @!ext-id "/popup.html")
                                          #js {:waitUntil "networkidle"}))
                         (js-await (wait-for-popup-ready popup))

                         (js-await (-> (expect (.locator popup ".fs-confirmations-section"))
                                       (.toBeVisible #js {:timeout 2000})))

                         (js-await (-> (expect (.locator popup (str ".confirmation-item:has-text(\"" from-name-a "\"):has-text(\"" to-a "\")")))
                                       (.toBeVisible #js {:timeout 2000})))
                         (js-await (-> (expect (.locator popup (str ".confirmation-item:has-text(\"" from-name-b "\"):has-text(\"" to-b "\")")))
                                       (.toBeVisible #js {:timeout 2000})))

                         ;; Confirm both by clicking confirm within each item
                         (let [item-a (.locator popup (str ".confirmation-item:has-text(\"" from-name-a "\")"))
                               item-b (.locator popup (str ".confirmation-item:has-text(\"" from-name-b "\")"))]
                           (js-await (.click (.locator item-a ".confirmation-confirm")))
                           (js-await (.click (.locator item-b ".confirmation-confirm"))))

                         ;; Verify renamed scripts show up and old names do not
                         (js-await (-> (expect (.locator popup (str ".script-item:has-text(\"" to-a "\")")))
                                       (.toBeVisible #js {:timeout 2000})))
                         (js-await (-> (expect (.locator popup (str ".script-item:has-text(\"" to-b "\")")))
                                       (.toBeVisible #js {:timeout 2000})))
                         (js-await (-> (expect (.locator popup (str ".script-item:has-text(\"" from-name-a "\")")))
                                       (.not.toBeVisible #js {:timeout 2000})))
                         (js-await (-> (expect (.locator popup (str ".script-item:has-text(\"" from-name-b "\")")))
                                       (.not.toBeVisible #js {:timeout 2000})))

                         (js-await (assert-no-errors! popup))
                         (js-await (.close popup))))))

             (test "popup refreshes after epupp.fs/mv!"
                   (^:async fn []
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
                       (js-await (.close popup)))))

             ;; Final error check
             (test "no uncaught errors during UI reactivity tests"
                   (^:async fn []
                     (let [popup (js-await (.newPage @!context))]
                       (js-await (.goto popup (str "chrome-extension://" @!ext-id "/popup.html")
                                        #js {:waitUntil "networkidle"}))
                       (js-await (assert-no-errors! popup))
                       (js-await (.close popup)))))))
