(ns e2e.primitives-test
  "E2E tests for REPL file sync primitives API.
   Tests epupp/cat, epupp/ls, epupp/save!, epupp/mv!, epupp/rm!

   These test the full pipeline: nREPL client -> browser-nrepl -> extension -> Scittle -> page"
  (:require ["@playwright/test" :refer [test expect chromium]]
            ["net" :as net]
            ["path" :as path]
            [fixtures :refer [http-port nrepl-port-1 ws-port-1
                              assert-no-errors! create-popup-page
                              wait-for-popup-ready]]))

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

(.describe test "REPL Primitives"
           (fn []
             (.beforeAll test (fn [] (setup-browser!)))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "epupp/cat retrieves script code by name"
                   (^:async fn []
                     ;; First verify epupp namespace is available
                     (let [ns-check (js-await (eval-in-browser "(fn? epupp/cat)"))]
                       (js/console.log "=== epupp/cat exists? ===" (js/JSON.stringify ns-check))
                       (-> (expect (.-success ns-check)) (.toBe true))
                       (-> (expect (.-values ns-check)) (.toContain "true")))

                     ;; Call cat and store the promise, then check its result
                     ;; We need to wait for the promise to resolve, so use .then
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !cat-result (atom nil))
                                                    (-> (epupp/cat \"GitHub Gist Installer (Built-in)\")
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
                               (throw (js/Error. "Timeout waiting for epupp/cat result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             (test "epupp/cat returns nil for non-existent script"
                   (^:async fn []
                     ;; Setup and call cat for non-existent script
                     (let [setup-result (js-await (eval-in-browser
                                                   "(def !cat-nil-result (atom :pending))
                                                    (-> (epupp/cat \"does-not-exist.cljs\")
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
                               (throw (js/Error. "Timeout waiting for epupp/cat nil result"))
                               (do
                                 (js-await (sleep 50))
                                 (recur)))))))))

             ;; Final error check
             (test "no uncaught errors during primitives tests"
                   (^:async fn []
                     (let [ext-id (js-await (get-extension-id @!context))
                           popup (js-await (.newPage @!context))]
                       (js-await (.goto popup (str "chrome-extension://" ext-id "/popup.html")
                                        #js {:waitUntil "networkidle"}))
                       (js-await (assert-no-errors! popup))
                       (js-await (.close popup)))))))
