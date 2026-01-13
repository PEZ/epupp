# E2E Test Performance

Guide for measuring and improving E2E test performance. For general E2E testing documentation, see [testing-e2e.md](testing-e2e.md).

## Measuring Test Performance

### Timing Report

Generate a timing report sorted fastest-to-slowest:

```bash
bb test:e2e:timing
```

Output includes:
- Total test count, duration, and average
- All tests sorted by duration (fastest first)
- Slowest 10 tests with file names

```
E2E Test Timing Report
======================
Tests: 59 | Total: 45.83s | Average: 776ms

Tests sorted by duration (fastest first):
------------------------------------------------------------
4ms      string operations
4ms      multiple forms evaluation
...
4.86s    two tabs connected to different servers evaluate independently
------------------------------------------------------------

Slowest 10 tests:
  4.86s    two tabs connected to different servers... (repl_ui_spec.mjs)
  ...
```

### Finding Slow Tests

Tail the report for the slowest tests:

```bash
bb test:e2e:timing 2>&1 | tail -15
```

Run a specific slow test in isolation to profile:

```bash
bb test:e2e --grep "two tabs connected"
```

## Common Performance Issues

### 1. Fixed Sleeps Instead of Polling

**Problem**: Hardcoded `sleep 2000` waits 2 seconds even when the operation completes in 20ms.

```clojure
;; ❌ Slow - waits full 2 seconds regardless
(js-await (sleep 2000))

;; ✅ Fast - polls every 100ms, returns immediately when ready
(js-await (wait-for-script-tag "scittle" 5000))
```

**Impact**: Replacing six 2-second sleeps with polling reduced suite time from 44s to 34s (22%).

### 2. Unchained Promise Methods in Squint

**Problem**: `.evaluate` and `.then` as separate statements don't chain properly.

```clojure
;; ❌ BROKEN - .then called on callback function, not the promise
(.evaluate page (fn [] ...))
(.then (fn [result] ...))

;; ✅ CORRECT - threading macro chains method calls
(-> (.evaluate page (fn [] ...))
    (.then (fn [result] ...)))
```

### 3. Unnecessary Waits

**Problem**: Arbitrary timeouts or overly long poll intervals.

```clojure
;; ❌ Slow - fixed 2 second wait
(js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 2000))))

;; ✅ Fast - waits only as long as needed
(js-await (wait-for-save-status panel "Created"))
```

### 2. Redundant Setup

**Problem**: Each test creates its own browser context when sharing would work.

**Solution**: Use Playwright's test fixtures to share browser instances across tests in the same file. The `beforeAll`/`afterAll` pattern reduces per-test overhead.

### 3. Sequential Operations

**Problem**: Operations that could run in parallel are sequential.

```clojure
;; ❌ Slow - sequential page creation
(let [page1 (js-await (.newPage context))
      _ (js-await (.goto page1 url1))
      page2 (js-await (.newPage context))
      _ (js-await (.goto page2 url2))]
  ...)

;; ✅ Fast - parallel navigation
(let [page1 (js-await (.newPage context))
      page2 (js-await (.newPage context))
      _ (js-await (js/Promise.all #js [(.goto page1 url1)
                                        (.goto page2 url2)]))]
  ...)
```

### 4. Over-Polling

**Problem**: Polling too frequently or for too long.

```clojure
;; ❌ Slow - 5 second timeout when 500ms would suffice
(js-await (wait-for-event popup "SCRIPT_INJECTED" 5000))

;; ✅ Fast - appropriate timeout for the operation
(js-await (wait-for-event popup "SCRIPT_INJECTED" 100))
```

### 5. Heavy Test Scope

**Problem**: Single test does too much, making it slow and hard to debug.

**Solution**: Split into focused tests. A test that takes 5+ seconds often covers multiple concerns that could be separate 500ms tests.

## Optimization Strategies

### Baseline Before Optimizing

1. Run `bb test:e2e:timing` to establish baseline
2. Identify the slowest tests
3. Profile individual tests to understand where time goes
4. Make targeted changes
5. Re-run timing report to measure improvement

### Target the Tail

The slowest 10% of tests often account for 50%+ of total time. Focus optimization efforts there first.

### Reduce Browser Overhead

- Reuse browser contexts when tests don't need isolation
- Minimize page navigations
- Use `waitForLoadState('domcontentloaded')` instead of `'load'` when full load isn't needed

### Parallelize Carefully

Playwright can run tests in parallel (`--workers=N`), but extension tests often need isolation. Profile to determine if parallelization helps or causes flakiness.

## Performance Regression Detection

### CI Integration

Add timing report to CI artifacts for trend analysis:

```yaml
- name: E2E Tests with Timing
  run: bb test:e2e:timing 2>&1 | tee timing-report.txt

- name: Upload Timing Report
  uses: actions/upload-artifact@v4
  with:
    name: e2e-timing-report
    path: timing-report.txt
```

### Alerting on Regression

Compare total time or slowest test against thresholds:

```bash
# Fail if total exceeds 60 seconds
TOTAL=$(bb test:e2e:timing 2>&1 | grep "Total:" | grep -oE '[0-9]+\.[0-9]+s')
# ... compare and alert
```

## Performance Budget

Suggested targets for this project:

| Metric | Target | Action if Exceeded |
|--------|--------|-------------------|
| Total suite time | < 60s | Investigate slowest tests |
| Average test time | < 1s | Review test design |
| Slowest single test | < 5s | Consider splitting |
| Tests > 2s | < 5 tests | Optimize or justify |

These are guidelines, not hard rules. Some tests legitimately need more time (multi-tab REPL tests, library loading tests).
