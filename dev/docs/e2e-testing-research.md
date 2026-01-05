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

### Setup

Playwright is already configured as a dev dependency. Tests are compiled separately from the main source:

```bash
bb test:e2e:compile  # Compiles e2e/*.cljs to build/e2e/*.mjs
```

### Playwright Configuration

Two Playwright configs handle different test categories:

```javascript
// playwright.config.js - UI tests (popup, panel)
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './build/e2e',
  testMatch: '**/*_{test,spec}.mjs',
  testIgnore: '**/repl_ui_spec.mjs',  // REPL tests need infrastructure
  timeout: 30000,
  use: { channel: 'chromium' },
  workers: 1,  // Extensions need isolation
});
```

```javascript
// e2e/playwright.repl.config.js - REPL integration tests
// Uses same settings but includes repl_ui_spec.mjs
```

### Test Fixture

The `fixtures.cljs` module provides extension context helpers:

```clojure
;; e2e/fixtures.cljs
(ns fixtures
  (:require ["@playwright/test" :refer [test chromium]]
            ["path" :as path]
            ["url" :as url]))

(def extension-path
  (path/resolve __dirname ".." ".." "dist" "chrome"))

(defn ^:async create-extension-context []
  (js-await
    (.launchPersistentContext chromium ""
      #js {:headless false
           :args #js ["--no-sandbox"
                      (str "--disable-extensions-except=" extension-path)
                      (str "--load-extension=" extension-path)]})))

(defn ^:async get-extension-id [context]
  (let [workers (.serviceWorkers context)]
    (if (pos? (.-length workers))
      (-> (aget workers 0) (.url) (.split "/") (aget 2))
      (let [sw (js-await (.waitForEvent context "serviceworker"))]
        (-> (.url sw) (.split "/") (aget 2))))))

(defn ^:async with-extension [test-fn]
  "Helper that sets up extension context, runs test fn, then cleans up."
  (let [context (js-await (create-extension-context))
        ext-id (js-await (get-extension-id context))]
    (try
      (js-await (test-fn context ext-id))
      (finally
        (js-await (.close context))))))
```

### Popup Tests

```clojure
;; e2e/popup_test.cljs
(ns popup-test
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [with-extension]]))

(test "extension loads and popup renders"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
            (js-await (-> (expect (.locator page "#nrepl-port")) (.toBeVisible)))
            (js-await (-> (expect (.locator page "#ws-port")) (.toBeVisible)))))))))

(test "port inputs accept values"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
            (js-await (.fill page "#nrepl-port" "9999"))
            (js-await (-> (expect (.locator page "#nrepl-port")) (.toHaveValue "9999")))))))))
```

### What Playwright Tests

| Scenario | Why Playwright |
|----------|----------------|
| Extension loads without errors | Verifies build output works |
| Popup UI renders correctly | Real DOM, real CSS |
| Port inputs work | Real form interactions |

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
  fixtures.cljs           ; Extension context setup for popup tests
  playwright.repl.config.js  ; Playwright config for REPL integration
  popup_test.cljs         ; Popup UI tests (3 tests)
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
bb test:e2e          # Popup tests (builds first)
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
| Playwright UI tests | 3 |
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
