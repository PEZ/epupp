(ns fs-ui-reactivity-helpers
  (:require ["@playwright/test" :refer [chromium]]
            ["net" :as net]
            ["path" :as path]
            [fixtures :refer [http-port nrepl-port-1 ws-port-1
                              clear-fs-scripts]]
            [fs-write-helpers :refer [wait-for-script-tag]]))

(def ^:private !context (atom nil))
(def ^:private !ext-id (atom nil))

(defn get-context []
  @!context)

(defn get-ext-id []
  @!ext-id)

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
              (js-await (sleep 20))
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

(defn close-browser! []
  (when-let [ctx @!context]
    (.close ctx)
    (reset! !context nil)
    (reset! !ext-id nil)))
