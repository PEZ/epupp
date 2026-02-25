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
;; Script Count Constants
;; =============================================================================

(def builtin-script-count
  "Number of built-in scripts in test builds.
   Test/dev builds include security-probe in addition to base built-ins."
  3)

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
    (js-await (.goto panel-page panel-url #js {:timeout 3000}))
    ;; Wait for panel to be fully initialized - code-area indicates JS has loaded
    (js-await (-> (expect (.locator panel-page "#code-area"))
                  (.toBeVisible #js {:timeout 3000})))
    panel-page))

(defn- mock-devtools-script-for-tab
  "Generate mock devtools script for a specific tab ID."
  [tab-id]
  (str "
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
        tabId: " tab-id "
      },
      network: {
        onNavigated: { addListener: function() {} }
      },
      panels: { themeName: 'dark' }
    };
  }
  "))

(defn ^:async create-panel-page-for-tab
  "Create a panel page with mocked chrome.devtools for a specific tab ID.
   Use this when you need the panel to think it's inspecting a real test tab."
  [context ext-id tab-id]
  (let [panel-page (js-await (.newPage context))
        panel-url (str "chrome-extension://" ext-id "/panel.html")]
    (js-await (.addInitScript panel-page (mock-devtools-script-for-tab tab-id)))
    (js-await (.goto panel-page panel-url #js {:timeout 3000}))
    (js-await (-> (expect (.locator panel-page "#code-area"))
                  (.toBeVisible #js {:timeout 3000})))
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
    (js-await (.goto popup-page popup-url #js {:timeout 3000}))
    ;; Wait for popup to be fully initialized - nrepl-port input indicates JS has loaded
    (js-await (-> (expect (.locator popup-page "#nrepl-port"))
                  (.toBeVisible #js {:timeout 3000})))
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

(defn ^:async clear-fs-scripts
  "Clear stored scripts except built-ins.
   Leaves built-ins intact to avoid races with sync-builtin-scripts!."
  [ext-page]
  (let [scripts-result (js-await (send-runtime-message ext-page "e2e/get-storage" #js {:key "scripts"}))
        scripts (or (.-value scripts-result) #js [])
        builtins (.filter scripts
                          (fn [s]
                            (let [id (.-id s)]
                              (and id (.startsWith id "epupp-builtin-")))))
        _ (js-await (send-runtime-message ext-page "e2e/set-storage" #js {:key "scripts" :value builtins}))]
    true))

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

(defn ^:async activate-tab
  "Activate a tab and focus its window via background message."
  [ext-page tab-id]
  (let [result (js-await (send-runtime-message ext-page "e2e/activate-tab" #js {:tabId tab-id}))]
    (if (and result (.-success result))
      true
      (throw (js/Error. (str "Failed to activate tab: " (or (.-error result) "unknown error")))))))

(defn ^:async update-icon
  "Force icon update for a tab via background message (logs ICON_STATE_CHANGED)."
  [ext-page tab-id]
  (let [result (js-await (send-runtime-message ext-page "e2e/update-icon" #js {:tabId tab-id}))]
    (if (and result (.-success result))
      true
      (throw (js/Error. (str "Failed to update icon: " (or (.-error result) "unknown error")))))))

(defn ^:async get-icon-display-state
  "Get the current display icon state for a tab.
   Returns state string (\"disconnected\", \"injected\", \"connected\") or throws."
  [ext-page tab-id]
  (let [result (js-await (send-runtime-message ext-page "e2e/get-icon-display-state" #js {:tabId tab-id}))]
    (if (and result (.-success result))
      (.-state result)
      (throw (js/Error. (str "Failed to get icon state: " (or (.-error result) "unknown error")))))))

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
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
              (recur))))))))

;; =============================================================================
;; Wait Helpers - Use these instead of sleep for reliable tests
;; =============================================================================

(defn ^:async poll-until
  "Generic polling helper. Calls pred-fn repeatedly until it returns truthy or timeout.
   Returns the truthy value on success, throws on timeout.

   pred-fn: Zero-arg function that returns falsy to continue polling, truthy to stop.
            Can be async (return a Promise).
   timeout-ms: Maximum time to wait before throwing.
   poll-interval: (optional) Milliseconds between polls, defaults to 20."
  ([pred-fn timeout-ms]
   (poll-until pred-fn timeout-ms 20))
  ([pred-fn timeout-ms poll-interval]
   (let [start (.now js/Date)]
     (loop []
       (let [result (js-await (pred-fn))]
         (if result
           result
           (if (> (- (.now js/Date) start) timeout-ms)
             (throw (js/Error. "Timeout in poll-until"))
             (do
               (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve poll-interval))))
               (recur)))))))))

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
                (.toHaveCount n #js {:timeout 3000}))))

(defn ^:async wait-for-save-status
  "Wait for system banner to appear with expected text (e.g., 'Created', 'Saved', 'Renamed').
   Use after clicking save/rename button instead of sleep.
   Works with both popup (.system-banner) and panel banners.
   With multi-banner support, uses :has-text filter and .first to handle multiple matches."
  [page text]
  ;; Use :has-text filter then .first to handle multiple banners with same text
  (let [banner (.first (.locator page (str ".system-banner:has-text(\"" text "\")")))]
    (js-await (-> (expect banner)
                  (.toBeVisible #js {:timeout 3000})))))

(defn ^:async wait-for-checkbox-state
  "Wait for checkbox to reach expected checked state.
   Use after toggling checkboxes instead of sleep."
  [checkbox checked?]
  (if checked?
    (js-await (-> (expect checkbox) (.toBeChecked #js {:timeout 3000})))
    (js-await (-> (expect checkbox) (.not.toBeChecked #js {:timeout 3000})))))

(defn ^:async wait-for-panel-ready
  "Wait for panel to be ready after reload/navigation.
   Useful after .reload() calls instead of sleep."
  [panel]
  (js-await (-> (expect (.locator panel "#code-area"))
                (.toBeVisible #js {:timeout 3000}))))

(defn ^:async wait-for-popup-ready
  "Wait for popup to be ready after reload/navigation.
   Useful after .reload() calls instead of sleep."
  [popup]
  (js-await (-> (expect (.locator popup ".popup-header"))
                (.toBeVisible #js {:timeout 3000}))))

(defn ^:async wait-for-edit-hint
  "Wait for the edit hint message to appear in popup as a system banner.
   Use after clicking edit button instead of sleep.
   With multi-banner, we just need at least one banner to be visible."
  [popup]
  (js-await (-> (expect (.first (.locator popup ".system-banner")))
                (.toBeVisible #js {:timeout 3000}))))

(defn ^:async wait-for-scripts-loaded
  "Wait for panel to load scripts-list from storage.
   Uses data-e2e-scripts-count attribute on save-script-section.
   Call after wait-for-panel-ready when testing conflict detection."
  [panel expected-count]
  (let [save-section (.locator panel ".save-script-section")]
    (js-await (-> (expect save-section)
                  (.toHaveAttribute "data-e2e-scripts-count" (str expected-count))))))

(defn ^:async wait-for-property-value
  "Wait for a property-row to have a specific value.
   Uses data-e2e-property attribute to find the row.
   Example: (wait-for-property-value panel \"name\" \"my_script.cljs\")"
  [panel property-name expected-value]
  (let [row (.locator panel (str "[data-e2e-property=\"" property-name "\"] .property-value"))]
    (js-await (-> (expect row)
                  (.toContainText expected-value #js {:timeout 3000})))))

(defn ^:async wait-for-editing-state
  "Wait for panel to be in editing or new-script state.
   Uses data-e2e-editing attribute on save-script-section."
  [panel editing?]
  (let [save-section (.locator panel ".save-script-section")]
    (js-await (-> (expect save-section)
                  (.toHaveAttribute "data-e2e-editing" (str editing?) #js {:timeout 3000})))))

(defn ^:async wait-for-conflict-state
  "Wait for panel to be in name conflict state or not.
   Uses data-e2e-conflict attribute on save-script-section."
  [panel has-conflict?]
  (let [save-section (.locator panel ".save-script-section")]
    (js-await (-> (expect save-section)
                  (.toHaveAttribute "data-e2e-conflict" (str has-conflict?) #js {:timeout 3000})))))

(defn ^:async wait-for-banner-type
  "Wait for a system banner of a specific type to appear.
   Uses data-e2e-banner-type attribute."
  [page banner-type]
  (let [banner (.locator page (str "[data-e2e-banner-type=\"" banner-type "\"]"))]
    (js-await (-> (expect (.first banner))
                  (.toBeVisible #js {:timeout 3000})))))

(defn ^:async wait-for-scittle-status
  "Wait for Scittle to reach a specific status.
   Uses data-e2e-scittle-status attribute on code-input-area."
  [panel status]
  (let [code-area (.locator panel ".code-input-area")]
    (js-await (-> (expect code-area)
                  (.toHaveAttribute "data-e2e-scittle-status" status #js {:timeout 5000})))))

(defn ^:async wait-for-connection-count
  "Wait for popup to show specific number of connections.
   Uses data-e2e-connection-count attribute on repl-connect section."
  [popup expected-count]
  (let [section (.locator popup "[data-e2e-section=\"repl-connect\"]")]
    (js-await (-> (expect section)
                  (.toHaveAttribute "data-e2e-connection-count" (str expected-count) #js {:timeout 5000})))))

(defn get-script-item
  "Get a script item locator by name using data-script-name attribute.
   More reliable than :has-text() which is a substring match.
   Returns a Playwright Locator."
  [page script-name]
  (.locator page (str ".script-item[data-script-name=\"" script-name "\"]")))

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
      (let [events (js-await (get-test-events-via-message ext-page))
            found (first (filter #(= (.-event %) event-name) events))]
        (if found
          found
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. (str "Timeout waiting for event: " event-name
                                   ". Events so far: " (js/JSON.stringify (clj->js (map #(.-event %) events))))))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
              (recur))))))))

(defn ^:async assert-no-new-event-within
  "Assert that no NEW event with given name occurs within timeout-ms.
  Polls rapidly (every 20ms) and fails immediately if count increases.

   initial-count: The number of events of this type that existed before the action
   Use for tests that verify something should NOT happen."
  [ext-page event-name initial-count timeout-ms]
  (let [start (.now js/Date)
      poll-interval 20]
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