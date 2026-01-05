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
      (js-await (.goto test-page (str "http://localhost:" http-port "/")))
      (js-await (sleep 500))
      ;; Open popup to send messages to background worker
      (let [bg-page (js-await (.newPage ctx))]
        (js-await (.goto bg-page
                         (str "chrome-extension://" ext-id "/popup.html")
                         #js {:waitUntil "networkidle"}))
        (js-await (sleep 2000))
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
                                               :wsPort ws-port}))]
            (when-not (and connect-result (.-success connect-result))
              (throw (js/Error. (str "Connection failed: " (.-error connect-result)))))
            (js-await (.close bg-page))
            (js-await (sleep 2000))))))))

(.describe test "REPL Integration"
           (fn []
             (.beforeAll test (fn [] (setup-browser!)))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "simple arithmetic evaluation"
                   (^:async fn []
                     (let [result (js-await (eval-in-browser "(+ 1 2 3)"))]
                       (-> (expect (.-success result)) (.toBe true))
                       (-> (expect (.-values result)) (.toContain "6")))))

             (test "string operations"
                   (^:async fn []
                     (let [result (js-await (eval-in-browser "(str \"Hello\" \" \" \"World\")"))]
                       (-> (expect (.-success result)) (.toBe true))
                       (-> (expect (.some (.-values result)
                                          (fn [v] (.includes v "Hello World"))))
                           (.toBe true)))))

             (test "DOM access in page context"
                   (^:async fn []
                     (let [result (js-await (eval-in-browser "(.-title js/document)"))]
                       (-> (expect (.-success result)) (.toBe true))
                       (-> (expect (.some (.-values result)
                                          (fn [v] (.includes v "Test Page"))))
                           (.toBe true)))))

             (test "multiple forms evaluation"
                   (^:async fn []
                     (let [result (js-await (eval-in-browser "(def x 10) (def y 20) (+ x y)"))]
                       (-> (expect (.-success result)) (.toBe true))
                       (-> (expect (.-values result)) (.toContain "30")))))))
