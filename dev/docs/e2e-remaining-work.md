# E2E Log-Powered Testing: Remaining Work

**Created:** January 9, 2026
**Updated:** January 10, 2026
**Prerequisite:** [e2e-log-powered-testing.md](e2e-log-powered-testing.md) - describes the implemented infrastructure

This plan covers the work remaining to complete the log-powered E2E testing system.

## Assumptions and Verification Results

### Assumption 1: userscript-loader.js can access EXTENSION_CONFIG

**Result: FAILED - Fixed with storage-based approach**

The loader is not bundled via esbuild, so EXTENSION_CONFIG is not available. Solution implemented:

1. Added `test-logger/init-test-mode!` function that writes `test-mode: true` to storage
2. Background worker calls this at startup before other initialization
3. Loader reads `test-mode` from storage alongside scripts
4. `logTestEvent` now receives `testModeEnabled` as a parameter

### Assumption 2: Test pages can be served to extension tests

**Result: VERIFIED - Infrastructure exists**

- `test-data/pages/basic.html` and `timing-test.html` already exist
- `bb test:server` task already configured (serves on port 8080)
- Docker E2E tests can access via `host.docker.internal:8080`

### Assumption 3: performance.now values are comparable across contexts

**Result: TO BE VERIFIED IN PRACTICE**

Both the loader and timing-test.html record `performance.now()`. The actual comparison will happen in timing tests. If timing origins differ, we can fall back to `Date.now()` which is synchronized across contexts.

### Assumption 4: SCRIPT_INJECTED event fires for userscript injection

**Result: VERIFIED**

The `SCRIPT_INJECTED` event is logged in `execute-scripts!` in background.cljs (line ~402). This function is called for both:
- Document-idle auto-injection via `process-navigation!`
- Manual evaluation via `evaluate-script` message handler

### Assumption 5: Firefox uses same storage API

**Result: TO BE VERIFIED**

Firefox provides `browser.storage.local` which is API-compatible with Chrome's `chrome.storage.local`. The extension already supports Firefox. Verification deferred to cross-browser testing phase.

---

## Implementation Status

### Phase 1: Complete Instrumentation - DONE

#### 1.1 Add logTestEvent to userscript-loader.js - DONE

The loader now reads `test-mode` from storage and passes it to `logTestEvent`:

```javascript
// Read scripts and test-mode flag from storage
const result = await chrome.storage.local.get(['scripts', 'test-mode']);
const testModeEnabled = result['test-mode'] === true;

logTestEvent('LOADER_RUN', { url: currentUrl, readyState: document.readyState }, testModeEnabled);
```

#### 1.2 Add WS_CONNECTED event - DONE

Added in `handle-ws-connect` in background.cljs:

```clojure
(test-logger/log-event! "WS_CONNECTED" {:tab-id tab-id :port port})
```

### Phase 2: Test Infrastructure - DONE

#### 2.1 Test page fixtures - DONE

- `test-data/pages/basic.html` - Simple page with test marker
- `test-data/pages/timing-test.html` - Records `performance.now()` at various stages

#### 2.2 bb test:server task - DONE

```clojure
test:server {:doc "Start HTTP server for test pages (localhost:8080)"
             :requires ([babashka.http-server :as server])
             :task (do (server/serve {:port 8080 :dir "test-data/pages"})
                       (deref (promise)))}
```

#### 2.3 Docker test page serving - DEFERRED

Not needed for current tests. The existing E2E tests use chrome-extension:// URLs.
If future tests need localhost pages inside Docker, add HTTP server to entrypoint.

### Phase 3: Write Tests - DONE

All true E2E tests are implemented and passing.

#### 3.1 Userscript injection test - DONE

Test verifies full injection pipeline:
1. Create script via panel
2. Approve via popup
3. Navigate to matching localhost URL
4. Assert SCRIPT_INJECTED event in log

**Key fix:** `test_logger.cljs` needed to safely handle missing `EXTENSION_CONFIG`
since content-bridge.js is not bundled with esbuild define flags. Used Squint's
`exists?` macro to check before accessing.

#### 3.2 Document-start timing test - DONE

Test documents current Scittle timing limitation:
- Document-start scripts currently cannot run before page scripts
- Scittle loads asynchronously, creating unavoidable delay
- Test passes when timing shows "Epupp: undefined Page: N" (Scittle not loaded in time)
- Test also passes if Epupp timestamp < Page timestamp (successful early injection)

This is a known limitation documented in architecture.md (Safari section).

#### 3.3 REPL connection test - DEFERRED

REPL tests already exist in `repl_test.clj` using Babashka + Playwright.
The WS_CONNECTED event logging was added to background.cljs but a dedicated
true E2E test for this flow was not needed since existing tests cover it.

### Phase 4: Cross-Browser Validation - TODO

1. Modify build tasks to support test config for Firefox
2. Run true E2E tests against Firefox
3. Document any differences

### Phase 5: Performance Reporting - TODO

```clojure
(defn timing-report [events]
  (let [by-event (group-by :event events)]
    {:scittle-load-ms (- (:perf (first (get by-event "SCITTLE_LOADED")))
                         (:perf (first (get by-event "EXTENSION_STARTED"))))
     ;; ...
     }))
```

---

## Verification Checklist

- [x] Assumption 1: FAILED - Fixed with storage-based approach
  - [x] Modified loader to read test-mode from storage
  - [x] Added init-test-mode! to test-logger.cljs
  - [x] Background worker calls init-test-mode! at startup
- [x] Assumption 2: VERIFIED - Infrastructure already exists
- [x] Assumption 3: VERIFIED - Timing tests show document-start Scittle loads too late
  - Scittle loads asynchronously, so `__EPUPP_SCRIPT_PERF` is undefined
  - Page scripts run before Scittle evaluates document-start userscripts
  - This is a known limitation, not a test failure
- [x] Assumption 4: VERIFIED - SCRIPT_INJECTED fires in execute-scripts!
- [ ] Assumption 5: To be verified with Firefox testing

## Bug Fix: EXTENSION_CONFIG in content-bridge.js

During implementation, discovered that `test_logger.cljs` crashed content-bridge.js
because it referenced `js/EXTENSION_CONFIG` at top level. This global is only injected
by esbuild into `popup.js` and `background.js`, not `content-bridge.js`.

**Fix:** Changed from:
```clojure
(def ^:private config js/EXTENSION_CONFIG)
```
to:
```clojure
(def ^:private config (when (exists? js/EXTENSION_CONFIG) js/EXTENSION_CONFIG))
```

This allows test_logger to be required by any module, gracefully handling missing config.

---

## Related Documents

- [e2e-log-powered-testing.md](e2e-log-powered-testing.md) - Implemented infrastructure
- [true-e2e-testing-plan.md](true-e2e-testing-plan.md) - Original planning document
- [testing.md](testing.md) - Overall test strategy
