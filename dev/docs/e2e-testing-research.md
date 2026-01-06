# E2E Testing Strategy for Scittle Tamper

**Created:** January 5, 2026
**Status:** Implemented

This document describes the testing strategy for Scittle Tamper browser extension.

## Testing Philosophy

Our testing strategy follows a hybrid approach optimized for a browser extension with REPL connectivity:

1. **Unit Tests (Vitest)** - Pure functions and action handlers
2. **Playwright E2E** - UI interactions and browser integration
3. **browser-nrepl Integration** - Full REPL pipeline verification

This combination provides fast feedback from unit tests, real browser verification via Playwright, and end-to-end REPL flow testing through the actual nREPL protocol.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Test Infrastructure                          │
├──────────────────┬───────────────────────┬──────────────────────────┤
│   Vitest (Node)  │   Playwright (Chrome)  │   browser-nrepl (bb)     │
│                  │                        │                          │
│  Pure functions  │  Extension loading     │  Full REPL pipeline      │
│  Action handlers │  Popup UI interactions │  WebSocket connectivity  │
│  URL matching    │  Panel evaluation      │  Code evaluation flow    │
│  Script utils    │  Storage operations    │  Multi-tab scenarios     │
└──────────────────┴───────────────────────┴──────────────────────────┘
```

## Layer 1: Unit Tests (Vitest)

Current: 92 tests covering pure functions.

### What to Test

- **Action handlers** - State transitions in Uniflow pattern
- **URL pattern matching** - `url-matches-pattern?` and related functions
- **Script utilities** - ID generation, validation, data transformations
- **Pure UI logic** - Rendering decisions based on state

### Running Tests

```bash
bb test           # Run once
bb test:watch     # Watch mode (Squint + Vitest in parallel)
```

### Architectural Goal

Relentlessly extract pure functions from side-effectful code. Every piece of logic that can be tested without browser APIs should be a pure function tested in Vitest.

```clojure
;; Good: Pure function, easily tested
(defn should-show-approval? [script current-url]
  (and (:script/enabled script)
       (url-matches-pattern? current-url (:script/match script))
       (not (pattern-approved? script current-url))))

;; Test in Vitest
(test "shows approval when pattern not approved"
  (fn []
    (-> (expect (should-show-approval?
                  {:script/enabled true
                   :script/match ["*://github.com/*"]
                   :script/approved-patterns []}
                  "https://github.com/foo"))
        (.toBe true))))
```

## Layer 2: Playwright E2E Tests (Squint)

For testing real browser interactions that cannot be unit tested. Tests are written in Squint and compiled to `.mjs` files that Playwright runs.

### Test Design Philosophy: Consolidated User Journeys

**Prefer fewer, comprehensive workflow tests over many small isolated tests.**

Each E2E test should represent a complete user journey with multiple sequential phases that build on each other. This approach:

- **Reduces browser launches** - Each test launches a fresh browser context, which is expensive (~2-3s overhead)
- **Tests realistic flows** - Users don't perform isolated actions; they complete workflows
- **Catches integration issues** - Sequential operations reveal state management bugs
- **Keeps test count manageable** - Aim for 4-6 comprehensive tests, not 20+ micro-tests

**Structure pattern:**
```clojure
(test "Component: feature workflow"
  (^:async fn []
    (let [context (js-await (launch-browser))
          ext-id (js-await (get-extension-id context))]
      (try
        ;; === PHASE 1: Setup/initial state ===
        ;; ...

        ;; === PHASE 2: Primary action ===
        ;; ...

        ;; === PHASE 3: Verify effects ===
        ;; ...

        ;; === PHASE 4: Related feature ===
        ;; ...
        (finally
          (js-await (.close context)))))))
```

**What NOT to test in E2E:**
- Timing-based UI feedback (e.g., "message disappears after 3 seconds") - test in unit tests
- Pure function logic - belongs in Vitest
- Every edge case - pick representative scenarios

### Current Test Organization

| Test | Coverage |
|------|----------|
| Panel: evaluation and save workflow | UI rendering, code eval, clear results, save form, validation |
| Popup: REPL connection setup | Port inputs, command generation, copy button |
| Popup: script management and approval | Empty state, create via panel, enable/disable, delete, Allow/Deny approval |
| Integration: script lifecycle | Cross-component: panel save -> popup view -> popup edit -> panel receive -> delete |

### Setup

Playwright is configured as a dev dependency. Tests compile separately from main source:

```bash
bb test:e2e:compile  # Compiles e2e/*.cljs to build/e2e/*.mjs
```

### Test Fixtures

The `fixtures.cljs` module provides extension context helpers:

```clojure
;; Key helpers
(defn ^:async launch-browser [] ...)        ; Launch Chrome with extension
(defn ^:async get-extension-id [context] ...)
(defn ^:async create-popup-page [context ext-id] ...)
(defn ^:async create-panel-page [context ext-id] ...)  ; Includes mock chrome.devtools
(defn ^:async clear-storage [page] ...)
(defn ^:async sleep [ms] ...)
```

### Test URL Override for Approval Workflow

The popup uses `chrome.tabs.query` to detect the current URL, which doesn't work reliably in Playwright. Use `addInitScript` to override:

```clojure
;; Set test URL BEFORE navigating - popup reads URL on init
(let [popup (js-await (.newPage context))
      popup-url (str "chrome-extension://" ext-id "/popup.html")]
  (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'https://example.com/';"))
  (js-await (.goto popup popup-url))
  ;; Now popup thinks current tab is on example.com
  ...)
```

This is implemented in `popup.cljs`'s `get-active-tab` function which checks for the override.

### Creating Test Data

**Important:** `page.evaluate()` returns `undefined` for chrome-extension:// pages in Playwright. You cannot seed storage directly via JavaScript evaluation.

**Solution:** Create scripts through the panel UI, just like a real user:

```clojure
;; Create script via panel (the working approach)
(let [panel (js-await (create-panel-page context ext-id))]
  (js-await (.fill (.locator panel "textarea") "(println \"test\")"))
  (js-await (.fill (.locator panel "#script-name") "Test Script"))
  (js-await (.fill (.locator panel "#script-match") "*://example.com/*"))
  (js-await (.click (.locator panel "button.btn-save")))
  (js-await (sleep 300))
  (js-await (.close panel)))
```

### What Playwright Tests

| Scenario | Why Playwright |
|----------|----------------|
| Extension loads without errors | Verifies build output works |
| UI renders correctly | Real DOM, real CSS |
| Cross-component data flow | Panel -> storage -> Popup |
| User interactions | Click, fill, dialog handling |

## Layer 3: browser-nrepl Integration Tests

For testing the full REPL pipeline: Editor -> nREPL -> WebSocket -> Extension -> Page.

### Architecture

```
┌──────────────────┐     ┌─────────────────────┐     ┌─────────────────┐
│  repl_test.clj   │────>│  browser-nrepl      │────>│  Chrome + Ext   │
│  (Babashka)      │     │  (relay server)     │     │  (Playwright)   │
│                  │<────│  nREPL:12345        │<────│                 │
│  nrepl-client    │     │  WebSocket:12346    │     │  Scittle REPL   │
└──────────────────┘     └─────────────────────┘     └─────────────────┘
```

### Test Orchestration

The `repl_test.clj` script orchestrates the full test pipeline:

1. Starts browser-nrepl relay server (nREPL port 12345, WebSocket port 12346)
2. Starts HTTP server on port 8765 with test page
3. Launches Chrome via Playwright helper (`connect_helper.cljs`)
4. Waits for extension to connect to browser-nrepl
5. Runs tests using `babashka/nrepl-client` to eval code in the browser
6. Cleans up all processes

```clojure
;; e2e/repl_test.clj (excerpt)
(deftest string-eval-test
  (testing "String operations evaluate correctly"
    (let [result (eval-code "(str \"Hello\" \" \" \"World\")")]
      (is (= "\"Hello World\"" (:value result))))))

(deftest dom-access-test
  (testing "Can read DOM elements"
    (let [result (eval-code "(-> js/document
                                (.getElementById \"heading\")
                                .-textContent)")]
      (is (= "\"Test Page\"" (:value result))))))

(deftest multi-form-test
  (testing "Multiple forms return last value"
    (let [result (eval-code "(def x 2) (def y 3) (* x y)")]
      (is (= "6" (:value result))))))
```

### Playwright Connect Helper

The `connect_helper.cljs` script handles browser launch and REPL connection:

```clojure
;; e2e/connect_helper.cljs (excerpt)
(defn ^:async main []
  ;; Launch Chrome with extension
  (let [context (js-await (launch-browser extension-path))
        ext-id (js-await (get-extension-id context))
        test-page (js-await (.newPage context))]
    ;; Load test page
    (js-await (.goto test-page test-page-url))
    ;; Navigate to popup and trigger connection
    (let [popup-page (js-await (.newPage context))]
      (js-await (.goto popup-page (str "chrome-extension://" ext-id "/popup.html")))
      ;; Use dev-only e2e/find-tab-id to locate test page tab
      ;; Then send connect-tab message to background worker
      ...)))
```

### Build Configuration

REPL integration tests require dev config for the `e2e/find-tab-id` message handler. The `build:test` task provides dev config without bumping the manifest version:

```bash
bb build:test  # Dev config, no version bump - used by e2e tests
bb build:dev   # Dev config + version bump - for development
bb build       # Prod config - for release
```

### Running REPL Integration Tests

```bash
bb test:repl-e2e     # Full pipeline test
bb test:repl-e2e:ui  # Interactive Playwright UI
```

### Test Scenarios

| Test | Description |
|------|-------------|
| Simple arithmetic | `(+ 1 2 3)` returns `6` |
| String operations | `(str "Hello" " World")` returns `"Hello World"` |
| DOM access | Read `#heading` textContent from test page |
| Multiple forms | `(def x 2) (def y 3) (* x y)` returns `6` |

## File Structure

```
e2e/
  connect_helper.cljs     ; Playwright helper for REPL tests
  fixtures.cljs           ; Extension context setup, page helpers
  integration_test.cljs   ; Cross-component workflow (1 test)
  panel_test.cljs         ; Panel UI workflow (1 test)
  popup_test.cljs         ; Popup UI workflows (2 tests)
  playwright.repl.config.js  ; Playwright config for REPL integration
  repl_test.clj           ; Babashka test orchestration
  repl_ui_spec.cljs       ; Full REPL pipeline tests (4 tests)

build/e2e/               ; Squint-compiled output (via bb test:e2e:compile)
```

## Test Commands

```bash
# Unit tests (Vitest)
bb test              # Run Vitest once
bb test:watch        # Watch mode (Squint + Vitest in parallel)

# E2E tests - Popup/UI (Playwright + Squint)
bb test:e2e          # UI tests (builds first)
bb test:e2e:ci       # CI variant (assumes artifacts exist)
bb test:e2e:ui       # Interactive Playwright UI

# E2E tests - REPL Integration
bb test:repl-e2e     # Full pipeline (builds first)
bb test:repl-e2e:ci  # CI variant (assumes artifacts exist)
bb test:repl-e2e:ui  # Interactive Playwright UI for REPL tests
```

## Coverage

| Layer | Tests |
|-------|-------|
| Unit tests (Vitest) | 92 |
| Playwright UI tests | 4 |
| REPL integration | 4 |

## CI Integration

GitHub Actions runs all tests on every push and PR:

```
┌───────────────┐   ┌─────────────┐
│ build-release │   │ build-test  │  <- Parallel builds
└───────────────┘   └──────┬──────┘
                           │
                   ┌───────┴───────┐
                   ▼               ▼
            ┌────────────┐  ┌────────────┐
            │ unit-tests │  │ e2e-tests  │  <- Parallel tests
            └────────────┘  └────────────┘
                   │               │
                   └───────┬───────┘
                           ▼
                     ┌─────────┐
                     │ release │  <- On version tags only
                     └─────────┘
```

**Workflow:** [.github/workflows/build.yml](../../.github/workflows/build.yml)

**Key details:**
- E2E tests use `xvfb-run` for headed Chrome on Linux
- Test artifacts uploaded on failure for debugging
- Release job requires all tests to pass

## References

- [Playwright Chrome Extensions](https://playwright.dev/docs/chrome-extensions)
- [Chrome E2E Testing Docs](https://developer.chrome.com/docs/extensions/how-to/test/end-to-end-testing)
- [babashka/nrepl-client](https://github.com/babashka/nrepl-client)
- [sci.nrepl.browser-server](https://github.com/babashka/sci.nrepl) - browser-nrepl implementation
