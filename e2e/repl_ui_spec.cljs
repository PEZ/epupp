(ns e2e.repl-ui-spec
  "REPL Integration Tests for Playwright UI

   These tests verify the full nREPL pipeline: editor -> browser-nrepl -> extension -> page
   They run as part of the unified E2E test suite with shared infrastructure."
  (:require ["@playwright/test" :refer [test expect chromium]]
            ["net" :as net]
            ["path" :as path]
            [fixtures :refer [http-port nrepl-port-1 ws-port-1 nrepl-port-2 ws-port-2]]))

(def !context (atom nil))

(defn ^:async sleep [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async wait-for-connection-count
  "Poll get-connections until we have expected-count connections, or timeout.
   Much faster than fixed sleep when connections establish quickly."
  [popup expected-count timeout-ms]
  (let [start (.now js/Date)
        poll-interval 50]
    (loop []
      (let [result (js-await
                    (.evaluate popup
                               (fn []
                                 (js/Promise.
                                  (fn [res]
                                    (js/chrome.runtime.sendMessage
                                     #js {:type "get-connections"}
                                     res))))))
            count (if (and result (.-success result))
                    (.-length (.-connections result))
                    0)]
        (if (>= count expected-count)
          count
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. (str "Timeout waiting for " expected-count " connections. Got: " count)))
            (do
              (js-await (sleep poll-interval))
              (recur))))))))



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
  "Poll the page via nREPL until a script tag matching the pattern appears.
   Much faster than fixed sleeps - returns as soon as injection completes.
   Returns true when found, throws on timeout."
  [pattern timeout-ms]
  (let [start (.now js/Date)
        poll-interval 100
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
      ;; Load test page - wait for load state instead of fixed sleep
      (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html")))
      (js-await (.waitForLoadState test-page "domcontentloaded"))
      ;; Open popup to send messages to background worker
      (let [bg-page (js-await (.newPage ctx))]
        (js-await (.goto bg-page
                         (str "chrome-extension://" ext-id "/popup.html")
                         #js {:waitUntil "networkidle"}))
        ;; Short stabilization wait - networkidle should handle most of it
        (js-await (sleep 300))
        ;; Find test page tab ID
        (let [find-result (js-await (send-runtime-message
                                     bg-page "e2e/find-tab-id"
                                     #js {:urlPattern "http://localhost:*/*"}))]
          (when-not (and find-result (.-success find-result))
            (throw (js/Error. (str "Could not find test tab: " (.-error find-result)))))
          ;; Connect to test page using server 1
          (let [connect-result (js-await (send-runtime-message
                                          bg-page "connect-tab"
                                          #js {:tabId (.-tabId find-result)
                                               :wsPort ws-port-1}))]
            (when-not (and connect-result (.-success connect-result))
              (throw (js/Error. (str "Connection failed: " (.-error connect-result)))))
            (js-await (.close bg-page))
            ;; Wait for Scittle to be available after connect (poll instead of 2s sleep)
            (js-await (wait-for-script-tag "scittle" 5000))))))))

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
                       (-> (expect (.-values result)) (.toContain "30")))))

             (test "get-connections returns connected tab with port"
                   (^:async fn []
                     ;; Open popup to query connections
                     (let [ext-id (js-await (get-extension-id @!context))
                           popup (js-await (.newPage @!context))]
                       (js-await (.goto popup
                                        (str "chrome-extension://" ext-id "/popup.html")
                                        #js {:waitUntil "networkidle"}))
                       ;; Poll until connection exists (faster than fixed 500ms sleep)
                       (js-await (wait-for-connection-count popup 1 5000))
                       (let [result (js-await (send-runtime-message popup "get-connections" #js {}))]
                         (js/console.log "get-connections result:" (js/JSON.stringify result))
                         (-> (expect (.-success result)) (.toBe true))
                         (-> (expect (.-connections result)) (.toBeDefined))
                         (let [connections (.-connections result)]
                           ;; Should have at least one connection
                           (-> (expect (.-length connections)) (.toBeGreaterThan 0))
                           ;; First connection should have the expected port (returned as string)
                           ;; Use bracket notation for hyphenated keys (Squint converts .- to underscore)
                           (let [conn (aget connections 0)]
                             (-> (expect (aget conn "port")) (.toBe (str ws-port-1)))
                             (-> (expect (aget conn "title")) (.toBeDefined))
                             (-> (expect (aget conn "tab-id")) (.toBeDefined)))))
                       (js-await (.close popup)))))

             (test "epupp/manifest! loads Replicant for REPL evaluation"
                   (^:async fn []
                     ;; Step 1: Verify epupp namespace was injected at connect time
                     (let [ns-check (js-await (eval-in-browser "(fn? epupp/manifest!)"))]
                       (js/console.log "=== ns-check result ===" (js/JSON.stringify ns-check))
                       (-> (expect (.-success ns-check)) (.toBe true))
                       (-> (expect (.-values ns-check)) (.toContain "true")))

                     ;; Step 2: Load Replicant via manifest
                     ;; Call manifest! - it returns a promise but we can't wait for it in nREPL
                     ;; Just verify it doesn't error and returns a promise
                     (let [manifest-result (js-await (eval-in-browser
                                                      "(epupp/manifest! {:epupp/require [\"scittle://replicant.js\"]})"))]
                       (js/console.log "=== manifest-result ===" (js/JSON.stringify manifest-result))
                       (-> (expect (.-success manifest-result)) (.toBe true)))

                     ;; Step 3: Wait for replicant script tag to appear (poll instead of fixed sleep)
                     (js-await (wait-for-script-tag "replicant" 5000))

                     ;; Step 4: Verify Replicant is now available
                     (let [replicant-check (js-await (eval-in-browser "(boolean (resolve 'replicant.dom/render))"))]
                       (js/console.log "=== replicant-check ===" (js/JSON.stringify replicant-check))
                       (-> (expect (.-success replicant-check)) (.toBe true)))

                     ;; Step 4: Use Replicant to render Epupp-themed UI
                     (let [render-result (js-await (eval-in-browser
                                                    "(require '[replicant.dom :as r])
                                                     (let [container (js/document.createElement \"div\")]
                                                       (set! (.-id container) \"epupp-repl-test\")
                                                       (.appendChild js/document.body container)
                                                       (r/render container
                                                         [:div.epupp-banner
                                                          [:h2 \"Epupp REPL\"]
                                                          [:p \"Live tampering your web!\"]]))
                                                     :rendered"))]
                       (-> (expect (.-success render-result)) (.toBe true))
                       (-> (expect (.-values render-result)) (.toContain ":rendered")))

                     ;; Step 5: Verify DOM was actually modified
                     (let [dom-check (js-await (eval-in-browser
                                                "(boolean (js/document.getElementById \"epupp-repl-test\"))"))]
                       (-> (expect (.-success dom-check)) (.toBe true))
                       (-> (expect (.-values dom-check)) (.toContain "true")))))

             (test "epupp/manifest! is idempotent - no duplicate script tags"
                   (^:async fn []
                     ;; First, load Replicant
                     (let [first-load (js-await (eval-in-browser
                                                 "(epupp/manifest! {:epupp/require [\"scittle://replicant.js\"]})"))]
                       (-> (expect (.-success first-load)) (.toBe true)))

                     ;; Wait for replicant script tag to appear (poll instead of fixed sleep)
                     (js-await (wait-for-script-tag "replicant" 5000))

                     ;; Count replicant script tags after first load (should be 1)
                     (let [count-after-first (js-await (eval-in-browser
                                                        "(.-length (js/document.querySelectorAll \"script[src*='replicant']\"))"))]
                       (-> (expect (.-success count-after-first)) (.toBe true))
                       (js/console.log "=== replicant scripts after first load ===" (.-values count-after-first))
                       ;; nREPL returns values as strings
                       (-> (expect (first (.-values count-after-first))) (.toBe "1"))

                       ;; Call manifest! again
                       (let [second-load (js-await (eval-in-browser
                                                    "(epupp/manifest! {:epupp/require [\"scittle://replicant.js\"]})"))]
                         (-> (expect (.-success second-load)) (.toBe true)))

                       ;; Short wait for idempotent check - script already exists, just needs to settle
                       (js-await (sleep 200))

                       ;; Count replicant script tags after second load
                       (let [count-after-second (js-await (eval-in-browser
                                                           "(.-length (js/document.querySelectorAll \"script[src*='replicant']\"))"))]
                         (-> (expect (.-success count-after-second)) (.toBe true))
                         (js/console.log "=== replicant scripts after second load ===" (.-values count-after-second))

                         ;; Should still be 1 - no duplicates added (nREPL returns strings)
                         (-> (expect (first (.-values count-after-second))) (.toBe "1"))))))))

;; =============================================================================
;; Multi-Tab Multi-Server Tests
;; =============================================================================

(defn ^:async eval-on-port
  "Evaluate code via nREPL on specified port. Returns {:success bool :values [...] :error str}"
  [nrepl-port code]
  (js/Promise.
   (fn [resolve]
     (let [client (.createConnection net #js {:port nrepl-port :host "localhost"})
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
                                         (.includes response "3:err")))]
                    (resolve #js {:success success :values @values}))))))
       (.on client "error"
            (fn [err]
              (resolve #js {:success false :error (.-message err)})))
       (let [msg (str "d2:op4:eval4:code" (.-length code) ":" code "e")]
         (.write client msg))))))

(.describe test "Multi-Tab REPL"
           (fn []
             (test "two tabs connected to different servers evaluate independently"
                   (^:async fn []
                     (let [extension-path (.resolve path "dist/chrome")
                           ctx (js-await (.launchPersistentContext
                                          chromium ""
                                          #js {:headless false
                                               :args #js ["--no-sandbox"
                                                          "--allow-file-access-from-files"
                                                          (str "--disable-extensions-except=" extension-path)
                                                          (str "--load-extension=" extension-path)]}))]
                       (try
                         (let [ext-id (js-await (get-extension-id ctx))
                               ;; Open two test pages
                               page1 (js-await (.newPage ctx))
                               page2 (js-await (.newPage ctx))]
                           (js-await (.goto page1 (str "http://localhost:" http-port "/basic.html")))
                           (js-await (.goto page2 (str "http://localhost:" http-port "/timing-test.html")))
                           ;; Wait for both pages to be loaded (faster than fixed sleep)
                           (js-await (.waitForLoadState page1 "domcontentloaded"))
                           (js-await (.waitForLoadState page2 "domcontentloaded"))

                           ;; Open popup to connect tabs
                           (let [popup (js-await (.newPage ctx))]
                             (js-await (.goto popup (str "chrome-extension://" ext-id "/popup.html")
                                              #js {:waitUntil "networkidle"}))
                             ;; Short stabilization - networkidle handles most of it
                             (js-await (sleep 200))

                             ;; Find both tabs (use Chrome match patterns, not globs)
                             (let [find1 (js-await (send-runtime-message popup "e2e/find-tab-id"
                                                                         #js {:urlPattern "*://*/basic.html"}))
                                   find2 (js-await (send-runtime-message popup "e2e/find-tab-id"
                                                                         #js {:urlPattern "*://*/timing-test.html"}))]
                               (-> (expect (.-success find1)) (.toBe true))
                               (-> (expect (.-success find2)) (.toBe true))
                               (js/console.log "Tab 1 ID:" (.-tabId find1) "Tab 2 ID:" (.-tabId find2))

                               ;; Connect tab 1 to server 1 (port 12346)
                               (let [conn1 (js-await (send-runtime-message popup "connect-tab"
                                                                           #js {:tabId (.-tabId find1)
                                                                                :wsPort ws-port-1}))]
                                 (-> (expect (.-success conn1)) (.toBe true))
                                 (js/console.log "Tab 1 connected to server 1 (port" ws-port-1 ")"))

                               ;; Connect tab 2 to server 2 (port 12348)
                               (let [conn2 (js-await (send-runtime-message popup "connect-tab"
                                                                           #js {:tabId (.-tabId find2)
                                                                                :wsPort ws-port-2}))]
                                 (-> (expect (.-success conn2)) (.toBe true))
                                 (js/console.log "Tab 2 connected to server 2 (port" ws-port-2 ")"))

                               ;; Poll until both connections are established (faster than fixed 500ms sleep)
                               (let [conn-count (js-await (wait-for-connection-count popup 2 5000))]
                                 (js/console.log "Active connections:" conn-count))

                               ;; Evaluate on server 1 - should execute in page 1 (basic.html)
                               (let [result1 (js-await (eval-on-port nrepl-port-1 "(.-title js/document)"))]
                                 (-> (expect (.-success result1)) (.toBe true))
                                 (-> (expect (.some (.-values result1)
                                                    (fn [v] (.includes v "Basic"))))
                                     (.toBe true))
                                 (js/console.log "Server 1 eval result:" (.-values result1)))

                               ;; Evaluate on server 2 - should execute in page 2 (timing-test.html)
                               (let [result2 (js-await (eval-on-port nrepl-port-2 "(.-title js/document)"))]
                                 (-> (expect (.-success result2)) (.toBe true))
                                 (-> (expect (.some (.-values result2)
                                                    (fn [v] (.includes v "Timing"))))
                                     (.toBe true))
                                 (js/console.log "Server 2 eval result:" (.-values result2)))

                               ;; Verify evaluations are truly independent by defining different vars
                               (let [def1 (js-await (eval-on-port nrepl-port-1 "(def my-tab :tab-one)"))
                                     def2 (js-await (eval-on-port nrepl-port-2 "(def my-tab :tab-two)"))
                                     read1 (js-await (eval-on-port nrepl-port-1 "my-tab"))
                                     read2 (js-await (eval-on-port nrepl-port-2 "my-tab"))]
                                 (-> (expect (.-success def1)) (.toBe true))
                                 (-> (expect (.-success def2)) (.toBe true))
                                 ;; Each server maintains separate state
                                 (-> (expect (.-values read1)) (.toContain ":tab-one"))
                                 (-> (expect (.-values read2)) (.toContain ":tab-two"))
                                 (js/console.log "Independent state verified: tab1=" (.-values read1)
                                                 "tab2=" (.-values read2))))

                             (js-await (.close popup))))
                         (finally
                           (js-await (.close ctx)))))))))
