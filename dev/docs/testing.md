# Testing

This document describes Epupp's testing strategy, the local setup, and the test utilities used by the suite.

## Overview

Testing is organized into three layers:

1. **Unit tests (Vitest)** - fast, pure logic: reducers/action handlers, URL matching, script utilities.
2. **Playwright E2E (Squint)** - browser extension workflows including:
   - UI tests (popup and DevTools panel)
   - Log-powered tests for internal extension behavior (injection, timing)
3. **REPL integration E2E (Babashka + Playwright)** - full pipeline: editor nREPL client -> browser-nrepl relay -> extension -> page Scittle REPL.

A core goal is to extract pure functions and keep most business logic unit-testable.

## Quick Commands

**Default (AI Agents and Automated Testing):**
- `bb test` - Unit tests (fast, always run after changes)
- `bb test:e2e` - UI E2E tests (headless in Docker)
- `bb test:repl-e2e` - REPL integration tests (headless in Docker)

**Human Developers (Visible Browser):**
- `bb test:watch` - Unit test watcher
- `bb test:e2e:headed` - UI E2E tests (browsers visible)
- `bb test:e2e:ui:headed` - Playwright UI (interactive debugging)
- `bb test:repl-e2e:headed` - REPL tests (browsers visible)
- `bb test:repl-e2e:ui:headed` - REPL tests with Playwright UI

**CI/CD Only:**
- `bb test:e2e:ci` - E2E without rebuild (GitHub Actions)
- `bb test:repl-e2e:ci` - REPL tests without rebuild (GitHub Actions)

**Filter tests:** Pass `--grep "pattern"` to any Playwright test:
```bash
bb test:e2e --grep "popup"      # Run only popup tests
bb test:e2e --grep "rename"     # Run only rename-related tests
```

## Infrastructure Overview

### Server Dependencies

E2E and REPL tests rely on two servers started automatically by the bb tasks:

| Server | Port | Purpose | Started By |
|--------|------|---------|------------|
| HTTP test server | 18080 | Serves test pages from `test-data/pages/` | `run-e2e-tests!` wrapper |
| browser-nrepl | 12345 (nREPL), 12346 (WS) | REPL relay between editor and browser | `run-e2e-tests!` wrapper |

The `tasks.clj` file provides `with-test-server` and `with-browser-nrepl` wrappers that manage server lifecycle:

```clojure
;; tasks.clj - automatic server management
(defn run-e2e-tests! [args]
  (with-test-server
    #(with-browser-nrepl
       (fn [] (apply p/shell "npx playwright test" args)))))
```

### Docker vs Headed Mode

| Mode | Command | Servers | Browser |
|------|---------|---------|---------|
| Docker (default) | `bb test:e2e` | Inside container | Headless via Xvfb |
| Headed | `bb test:e2e:headed` | On host | Visible on host |
| CI | `bb test:e2e:ci` | On host (with xvfb-run) | Headless |

Docker builds use `Dockerfile.e2e` which includes:
- Playwright base image with Chromium
- Babashka for task orchestration
- Java (required for bb deps resolution)
- Xvfb for virtual display

### Test Data Pages

Static HTML pages in `test-data/pages/` serve as test targets:

| File | Purpose |
|------|---------|
| `basic.html` | Simple page with `#test-marker` element for basic E2E tests |
| `manual-test.html` | Manual testing harness |
| `spa-test.html` | SPA navigation testing |
| `timing-test.html` | Script timing verification |

## Writing Reliable E2E Tests: No Sleep Patterns

**Critical: Never use `sleep()` or arbitrary timeouts in E2E tests.**

Use Playwright's auto-waiting and custom wait helpers from [fixtures.cljs](../../e2e/fixtures.cljs):

```clojure
;; ✅ Good - Playwright waits for button to be actionable
(js-await (.click (.locator page "button.save")))

;; ✅ Good - Playwright waits for condition to be true
(js-await (-> (expect (.locator page ".status"))
              (.toContainText "Ready")))

;; ✅ Good - Custom helpers for domain-specific waiting
(js-await (wait-for-save-status panel "Created"))
(js-await (wait-for-script-count popup 2))
(js-await (wait-for-checkbox-state checkbox false))
(js-await (wait-for-panel-ready panel))

;; ❌ Bad - Never do this
(js-await (.sleep 1000))
```

This approach eliminates flakiness and makes test failures meaningful.

## Unit tests (Vitest)

**What to test**
- Uniflow action handlers: pure state transitions.
- URL pattern matching: `url-matches-pattern?` and helpers.
- Script utilities: IDs, validation, data transformations.

**Where**
- Source tests: `test/*.cljs`
- Compiled output watched by Vitest: `build/test/**/*_test.mjs`

**Run**
- `bb test` for a single run
- `bb test:watch` for watch mode

**Gotchas (Squint/JS interop)**
- Clojure `nil` becomes JS `undefined` in Squint tests. Prefer `.toBeUndefined()` instead of `.toBeNull()`.

## Playwright E2E tests (Squint)

Playwright E2E tests validate workflows that cannot be unit tested: extension load, DOM rendering, UI interactions, cross-component storage flows, and internal extension behavior.

**Where**
- Source tests: `e2e/*.cljs`
- Compiled output: `build/e2e/*.mjs`

**Run**
- `bb test:e2e` (headless in Docker - default)
- `bb test:e2e:headed` (visible browser on host)
- `bb test:e2e:ui:headed` (Playwright UI for debugging)

**Default headless mode** (`bb test:e2e`):
- Runs tests in a Docker container with virtual display (Xvfb)
- No browser windows appear on the host machine
- Requires Docker to be running
- Builds ARM64 image on Apple Silicon for faster execution
- First run builds the image (~30s), subsequent runs are faster
- Pass Playwright options: `bb test:e2e --grep "pattern"`

### Design philosophy: consolidated user journeys

Prefer fewer, comprehensive workflow tests over many small isolated tests.

- Each E2E test should represent a complete user journey with multiple sequential phases.
- This reduces browser launches (expensive), tests realistic flows, and catches integration issues.
- Avoid testing timing-based UI feedback in E2E. Prefer unit tests for those.

### Test files

| File | Purpose |
|------|---------|
| `e2e/popup_test.cljs` | Popup UI: REPL setup, script management, settings, approvals, connection tracking |
| `e2e/panel_test.cljs` | Panel: evaluation and save workflow |
| `e2e/integration_test.cljs` | Cross-component script lifecycle (panel -> popup -> panel) |
| `e2e/log_powered_test.cljs` | Internal behavior via event logging (injection, timing) |
| `e2e/z_final_error_check_test.cljs` | Final validation - catches any uncaught errors |

### UI Tests vs Log-Powered Tests

**UI tests** (popup, panel, integration) assert on visible DOM elements using Playwright's built-in waiting. They test what a user sees and clicks.

**Log-powered tests** observe internal extension behavior that's invisible to standard Playwright assertions. Since `page.evaluate()` returns `undefined` on `chrome-extension://` pages, these tests use a different approach:

1. Extension code emits structured events to `chrome.storage.local` via `test-logger.cljs`
2. Tests trigger actions (navigate, approve scripts, etc.)
3. Tests click a dev log button in popup that dumps events to console
4. Playwright captures console output and parses the event data
5. Assertions run on the captured events

**When to use log-powered tests:**
- Verifying userscript injection occurred (no visible UI feedback)
- Testing timing (document-start vs page scripts)
- Validating internal state transitions
- Performance measurement (events include `performance.now` timestamps)

### Log-powered test infrastructure

**Event logging** (`src/test_logger.cljs`):
```clojure
(log-event! "SCRIPT_INJECTED" {:script-id "..." :tab-id 123})
```

Events are only logged when built with test config (`bb build:test`). Each event includes:
- `:event` - `SCREAMING_SNAKE_CASE` name
- `:ts` - Wall clock timestamp (`Date.now`)
- `:perf` - High-resolution timing (`performance.now`)
- `:data` - Event-specific payload

**Instrumented events:**
| Event | Location | Purpose |
|-------|----------|---------|
| `EXTENSION_STARTED` | background.cljs | Baseline timing |
| `SCITTLE_LOADED` | background.cljs | Load performance |
| `SCRIPT_INJECTED` | background.cljs | Injection tracking |
| `BRIDGE_READY_CONFIRMED` | background.cljs | Bridge setup overhead |
| `WS_CONNECTED` | background.cljs | REPL connection tracking |
| `NAVIGATION_STARTED` | background.cljs | Auto-injection pipeline |
| `NAVIGATION_PROCESSED` | background.cljs | Script matching diagnostics |

**Test helpers** (`e2e/fixtures.cljs`):
```clojure
(get-test-events popup)           ; Read events via dev log button
(wait-for-event popup "SCRIPT_INJECTED" 10000)  ; Poll until event appears
(generate-timing-report events)   ; Extract performance metrics
(print-timing-report report)      ; Formatted console output
```

### Fixtures and helpers

Most tests use helpers in `e2e/fixtures.cljs`:
- `launch-browser` / `create-extension-context` - launch Chromium with the extension loaded
- `get-extension-id` - resolve the installed extension ID
- `create-popup-page` - open `popup.html` and wait for initialization
- `create-panel-page` - open `panel.html` with mocked `chrome.devtools` environment
- `clear-storage` - ensure a clean test baseline
- `wait-for-*` helpers - domain-specific waiting (save status, script count, etc.)

**Runtime message helpers** for background worker communication:
- `send-runtime-message` - Send messages to background via `chrome.runtime.sendMessage`
- `find-tab-id` - Find tab matching URL pattern (requires dev build)
- `connect-tab` - Connect REPL to specific tab
- `get-connections` - Get active REPL connections from background worker

**Test event helpers** for log-powered tests:
- `clear-test-events!` - Clear events in storage before test
- `get-test-events` - Read events via dev log button (workaround for Playwright limitation)
- `wait-for-event` - Poll until specific event appears

### Known limitations and required patterns

**1. chrome-extension pages and `page.evaluate()`**

In Playwright, `page.evaluate()` returns `undefined` for `chrome-extension://` pages. This means tests cannot reliably seed storage by evaluating JS in the extension page.

Working patterns:
- Create scripts through the Panel UI (like a real user)
- Use log-powered assertions for internal behavior

**2. Test URL override for popup approval workflow**

The popup normally uses `chrome.tabs.query` to detect the current tab URL. This does not work reliably in Playwright.

For tests that depend on the "current URL" (for approval UI), set the override before navigating:

```clojure
(let [popup (js-await (.newPage context))
      popup-url (str "chrome-extension://" ext-id "/popup.html")]
  (js-await (.addInitScript popup
                            "window.__scittle_tamper_test_url = 'https://example.com/';"))
  (js-await (.goto popup popup-url)))
```

The override is implemented in `get-active-tab` in `src/popup.cljs`.

**3. Async userscript injection timing**

Userscript injection happens asynchronously after `webNavigation.onCompleted`. When testing injection:
- Keep the target page open while waiting for the `SCRIPT_INJECTED` event
- Use `wait-for-event` to poll for the event before closing pages

**4. REPL integration tests require real servers**

The REPL E2E tests connect to actual browser-nrepl and HTTP servers. These are started automatically by the bb tasks but require:
- Ports 12345/12346 available for browser-nrepl
- Port 18080 for UI tests / 8765 for REPL tests HTTP server
- A test build with dev config (`bb build:test`)

## REPL integration E2E tests

These tests validate the full REPL pipeline:

`nREPL client -> browser-nrepl (relay server) -> extension background worker -> content bridge -> ws bridge -> Scittle REPL -> page`

**Where**
- Orchestration (Babashka): `e2e/repl_test.clj` - Clojure test namespace with setup/teardown
- Playwright helper: `e2e/connect_helper.cljs` - Node script for browser automation
- Squint specs: `e2e/repl_ui_spec.cljs` - Playwright tests for UI mode

**Run**
- `bb test:repl-e2e` (headless in Docker - default)
- `bb test:repl-e2e:headed` (visible browser on host)
- `bb test:repl-e2e:ui:headed` (Playwright UI for debugging)

### Architecture

The REPL integration tests have a more complex setup than UI tests because they require:

1. **browser-nrepl server** - Babashka relay on ports 12345/12346
2. **HTTP test server** - Serves test page on port 8765 (separate from UI tests' 18080)
3. **Playwright browser** - Launches Chrome with extension, connects to test page
4. **nREPL client** - Babashka's nrepl-client sends eval requests

```
┌─────────────┐    ┌───────────────┐    ┌────────────┐    ┌──────────────┐
│ repl_test   │───>│ browser-nrepl │<──>│ Extension  │<──>│ Test Page    │
│ (Babashka)  │    │ (relay)       │    │ Background │    │ (Scittle)    │
└─────────────┘    └───────────────┘    └────────────┘    └──────────────┘
     ↓ nREPL                               ↑ WS              ↑ inject
     └───────────────────────────────────────────────────────┘
```

### Execution modes

**Babashka-orchestrated** (`bb test:repl-e2e:headed`):
1. `repl_test/run-integration-tests` starts servers
2. `connect_helper.mjs` launches browser and connects via background APIs
3. Babashka tests send nREPL eval requests and assert on results
4. Cleanup destroys all processes

**Playwright UI mode** (`bb test:repl-e2e:ui:headed`):
1. `repl_test/start-servers!` starts only browser-nrepl + HTTP server
2. Playwright's `--ui` runner manages browser lifecycle
3. `repl_ui_spec.cljs` tests run in interactive Playwright UI
4. Useful for debugging - can step through tests visually

### Test coverage

| Test | Purpose |
|------|---------|
| `simple-eval-test` | Basic arithmetic `(+ 1 2 3)` |
| `string-eval-test` | String operations |
| `dom-access-test` | Access page DOM via `js/document` |
| `multi-form-test` | Multiple form evaluation |
| `get-connections-test` | Verify connection tracking API |

### Default headless mode

`bb test:repl-e2e` runs in Docker:
- Starts browser-nrepl and HTTP server inside container
- Uses Xvfb for virtual display
- Full isolation from host environment

**Ports and build mode**
- The relay uses ports 12345 (nREPL) and 12346 (WebSocket)
- REPL integration tests require dev/test config for the `e2e/find-tab-id` message handler
- Use `bb build:test` for dev config without version bump

**CI**
On Linux in CI, headed Chromium often requires Xvfb:
```bash
xvfb-run --auto-servernum bb test:e2e:ci
xvfb-run --auto-servernum bb test:repl-e2e:ci
```

## Troubleshooting

- If Playwright cannot launch Chromium locally, ensure Playwright browsers are installed (Playwright will prompt; the standard fix is `npx playwright install`).
- If tests are flaky, first check whether they rely on timing-based feedback. Prefer asserting stable state and moving timing logic to unit tests.
- If you need seed data for extension pages, do it via UI actions (Panel save workflow), not via `page.evaluate()`.
