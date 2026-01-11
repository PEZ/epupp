(ns test-logger
  "Structured logging for E2E test assertions.
   Only emits when EXTENSION_CONFIG.test is true.

   Writes events to chrome.storage.local for easy retrieval:
   - All extension contexts (background, content) can write
   - Playwright reads via extension page evaluate
   - Works identically in Chrome and Firefox")

;; EXTENSION_CONFIG is injected by esbuild at bundle time from config/*.edn
;; All extension bundles now get config injected
(def ^:private config js/EXTENSION_CONFIG)

(defn test-mode?
  "Check if test mode is enabled via build config."
  []
  (and config (.-test config)))

(defn ^:async init-test-mode!
  "Store test-mode flag in chrome.storage.local for non-bundled scripts.
   Call this at background worker startup. The userscript-loader.js reads
   this flag since it's not bundled via esbuild."
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

(defn install-global-error-handlers!
  "Install global error handlers that log to test events.
   Call this early in each context (background, popup, panel).
   Guards against double-installation using a flag on global-obj.

   context-name: String like 'background', 'popup', 'panel'
   global-obj: The global object (js/self for service worker, js/window for pages)"
  [context-name global-obj]
  (when (and (test-mode?)
             (not (aget global-obj "__epupp_error_handlers_installed")))
    (aset global-obj "__epupp_error_handlers_installed" true)
    ;; Uncaught exceptions
    (.addEventListener global-obj "error"
                       (fn [event]
                         (log-event! "UNCAUGHT_ERROR"
                                     {:context context-name
                                      :message (.-message event)
                                      :filename (.-filename event)
                                      :lineno (.-lineno event)
                                      :colno (.-colno event)
                                      :stack (when-let [err (.-error event)]
                                               (.-stack err))})))
    ;; Unhandled promise rejections
    (.addEventListener global-obj "unhandledrejection"
                       (fn [event]
                         (let [reason (.-reason event)]
                           (log-event! "UNHANDLED_REJECTION"
                                       {:context context-name
                                        :message (if (instance? js/Error reason)
                                                   (.-message reason)
                                                   (str reason))
                                        :stack (when (instance? js/Error reason)
                                                 (.-stack reason))}))))
    (js/console.log (str "[" context-name "] Global error handlers installed for test mode"))))
