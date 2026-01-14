# E2E Testing

Detailed documentation for Playwright E2E tests. For overview, see [testing.md](testing.md).

## Commands

**Default (AI Agents and Automated Testing):**
```bash
bb test:e2e  # All E2E tests (headless in Docker), includes REPL integration
```

**Parallel execution (faster, for humans with multi-core machines):**
```bash
bb test:e2e:parallel --shards 6  # ~16s vs ~32s sequential
```

6 shards is the sweet spot - tests are distributed evenly (round-robin) across Docker containers, each running in isolation. More shards adds diminishing returns due to container startup overhead.

**Only for Human Developers (Visible Browser):**
```bash
bb test:e2e:headed     # E2E tests
```

When you need help from the human's eyes, you can suggest to run the tests in UI mode: `bb test:e2e:ui:headed`

**Filter tests:**

The test tasks take all Playwright options, including `--grep`.

## Infrastructure

### Server Dependencies

| Server | Port | Purpose |
|--------|------|---------|
| HTTP test server | 18080 | Serves `test-data/pages/` |
| browser-nrepl #1 | 12345 (nREPL), 12346 (WS) | Primary REPL relay |
| browser-nrepl #2 | 12347 (nREPL), 12348 (WS) | Multi-tab testing |

Servers are started and available for all e2e test.

### Docker vs Headed

| Mode | Command | Servers | Browser |
|------|---------|---------|---------|
| Docker | `bb test:e2e` | Inside container | Headless via Xvfb |
| Headed | `bb test:e2e:headed` | On host | Visible |
| CI | `bb test:e2e:ci` | On host + xvfb-run | Headless |

### Test Pages

Look for available static HTML in `test-data/pages/`.

## Test Files

All e2e test code is found in `e2e/`. Noting some, but not all, here:

| File | Purpose |
|------|---------|
| `e2e/extension_test.cljs` | Extension infrastructure: startup, test infrastructure, error checking |
| `e2e/integration_test.cljs` | Cross-component script lifecycle |
| `e2e/require_test.cljs` | Scittle library require functionality |
| `e2e/repl_ui_spec.cljs` | REPL integration: nREPL evaluation, DOM access, connections |

## Fixtures and Helpers

There is an extensive library of helpers in `e2e/fixtures.cljs`, covering:

* Browser Setup
* Wait Helpers (Use Instead of Sleep!)
* Runtime Message Helpers
* Log-Powered Test Helpers

## Writing E2E Tests

### Performance: No Fixed Sleeps

**Critical**: Never use fixed-delay sleeps. They waste time and make tests flaky.

| Pattern | Problem | Solution |
|---------|---------|----------|
| `(sleep 500)` after action | Wastes 500ms even if ready in 10ms | Poll with 30ms interval |
| `(sleep 100)` post-networkidle | networkidle already ensures stability | Remove entirely |
| `(sleep 200)` for "state to settle" | Sync operations are immediate | Remove or use assertion timeout |

**Use Playwright's built-in polling assertions:**

```clojure
;; ❌ Bad - wastes time
(js-await (.type (.-keyboard panel) "X"))
(js-await (sleep 100))
(let [value (js-await (.inputValue textarea))]
  (js-await (-> (expect value) (.not.toEqual initial))))

;; ✅ Good - returns immediately when ready
(js-await (.type (.-keyboard panel) "X"))
(js-await (-> (expect textarea)
              (.toHaveValue (js/RegExp. "X$") #js {:timeout 500})))
```

**For custom conditions, use fast polling (30ms):**

```clojure
(defn ^:async wait-for-condition [timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (if (check-condition)
        true
        (if (> (- (.now js/Date) start) timeout-ms)
          (throw (js/Error. "Timeout"))
          (do
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 30))))
            (recur)))))))
```

**The only legitimate sleeps are for negative assertions** - proving nothing happens requires waiting the full duration. Use `assert-no-new-event-within` for these.

### Testing "Nothing Happens"

For tests that verify something should NOT happen (e.g., no reconnection on SPA navigation), use `assert-no-new-event-within` instead of sleeping then checking.

The helper polls every 50ms for the specified timeout, failing immediately if a new event appears. This is both faster (fails fast if event occurs) and more reliable (shorter window = less false negatives).

### Short Timeouts for TDD

When doing test-driven development with E2E tests, use short timeouts (~500ms) to fail fast. Long timeouts slow down the red-green-refactor cycle.

```clojure
;; ✅ Good for TDD - fails quickly when element missing
(js-await (-> (expect (.locator popup ".new-feature"))
              (.toBeVisible #js {:timeout 500})))
```

The fixture wait helpers use 500ms timeouts by default for this reason. Only increase timeouts when you know an operation legitimately takes longer (e.g., network requests, heavy computations).

### Consolidated User Journeys

Prefer comprehensive workflow tests over isolated clicks:

```clojure
(test "Popup: script management workflow"
  (^:async fn []
    ;; === PHASE 1: Initial state ===
    ;; ... verify starting conditions

    ;; === PHASE 2: Create scripts ===
    ;; ... create via panel

    ;; === PHASE 3: Verify and toggle ===
    ;; ... check list, enable/disable

    ;; === PHASE 4: Approval workflow ===
    ;; ... test allow/deny
    ))
```

### Test URL Override

For approval workflow tests, override the "current URL":

```clojure
(let [popup (js-await (.newPage context))]
  (js-await (.addInitScript popup
    "window.__scittle_tamper_test_url = 'https://example.com/';"))
  (js-await (.goto popup popup-url)))
```

## UI Tests vs Log-Powered Tests

### UI Tests
Assert on visible DOM elements. Test what users see and click.

### Log-Powered Tests
Some tests observe internal behavior invisible to UI assertions using event logging. These tests are distributed across feature-specific files (popup_test.cljs, extension_test.cljs, userscript_test.cljs) rather than isolated in a single file.

**Error checking**: Each test calls `assert-no-errors!` before closing extension pages (popup/panel). This checks accumulated events for `UNCAUGHT_ERROR` and `UNHANDLED_REJECTION`, catching errors at the individual test level with good failure locality.

**Pattern:**
1. Extension emits events to `chrome.storage.local` via `test-logger.cljs`
2. Tests trigger actions
3. Tests click dev log button (dumps events to console)
4. Playwright captures console output
5. Assertions run on captured events

**Use log-powered tests for:**
- Userscript injection verification (userscript_test.cljs)
- Timing tests (userscript_test.cljs)
- Internal state transitions (popup_test.cljs, extension_test.cljs)
- Performance measurement (userscript_test.cljs)

**Instrumented events:**
| Event | Purpose |
|-------|---------|
| `EXTENSION_STARTED` | Baseline timing |
| `SCITTLE_LOADED` | Load performance |
| `SCRIPT_INJECTED` | Injection tracking |
| `BRIDGE_READY_CONFIRMED` | Bridge setup overhead |
| `WS_CONNECTED` | Connection tracking |
| `NAVIGATION_STARTED` | Auto-injection pipeline |
| `NAVIGATION_PROCESSED` | Script matching |

## REPL Integration Tests

Full pipeline: `nREPL client -> browser-nrepl -> extension -> Scittle -> page`

### Architecture

```
┌───────────────────┐    ┌───────────────┐    ┌────────────┐    ┌──────────────┐
│ repl_ui_spec.cljs │───>│ browser-nrepl │<──>│ Extension  │<──>│ Test Page    │
│ (Playwright)      │    │ (relay)       │    │ Background │    │ (Scittle)    │
└───────────────────┘    └───────────────┘    └────────────┘    └──────────────┘
```

### Files

| File | Purpose |
|------|---------|
| `e2e/connect_helper.cljs` | Node script for browser automation |
| `e2e/repl_ui_spec.cljs` | Playwright tests for REPL integration |

### Test Coverage

| Test | Purpose |
|------|---------|
| `simple arithmetic evaluation` | Basic arithmetic `(+ 1 2 3)` |
| `string operations` | String operations |
| `DOM access in page context` | Access DOM via `js/document` |
| `multiple forms evaluation` | Multiple form evaluation |
| `get-connections returns connected tab with port` | Connection tracking API |

## Known Limitations

### 1. page.evaluate() on Extension Pages

Returns `undefined` for `chrome-extension://` pages. Workarounds:
- Create data through UI (Panel save workflow)
- Use log-powered assertions

### 2. Tab URL Detection

`chrome.tabs.query` doesn't work reliably in Playwright. Use `__scittle_tamper_test_url` override.

### 3. Async Injection Timing

Keep target page open while waiting for `SCRIPT_INJECTED` event.

### 4. Server Requirements

REPL tests need:
- Ports 12345/12346 for browser-nrepl
- Port 18080 for HTTP server
- Dev/test build (`bb build:test`)

## Troubleshooting

- **Playwright can't launch Chrome**: Run `npx playwright install`
- **Flaky tests**: Check for timing-based assertions; use wait helpers
- **Storage seeding fails**: Use UI actions, not `page.evaluate()`
- **CI failures**: Ensure `xvfb-run` wraps the command
