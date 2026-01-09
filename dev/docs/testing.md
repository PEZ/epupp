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
| `e2e/panel_test.cljs` | Panel: evaluation and save workflow |
| `e2e/popup_test.cljs` | Popup UI workflows (REPL setup, script management, settings) |
| `e2e/integration_test.cljs` | Cross-component script lifecycle (panel -> popup -> panel) |
| `e2e/log_powered_test.cljs` | Internal behavior via event logging (injection, timing) |

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
- `launch-browser` - launch Chromium with the extension loaded
- `get-extension-id` - resolve the installed extension ID
- `create-popup-page` - open `popup.html`
- `create-panel-page` - open `panel.html` with mocked `chrome.devtools` environment
- `clear-storage` - ensure a clean test baseline
- `wait-for-*` helpers - domain-specific waiting (save status, script count, etc.)

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

## REPL integration E2E tests

These tests validate the full REPL pipeline:

`nREPL client -> browser-nrepl (relay server) -> extension background worker -> content bridge -> ws bridge -> Scittle REPL -> page`

**Where**
- Orchestration (Babashka): `e2e/repl_test.clj`
- Playwright helper: `e2e/connect_helper.cljs`
- Squint specs: `e2e/repl_ui_spec.cljs`

**Run**
- `bb test:repl-e2e` (headless in Docker - default)
- `bb test:repl-e2e:headed` (visible browser on host)
- `bb test:repl-e2e:ui:headed` (Playwright UI for debugging)

**Default headless mode** (`bb test:repl-e2e`):
- Runs tests in Docker container with virtual display
- Starts browser-nrepl and HTTP server inside Docker
- Runs the full REPL integration test suite

**Ports and build mode**
- The relay uses ports 12345 (nREPL) and 12346 (WebSocket).
- REPL integration tests require dev config because they use a dev-only message handler (for example `e2e/find-tab-id`).
- Use `bb build:test` when you need a dev build without bumping the manifest version.

**CI**
On Linux in CI, headed Chromium often requires Xvfb:
- `xvfb-run --auto-servernum bb test:e2e:ci`
- `xvfb-run --auto-servernum bb test:repl-e2e:ci`

## Troubleshooting

- If Playwright cannot launch Chromium locally, ensure Playwright browsers are installed (Playwright will prompt; the standard fix is `npx playwright install`).
- If tests are flaky, first check whether they rely on timing-based feedback. Prefer asserting stable state and moving timing logic to unit tests.
- If you need seed data for extension pages, do it via UI actions (Panel save workflow), not via `page.evaluate()`.
