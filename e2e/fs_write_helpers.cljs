(ns e2e.fs-write-helpers
  "Shared helpers for REPL FS write E2E tests."
  (:require ["@playwright/test" :refer [chromium]]
            ["net" :as net]
            ["path" :as path]
            [fixtures :refer [http-port nrepl-port-1 ws-port-1
                              clear-fs-scripts send-runtime-message
                              find-tab-id connect-tab get-extension-id]]))

(defn ^:async sleep [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

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

(defn- unquote-eval-value [value]
  (if (and value
           (.startsWith value "\"")
           (.endsWith value "\""))
    (.substring value 1 (dec (.-length value)))
    value))

(defn ^:async wait-for-script-tag
  "Poll the page via nREPL until a script tag matching the pattern appears."
  [pattern timeout-ms]
  (let [start (.now js/Date)
  poll-interval 20
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

(defn ^:async wait-for-builtin-script
  "Wait for built-in script to be present in storage.
   Used to avoid races with ensure-gist-installer!."
  [ext-page script-id timeout-ms]
  (let [start (.now js/Date)
        poll-interval 20]
    (loop []
      (let [result (js-await (send-runtime-message ext-page "e2e/get-storage" #js {:key "scripts"}))
            scripts (or (.-value result) #js [])
            found (.some scripts (fn [script] (= (.-id script) script-id)))]
        (if found
          true
          (if (> (- (.now js/Date) start) (or timeout-ms 3000))
            (throw (js/Error. (str "Timeout waiting for built-in script: " script-id)))
            (do
              (js-await (sleep poll-interval))
              (recur))))))))

(defn ^:async ensure-builtin-script!
  "Ensure the built-in gist installer exists in storage via background message."
  [context]
  (let [ext-id (js-await (get-extension-id context))
        popup (js-await (.newPage context))]
    (js-await (.goto popup (str "chrome-extension://" ext-id "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (let [result (js-await (send-runtime-message popup "e2e/ensure-builtin" #js {}))]
      (js-await (.close popup))
      (if (and result (.-success result))
        true
        (throw (js/Error. (str "Failed to ensure builtin: " (or (.-error result) "unknown error"))))))))

(defn ^:async setup-browser!
  "Launch browser, connect REPL, enable FS sync, return context."
  []
  (let [extension-path (.resolve path "dist/chrome")
        ctx (js-await (.launchPersistentContext
                       chromium ""
                       #js {:headless false
                            :args #js ["--no-sandbox"
                                       "--allow-file-access-from-files"
                                       "--enable-features=ExtensionsManifestV3Only"
                                       (str "--disable-extensions-except=" extension-path)
                                       (str "--load-extension=" extension-path)]}))]
    (let [ext-id (js-await (get-extension-id ctx))
          test-page (js-await (.newPage ctx))]
      (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html")))
      (js-await (.waitForLoadState test-page "domcontentloaded"))
      (let [bg-page (js-await (.newPage ctx))]
        (js-await (.goto bg-page
                         (str "chrome-extension://" ext-id "/popup.html")
                         #js {:waitUntil "networkidle"}))
        (js-await (clear-fs-scripts bg-page))
        (js-await (send-runtime-message bg-page "e2e/set-storage" #js {:key "fsReplSyncEnabled" :value true}))
        (js-await (wait-for-builtin-script bg-page "epupp-builtin-gist-installer" 5000))
        (let [tab-id (js-await (find-tab-id bg-page "http://localhost:*/*"))]
          (js-await (connect-tab bg-page tab-id ws-port-1))
          (js-await (.close bg-page))
          (js-await (wait-for-script-tag "scittle" 5000)))))
    ctx))

(defn unquote-result [value]
  (unquote-eval-value value))
