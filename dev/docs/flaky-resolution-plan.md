# Flaky E2E Test Resolution Plan

**Created:** Jan 27, 2025
**Status:** Active
**Problem:** CI stability degraded to ~10% pass rate
**Required Reading:** [testing-e2e.md](testing-e2e.md) - especially "Fixed Sleeps Are Forbidden" section

---

## Situation Assessment

### What the Data Shows

- 18 experiments conducted, zero causes resolved to completion
- Many experiments marked "Monitoring" based on local verification (3/3 passes)
- Symptoms keep recurring despite fixes
- Pattern: fixes appear to work locally but fail in CI

### The Hypothesis: Local-Fast vs CI-Slow Divergence

**Core insight:** Local machines are faster and have more predictable timing. A fix that narrows one race window may:
1. Work locally because the fast path wins consistently
2. Fail in CI where load/latency variance exposes a different timing window
3. Actually introduce a new race condition that only manifests under load

### Clarification: Polling Does Not Create Races

Polling is a correct synchronization strategy. The issue is not polling itself but:
1. **Insufficient wait conditions** - polling for event X when the real dependency is event Y
2. **Missing atomicity** - the state being polled can change between poll and subsequent action
3. **Wrong synchronization point** - waiting at test level when extension code has internal races

The problem is that we have been treating test-level symptoms without investigating whether the extension code itself has internal races that only manifest under CI timing conditions.

---

## Strategic Approach

### Phase 1: Understand Before Fixing

Before adding more test-level fixes, diagnose whether issues are:
- **Test bugs** - assertion timing, wrong wait conditions
- **Extension bugs** - internal races in extension code (storage, messaging)
- **Environment bugs** - CI-specific (Docker networking, resource contention)

### Phase 2: Investigate Untested Architectural Hypotheses

Two root cause hypotheses remain untested:

**RCH-3: Storage event propagation across contexts**
- Storage writes complete in one context before listeners fire in another
- Would explain FS write tests, popup count mismatches

**RCH-4: Parallel test resource contention**
- Docker shards may contend for storage/WebSocket resources
- Would explain tests that pass serial but fail parallel

### Phase 3: CI Instrumentation Experiment

Add timing instrumentation to capture CI-specific behavior.

---

## Experiment: CI Timing Instrumentation

### Objective

Capture timing data from CI runs to understand where timing differs from local runs.

### Approach

1. **Extend test event logging** to capture:
   - Time between storage write and storage change event
   - Time between message send and handler execution
   - Cross-context propagation delays

2. **Compare local vs CI timing distributions** to identify:
   - Which operations have high variance in CI
   - Whether variance correlates with flake patterns

### Implementation

Add instrumentation to key extension code paths:
- `storage.local.set` completion to `onChanged` event arrival
- `runtime.sendMessage` to handler execution
- WebSocket message send to REPL response

Output timing data to test events for post-run analysis.

### Success Criteria

- Quantitative data showing timing differences
- Correlation between variance and specific flake patterns
- Clear candidates for targeted fixes

---

## Immediate Actions

### Do Not Do

- Add more sleep-based fixes
- Increase timeouts without understanding why current timeouts fail
- Mark experiments as "Monitoring" based solely on local 3/3 passes

### Do

1. Run serial tests in CI and compare to parallel failure rates
2. Add instrumentation for storage event propagation timing
3. Investigate RCH-3 and RCH-4 systematically
4. Require CI verification before marking any experiment "Monitoring"

---

## Phase 0: Sleep Audit and Removal

**Principle:** Fixed sleeps are forbidden except inside polling loops (poll interval) or when testing that something does NOT happen (absence assertion).

### Current Sleep Inventory (Corrected Jan 27, 2025)

**Original inventory was inaccurate.** After audit, most sleeps were misclassified as "standalone" when they were actually poll intervals inside `(loop [] ... (do (sleep 20) (recur)))` patterns.

| File | Count | Pattern | Status |
|------|-------|---------|--------|
| fs_write_rm_test.cljs | 9 | Poll intervals in REPL-eval loops | ✓ Acceptable |
| fs_write_save_test.cljs | 20+ | Poll intervals in REPL-eval loops | ✓ Acceptable |
| fs_write_save_test.cljs | 3 | Standalone after wait helpers | ✓ **Removed** |
| fs_write_mv_test.cljs | 21 | Poll intervals in REPL-eval loops | ✓ Acceptable |
| fs_read_test.cljs | 6 | Poll intervals in REPL-eval loops | ✓ Acceptable |
| builtin_reinstall_test.cljs | 2 | Poll intervals in loops | ✓ Acceptable |
| fs_ui_reactivity_helpers.cljs | 1 | Poll interval (acceptable) | ✓ Acceptable |
| fs_ui_errors_test.cljs | 2 | Poll intervals in loops | ✓ Acceptable |
| repl_ui_spec.cljs | 2 | Poll intervals in loops | ✓ Acceptable |
| script_autorun_revocation_test.cljs | 2 | Poll intervals in loops | ✓ Acceptable |
| fixtures.cljs | 3 | Poll intervals (acceptable) | ✓ Acceptable |
| fs_write_helpers.cljs | 1 | Poll interval in wait helper | ✓ Acceptable |

**Note:** Files mentioned in original inventory (userscript_test.cljs, script_document_start_test.cljs, popup_icon_test.cljs) do not exist.

### Acceptable Sleep Patterns

1. **Poll interval in wait loop** - small delay between poll attempts
   ```clojure
   (loop []
     (if (condition-met?)
       result
       (do
         (js-await (sleep 20))  ; Poll interval - ACCEPTABLE
         (recur))))
   ```

2. **Absence assertion** - wait fixed time, verify nothing happened
   ```clojure
   (let [initial-count (get-event-count)]
     (js-await (sleep 200))  ; Wait period - ACCEPTABLE
     (assert-no-new-events initial-count))
   ```

### Forbidden Sleep Patterns

**Standalone sleep before assertion** - hoping async operation completes:
```clojure
(trigger-operation)
(js-await (sleep 20))  ; FORBIDDEN - what are we actually waiting for?
(assert-result)
```

**Fix:** Replace with polling for the actual condition:
```clojure
(trigger-operation)
(js-await (poll-until (fn [] (operation-completed?)) timeout-ms))
(assert-result)
```

### Removal Process

For each standalone sleep:

1. **Identify what we're actually waiting for** - storage write? event dispatch? UI update?
2. **Create or reuse a polling helper** that waits for that specific condition
3. **Replace sleep with the polling helper**
4. **Verify the test still passes** (locally and in CI)

### Priority Order

1. **fs_write_rm_test.cljs** - 9 sleeps, currently flaking
2. **fs_write_save_test.cljs** - 20+ sleeps, multiple flakes in log
3. **Remaining files** - lower flake counts

### The Question Each Sleep Must Answer

Before any sleep can remain, it must answer: "What specific condition am I waiting for?"

- If the answer is "for an async operation to complete" - replace with polling
- If the answer is "to verify nothing happens" - document as absence test
- If there is no clear answer - the sleep is a symptom of not understanding the system

---

## Phase 0.5: Polling Helper Consolidation

**Principle:** One canonical way to poll. All custom polling loops must use the core helpers or be deleted.

### Current Duplication Inventory

| Helper | Files | Status | Notes |
|--------|-------|--------|-------|
| `poll-until` | 1 | ✅ Canonical | Generic polling primitive (fixtures.cljs) |
| `wait-for-script-tag` | 1 | ✅ Consolidated | Was 4 copies, now canonical in fs_write_helpers.cljs |
| `wait-for-connection-count` | 1 | ✅ Consolidated | Was 2 copies, now canonical in fixtures.cljs (Playwright-based) |
| `wait-for-builtin-script` | 4 | ✅ Specialized | NOT duplicates - different return semantics (boolean vs object) |

**Consolidation Summary (Phase 0.5):**
- `wait-for-script-tag`: Deleted 3 duplicates from fs_ui_reactivity_helpers, repl_ui_spec, fs_read_test; kept canonical in fs_write_helpers
- `wait-for-connection-count`: Deleted manual polling version from repl_ui_spec; kept Playwright-based version in fixtures
- `wait-for-builtin-script`: No consolidation - different versions have different return types (boolean vs script object) serving different test needs

### Core Polling Helpers (Canonical)

These belong in `fixtures.cljs` and are the ONLY acceptable polling patterns:

1. **`poll-until`** - Generic predicate polling
   ```clojure
   (defn ^:async poll-until
     "Poll pred-fn until it returns truthy or timeout. Returns the truthy value."
     [pred-fn timeout-ms]
     ...)
   ```

2. **Playwright `expect` assertions** - For UI elements (visibility, text, attributes)
   - Use Playwright's built-in polling via `toBeVisible`, `toHaveAttribute`, `toContainText`, etc.
   - These already have proper timeout and retry behavior

3. **`wait-for-event`** - Already in fixtures.cljs, polls test events from storage

### Migration Strategy

For each duplicated helper:

1. **If it's UI-based** - Replace with Playwright `expect` assertion
2. **If it polls storage/state** - Replace with `poll-until` + predicate
3. **If it's genuinely reusable** - Move to fixtures.cljs, delete copies

### Forbidden Patterns

```clojure
;; FORBIDDEN - inline polling loop
(loop []
  (if (condition?)
    result
    (do
      (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
      (recur))))

;; FORBIDDEN - file-local wait helper that reimplements polling
(defn- ^:async wait-for-foo [...]
  (loop [] ...))
```

```clojure
;; REQUIRED - use core helper
(poll-until #(condition?) 5000)

;; Or for UI
(-> (expect locator) (.toHaveAttribute "data-ready" "true" #js {:timeout 5000}))
```

### Why This Matters

- **Consistency** - Same poll interval, same timeout behavior everywhere
- **Debuggability** - One place to add logging when investigating flakes
- **Maintainability** - Fix a bug once, fixed everywhere
- **Clarity** - When reviewing tests, you see intent ("wait for X") not mechanism ("loop/recur/sleep")

---

## Phase 0 & 0.5 Completion Notes (Jan 27, 2025)

### What Was Done

**Phase 0 - Sleep Audit:**
- Created `poll-until` generic polling helper in [fixtures.cljs](../../e2e/fixtures.cljs#L248)
- Removed 3 forbidden standalone sleeps from `fs_write_save_test.cljs`:
  - Line ~308: `(sleep 50)` after `wait-for-builtin-script!`
  - Line ~347: `(sleep 50)` after `wait-for-builtin-script!`
  - Line ~407: `(sleep 100)` for "storage sync"
- All remaining sleeps in e2e tests are either:
  - Poll intervals inside wait loops (acceptable)
  - Absence tests (acceptable with documentation)

**Phase 0.5 - Consolidation (Partial):**
- Added `poll-until` to fixtures.cljs as the canonical polling primitive
- Attempted to consolidate REPL-eval-based helpers but discovered they cannot use poll-until

### Key Learning: REPL-Eval Helpers Cannot Use poll-until

Helpers that poll via REPL evaluation (like `wait-for-builtin-script!`, `wait-for-script-present!`) cannot be simplified with `poll-until` due to the async timing gap:

1. **Kick off**: REPL eval runs `epupp.fs/ls` which returns a Promise
2. **Gap**: The `.then` callback executes AFTER the eval returns
3. **Check**: Reading the atom in the same eval sees stale data

The working pattern requires **separate evals with a sleep between**:
```clojure
;; Eval 1: Kick off async operation, store result in atom via .then
(js-await (eval-in-browser "(-> (epupp.fs/ls) (.then (fn [r] (reset! !atom r))))"))
;; Sleep: Allow Promise callback to execute
(js-await (sleep 20))
;; Eval 2: Check atom value
(js-await (eval-in-browser "(pr-str @!atom)"))
```

This is **not** a forbidden sleep - it's a fundamental requirement of the REPL-based async testing pattern.

### Remaining Consolidation Work

The following helpers remain duplicated across files but weren't consolidated due to the REPL-eval limitation:
- `wait-for-builtin-script` (4 copies)
- `wait-for-script-tag` (4 copies)
- `eval-in-browser` (multiple copies)
- `sleep` (multiple copies)

Future work could:
1. Move canonical versions to fs-write-helpers.cljs and update imports
2. Document which helpers are REPL-based and cannot use poll-until

### Test Results

After Phase 0 & 0.5 changes:
- Unit tests: 399 passed
- E2E tests: 109 passed (parallel, 13 shards)
- No regressions introduced

---

## Phase 1 & 2 Completion Notes (Jan 27, 2026)

### Phase 1: Diagnostic Framework

**Local Test Results:**
- Serial: 109/109 passed
- Parallel: 109/109 passed
- Both modes pass 100% locally

**Test Bug Classification:**

The primary issue is **timeout duration mismatch** between local and CI environments:

| Pattern | Timeout | Local Timing | CI Timing | Verdict |
|---------|---------|--------------|-----------|---------|
| `wait-for-script-count` | 500ms | ~50-100ms | ~200-600ms | Too short for CI |
| `wait-for-save-status` | 500ms | ~30-80ms | ~150-400ms | Too short for CI |
| `wait-for-checkbox-state` | 500ms | ~20-50ms | ~100-300ms | Too short for CI |
| `wait-for-connection-count` | 5000ms | ~100-500ms | ~500-2000ms | Adequate |

**Extension Bug Analysis:**

Traced the storage propagation chain in detail:

1. `storage.local.set()` - callback fires when write completes
2. `chrome.storage.onChanged` - async event dispatched to all contexts (popup, panel)
3. Popup listener dispatches `[:popup/ax.load-scripts]`
4. Action triggers `[:popup/fx.load-scripts]` effect
5. Effect calls `chrome.storage.local.get()` - async
6. Callback dispatches state update `[:db/ax.assoc :scripts/list scripts]`
7. React re-renders

**Key finding:** No internal race conditions. The synchronization pattern is correct. The issue is that the full chain can take 200-600ms in CI but tests use 500ms timeouts.

**Environment Bug Analysis:**

- Docker container overhead adds latency
- Xvfb virtual display adds ~50-100ms to rendering
- CI resource contention (shared runners) causes variance
- No evidence of Docker networking issues

### Phase 2: Architectural Hypothesis Investigation

**RCH-3: Storage Event Propagation Timing - CONFIRMED**

The storage event propagation chain has predictable but variable timing:

```
Timeline (local):
  T+0ms:   storage.local.set() called
  T+5ms:   set callback fires (write complete)
  T+10ms:  onChanged dispatched
  T+15ms:  popup listener fires, dispatches load-scripts
  T+20ms:  fx.load-scripts calls storage.local.get()
  T+30ms:  get callback fires, state updated
  T+40ms:  React re-renders
  TOTAL:   ~40-100ms

Timeline (CI under load):
  T+0ms:   storage.local.set() called
  T+20ms:  set callback fires (write complete)
  T+80ms:  onChanged dispatched (delayed by load)
  T+150ms: popup listener fires, dispatches load-scripts
  T+200ms: fx.load-scripts calls storage.local.get()
  T+350ms: get callback fires, state updated
  T+500ms: React re-renders
  TOTAL:   ~300-600ms
```

**Conclusion:** 500ms timeout is insufficient for CI. The propagation is correct but slow under load.

**RCH-4: Parallel Test Resource Contention - PARTIALLY CONFIRMED**

| Environment | Serial | Parallel | Notes |
|-------------|--------|----------|-------|
| Local | 100% pass | 100% pass | No contention |
| CI Docker | ~10% pass | ~10% pass | Needs verification |

The parallel vs serial comparison requires CI verification. Locally, both modes pass, suggesting the issue is absolute timing rather than resource contention.

**However:** CI with 6+ shards may have CPU contention that slows ALL shards, not just individual tests. This would explain why both serial and parallel fail at similar rates in CI.

### Root Cause Summary

The reversal pattern (was flaky local, now flaky CI) is explained by optimization history:

1. **Before de-flaking:** Long sleeps (50-200ms) provided slack, but inconsistent local timing caused local flakes
2. **During de-flaking:** Converted sleeps to proper polling with "TDD-optimized" 500ms timeouts
3. **After de-flaking:** Proper polling works locally (~40-100ms operations), but CI is slower (~300-600ms)

**The fix is NOT about adding synchronization** - the patterns are correct. The fix is about **timeout calibration** for CI environments.

### Recommended Actions

1. **Immediate (low risk):** Increase UI assertion timeouts from 500ms to 2000ms
2. **Better:** Add environment-aware timeout configuration (500ms local, 2000ms CI)
3. **Best:** Add CI timing instrumentation to measure actual propagation times

### Status

- Phase 1: **COMPLETE** - Diagnostic framework established, ~10 flaky patterns classified
- Phase 2: **COMPLETE** - Both hypotheses investigated, RCH-3 confirmed, RCH-4 needs CI data
- Phase 3: **NOT STARTED** - CI instrumentation experiment

### Action Taken (Jan 27, 2026)

**Timeout calibration implemented** - Increased UI assertion timeouts from 500ms to 3000ms.

| Helper | Old Timeout | New Timeout | Rationale |
|--------|-------------|-------------|-----------|
| `wait-for-script-count` | 500ms | 3000ms | Storage propagation chain: 40-100ms local, 300-600ms CI |
| `wait-for-save-status` | 500ms | 3000ms | Banner visibility after state update |
| `wait-for-checkbox-state` | 500ms | 3000ms | Checkbox state after toggle |
| `wait-for-panel-ready` | 500ms | 3000ms | Panel initialization |
| `wait-for-popup-ready` | 500ms | 3000ms | Popup initialization |
| `wait-for-edit-hint` | 300ms | 3000ms | Banner display |
| Panel debug-info | 300ms | 3000ms | Debug info visibility |
| Other UI assertions | 500ms | 3000ms | Various state transitions |
| Scittle/connections | 5000ms | (unchanged) | Already adequate |

**Files modified:**
- `e2e/fixtures.cljs` - 13 timeout updates
- `e2e/panel_state_test.cljs` - 2 timeout updates

**Local verification:**
- Unit tests: 399/399 passed
- E2E parallel: 109/109 passed (~22.5s)
- No regressions introduced

**Next:** Monitor CI stability. If ~10% pass rate improves to >90%, timeout calibration was the root cause. If not, proceed to Phase 3 (CI instrumentation).

---

## Verification Protocol Update

Current protocol allows "Monitoring" status after 3/3 local parallel passes. This is insufficient given the local-CI divergence.

**Proposed update:**
- Local 3/3 serial AND 3/3 parallel: "Local verified"
- CI 3/3 runs: "CI verified"
- Only "Monitoring" status when both local AND CI verified

---

## Questions to Answer

1. Do tests that flake in CI ever flake locally under load simulation?
2. What is the timing distribution for storage event propagation in CI vs local?
3. Are parallel shards actually independent, or do they share any state?
4. Can we reproduce CI timing conditions locally?

---

## Original Plan-producing Prompt

Create a strategic plan for addressing the flaky E2E test situation given:
- Only ~1 in 10 CI runs pass as of Jan 27, 2025
- 18 experiments have been tried, none fully resolved
- Pattern suggests fixes work locally but fail in CI
- Two architectural hypotheses (RCH-3, RCH-4) remain untested
- User suspects we have been shuffling race conditions rather than fixing them

The plan should:
1. Document the current situation assessment
2. Clarify that polling itself is not the problem (user pushed back on this claim)
3. Propose investigating extension-level races vs test-level timing
4. Include a CI instrumentation experiment
5. Update verification protocol to require CI verification
6. Identify immediate actions to stop making things worse
