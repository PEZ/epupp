(ns fixtures
  "Playwright fixtures for testing Chrome extension.
   Provides helpers for launching Chrome with the extension loaded,
   creating panel/popup pages, and managing test state."
  (:require ["@playwright/test" :refer [chromium expect]]
            ["path" :as path]
            ["url" :as url]))

(def ^:private __dirname
  (path/dirname (url/fileURLToPath js/import.meta.url)))

(def extension-path
  "Absolute path to the built extension directory (dist/chrome after bb build)."
  (path/resolve __dirname ".." ".." "dist" "chrome"))

;; =============================================================================
;; Port Constants - Must match tasks.clj
;; =============================================================================

(def http-port 18080)

;; Two browser-nrepl servers for multi-tab testing
(def nrepl-port-1 12345)
(def ws-port-1 12346)
(def nrepl-port-2 12347)
(def ws-port-2 12348)

;; =============================================================================
;; Browser Helpers
;; =============================================================================

(defn ^:async create-extension-context
  "Launch Chrome with the extension loaded.
   Returns a persistent browser context.
   Note: Extensions require headed mode (headless: false)."
  []
  (js-await
   (.launchPersistentContext chromium ""
                             #js {:headless false
                                  :args #js ["--no-sandbox"
                                             (str "--disable-extensions-except=" extension-path)
                                             (str "--load-extension=" extension-path)]})))

;; Alias for backward compatibility
(def launch-browser create-extension-context)

(defn ^:async get-extension-id
  "Extract extension ID from service worker URL.
   Waits for service worker if not immediately available."
  [context]
  (let [workers (.serviceWorkers context)]
    (if (pos? (.-length workers))
      ;; Workers available - get URL from first one
      (let [sw-url (-> (aget workers 0) (.url))]
        (-> sw-url (.split "/") (aget 2)))
      ;; Wait for serviceworker event - returns a Worker object
      (let [sw (js-await (.waitForEvent context "serviceworker"))
            sw-url (.url sw)]
        (-> sw-url (.split "/") (aget 2))))))

;; =============================================================================
;; DevTools Panel Helpers
;; =============================================================================

(def mock-devtools-script
  "JavaScript to inject mock chrome.devtools APIs for panel testing.
   Mocks inspectedWindow.eval, network.onNavigated, and panels.themeName."
  "
  // Mock chrome.devtools APIs for panel testing
  window.__mockEvalCalls = [];
  window.__mockHostname = 'test.example.com';

  if (!chrome.devtools) {
    chrome.devtools = {
      inspectedWindow: {
        eval: function(expr, callback) {
          window.__mockEvalCalls.push(expr);
          if (expr.includes('location.hostname')) {
            callback(window.__mockHostname, undefined);
          } else if (expr.includes('location.href')) {
            callback('https://test.example.com/some/path', undefined);
          } else if (expr.includes('scittle') && !expr.includes('eval_string')) {
            callback({ hasScittle: true, hasNrepl: false }, undefined);
          } else if (expr.includes('eval_string')) {
            callback({ value: 'mock-result', error: null }, undefined);
          } else {
            callback(undefined, undefined);
          }
        },
        tabId: 99999
      },
      network: {
        onNavigated: { addListener: function() {} }
      },
      panels: { themeName: 'dark' }
    };
  }
")

(defn ^:async create-panel-page
  "Create a page with mocked chrome.devtools and navigate to panel.html.
   Waits for panel to be ready by checking for the code textarea."
  [context ext-id]
  (let [panel-page (js-await (.newPage context))
        panel-url (str "chrome-extension://" ext-id "/panel.html")]
    (js-await (.addInitScript panel-page mock-devtools-script))
    (js-await (.goto panel-page panel-url #js {:timeout 1000}))
    ;; Wait for panel to be fully initialized - code-area indicates JS has loaded
    (js-await (-> (expect (.locator panel-page "#code-area"))
                  (.toBeVisible #js {:timeout 500})))
    panel-page))

;; =============================================================================
;; Popup Helpers
;; =============================================================================

(defn ^:async create-popup-page
  "Create a popup page.
   Waits for popup to be ready by checking for the nREPL port input."
  [context ext-id]
  (let [popup-page (js-await (.newPage context))
        popup-url (str "chrome-extension://" ext-id "/popup.html")]
    (js-await (.goto popup-page popup-url #js {:timeout 1000}))
    ;; Wait for popup to be fully initialized - nrepl-port input indicates JS has loaded
    (js-await (-> (expect (.locator popup-page "#nrepl-port"))
                  (.toBeVisible #js {:timeout 500})))
    popup-page))

;; =============================================================================
;; Storage Helpers
;; =============================================================================

(defn ^:async clear-storage
  "Clear extension storage to ensure clean test state"
  [page]
  (js-await (.evaluate page "() => chrome.storage.local.clear()")))

;; =============================================================================
;; Runtime Message Helpers - For sending messages to background worker
;; =============================================================================

(defn ^:async send-runtime-message
  "Send a message to the background worker via chrome.runtime.sendMessage.
   ext-page must be an extension page (popup or panel) that has access to chrome.runtime.
   Returns the response from the background worker."
  [ext-page msg-type data]
  (js-await
   (.evaluate ext-page
              (fn [opts]
                (js/Promise.
                 (fn [resolve]
                   (js/chrome.runtime.sendMessage
                    (js/Object.assign #js {:type (.-type opts)} (.-data opts))
                    resolve))))
              #js {:type msg-type :data (or data #js {})})))

(defn ^:async find-tab-id
  "Find a tab matching the given URL pattern. Returns tab ID or throws.
   ext-page must be an extension page (popup/panel)."
  [ext-page url-pattern]
  (let [result (js-await (send-runtime-message ext-page "e2e/find-tab-id"
                                               #js {:urlPattern url-pattern}))]
    (if (and result (.-success result))
      (.-tabId result)
      (throw (js/Error. (str "Could not find tab matching: " url-pattern
                             " - " (or (.-error result) "unknown error")))))))

(defn ^:async connect-tab
  "Connect the REPL to a specific tab via WebSocket port.
   ext-page must be an extension page (popup/panel).
   Returns true on success, throws on failure."
  [ext-page tab-id ws-port]
  (let [result (js-await (send-runtime-message ext-page "connect-tab"
                                               #js {:tabId tab-id :wsPort ws-port}))]
    (if (and result (.-success result))
      true
      (throw (js/Error. (str "Connection failed: " (or (.-error result) "unknown error")))))))

(defn ^:async get-connections
  "Get active REPL connections from background worker.
   ext-page must be an extension page (popup/panel).
   Returns a vector of connection maps on success."
  [ext-page]
  (let [result (js-await (send-runtime-message ext-page "get-connections"
                                               #js {}))]
    (if (and result (.-success result))
      (.-connections result)
      (throw (js/Error. (str "get-connections failed: " (or (.-error result) "unknown error")))))))

(defn ^:async wait-for-connection
  "Wait for WebSocket connection to be established after connect-tab.
   Polls get-connections until count is at least 1, or timeout.
   Returns the connection count."
  [ext-page timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [current-count (.-length (js-await (get-connections ext-page)))]
        (if (pos? current-count)
          current-count
          (if (> (- (.now js/Date) start) (or timeout-ms 5000))
            (throw (js/Error. (str "Timeout waiting for connection. Count: " current-count)))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 30))))
              (recur))))))))

;; =============================================================================
;; Wait Helpers - Use these instead of sleep for reliable tests
;; =============================================================================

(defn ^:async get-test-events-via-message
  "Read test events from storage via background worker message.
   Works from any extension page (popup, panel, or background page).
   Returns JS array of event objects."
  [ext-page]
  (let [result (js-await (send-runtime-message ext-page "e2e/get-test-events" nil))]
    (if (and result (.-success result))
      (.-events result)
      (array))))

(defn ^:async wait-for-script-count
  "Wait for the script list to have exactly n items.
   Use after save/delete operations instead of sleep."
  [page n]
  (js-await (-> (expect (.locator page ".script-item"))
                (.toHaveCount n #js {:timeout 500}))))

(defn ^:async wait-for-save-status
  "Wait for save status to appear with expected text (e.g., 'Created', 'Saved').
   Use after clicking save button instead of sleep."
  [page text]
  (js-await (-> (expect (.locator page ".save-status"))
                (.toContainText text #js {:timeout 500}))))

(defn ^:async wait-for-checkbox-state
  "Wait for checkbox to reach expected checked state.
   Use after toggling checkboxes instead of sleep."
  [checkbox checked?]
  (if checked?
    (js-await (-> (expect checkbox) (.toBeChecked #js {:timeout 500})))
    (js-await (-> (expect checkbox) (.not.toBeChecked #js {:timeout 500})))))

(defn ^:async wait-for-panel-ready
  "Wait for panel to be ready after reload/navigation.
   Useful after .reload() calls instead of sleep."
  [panel]
  (js-await (-> (expect (.locator panel "#code-area"))
                (.toBeVisible #js {:timeout 500}))))

(defn ^:async wait-for-popup-ready
  "Wait for popup to be ready after reload/navigation.
   Useful after .reload() calls instead of sleep."
  [popup]
  (js-await (-> (expect (.locator popup "#nrepl-port"))
                (.toBeVisible #js {:timeout 500}))))

(defn ^:async wait-for-edit-hint
  "Wait for the edit hint message to appear in popup.
   Use after clicking edit button instead of sleep.
   The hint message is generic ('Open the Epupp panel in Developer Tools'),
   so this just waits for visibility."
  [popup]
  (js-await (-> (expect (.locator popup ".script-edit-hint"))
                (.toBeVisible #js {:timeout 300}))))

;; =============================================================================
;; Test Event Helpers - for true E2E testing via structured logging
;; =============================================================================

(defn ^:async clear-test-events!
  "Clear test events in storage. Call at start of each test.
   Must be called from an extension page (popup/panel)."
  [ext-page]
  (js-await
   (.evaluate ext-page
              "() => new Promise(resolve => {
                 chrome.storage.local.set({ 'test-events': [] }, resolve);
               })")))

(defn ^:async get-test-events
  "Read test events from storage via the dev log button.

   Workaround for Playwright limitation: page.evaluate returns undefined on extension pages.
   Instead, we click the 'Dump Dev Log' button which console.logs the events,
   then capture the console output.

   Returns JS array of event objects with .event, .ts, .perf, .data properties."
  [ext-page]
  (let [marker "__EPUPP_DEV_LOG__"
        result-promise
        (js/Promise.
         (fn [resolve]
           (let [timeout-id (js/setTimeout (fn [] (resolve (array))) 5000)]
             (.on ext-page "console"
                  (fn [msg]
                    (when (= "log" (.type msg))
                      (let [text (.text msg)]
                        (when (.startsWith text marker)
                          (js/clearTimeout timeout-id)
                          (let [json-str (.trim (.substring text (.-length marker)))]
                            (try
                              (resolve (js/JSON.parse json-str))
                              (catch :default e
                                (js/console.error "Failed to parse dev log:" e)
                                (resolve (array)))))))))))))]
    ;; Click the dev log button
    (let [dev-log-btn (.locator ext-page ".dev-log-btn")]
      (js-await (-> (expect dev-log-btn) (.toBeVisible (js-obj "timeout" 5000))))
      (js-await (.click dev-log-btn)))
    ;; Wait for the result
    (js-await result-promise)))

(defn ^:async wait-for-event
  "Poll storage until event appears or timeout.
   ext-page: popup or panel page for storage access
   event-name: SCREAMING_SNAKE event name (string)
   timeout-ms: max wait time"
  [ext-page event-name timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [events (js-await (get-test-events ext-page))
            found (first (filter #(= (.-event %) event-name) events))]
        (if found
          found
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. (str "Timeout waiting for event: " event-name
                                   ". Events so far: " (js/JSON.stringify (clj->js (map #(.-event %) events))))))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 30))))
              (recur))))))))

(defn ^:async assert-no-new-event-within
  "Assert that no NEW event with given name occurs within timeout-ms.
   Polls rapidly (every 50ms) and fails immediately if count increases.

   initial-count: The number of events of this type that existed before the action
   Use for tests that verify something should NOT happen."
  [ext-page event-name initial-count timeout-ms]
  (let [start (.now js/Date)
        poll-interval 30]
    (loop []
      (let [events (js-await (get-test-events ext-page))
            current-count (.-length (.filter events (fn [e] (= (.-event e) event-name))))]
        (if (> current-count initial-count)
          (throw (js/Error. (str "Unexpected new event occurred: " event-name
                                 " (count went from " initial-count " to " current-count ")"
                                 " after " (- (.now js/Date) start) "ms")))
          (if (> (- (.now js/Date) start) timeout-ms)
            true  ; Success - no new events
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve poll-interval))))
              (recur))))))))

;; =============================================================================
;; Performance Reporting
;; =============================================================================

(defn generate-timing-report
  "Extract performance metrics from test events.

   Returns a map with timing measurements in milliseconds:
   - :scittle-load-ms - Time from extension start to Scittle loaded
   - :injection-overhead-ms - Time from Scittle ready to script injected
   - :bridge-setup-ms - Time from navigation to bridge ready
   - :document-start-delta-ms - Time between loader run and first page script
                                (negative = loader ran first, as expected)

   Returns nil for metrics where required events are missing."
  [events]
  (let [by-event (group-by #(.-event %) events)
        get-perf (fn [event-name]
                   (when-let [evt (first (get by-event event-name))]
                     (.-perf evt)))
        extension-start (get-perf "EXTENSION_STARTED")
        scittle-loaded (get-perf "SCITTLE_LOADED")
        script-injected (get-perf "SCRIPT_INJECTED")
        bridge-ready (get-perf "BRIDGE_READY_CONFIRMED")
        loader-run (get-perf "LOADER_RUN")]
    {:scittle-load-ms (when (and extension-start scittle-loaded)
                        (- scittle-loaded extension-start))
     :injection-overhead-ms (when (and scittle-loaded script-injected)
                              (- script-injected scittle-loaded))
     :bridge-setup-ms (when (and extension-start bridge-ready)
                        (- bridge-ready extension-start))
     :document-start-delta-ms loader-run  ; Raw value - negative means ran before page
     :all-events (map #(.-event %) events)}))

(defn print-timing-report
  "Print formatted timing report to console.
   Highlights metrics that exceed target thresholds."
  [report]
  (println "\n=== Performance Report ===")
  (when-let [ms (:scittle-load-ms report)]
    (println (str "Scittle load time: " (.toFixed ms 2) "ms"
                  (when (> ms 200) " ⚠️  (target: <200ms)"))))
  (when-let [ms (:injection-overhead-ms report)]
    (println (str "Injection overhead: " (.toFixed ms 2) "ms"
                  (when (> ms 50) " ⚠️  (target: <50ms)"))))
  (when-let [ms (:bridge-setup-ms report)]
    (println (str "Bridge setup: " (.toFixed ms 2) "ms"
                  (when (> ms 100) " ⚠️  (target: <100ms)"))))
  (when-let [ms (:document-start-delta-ms report)]
    (println (str "Document-start timing: " (.toFixed ms 2) "ms"
                  (if (>= ms 0)
                    " ⚠️  (loader should run before page scripts)"
                    " ✓ (ran before page scripts)"))))
  (println "\nAll events captured:" (clj->js (:all-events report)))
  (println "==========================\n"))

(defn ^:async wait-for-panel-state-saved
  "Wait for panel state to be saved to storage for the test hostname.
   Use after filling code in panel before closing to ensure async write completes."
  [panel expected-code-pattern]
  (let [check-fn (str "() => new Promise((resolve, reject) => {
      const key = 'panelState:test.example.com';
      const pattern = " (js/JSON.stringify expected-code-pattern) ";
      let attempts = 0;
      const maxAttempts = 50;
      const check = () => {
        attempts++;
        chrome.storage.local.get([key], (result) => {
          const saved = result[key];
          if (saved && saved.code && saved.code.includes(pattern)) {
            resolve(true);
          } else if (attempts >= maxAttempts) {
            reject(new Error('Panel state not saved after ' + attempts + ' attempts. Code: ' + (saved ? saved.code : 'null')));
          } else {
            setTimeout(check, 100);
          }
        });
      };
      check();
    })")]
    (js-await (.evaluate panel check-fn))))



;; =============================================================================
;; Error Assertion Helper - Call at end of each test
;; =============================================================================

(defn ^:async assert-no-errors!
  "Assert that no UNCAUGHT_ERROR or UNHANDLED_REJECTION events were logged.
   Call this at the end of each test before closing the context.
   Uses message API so works from any extension page (popup/panel)."
  [ext-page]
  (let [events (js-await (get-test-events-via-message ext-page))
        errors (.filter events
                        (fn [e]
                          (or (= (.-event e) "UNCAUGHT_ERROR")
                              (= (.-event e) "UNHANDLED_REJECTION"))))]
    (when (pos? (.-length errors))
      (js/console.error "Found errors:" (js/JSON.stringify errors nil 2)))
    (js-await (-> (expect (.-length errors))
                  (.toBe 0)))))