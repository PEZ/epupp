# True E2E Testing via Structured Logging

**Created:** January 9, 2026
**Status:** Planning

This document outlines the approach for testing userscript loading, run-at timing, match targeting, and full REPL interaction using structured log capture.

## Problem Statement

Current Playwright E2E tests are limited by:
1. **Extension page restrictions** - `page.evaluate()` returns `undefined` on chrome-extension:// pages
2. **Service worker isolation** - Background worker logs aren't captured by `page.on('console')`
3. **No visibility into timing** - Can't verify document-start runs before page scripts
4. **No real page testing** - Only test popup/panel, not actual userscript injection

## Solution: Log-Based Assertions

Use structured logging as observable events that tests can capture and assert on.

### Architecture

Simple storage-based approach that works identically in Chrome and Firefox:

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│ Babashka Tests  │────▶│ HTTP Server      │────▶│ Test Pages      │
│ (clojure.test)  │     │ (localhost:8080) │     │ (.html fixtures)│
└────────┬────────┘     └──────────────────┘     └─────────────────┘
         │
         │ 1. Playwright triggers actions
         │ 2. Read events from storage
         │ 3. Assert with clojure.test
         ▼
┌─────────────────┐     ┌─────────────────────────────────────────┐
│ Headless Chrome │────▶│ Extension (any context)                 │
│ or Firefox      │     │ - Background worker                     │
└─────────────────┘     │ - Content bridge                        │
                        │ - Userscript loader                     │
                        └──────────────────┬──────────────────────┘
                                           │
                                           │ log-event! writes to
                                           ▼
                        ┌─────────────────────────────────────────┐
                        │ chrome.storage.local                    │
                        │ {:test-events [{:event "..." ...}]}    │
                        └─────────────────────────────────────────┘
```

**Why `chrome.storage.local`?**
- Works in both Chrome and Firefox (WebExtensions API)
- All extension contexts can write to it (background, content scripts, loader)
- Easy to read via Playwright evaluating in popup/panel page
- No CDP complexity or browser-specific code paths

## Phase 1: Minimal Infrastructure

### 1.1 Test Config

Add `config/test.edn`:
```clojure
{:dev true
 :test true  ; <-- Enables structured test logging
 :depsString "..."
 :allowedScriptOrigins ["http://localhost:"]}
```

### 1.2 Structured Logging Module

Create `src/test_logger.cljs`:
```clojure
(ns test-logger
  "Structured logging for E2E test assertions.
   Only emits when EXTENSION_CONFIG.test is true.

   Writes events to chrome.storage.local for easy retrieval:
   - All contexts (background, content, loader) can write
   - Playwright reads via extension page evaluate
   - Works identically in Chrome and Firefox")

(defn test-mode? []
  (and (exists? js/EXTENSION_CONFIG)
       (.-test js/EXTENSION_CONFIG)))

(defn ^:async log-event!
  "Append structured test event to storage. Only runs in test mode.

   Uses performance.now for high-resolution timing, enabling:
   - Timing assertions (document-start before page scripts)
   - Performance reports (Scittle load time, injection overhead)"
  [event data]
  (when (test-mode?)
    (let [entry {:event event
                 :ts (.now js/Date)              ; Wall clock for correlation
                 :perf (.now js/performance)     ; High-res for timing comparisons
                 :data data}]
      ;; Append to test-events array in storage
      (js-await
       (js/Promise.
        (fn [resolve]
          (.get js/chrome.storage.local #js ["test-events"]
                (fn [result]
                  (let [events (or (.-test-events result) #js [])
                        _ (.push events (clj->js entry))]
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
              (resolve (js->clj (or (.-test-events result) #js [])
                                :keywordize-keys true))))))))
```

**Note for userscript-loader.js:** Since the loader is plain JS (not Squint), it needs its own version:
```javascript
// In userscript-loader.js, add at top:
function logTestEvent(event, data) {
  if (typeof EXTENSION_CONFIG !== 'undefined' && EXTENSION_CONFIG.test) {
    chrome.storage.local.get(['test-events'], (result) => {
      const events = result['test-events'] || [];
      events.push({
        event: event,
        ts: Date.now(),
        perf: performance.now(),
        data: data
      });
      chrome.storage.local.set({ 'test-events': events });
    });
  }
}
```

### 1.3 Instrument Key Points

Add test events at critical flow points:

| Event | Location | Data | Timing Use |
|-------|----------|------|------------|
| `EXTENSION_STARTED` | background.cljs | `{:version "..."}` | Baseline |
| `SCITTLE_LOADED` | background.cljs | `{:tab-id N :url "..."}` | Load perf |
| `SCRIPT_INJECTED` | background.cljs | `{:script-id "..." :timing "..." :url "..."}` | Injection perf |
| `BRIDGE_READY` | content_bridge.cljs | `{:url "..."}` | Bridge overhead |
| `WS_CONNECTED` | background.cljs | `{:tab-id N :port N}` | Connection time |
| `LOADER_RUN` | userscript-loader.js | `{:timing "..." :scripts-found N}` | document-start verification |

### 1.4 Test Page Fixtures

Create `test-data/pages/`:
```
test-data/pages/
├── basic.html          # Simple page for document-idle tests
├── timing-test.html    # Records when scripts run vs page scripts
└── match-test.html     # For URL pattern matching tests
```

Example `timing-test.html`:
```html
<!DOCTYPE html>
<html>
<head>
  <script>
    // Use performance.now for high-resolution comparison with extension events
    window.__PAGE_SCRIPT_PERF = performance.now();
    window.__PAGE_SCRIPT_TIME = Date.now();
    console.log('[PageScript] Executed at', document.readyState, 'perf:', window.__PAGE_SCRIPT_PERF);
  </script>
</head>
<body>
  <div id="timing-marker"></div>
  <script>
    window.__PAGE_LOADED = true;
  </script>
</body>
</html>
```

### 1.5 Test Event Helpers

Add to `e2e/fixtures.cljs`:
```clojure
(defn ^:async clear-test-events!
  "Clear test events in storage. Call at start of each test.
   Must be called from an extension page (popup/panel)."
  [ext-page]
  (js-await
   (.evaluate ext-page
              "chrome.storage.local.set({ 'test-events': [] })")))

(defn ^:async get-test-events
  "Read test events from storage via an extension page.
   Returns vector of event maps with :event, :ts, :perf, :data keys."
  [ext-page]
  (let [result (js-await
                (.evaluate ext-page
                           "new Promise(resolve => {
                              chrome.storage.local.get(['test-events'], result => {
                                resolve(result['test-events'] || []);
                              });
                            })"))]
    (js->clj result :keywordize-keys true)))

(defn ^:async wait-for-event
  "Poll storage until event appears or timeout.
   ext-page: popup or panel page for storage access
   event-name: SCREAMING_SNAKE event name
   timeout-ms: max wait time"
  [ext-page event-name timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [events (js-await (get-test-events ext-page))
            found (first (filter #(= (:event %) event-name) events))]
        (if found
          found
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. (str "Timeout waiting for event: " event-name
                                   ". Events so far: " (pr-str (map :event events)))))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
              (recur))))))))

(defn ^:async export-events-to-file
  "Export test events to JSON file for Babashka consumption."
  [ext-page filepath]
  (let [events (js-await (get-test-events ext-page))
        json (js/JSON.stringify (clj->js events) nil 2)]
    ;; Write to file via Node fs (in Playwright context)
    (js-await (.writeFile (js/require "fs/promises") filepath json))))
```

**Why this is simpler than CDP:**
- No multi-session setup per browser
- No browser-specific code paths
- Firefox and Chrome use identical code
- Easy to debug (just look at storage in DevTools)

### 1.6 BB Task: Test Server

Add to `bb.edn`:
```clojure
test:server {:doc "Start HTTP server for test pages"
             :task (shell "npx http-server test-data/pages -p 8080 -c-1")}
```

## Phase 2: First Test Cases

**Two-layer test approach:**
1. **Playwright (Squint)** - Browser automation: launch, navigate, click, wait
2. **Babashka (`clojure.test`)** - Assertions on exported event data

### 2.1 Test: Extension Startup

```clojure
;; e2e/true_e2e_test.cljs - Playwright automation
(test "True E2E: extension starts and emits startup event"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))
              popup (js-await (create-popup-page context ext-id))]
          (try
            ;; Clear any previous events
            (js-await (clear-test-events! popup))

            ;; Navigate to trigger extension activity
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:8080/basic.html"))

              ;; Wait for event in storage
              (let [started (js-await (wait-for-event popup "EXTENSION_STARTED" 5000))]
                (js-await (-> (expect (:event started)) (.toBe "EXTENSION_STARTED")))))
            (finally
              (js-await (.close context)))))))
```

**Alternative: Export for Babashka assertions**
```clojure
;; At end of Playwright test, export events:
(js-await (export-events-to-file popup "test-results/startup-events.json"))

;; Then in Babashka (e2e/assertions.clj):
(ns e2e.assertions
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]))

(deftest extension-startup
  (let [events (json/parse-string (slurp "test-results/startup-events.json") true)]
    (testing "extension emits startup event"
      (is (some #(= (:event %) "EXTENSION_STARTED") events)))))
```
```

### 2.2 Test: Userscript Injection (document-idle)

```clojure
(test "True E2E: userscript injects on matching URL"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))
              popup (js-await (create-popup-page context ext-id))]
          (try
            ;; Clear events
            (js-await (clear-test-events! popup))

            ;; Create and approve a test script via panel/popup UI
            ;; ... (use existing test patterns from integration_test.cljs)

            ;; Navigate to matching URL in a new page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:8080/basic.html"))

              ;; Wait for injection event
              (let [injected (js-await (wait-for-event popup "SCRIPT_INJECTED" 5000))]
                (js-await (-> (expect (:event injected)) (.toBe "SCRIPT_INJECTED")))
                (js-await (-> (expect (get-in injected [:data :timing])) (.toBe "document-idle")))))
            (finally
              (js-await (.close context)))))))
```

### 2.3 Test: Document-Start Timing

```clojure
(test "True E2E: document-start runs before page scripts"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))
              popup (js-await (create-popup-page context ext-id))]
          (try
            (js-await (clear-test-events! popup))

            ;; ... setup script with document-start timing via panel UI

            ;; Navigate to timing test page
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:8080/timing-test.html"))

              ;; Get loader event performance timestamp from storage
              (let [loader (js-await (wait-for-event popup "LOADER_RUN" 5000))
                    ;; Get page script timestamp from page
                    page-time (js-await (.evaluate page "window.__PAGE_SCRIPT_PERF"))]
                ;; Loader should run before page script (perf time is lower)
                (js-await (-> (expect (< (:perf loader) page-time)) (.toBe true)))))
            (finally
              (js-await (.close context)))))))
```

### 2.4 Test: REPL Connection

```clojure
(test "True E2E: REPL connects and Scittle loads"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))
              popup (js-await (create-popup-page context ext-id))]
          (try
            (js-await (clear-test-events! popup))

            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:8080/basic.html"))

              ;; Start browser-nrepl (use subprocess)
              ;; Connect via popup UI
              ;; ... (existing patterns from repl_test.clj)

              ;; Wait for WebSocket connection event in storage
              (let [ws-event (js-await (wait-for-event popup "WS_CONNECTED" 5000))]
                (js-await (-> (expect (:event ws-event)) (.toBe "WS_CONNECTED"))))

              ;; Verify Scittle loaded
              (let [scittle-event (js-await (wait-for-event popup "SCITTLE_LOADED" 5000))]
                (js-await (-> (expect (:event scittle-event)) (.toBe "SCITTLE_LOADED")))))
            (finally
              (js-await (.close context)))))))
```

## Phase 3: Docker Setup (for `:ai` variants)

Build on existing Docker infrastructure from `Dockerfile.e2e`:

```dockerfile
# Add http-server for test pages
RUN npm install -g http-server

# Copy test pages
COPY test-data/pages /app/test-data/pages

# Expose HTTP server port
EXPOSE 8080
```

Update `bb test:e2e` to:
1. Build with test config
2. Start http-server inside Docker
3. Run storage-based tests (no CDP needed)

## Safari Strategy

Safari doesn't support:
- `registerContentScripts` (document-start timing)
- Headless mode
- Docker

**Decision:** Accept manual testing for Safari. Document the test cases that would be run manually.

## Implementation Order

1. **Create test.edn config** - Add `:test true` flag
2. **Create test-logger.cljs** - Storage-based event logging
3. **Add JS version to userscript-loader.js** - Plain JS `logTestEvent` function
4. **Instrument 3-4 key points** - EXTENSION_STARTED, SCITTLE_LOADED, SCRIPT_INJECTED
5. **Create test pages** - basic.html, timing-test.html
6. **Add storage helpers to fixtures.cljs** - `get-test-events`, `wait-for-event`
7. **Write first test** - Extension startup
8. **Iterate** - Add more events and tests as needed

## Success Criteria for Phase 1

- [ ] Events written to storage from background worker
- [ ] Events written to storage from content bridge
- [ ] Events written to storage from userscript-loader.js
- [ ] Can read events via extension page (popup/panel)
- [ ] Can verify extension startup
- [ ] Can verify userscript injection fires
- [ ] Tests pass in Chrome
- [ ] Tests pass in Firefox (same code)

## Performance Reporting

With `performance.now` on all events, we can generate timing reports:

```clojure
(defn timing-report [events]
  (let [by-event (group-by :event events)
        extension-start (-> (get by-event "EXTENSION_STARTED") first :perf)
        scittle-loaded (-> (get by-event "SCITTLE_LOADED") first :perf)
        script-injected (-> (get by-event "SCRIPT_INJECTED") first :perf)]
    {:scittle-load-ms (- scittle-loaded extension-start)
     :injection-overhead-ms (- script-injected scittle-loaded)}))
```

**Target metrics to track:**
| Metric | Description | Target |
|--------|-------------|--------|
| Scittle load time | Time from extension start to Scittle ready | < 200ms |
| Injection overhead | Time from Scittle ready to script injected | < 50ms |
| Bridge setup | Time from navigation to bridge ready | < 100ms |
| document-start delta | Time between loader run and first page script | < 0 (must be negative) |

## Open Questions

1. ~~**Log verbosity toggle**~~ - **Decided:** Test mode = structured events only. Dev mode = verbose debugging.
2. ~~**Event naming convention**~~ - **Decided:** `SCREAMING_SNAKE_CASE`
3. ~~**Firefox CDP support**~~ - **Resolved:** Using `chrome.storage.local` instead - works in both browsers.

## Decisions Log

| Decision | Rationale |
|----------|----------|
| `SCREAMING_SNAKE_CASE` for events | Clear visual distinction, grep-friendly |
| Test mode = structured events only | Clean separation: test assertions vs debug verbosity |
| `performance.now` for timing | High-resolution timing for perf reports and timing assertions |
| Wall clock (`Date.now`) included | Event correlation across contexts |
| `chrome.storage.local` over CDP | Works in both Chrome and Firefox, simpler code, no browser-specific paths |

## Related Documents

- [testing.md](testing.md) - Existing test strategy
- [architecture.md](architecture.md) - Extension architecture
- [userscripts-architecture.md](userscripts-architecture.md) - Userscript design
