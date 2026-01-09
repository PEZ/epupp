(ns test-logger
  "Structured logging for E2E test assertions.
   Only emits when EXTENSION_CONFIG.test is true.

   Writes events to chrome.storage.local for easy retrieval:
   - All extension contexts (background, content) can write
   - Playwright reads via extension page evaluate
   - Works identically in Chrome and Firefox")

;; EXTENSION_CONFIG is injected by esbuild at bundle time from config/*.edn
;; Some bundles (content-bridge, ws-bridge) don't get it injected, so check first
(def ^:private config
  (when (exists? js/EXTENSION_CONFIG)
    js/EXTENSION_CONFIG))

(defn test-mode?
  "Check if test mode is enabled via build config."
  []
  (and config (.-test config)))

(defn ^:async init-test-mode!
  "Store test-mode flag in chrome.storage.local for non-bundled scripts.
   Call this at background worker startup. The userscript-loader.js reads
   this flag since it can't access EXTENSION_CONFIG (not bundled via esbuild)."
  []
  (js-await
   (js/Promise.
    (fn [resolve]
      (.set js/chrome.storage.local
            #js {:test-mode (test-mode?)}
            resolve)))))

(defn ^:async log-event!
  "Append structured test event to storage. Only runs in test mode.

   Uses performance.now for high-resolution timing, enabling:
   - Timing assertions (document-start before page scripts)
   - Performance reports (Scittle load time, injection overhead)"
  [event data]
  ;; Always store a debug flag to confirm this function was called
  (.set js/chrome.storage.local #js {:test-logger-debug #js {:called true
                                                             :event event
                                                             :test-mode (test-mode?)}})
  (when (test-mode?)
    (let [entry #js {:event event
                     :ts (.now js/Date)
                     :perf (.now js/performance)
                     :data (clj->js data)}]
      (js-await
       (js/Promise.
        (fn [resolve]
          (.get js/chrome.storage.local #js ["test-events"]
                (fn [result]
                  ;; Use bracket notation for hyphenated key access
                  (let [events (or (aget result "test-events") #js [])]
                    (.push events entry)
                    (.set js/chrome.storage.local #js {:test-events events}
                          resolve))))))))))

(defn ^:async clear-test-events!
  "Clear all test events. Call at start of each test."
  []
  (js-await
   (js/Promise.
    (fn [resolve]
      (.set js/chrome.storage.local #js {:test-events #js []} resolve)))))

(defn ^:async get-test-events
  "Retrieve all test events from storage."
  []
  (js-await
   (js/Promise.
    (fn [resolve]
      (.get js/chrome.storage.local #js ["test-events"]
            (fn [result]
              ;; Use bracket notation for hyphenated key access
              (resolve (or (aget result "test-events") #js []))))))))
