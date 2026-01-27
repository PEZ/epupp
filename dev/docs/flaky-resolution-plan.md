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

### Current Sleep Inventory

| File | Count | Pattern |
|------|-------|---------|
| fs_write_rm_test.cljs | 9 | Standalone `(sleep 20)` before assertions |
| fs_write_save_test.cljs | 20+ | Standalone `(sleep 20)` and `(sleep 50)` before assertions |
| fs_ui_popup_flash_test.cljs | 2 | `(sleep 100)` - possibly absence tests |
| userscript_test.cljs | 2 | Inline polling loop - migrate to `poll-until` |
| script_document_start_test.cljs | 2 | Inline polling loop - migrate to `poll-until` |
| popup_icon_test.cljs | 1 | Inline polling loop - migrate to `poll-until` |
| fs_ui_reactivity_helpers.cljs | 1 | Poll interval (acceptable) |
| fixtures.cljs | 3 | Poll intervals (acceptable) |

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

| Helper | Duplicated In | Notes |
|--------|---------------|-------|
| `wait-for-builtin-script` | fs_write_helpers.cljs, fs_write_save_test.cljs, builtin_reinstall_test.cljs, fs_read_test.cljs | 4 copies, slight variations |
| `wait-for-script-tag` | fs_write_helpers.cljs, fs_ui_reactivity_helpers.cljs, fs_read_test.cljs, repl_ui_spec.cljs | 4 copies |
| `wait-for-connection-count` | fixtures.cljs, repl_ui_spec.cljs | 2 copies |
| Inline polling loops | userscript_test.cljs, script_document_start_test.cljs, builtin_reinstall_test.cljs | Raw setTimeout in loop/recur |

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
