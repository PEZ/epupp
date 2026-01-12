# E2E Testing

Detailed documentation for Playwright E2E tests. For overview, see [testing.md](testing.md).

## Commands

**Default (AI Agents and Automated Testing):**
```bash
bb test:e2e  # All E2E tests (headless in Docker), includes REPL integration
```

**Human Developers (Visible Browser):**
```bash
bb test:e2e:headed     # E2E tests
bb test:e2e:ui:headed  # Playwright interactive UI
```

**CI/CD Only:**
```bash
bb test:e2e:ci  # Assumes build artifacts exist
```

**Filter tests:**
```bash
bb test:e2e --grep "popup"
bb test:e2e --grep "REPL"
```

## Infrastructure

### Server Dependencies

| Server | Port | Purpose |
|--------|------|---------|
| HTTP test server | 18080 | Serves `test-data/pages/` |
| browser-nrepl | 12345 (nREPL), 12346 (WS) | REPL relay |

Servers start automatically via `tasks.clj` wrappers:

```clojure
(defn run-e2e-tests! [args]
  (with-test-server
    #(with-browser-nrepl
       (fn [] (apply p/shell "npx playwright test" args)))))
```

### Docker vs Headed

| Mode | Command | Servers | Browser |
|------|---------|---------|---------|
| Docker | `bb test:e2e` | Inside container | Headless via Xvfb |
| Headed | `bb test:e2e:headed` | On host | Visible |
| CI | `bb test:e2e:ci` | On host + xvfb-run | Headless |

### Test Pages

Static HTML in `test-data/pages/`:

| File | Purpose |
|------|---------|
| `basic.html` | Simple page with `#test-marker` for basic tests |
| `manual-test.html` | Manual testing harness |
| `spa-test.html` | SPA navigation testing |
| `timing-test.html` | Script timing verification |

## Test Files

| File | Purpose |
|------|---------|
| `e2e/popup_test.cljs` | Popup UI: REPL setup, scripts, settings, approvals, connections |
| `e2e/panel_test.cljs` | Panel: evaluation and save workflow |
| `e2e/integration_test.cljs` | Cross-component script lifecycle |
| `e2e/log_powered_test.cljs` | Internal behavior via event logging |
| `e2e/repl_ui_spec.cljs` | REPL integration: nREPL evaluation, DOM access, connections |
| `e2e/z_final_error_check_test.cljs` | Final validation for uncaught errors |

## Fixtures and Helpers

All helpers in `e2e/fixtures.cljs`:

### Browser Setup
- `launch-browser` / `create-extension-context` - Launch Chrome with extension
- `get-extension-id` - Extract extension ID from service worker URL
- `create-popup-page` - Open popup.html, wait for initialization
- `create-panel-page` - Open panel.html with mocked `chrome.devtools`
- `clear-storage` - Reset extension storage

### Wait Helpers (Use Instead of Sleep!)
- `wait-for-save-status` - Wait for save status text
- `wait-for-script-count` - Wait for N scripts in list
- `wait-for-checkbox-state` - Wait for checked/unchecked
- `wait-for-panel-ready` - Wait for panel initialization
- `wait-for-popup-ready` - Wait for popup initialization
- `wait-for-edit-hint` - Wait for edit hint visibility

### Runtime Message Helpers
- `send-runtime-message` - Send to background worker
- `find-tab-id` - Find tab by URL pattern (requires dev build)
- `connect-tab` - Connect REPL to tab
- `get-connections` - Get active connections

### Log-Powered Test Helpers
- `clear-test-events!` - Clear events before test
- `get-test-events` - Read events via dev log button
- `wait-for-event` - Poll until event appears

## Writing E2E Tests

### No Sleep Patterns

**Critical**: Never use arbitrary timeouts. Use Playwright's auto-waiting:

```clojure
;; ✅ Good - Playwright waits automatically
(js-await (.click (.locator page "button.save")))
(js-await (-> (expect (.locator page ".status"))
              (.toContainText "Saved")))

;; ✅ Good - Custom wait helpers
(js-await (wait-for-save-status panel "Created"))
(js-await (wait-for-script-count popup 2))

;; ❌ Bad - Never do this
(js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 1000))))
```

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
Observe internal behavior invisible to UI assertions:

1. Extension emits events to `chrome.storage.local` via `test-logger.cljs`
2. Tests trigger actions
3. Tests click dev log button (dumps events to console)
4. Playwright captures console output
5. Assertions run on captured events

**Use log-powered tests for:**
- Userscript injection verification
- Timing tests (document-start vs page scripts)
- Internal state transitions
- Performance measurement

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
