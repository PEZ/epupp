# True E2E Testing via Structured Logging

**Created:** January 9, 2026
**Status:** Phase 1 Infrastructure Complete

This document describes the test infrastructure for verifying userscript loading, run-at timing, match targeting, and full REPL interaction using structured event capture.

## Overview

True E2E tests observe extension behavior through structured events logged to `chrome.storage.local`. This approach works around Playwright limitations with extension pages while providing visibility into timing and cross-context flows.

### Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│ Playwright      │────▶│ HTTP Server      │────▶│ Test Pages      │
│ (Squint E2E)    │     │ (localhost:8000) │     │ (.html fixtures)│
└────────┬────────┘     └──────────────────┘     └─────────────────┘
         │
         │ 1. Triggers user actions
         │ 2. Clicks dev log button
         │ 3. Captures console output
         ▼
┌─────────────────┐     ┌─────────────────────────────────────────┐
│ Headless Chrome │────▶│ Extension (any context)                 │
│ (via Docker)    │     │ - Background worker                     │
└─────────────────┘     │ - Content bridge                        │
                        │ - Userscript loader                     │
                        └──────────────────┬──────────────────────┘
                                           │
                                           │ log-event! writes to
                                           ▼
                        ┌─────────────────────────────────────────┐
                        │ chrome.storage.local                    │
                        │ {"test-events": [{event: "...", ...}]} │
                        └──────────────────┬──────────────────────┘
                                           │
                                           │ Dev log button reads &
                                           │ console.logs with marker
                                           ▼
                        ┌─────────────────────────────────────────┐
                        │ Playwright console capture              │
                        │ page.on('console') → parse JSON         │
                        └─────────────────────────────────────────┘
```

### Why This Approach

**Problem:** `page.evaluate()` returns `undefined` on chrome-extension:// pages in Playwright.

**Solution:** Dev log button in popup that reads storage and emits to console with a marker prefix (`__EPUPP_DEV_LOG__`). Playwright captures console output via `page.on('console')`.

This serves dual purposes:
1. **Developer UX** - Visible button for debugging during manual testing
2. **E2E Test Access** - Programmatic event retrieval via console capture

## Implemented Infrastructure

### Test Configuration

**`config/test.edn`:**
```clojure
{:dev true
 :test true
 :depsString "..."
 :allowedScriptOrigins ["http://localhost:"]}
```

Build with: `bb build:test`

### Event Logging Module

**`src/test_logger.cljs`** - Structured logging that only runs when `EXTENSION_CONFIG.test` is true:

```clojure
(log-event! "EVENT_NAME" {:key "value"})  ; Append event to storage
(clear-test-events!)                       ; Clear all events
(get-test-events)                          ; Retrieve events
```

Each event includes:
- `:event` - SCREAMING_SNAKE name
- `:ts` - Wall clock timestamp (Date.now)
- `:perf` - High-resolution timing (performance.now)
- `:data` - Event-specific payload

**Squint gotcha:** Uses `(aget result "test-events")` for bracket notation access. The dot notation `(.-test-events result)` converts hyphens to underscores.

### Instrumented Events

| Event | Location | Data | Purpose |
|-------|----------|------|---------|
| `EXTENSION_STARTED` | background.cljs | `{:version "..."}` | Baseline timing |
| `SCITTLE_LOADED` | background.cljs | `{:tab-id N :url "..."}` | Load performance |
| `SCRIPT_INJECTED` | background.cljs | `{:script-id "..." :timing "..." :url "..."}` | Injection tracking |
| `BRIDGE_READY` | content_bridge.cljs | `{:url "..."}` | Bridge overhead |
| `LOADER_RUN` | userscript-loader.js | `{:timing "..." :scripts-found N}` | document-start verification |

### Dev Log Button

Visible in popup when built with test config. Located in the "Settings" section:

```clojure
;; In popup.cljs
(defn dev-log-button []
  [:button.dev-log-btn {:on-click #(dispatch! [[:popup/ax.dump-dev-log]])}
   "Dump Dev Log"])
```

Clicking outputs: `__EPUPP_DEV_LOG__[{...events...}]`

### Test Helpers

**`e2e/fixtures.cljs`** additions:

```clojure
(get-test-events popup timeout-ms)   ; Capture via console listener
(wait-for-event popup event-name timeout-ms)  ; Poll for specific event
(clear-test-events! popup)           ; Clear storage via evaluate
```

The console capture pattern:
1. Set up `page.on('console')` listener
2. Click dev log button
3. Parse JSON from message with marker prefix
4. Return parsed events

### Test Page Fixtures

**`test-data/pages/`:**
- `basic.html` - Simple page for document-idle tests
- `timing-test.html` - Records `window.__PAGE_SCRIPT_PERF` for timing comparisons

### BB Tasks

```clojure
test:server  ; Start HTTP server on port 8000 for test pages
build:test   ; Build extension with test config (no version bump)
```

## Writing True E2E Tests

**File:** `e2e/true_e2e_test.cljs`

```clojure
(test "extension starts and emits startup event"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))
              popup (js-await (create-popup-page context ext-id))]
          (try
            (js-await (clear-test-events! popup))
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:8000/basic.html"))
              (let [events (js-await (get-test-events popup 5000))
                    started (first (filter #(= "EXTENSION_STARTED" (:event %)) events))]
                (js-await (-> (expect started) (.toBeTruthy)))))
            (finally
              (js-await (.close context)))))))
```

### Running Tests

```bash
bb test:e2e          # All E2E tests in Docker (headless)
bb test:e2e --grep "True E2E"  # Only true E2E tests
```
```

## Remaining Work

### Additional Events to Instrument

| Event | Location | Data | Purpose |
|-------|----------|------|---------|
| `WS_CONNECTED` | background.cljs | `{:tab-id N :port N}` | REPL connection tracking |
| `WS_DISCONNECTED` | background.cljs | `{:tab-id N :reason "..."}` | Disconnect tracking |
| `USERSCRIPT_EVALUATED` | ws_bridge.cljs | `{:script-id "..."}` | Execution confirmation |

### Test Cases to Write

**Userscript Injection (document-idle):**
```clojure
(test "userscript injects on matching URL"
      (^:async fn []
        ;; 1. Create script via panel UI
        ;; 2. Approve via popup UI
        ;; 3. Navigate to matching URL
        ;; 4. Wait for SCRIPT_INJECTED event
        ;; 5. Verify script-id and timing in event data
        ))
```

**Document-Start Timing:**
```clojure
(test "document-start runs before page scripts"
      (^:async fn []
        ;; 1. Create script with {:epupp/run-at "document-start"}
        ;; 2. Approve and navigate to timing-test.html
        ;; 3. Get LOADER_RUN event's perf timestamp
        ;; 4. Get window.__PAGE_SCRIPT_PERF from page
        ;; 5. Assert loader perf < page script perf
        ))
```

**REPL Connection:**
```clojure
(test "REPL connects and Scittle loads"
      (^:async fn []
        ;; 1. Start browser-nrepl subprocess
        ;; 2. Navigate to test page
        ;; 3. Connect via popup UI
        ;; 4. Wait for WS_CONNECTED event
        ;; 5. Verify SCITTLE_LOADED event
        ))
```

### Firefox Testing

The infrastructure should work identically in Firefox (same WebExtensions API). Verify by:
1. Building Firefox version with test config
2. Running true E2E tests against Firefox
3. Confirming same events captured

### Performance Reporting

With `performance.now` on all events, generate timing reports:

```clojure
(defn timing-report [events]
  (let [by-event (group-by :event events)
        extension-start (-> (get by-event "EXTENSION_STARTED") first :perf)
        scittle-loaded (-> (get by-event "SCITTLE_LOADED") first :perf)
        script-injected (-> (get by-event "SCRIPT_INJECTED") first :perf)]
    {:scittle-load-ms (- scittle-loaded extension-start)
     :injection-overhead-ms (- script-injected scittle-loaded)}))
```

**Target metrics:**
| Metric | Description | Target |
|--------|-------------|--------|
| Scittle load time | Extension start to Scittle ready | < 200ms |
| Injection overhead | Scittle ready to script injected | < 50ms |
| Bridge setup | Navigation to bridge ready | < 100ms |
| document-start delta | Loader run vs first page script | < 0 (must be negative) |

## Safari Strategy

Safari doesn't support:
- `registerContentScripts` (document-start timing)
- Headless mode
- Docker

**Decision:** Accept manual testing for Safari. The test infrastructure documents what to verify manually.

## Decisions Log

| Decision | Rationale |
|----------|-----------|
| `SCREAMING_SNAKE_CASE` for events | Clear visual distinction, grep-friendly |
| Test mode = structured events only | Clean separation from dev debugging |
| `performance.now` for timing | High-resolution timing for perf reports |
| Wall clock (`Date.now`) included | Event correlation across contexts |
| Console capture via button | Works around `page.evaluate()` limitation on extension pages |
| Dev log button dual-purpose | Useful for developers AND E2E tests |

## Related Documents

- [testing.md](testing.md) - Test strategy overview
- [architecture/overview.md](../architecture/overview.md) - Extension architecture
- [userscripts-architecture.md](userscripts-architecture.md) - Userscript design
