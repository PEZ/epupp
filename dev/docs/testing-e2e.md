# E2E Testing

Detailed documentation for Playwright E2E tests. For overview, see [testing.md](testing.md).

## Commands

**Default (parallel in Docker):**
```bash
bb test:e2e          # All E2E tests, 6 parallel shards (~16s)
bb test:e2e --shards 4  # Customize shard count
```

Use `--` to separate task options from Playwright options.
```bash
bb test:e2e -- --grep "popup"  # Filter with Playwright args
```


**Only for Human Developers (visible browser):**
```bash
bb test:e2e:headed     # E2E tests (visible browser, requires build first)
```

Note: At rare occations Docker build fails for unknowwn reasons. Look closely at failed runs, and see if it is worth trying to just run it again.

When you need help from the human's eyes, you can suggest to run the tests in UI mode: `bb test:e2e:ui:headed`

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

All e2e test code is found in `e2e/`. Test files are split for better parallel distribution:

| File | Purpose |
|------|---------|
| `e2e/extension_test.cljs` | Extension infrastructure: startup, test infrastructure, error checking |
| `e2e/integration_test.cljs` | Cross-component script lifecycle |
| `e2e/popup_core_test.cljs` | REPL setup, scripts, settings, hints |
| `e2e/popup_autoconnect_test.cljs` | Auto-connect and reconnect behavior |
| `e2e/popup_connection_test.cljs` | Connection tracking and status |
| `e2e/popup_icon_test.cljs` | Toolbar icon state |
| `e2e/panel_eval_test.cljs` | Evaluation, selection eval, hints |
| `e2e/panel_save_test.cljs` | Save/create and rename |
| `e2e/panel_state_test.cljs` | Initialization, new button, undo |
| `e2e/require_test.cljs` | Scittle library require functionality |
| `e2e/repl_ui_spec.cljs` | REPL integration: nREPL evaluation, DOM access, connections |
| `e2e/userscript_test.cljs` | Userscript injection and lifecycle |

## Fixtures and Helpers

There is an extensive library of helpers in `e2e/fixtures.cljs`, covering:

* Browser Setup
* Wait Helpers (Use Instead of Sleep!)
* Runtime Message Helpers
* Log-Powered Test Helpers

## Writing E2E Tests

### Test File Structure (Mandatory Pattern)

**All E2E test files MUST follow the flat test structure pattern.** Deeply nested test forms create major editing problems for structural editing tools.

**Required Pattern:**
- Private test functions at top level: `(defn- ^:async test_feature_name [] ...)`
- Single shallow `describe` block at the END of the file
- Simple function references in test registrations: `(test "description" test_feature_name)`
- Shared setup via atoms and setup functions at top of file

**Model File:** [e2e/fs_ui_reactivity_test.cljs](../../e2e/fs_ui_reactivity_test.cljs)

**Why This Matters:**
- Top-level forms are reliably editable by line number
- No deep nesting prevents structural editing failures
- Each test function is independently modifiable
- Shared setup/teardown logic is clear and maintainable

For migration of legacy nested structures, see [e2e-test-structure-migration-plan.md](e2e-test-structure-migration-plan.md).

### Data Attributes for Test Observability

Use `data-e2e-*` prefixed attributes to create explicit contracts between UI code and E2E tests. This decouples tests from implementation details like CSS classes, element structure, and copy text.

**Benefits:**
- **Explicit intent**: When you see `data-e2e-*` in UI code, you know tests depend on it
- **Refactor-safe**: Change CSS classes, DOM structure, or copy freely without breaking tests
- **Self-documenting**: The prefix signals "this exists for E2E observability"
- **Searchable**: `grep data-e2e` shows all test touchpoints in the codebase

**Example - waiting for async state:**

```clojure
;; In UI component (panel.cljs)
[:div.save-script-section {:data-e2e-scripts-count (count scripts-list)}
  ...]

;; In test helper (fixtures.cljs)
(defn ^:async wait-for-scripts-loaded [panel expected-count]
  (let [save-section (.locator panel ".save-script-section")]
    (js-await (-> (expect save-section)
                  (.toHaveAttribute "data-e2e-scripts-count" (str expected-count))))))
```

**When to use data attributes vs CSS selectors:**
- Use `data-e2e-*` for: state values, counts, IDs, statuses - anything tests need to observe
- Use CSS classes for: finding elements that have stable semantic meaning (`.btn-save`, `#code-area`)
- Avoid depending on: text content, styling classes, structural nesting

**Convention**: When updating UI that has `data-e2e-*` attributes, consider whether E2E tests need updating. The prefix makes this relationship visible.

### Fixed Sleeps Are Forbidden

**Critical Policy**: Fixed sleeps are forbidden in E2E tests. This is not just a performance concern - sleeps are a correctness bug that causes flaky tests.

**Why sleeps cause flakiness:**
- A 20ms sleep works locally where operations complete in 5ms
- The same sleep fails in CI where operations sometimes take 50ms
- Increasing the sleep "fixes" it locally but just shifts the race window
- This pattern has contributed to ~90% CI failure rate

**The only acceptable sleeps:**

1. **Poll interval inside a wait loop** - small delay between condition checks:
   ```clojure
   (loop []
     (if (condition-met?)
       result
       (do
         (js-await (sleep 20))  ; Poll interval - ACCEPTABLE
         (recur))))
   ```

2. **Absence assertion** - proving nothing happens requires waiting:
   ```clojure
   (let [initial-count (get-event-count)]
     (js-await (sleep 200))  ; Required for absence test - ACCEPTABLE
     (assert-no-new-events initial-count))
   ```

**Forbidden pattern - standalone sleep before assertion:**
```clojure
(trigger-operation)
(js-await (sleep 20))  ; FORBIDDEN - hoping 20ms is "enough"
(assert-result)
```

**Required pattern - poll for the actual condition:**
```clojure
(trigger-operation)
(js-await (poll-until operation-completed? timeout-ms))
(assert-result)
```

**Before adding any sleep, answer:** "What specific condition am I waiting for?"
- If "for async operation to complete" → poll for completion instead
- If "to verify nothing happens" → document as absence test with comment
- If no clear answer → you don't understand the system well enough to write the test

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
    ))
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

`page.evaluate()` cannot access `chrome.storage.local` (or other Chrome extension APIs) from popup/panel pages - calls return `undefined` silently. This applies to both reading and writing storage.

Workarounds:
- **Reading storage**: Use `send-runtime-message` with `"e2e/get-storage"` to read values through the background worker, which has full API access
- **Writing storage**: Use `send-runtime-message` with `"e2e/set-storage"` to set values through the background worker
- **Mocking extension APIs**: `chrome.tabs.create` and similar APIs are frozen objects in extension contexts and cannot be reassigned or mocked via `Object.defineProperty`. Verify behavior indirectly (e.g., check that the correct value was stored rather than intercepting the API call)
- Create data through UI interactions when possible
- Use log-powered assertions for side effects that can't be observed directly

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
