(ns e2e.repl-ui-spec
  "REPL Integration Tests for Playwright UI

   These tests mirror the Babashka tests in repl_test.clj but run through
   Playwright's test framework, enabling the --ui interactive runner.

   Run with: bb test:repl-e2e:ui
   (This task starts browser-nrepl and HTTP server automatically)"
  (:require ["@playwright/test" :refer [test expect chromium]]
            ["net" :as net]
            ["path" :as path]))

(def nrepl-port 12345)
(def ws-port 12346)
(def http-port 8765)

(def !context (atom nil))

(defn ^:async sleep [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async eval-in-browser
  "Evaluate code via nREPL. Returns {:success bool :values [...] :error str}"
  [code]
  (js/Promise.
   (fn [resolve]
     (let [client (.createConnection net #js {:port nrepl-port :host "localhost"})
           !response (atom "")]
       (.on client "data"
            (fn [data]
              (swap! !response str (.toString data))
              ;; Check if response contains "done" status (bencode: 6:status...4:done)
              (when (.includes @!response "4:done")
                (.destroy client)
                (let [response @!response
                      values (atom [])
                      ;; Parse bencode values using length prefix
                      ;; Format: 5:value<len>:<content>
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

(defn ^:async get-extension-id [context]
  (let [workers (.serviceWorkers context)]
    (if (pos? (.-length workers))
      (-> (aget workers 0) (.url) (.split "/") (aget 2))
      (let [sw (js-await (.waitForEvent context "serviceworker"))]
        (-> (.url sw) (.split "/") (aget 2))))))

(.describe test "REPL Integration"
           (fn []
             (.beforeAll test
                         (fn []
                           (let [extension-path (.resolve path "dist/chrome")]
                             (-> (js/Promise.resolve)
                                 (.then
                                  (fn []
                                    (.launchPersistentContext
                                     chromium ""
                                     #js {:headless false
                                          :args #js ["--no-sandbox"
                                                     "--allow-file-access-from-files"
                                                     "--enable-features=ExtensionsManifestV3Only"
                                                     (str "--disable-extensions-except=" extension-path)
                                                     (str "--load-extension=" extension-path)]})))
                                 (.then
                                  (fn [ctx]
                                    (reset! !context ctx)
                                    (get-extension-id ctx)))
                                 (.then
                                  (fn [ext-id]
                                    (-> (.newPage @!context)
                                        (.then (fn [test-page]
                                                 (.goto test-page (str "http://localhost:" http-port "/"))))
                                        (.then (fn [] (sleep 500)))
                                        (.then (fn [] (.newPage @!context)))
                                        (.then (fn [bg-page]
                                                 (-> (.goto bg-page
                                                            (str "chrome-extension://" ext-id "/popup.html")
                                                            #js {:waitUntil "networkidle"})
                                                     (.then (fn [] (sleep 2000)))
                                                     (.then (fn []
                                                              (.evaluate bg-page
                                                                         (fn [url-pattern]
                                                                           (js/Promise.
                                                                            (fn [resolve]
                                                                              (js/chrome.runtime.sendMessage
                                                                               #js {:type "e2e/find-tab-id"
                                                                                    :urlPattern url-pattern}
                                                                               resolve))))
                                                                         "http://localhost:*/*")))
                                                     (.then (fn [find-result]
                                                              (when-not (and find-result (.-success find-result))
                                                                (throw (js/Error. (str "Could not find test tab: "
                                                                                       (.-error find-result)))))
                                                              (.evaluate bg-page
                                                                         (fn [opts]
                                                                           (js/Promise.
                                                                            (fn [resolve]
                                                                              (js/chrome.runtime.sendMessage
                                                                               #js {:type "connect-tab"
                                                                                    :tabId (.-tabId opts)
                                                                                    :wsPort (.-wsPort opts)}
                                                                               resolve))))
                                                                         #js {:tabId (.-tabId find-result)
                                                                              :wsPort ws-port})))
                                                     (.then (fn [connect-result]
                                                              (when-not (and connect-result (.-success connect-result))
                                                                (throw (js/Error. (str "Connection failed: "
                                                                                       (.-error connect-result)))))
                                                              (.close bg-page)))
                                                     (.then (fn [] (sleep 2000)))))))))))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "simple arithmetic evaluation"
                   (fn []
                     (-> (eval-in-browser "(+ 1 2 3)")
                         (.then (fn [result]
                                  (-> (expect (.-success result)) (.toBe true))
                                  (-> (expect (.-values result)) (.toContain "6")))))))

             (test "string operations"
                   (fn []
                     (-> (eval-in-browser "(str \"Hello\" \" \" \"World\")")
                         (.then (fn [result]
                                  (-> (expect (.-success result)) (.toBe true))
                                  (-> (expect (.some (.-values result)
                                                     (fn [v] (.includes v "Hello World"))))
                                      (.toBe true)))))))

             (test "DOM access in page context"
                   (fn []
                     (-> (eval-in-browser "(.-title js/document)")
                         (.then (fn [result]
                                  (-> (expect (.-success result)) (.toBe true))
                                  (-> (expect (.some (.-values result)
                                                     (fn [v] (.includes v "Test Page"))))
                                      (.toBe true)))))))

             (test "multiple forms evaluation"
                   (fn []
                     (-> (eval-in-browser "(def x 10) (def y 20) (+ x y)")
                         (.then (fn [result]
                                  (-> (expect (.-success result)) (.toBe true))
                                  (-> (expect (.-values result)) (.toContain "30")))))))))
