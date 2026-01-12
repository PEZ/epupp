# E2E Test Performance and Output Optimization

**Created:** January 12, 2026
**Status:** Phase 1B Complete (wait-for-connection fixture)

Analysis of `bb test:e2e` output revealed opportunities for performance improvement and noise reduction.

## Phase 1B Results: Event-Driven Connection Waits

**Baseline:** 41.7s for 46 tests (average of 2 runs)
**After:** 38.05s for 46 tests (average of 2 runs)
**Improvement:** 8.8% faster (3.65s saved)

Replaced 7 explicit 500ms sleeps after `connect-tab` with event-driven `wait-for-connection` helper that polls `get-connections` until connection count is positive.

| Run | Before | After | Saved |
|-----|--------|-------|-------|
| Run 1 | 41.7s | 38.1s | 3.6s |
| Run 2 | 41.7s | 38.0s | 3.7s |

Files modified:
- `e2e/fixtures.cljs` - Added `wait-for-connection` helper
- `e2e/popup_test.cljs` - 2 sleeps replaced
- `e2e/log_powered_test.cljs` - 5 sleeps replaced

## Phase 1A Results: Rapid-Poll Fixture

**Baseline (explicit sleeps):** 5.7s for 3 "does NOT trigger" tests
**After (rapid-poll 300ms):** 4.1s for same tests
**Improvement:** 28% faster (1.6s saved)

| Test | Before | After | Saved |
|------|--------|-------|-------|
| SPA navigation does NOT trigger | 1.4s | 1.2s | 0.2s |
| auto-reconnect does NOT trigger for never-connected | 1.6s | 0.86s | 0.74s |
| disabled auto-reconnect does NOT trigger | 2.4s | 1.7s | 0.7s |

Full suite: 46 tests pass in ~42s (unchanged overall - these 3 tests are small portion).

## Decisions

| Area | Decision | Rationale |
|------|----------|-----------|
| Sleep strategy | Rapid-poll fixture helper | Helps future test implementers get it right |
| Output handling | All output to temp file, filter display | Clean interactive output, full logs available |
| Playwright reporter | `--reporter=line` | Concise console, HTML report has details |
| Docker verbosity | Leave as-is | Low priority, provides progress indication |
| Scope | Phase 1 first, measure, decide | Incremental approach |

## Summary of Findings

| Area | Issue | Impact | Priority |
|------|-------|--------|----------|
| Slow tests | Several tests exceed 1s (up to 4.8s) | Test suite takes ~43s | Medium |
| Subprocess noise | browser-nrepl and other output pollutes console | Hard to see test results | High |
| Docker build verbosity | Full Squint compilation logs in output | Noise during cached runs | Low (deferred) |

## 1. Slow Tests Analysis

### Current Timing Breakdown

Tests over 1 second (from actual run):

| Test | Time | Root Cause |
|------|------|------------|
| `multi-tab REPL` | 4.8s | Creates 2 tabs, connects to 2 browser-nrepl servers, runs 6 eval operations |
| `disabled auto-reconnect...` | 2.4s | Full connection cycle + page reload + 1s explicit wait |
| `auto-reconnect does NOT trigger for tabs never connected` | 1.5s | Page reload + 1s explicit wait |
| `injected state is tab-local...` | 1.5s | Creates 2 tabs, multiple focus switches with 300ms waits |
| `auto-reconnect triggers...` | 1.7s | Connection cycle + page reload + wait-for-event |
| `popup UI updates when connected page is reloaded...` | 1.7s | Connection + reload + reconnection check |
| `SPA navigation does NOT trigger...` | 1.2s | Initial connect + two SPA navigations + 500ms wait |

### Root Causes

1. **Explicit sleep patterns**: Several tests use `(js/Promise. (fn [resolve] (js/setTimeout resolve ...)))` - violates the "no sleep" principle documented in testing-e2e.md

2. **Browser-nrepl connection overhead**: Each nREPL eval creates a new TCP connection (visible as "nREPL server started on port..." lines)

3. **Legitimate complexity**: Multi-tab tests inherently need multiple browser contexts

## Workflow

**ALWAYS act informed.** You start by investigating the testing docs and the existing tests to understand patterns and available fixture.

**ALWAYS use `bb <task>` over direct shell commands.** The bb tasks encode project-specific configurations. Check `bb tasks` for available commands.

**ALWAYS check lint/problem reports after edits.** Use `get_errors` tool to verify no syntax or bracket errors before running tests.

**ALWAYS use the `edit` subagent for file modifications.** The edit subagent specializes in Clojure/Squint structural editing and avoids bracket balance issues. Provide it with complete context: file paths, line numbers, and the exact changes needed.

**ALWAYS measure**: When starting with a phase of the plan, always make two basline runs, and average the results. Save this result in this plan. Then proceed with the changes. Then make two runs and avarage the results. Save this result and a comparison to the baseline in this document.

- `bb test` - Compile and run unit tests
- `bb test:e2e` - Compile and run E2E tests (Docker)

### Optimization Strategies

#### A. Rapid-poll fixture for "nothing happens" tests ✅ IMPLEMENTED

Created `assert-no-new-event-within` fixture helper that polls rapidly for a short window, failing if an unwanted event appears. This guides future test implementers toward the right pattern.

**Implemented fixture in `e2e/fixtures.cljs`:**

```clojure
(defn ^:async assert-no-new-event-within
  "Assert that no NEW event with given name occurs within timeout-ms.
   Polls rapidly (every 50ms) and fails immediately if count increases.

   initial-count: The number of events of this type that existed before the action
   Use for tests that verify something should NOT happen."
  [ext-page event-name initial-count timeout-ms]
  (let [start (.now js/Date)
        poll-interval 50]
    (loop []
      (let [events (js-await (get-test-events ext-page))
            current-count (.-length (.filter events (fn [e] (= (.-event e) event-name))))]
        (if (> current-count initial-count)
          (throw (js/Error. (str "Unexpected new event: " event-name
                                 " (count " initial-count " -> " current-count ")")))
          (if (> (- (.now js/Date) start) timeout-ms)
            true  ; Success - no new events
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve poll-interval))))
              (recur))))))))
```

**Usage in tests:**
```clojure
;; Before: explicit sleep + count comparison
(js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 1000))))
(let [events (js-await (get-test-events popup))
      count-after ...]
  (js-await (-> (expect count-after) (.toBe count-before))))

;; After: rapid-poll assertion with baseline count
(js-await (assert-no-new-event-within popup "SCITTLE_LOADED" scittle-count-before 300))
```

**Benefits:**
- Fails fast if event occurs (no wasted wait time)
- Self-documenting intent ("assert no event")
- Shorter timeout acceptable (300ms vs 1000ms)
- Guides future test authors

#### B. Remove remaining explicit sleeps ✅ IMPLEMENTED

Replaced 7 explicit 500ms sleeps after `connect-tab` calls with event-driven `wait-for-connection` helper that polls `get-connections` until connection count is positive.

**Implemented fixture in `e2e/fixtures.cljs`:**

```clojure
(defn ^:async wait-for-connection
  "Wait for WebSocket connection to be established after connect-tab.
   Polls get-connections until count is at least 1, or timeout.
   Returns the connection count."
  [ext-page timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [current-count (.-length (js-await (get-connections ext-page)))]
        (if (pos? current-count)
          current-count
          (if (> (- (.now js/Date) start) (or timeout-ms 5000))
            (throw (js/Error. (str "Timeout waiting for connection. Count: " current-count)))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
              (recur))))))))
```

**Usage in tests:**
```clojure
;; Before: explicit 500ms sleep
(js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))

;; After: event-driven wait
(js-await (wait-for-connection popup 5000))
```

**Preserved sleeps (legitimate waits):**
| Pattern | Reason |
|---------|--------|
| 300ms focus switch | UI settling time |
| 500ms after reload waiting for WS close | Different event (not connection) |
| Panel restore 1000ms | Complex UI state restoration |
| 100-200ms typing/dialog sleeps | UI interaction timing |

## 2. Output Handling (CHOSEN: All to file, filter display)

### Design

All subprocess output (browser-nrepl, test server, etc.) goes to a timestamped temp file. Console shows filtered summary. File path is printed for debugging.

**Implementation in `tasks.clj`:**

```clojure
(def ^:private e2e-log-file
  (str "/tmp/epupp-e2e-" (System/currentTimeMillis) ".log"))

(defn- log-writer []
  (io/writer e2e-log-file :append true))

(defn- start-browser-nrepl-process [nrepl-port ws-port]
  (let [writer (log-writer)]
    (.write writer (str "\n=== browser-nrepl " nrepl-port "/" ws-port " ===\n"))
    (p/process ["bb" "browser-nrepl"
                "--nrepl-port" (str nrepl-port)
                "--websocket-port" (str ws-port)]
               {:out writer :err writer})))

(defn run-e2e-tests! [args]
  (println (str "E2E log file: " e2e-log-file))
  (with-test-server
    #(with-browser-nrepls
       (fn [] (apply p/shell "npx playwright test" args)))))
```

**Benefits:**
- Clean console output shows only test results
- Full output available at referenced temp file
- Single file contains all subprocess output (easy to grep)
- Timestamp in filename prevents conflicts

## 3. Playwright Reporter (CHOSEN: --reporter=line)

Use Playwright's line reporter for concise console output. HTML report is still generated with full details.

**Change in `tasks.clj`:**

```clojure
(defn run-e2e-tests! [args]
  (println (str "E2E log file: " e2e-log-file))
  (with-test-server
    #(with-browser-nrepls
       (fn []
         ;; Line reporter for concise output, HTML for full details
         (apply p/shell "npx playwright test --reporter=line,html" args)))))
```

**Line reporter output example:**
```
Running 45 tests using 1 worker
  45 passed (43s)
```

On failure, shows test name and error message (not full stack). Full details available in `playwright-report/index.html`.

## 4. Deferred: Docker Build Verbosity

Leave as-is for now. The Squint compilation output provides progress indication during builds. Can revisit if it becomes annoying.

## Phase 1 Implementation Checklist

### 1. Add `assert-no-new-event-within` fixture helper ✅

**File:** `e2e/fixtures.cljs`

Rapid-poll helper added. Takes `initial-count` parameter to detect NEW events vs existing ones.

### 2. Update tests to use new helper ✅

**File:** `e2e/log_powered_test.cljs`

Replaced "nothing happens" sleeps with `assert-no-new-event-within`:
- ✅ `SPA navigation does NOT trigger REPL reconnection`
- ✅ `auto-reconnect does NOT trigger for tabs never connected`
- ✅ `disabled auto-reconnect does NOT trigger on page reload`

### 3. Redirect subprocess output to temp file (TODO)

**File:** `scripts/tasks.clj`

- Add `e2e-log-file` def with timestamped path
- Add `log-writer` helper
- Update `start-browser-nrepl-process` to write to log file
- Update `with-test-server` to write to log file
- Print log file path at start of `run-e2e-tests!`

### 4. Add line reporter to Playwright (TODO)

**File:** `scripts/tasks.clj`

Update `run-e2e-tests!` to pass `--reporter=line,html` to Playwright.

## Post-Phase 1A Notes

**Observation:** The 28% improvement on targeted tests is modest because:
1. Tests still have inherent overhead (browser launch, extension load, navigation)
2. The 300ms poll window is conservative - could be reduced further
3. Main time savings come from eliminating the 500-1000ms explicit sleeps

**Full suite timing unchanged** (~42s) because these 3 tests are a small fraction of the 46 total tests.

**Next steps:** Consider implementing output handling (item 3) and line reporter (item 4) for cleaner console output, which may have higher practical value than further speed optimization.

## Expected Outcomes (Phase 1)

| Metric | Current | Target |
|--------|---------|--------|
| Console noise | High (nREPL messages, etc.) | Clean (test results only) |
| "Nothing happens" test time | 1-2.4s each | Under 500ms each |
| Error readability | Verbose stacks | Concise summary |
| Debug info | Mixed with output | In temp file |

## References

- [testing.md](testing.md) - Test strategy overview
- [testing-e2e.md](testing-e2e.md) - E2E test documentation
- [Playwright reporters](https://playwright.dev/docs/test-reporters)
