# Testing

This document describes Scittle Tamper's testing strategy, the local setup, and the test utilities used by the suite.

## Overview

Testing is organized into three layers:

1. **Unit tests (Vitest)** - fast, pure logic: reducers/action handlers, URL matching, script utilities.
2. **Playwright UI E2E (Squint)** - browser extension UI workflows (popup and DevTools panel).
3. **REPL integration E2E (Babashka + Playwright)** - full pipeline: editor nREPL client -> browser-nrepl relay -> extension -> page Scittle REPL.

A core goal is to extract pure functions and keep most business logic unit-testable.

## Quick commands

- Unit tests once: `bb test`
- Unit test watcher: `bb test:watch`
- UI E2E tests: `bb test:e2e` (or `bb test:e2e:ui`)
- UI E2E tests (Docker, no browser popup): `bb test:e2e:ai`
- REPL integration tests: `bb test:repl-e2e` (or `bb test:repl-e2e:ui`)

CI variants (assume build artifacts exist):
- `bb test:e2e:ci`
- `bb test:repl-e2e:ci`

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

## UI E2E tests (Playwright, written in Squint)

UI E2E tests validate workflows that cannot be unit tested: extension load, DOM rendering, UI interactions, and cross-component storage flows.

**Where**
- Source tests: `e2e/*.cljs`
- Compiled output: `build/e2e/*.mjs`

**Run**
- `bb test:e2e` (headed - browsers visible on host)
- `bb test:e2e:ui` (Playwright UI)
- `bb test:e2e:ai` (Docker - no browser popup on host)

**Docker variant** (`bb test:e2e:ai`):
- Runs tests in a Docker container with virtual display (Xvfb)
- No browser windows appear on the host machine
- Requires Docker to be running
- Builds ARM64 image on Apple Silicon for faster execution
- First run builds the image (~30s), subsequent runs are faster
- Tests run and container exits cleanly when complete

### Design philosophy: consolidated user journeys

Prefer fewer, comprehensive workflow tests over many small isolated tests.

- Each E2E test should represent a complete user journey with multiple sequential phases.
- This reduces browser launches (expensive), tests realistic flows, and catches integration issues.
- Avoid testing timing-based UI feedback in E2E. Prefer unit tests for those.

Current UI E2E suite:
- `e2e/panel_test.cljs` - Panel: evaluation and save workflow
- `e2e/popup_test.cljs` - Popup workflows (2 tests)
- `e2e/integration_test.cljs` - Cross-component script lifecycle

### Fixtures and helpers

Most UI tests use helpers in `e2e/fixtures.cljs`, including:
- `launch-browser` - launch Chromium with the extension loaded
- `get-extension-id` - resolve the installed extension id
- `create-popup-page` - open `popup.html`
- `create-panel-page` - open `panel.html` with a mocked `chrome.devtools` environment
- `clear-storage` - ensure a clean test baseline

### Known limitations and required patterns

**1. chrome-extension pages and `page.evaluate()`**

In Playwright, `page.evaluate()` returns `undefined` for `chrome-extension://` pages. This means tests cannot reliably seed storage by evaluating JS in the extension page.

Working pattern: create scripts through the Panel UI (like a real user) and then verify them in the Popup UI.

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

## REPL integration E2E tests

These tests validate the full REPL pipeline:

`nREPL client -> browser-nrepl (relay server) -> extension background worker -> content bridge -> ws bridge -> Scittle REPL -> page`

**Where**
- Orchestration (Babashka): `e2e/repl_test.clj`
- Playwright helper: `e2e/connect_helper.cljs`
- Squint specs: `e2e/repl_ui_spec.cljs`

**Run**
- `bb test:repl-e2e` (headless)
- `bb test:repl-e2e:ui` (Playwright UI)

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
