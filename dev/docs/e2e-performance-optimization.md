# E2E Test Performance and Output Optimization

**Created:** January 12, 2026
**Status:** Phase 1 Ready

Analysis of `bb test:e2e` output revealed opportunities for performance improvement and noise reduction.

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

**ALWAYS use `bb <task>` over direct shell commands.** The bb tasks encode project-specific configurations. Check `bb tasks` for available commands.

**ALWAYS check lint/problem reports after edits.** Use `get_errors` tool to verify no syntax or bracket errors before running tests.

**ALWAYS use the `edit` subagent for file modifications.** The edit subagent specializes in Clojure/Squint structural editing and avoids bracket balance issues. Provide it with complete context: file paths, line numbers, and the exact changes needed.

**ALWAYS measure**: When starting with a phase of the plan, always make two basline runs, and average the results. Save this result in this plan. Then proceed with the changes. Then make two runs and avarage the results. Save this result and a comparison to the baseline in this document.

- `bb test` - Compile and run unit tests
- `bb test:e2e` - Compile and run E2E tests (Docker)

### Optimization Strategies

#### A. Rapid-poll fixture for "nothing happens" tests (CHOSEN)

Create a fixture helper that polls rapidly for a short window, failing if an unwanted event appears. This guides future test implementers toward the right pattern.

**New fixture in `e2e/fixtures.cljs`:**

```clojure
(defn ^:async assert-no-event-within
  "Assert that a specific event does NOT occur within timeout-ms.
   Polls rapidly (every 50ms) and fails immediately if event appears.
   Use for tests that verify something should NOT happen."
  [ext-page event-name timeout-ms]
  (let [start (.now js/Date)
        poll-interval 50]
    (loop []
      (let [events (js-await (get-test-events ext-page))
            found (first (filter #(= (.-event %) event-name) events))]
        (if found
          (throw (js/Error. (str "Unexpected event occurred: " event-name
                                 " after " (- (.now js/Date) start) "ms")))
          (if (> (- (.now js/Date) start) timeout-ms)
            true  ; Success - event did not occur
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve poll-interval))))
              (recur))))))))
```

**Usage in tests:**
```clojure
;; Before: explicit sleep
(js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 1000))))
(let [events (js-await (get-test-events popup))
      count-after ...]
  (js-await (-> (expect count-after) (.toBe count-before))))

;; After: rapid-poll assertion
(js-await (assert-no-event-within popup "SCITTLE_LOADED" 300))
```

**Benefits:**
- Fails fast if event occurs (no wasted wait time)
- Self-documenting intent ("assert no event")
- Shorter timeout acceptable (300ms vs 1000ms)
- Guides future test authors

#### B. Remove remaining explicit sleeps

Replace sleeps after connection/navigation with event-driven waits where possible.

| Current | Replace With |
|---------|--------------|
| Wait 500ms after connect | `wait-for-event popup "WS_CONNECTED"` or connection count check |
| Wait 500ms after SPA nav | Already using `toBeVisible` - remove extra sleep |
| Wait 300ms for focus switch | Keep - legitimate UI settling time |

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

### 1. Add `assert-no-event-within` fixture helper

**File:** `e2e/fixtures.cljs`

Add rapid-poll helper function as specified in section 1.A above.

### 2. Update tests to use new helper

**File:** `e2e/log_powered_test.cljs`

Replace "nothing happens" sleeps with `assert-no-event-within`:
- `auto-reconnect does NOT trigger for tabs never connected`
- `disabled auto-reconnect does NOT trigger on page reload`
- `SPA navigation does NOT trigger REPL reconnection`

### 3. Redirect subprocess output to temp file

**File:** `scripts/tasks.clj`

- Add `e2e-log-file` def with timestamped path
- Add `log-writer` helper
- Update `start-browser-nrepl-process` to write to log file
- Update `with-test-server` to write to log file
- Print log file path at start of `run-e2e-tests!`

### 4. Add line reporter to Playwright

**File:** `scripts/tasks.clj`

Update `run-e2e-tests!` to pass `--reporter=line,html` to Playwright.

## Post-Phase 1 Evaluation

After implementing Phase 1:

1. Run `bb test:e2e` and capture timing
2. Compare total suite time (target: under 40s)
3. Verify error output is still useful (not too brief)
4. Check temp file contains expected subprocess output
5. Decide if Phase 2 (more aggressive sleep removal) is needed

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
