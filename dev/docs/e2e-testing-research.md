# E2E Testing Strategy for Scittle Tamper

**Created:** January 5, 2026
**Status:** Decision

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

For testing real browser interactions that cannot be unit tested. Tests are written in Squint, following the same pattern as our Vitest unit tests.

### Setup

Add Playwright to dev dependencies:

```bash
npm install -D @playwright/test
npx playwright install chromium
```

### Squint Configuration

Add e2e test path to `squint.edn`:

```clojure
{:paths ["src" "test" "e2e"]
 :output-dir "build"
 :extension "mjs"}
```

### Playwright Configuration

Configure Playwright to find compiled `.mjs` test files:

```javascript
// playwright.config.js
import { defineConfig } from '@playwright/test';
import path from 'path';

export default defineConfig({
  testDir: './build/e2e',
  testMatch: '**/*_test.mjs',
  use: {
    channel: 'chromium',
  },
});
```

### Test Fixture (Squint)

```clojure
;; e2e/fixtures.cljs
(ns fixtures
  (:require ["@playwright/test" :refer [test chromium]]
            ["path" :as path]))

(def extension-path
  (path/join js/__dirname ".." "extension"))

(defn ^:async create-extension-context []
  (js-await
    (chromium/launchPersistentContext ""
      #js {:channel "chromium"
           :args #js [(str "--disable-extensions-except=" extension-path)
                      (str "--load-extension=" extension-path)]})))

(defn ^:async get-extension-id [context]
  (let [workers (.serviceWorkers context)]
    (if (pos? (.-length workers))
      (-> (aget workers 0) (.-url) (.split "/") (aget 2))
      (let [sw (js-await (.waitForEvent context "serviceworker"))]
        (-> (.-url sw) (.split "/") (aget 2))))))

;; Re-export for tests
(def ^:export base-test test)
```

### Test Scenarios (Squint)

Using Squint's `^:async` and `js-await` for clean async tests:

```clojure
;; e2e/popup_test.cljs
(ns popup-test
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [create-extension-context get-extension-id]]))

(test "popup renders with port inputs"
  (^:async fn []
    (let [context (js-await (create-extension-context))
          ext-id (js-await (get-extension-id context))
          page (js-await (.newPage context))]
      (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
      (js-await (-> (expect (.locator page "#nrepl-port")) (.toBeVisible)))
      (js-await (-> (expect (.locator page "#ws-port")) (.toBeVisible)))
      (js-await (.close context)))))

(test "copy command button works"
  (^:async fn []
    (let [context (js-await (create-extension-context))
          ext-id (js-await (get-extension-id context))
          page (js-await (.newPage context))]
      (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
      (js-await (.click page "button:has-text(\"Copy\")"))
      ;; Verify UI feedback (copy-feedback element appears)
      (js-await (-> (expect (.locator page ".copy-feedback")) (.toBeVisible)))
      (js-await (.close context)))))

(test "can save and list scripts"
  (^:async fn []
    (let [context (js-await (create-extension-context))
          ext-id (js-await (get-extension-id context))
          page (js-await (.newPage context))]
      ;; Open panel and fill script form
      (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
      (js-await (.fill page "#script-name" "Test Script"))
      (js-await (.fill page "#script-match" "*://example.com/*"))
      (js-await (.fill page "#code-editor" "(println \"hello\")"))
      (js-await (.click page "button:has-text(\"Save\")"))
      ;; Verify in popup
      (let [popup (js-await (.newPage context))]
        (js-await (.goto popup (str "chrome-extension://" ext-id "/popup.html")))
        (js-await (-> (expect (.locator popup "text=Test Script")) (.toBeVisible))))
      (js-await (.close context)))))
```

### Async Helper Pattern

For cleaner test setup/teardown:

```clojure
;; e2e/helpers.cljs
(ns helpers
  (:require [fixtures :refer [create-extension-context get-extension-id]]))

(defn ^:async with-extension-context [f]
  "Wraps test body with extension context setup/teardown.
   f receives [context ext-id] and should return a promise."
  (let [context (js-await (create-extension-context))
        ext-id (js-await (get-extension-id context))]
    (try
      (js-await (f context ext-id))
      (finally
        (js-await (.close context))))))

;; Usage in tests becomes cleaner:
(test "popup renders"
  (^:async fn []
    (js-await
      (with-extension-context
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
            (js-await (-> (expect (.locator page "#nrepl-port"))
                          (.toBeVisible)))))))))
```

### What Playwright Tests

| Scenario | Why Playwright |
|----------|----------------|
| Extension loads without errors | Verifies build output works |
| Popup UI renders correctly | Real DOM, real CSS |
| Panel evaluation UI | Chrome DevTools context |
| Storage persistence | Real `chrome.storage` API |
| Script list management | Full UI interaction flow |

### CI Configuration

```yaml
# .github/workflows/e2e.yml (partial)
- name: Run Playwright tests
  run: npx playwright test
  env:
    CI: true
```

Headless Chrome with `--headless=new` supports extensions, enabling CI integration.

## Layer 3: browser-nrepl Integration Tests

For testing the full REPL pipeline: Editor -> nREPL -> WebSocket -> Extension -> Page.

### Architecture

```
┌──────────────────┐     ┌─────────────────────┐     ┌─────────────────┐
│  Test Script     │────>│  browser-nrepl      │────>│  Chrome + Ext   │
│  (bb test-repl)  │     │  (relay server)     │     │  (Playwright)   │
│                  │<────│  nREPL:12345        │<────│                 │
│  nrepl-client    │     │  WebSocket:12346    │     │  Scittle REPL   │
└──────────────────┘     └─────────────────────┘     └─────────────────┘
```

### Babashka nREPL Client

Use the `babashka/nrepl-client` library for sending eval requests:

```clojure
;; bb.edn (add to :deps)
{:deps {babashka/nrepl-client
        {:git/url "https://github.com/babashka/nrepl-client"
         :git/sha "19fbef2525e47d80b9278c49a545de58f48ee7cf"}}}
```

### Test Flow

```clojure
;; e2e/repl_test.clj
(ns repl-test
  (:require [babashka.nrepl-client :as nrepl]
            [babashka.process :as p]
            [clojure.test :refer [deftest is testing]]))

(defn with-browser-nrepl [f]
  ;; Start browser-nrepl server
  (let [server (p/process ["bb" "browser-nrepl"]
                          {:out :inherit :err :inherit})]
    (Thread/sleep 2000) ; Wait for server startup
    (try
      (f)
      (finally
        (p/destroy server)))))

(deftest full-repl-flow
  (with-browser-nrepl
    (fn []
      (testing "eval expression returns result"
        ;; Assumes Playwright has connected extension to a test page
        (let [result (nrepl/eval-expr {:port 12345
                                       :expr "(+ 1 2 3)"})]
          (is (= ["6"] (:vals result))))))))
```

### Integration Test Scenarios

| Scenario | Test Approach |
|----------|---------------|
| WebSocket connection | Start server, verify extension connects |
| Simple eval | Send `(+ 1 2)`, verify response |
| DOM manipulation | Eval code that modifies page, verify via Playwright |
| Error handling | Eval invalid code, verify error response |
| Multi-expression | Send multiple forms, verify all results |
| Disconnect/reconnect | Close connection, reconnect, verify state |

### Combining Playwright + nREPL (Squint)

For full pipeline tests, combine Playwright browser control with nREPL evaluation:

```clojure
;; e2e/repl_integration_test.cljs
(ns repl-integration-test
  (:require ["@playwright/test" :refer [test expect]]
            ["child_process" :as cp]
            [fixtures :refer [create-extension-context get-extension-id]]))

(defn start-browser-nrepl []
  (cp/spawn "bb" #js ["browser-nrepl"]
            #js {:stdio "inherit"}))

(defn ^:async wait-for-server [ms]
  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve ms)))))

(test "full REPL eval flow"
  (^:async fn []
    (let [server (start-browser-nrepl)]
      (try
        (js-await (wait-for-server 2000))
        (let [context (js-await (create-extension-context))
              ext-id (js-await (get-extension-id context))
              popup (js-await (.newPage context))
              test-page (js-await (.newPage context))]
          ;; 1. Navigate test page
          (js-await (.goto test-page "http://localhost:3000/test-page.html"))
          ;; 2. Connect extension via popup
          (js-await (.goto popup (str "chrome-extension://" ext-id "/popup.html")))
          (js-await (.click popup "button:has-text(\"Connect\")"))
          (js-await (-> (expect (.locator popup "text=Connected")) (.toBeVisible)))
          ;; 3. Eval via nREPL would modify #result on test-page
          ;; (nREPL client call would go here - shell out to bb)
          ;; 4. Verify DOM change
          (js-await (-> (expect (.locator test-page "#result")) (.toHaveText "6")))
          (js-await (.close context)))
        (finally
          (.kill server))))))
```

**Note:** The nREPL client call can either shell out to Babashka or use a Node nREPL client library. The Babashka approach keeps the test orchestration in Clojure:

```bash
# From the test, shell out to bb for nREPL eval:
bb -e '(require (quote [babashka.nrepl-client :as nrepl])) (nrepl/eval-expr {:port 12345 :expr "(+ 1 2 3)"})'
```

## Implementation Plan

### Phase 1: Strengthen Unit Tests

1. Extract more pure functions from UI code
2. Add action handler tests for popup and panel
3. Target: 150+ unit tests covering all pure logic

### Phase 2: Playwright Setup (Squint)

1. Add `@playwright/test` dependency
2. Update `squint.edn` to include `e2e` path
3. Create `playwright.config.js` pointing to `build/e2e/**/*_test.mjs`
4. Create `e2e/fixtures.cljs` with extension context helpers
5. Create `e2e/helpers.cljs` with async test utilities
6. Write smoke tests for extension loading
7. Add popup and panel interaction tests

### Phase 3: browser-nrepl Integration

1. Add `babashka/nrepl-client` to deps
2. Create test harness for REPL flow
3. Write eval round-trip tests
4. Add error handling tests

### Phase 4: CI Integration

1. Configure GitHub Actions for Playwright
2. Add browser-nrepl integration tests to CI
3. Set up test reporting

## File Structure

```
e2e/
  fixtures.cljs      ; Extension context setup
  helpers.cljs       ; Async test utilities
  popup_test.cljs    ; Popup UI tests
  panel_test.cljs    ; Panel/DevTools tests
  repl_integration_test.cljs  ; Full pipeline tests

build/e2e/           ; Squint-compiled output
  fixtures.mjs
  helpers.mjs
  popup_test.mjs
  panel_test.mjs
  repl_integration_test.mjs
```

## Test Commands

```bash
# Unit tests (Vitest)
bb test              # Run Vitest once
bb test:watch        # Watch mode

# E2E tests (Playwright + Squint)
bb compile:e2e       # Compile e2e/*.cljs to build/e2e/*.mjs
npx playwright test  # Run all Playwright tests
npx playwright test --headed  # Run with visible browser

# Watch mode for E2E development
npx squint watch     # Watches all paths including e2e/
npx playwright test --ui  # Interactive test runner

# REPL integration (after setup)
bb test:repl         # Run browser-nrepl integration tests
```

## Coverage Goals

| Layer | Current | Target |
|-------|---------|--------|
| Unit tests | 92 | 150+ |
| Playwright | 0 | 15-20 |
| REPL integration | 0 | 10-15 |

The emphasis is on unit tests for fast feedback and comprehensive coverage of logic, with Playwright and REPL tests providing confidence in real-world scenarios.

## References

- [Playwright Chrome Extensions](https://playwright.dev/docs/chrome-extensions)
- [Chrome E2E Testing Docs](https://developer.chrome.com/docs/extensions/how-to/test/end-to-end-testing)
- [babashka/nrepl-client](https://github.com/babashka/nrepl-client)
- [sci.nrepl.browser-server](https://github.com/babashka/sci.nrepl) - browser-nrepl implementation
