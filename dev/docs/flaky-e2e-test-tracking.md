# Flaky E2E Test Tracking

Systematic tracking of flaky test symptoms, root cause hypotheses, and experiments. Designed for scientific backtracking and controlled forward progress.

---

## Symptom Log

Observable flaky test occurrences. Facts only - no conclusions about causes.

| Test | File | Failure Pattern | Flakes | Clean Runs |
|------|------|-----------------|--------|------------|
| FS Sync save operations | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Timeout | 1 | 63 |
| FS Sync mv operations | [fs_write_mv_test.cljs](../../e2e/fs_write_mv_test.cljs) | Timeout | 2 | 58 |
| FS Sync rm: existed flag | [fs_write_rm_test.cljs](../../e2e/fs_write_rm_test.cljs) | Timeout | 1 | 63 |
| Popup Icon: tab-local state | [popup_icon_test.cljs](../../e2e/popup_icon_test.cljs) | Assertion fail | 3 | 13 |
| Popup Icon: toolbar icon REPL state | [popup_icon_test.cljs](../../e2e/popup_icon_test.cljs) | Assertion fail | 2 | 58 |
| FS Sync save: rejects builtin | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | CI flake | 5 | 39 |
| Panel Save: create new script | [panel_save_create_test.cljs](../../e2e/panel_save_create_test.cljs) | UI locator timeout | 1 | 63 |
| REPL manifest loads Replicant | [repl_ui_spec.cljs](../../e2e/repl_ui_spec.cljs) | Assertion fail | 1 | 63 |
| Popup Core: script management | [popup_core_test.cljs](../../e2e/popup_core_test.cljs) | Count mismatch | 1 | 63 |
| FS save: rejects when exists | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Timeout | 4 | 42 |
| FS save: rejects reserved namespace | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Assertion fail | 3 | 47 |
| FS save: rejects path traversal names | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Assertion fail | 4 | 42 |
| FS mv: rejects rename to reserved namespace | [fs_write_mv_test.cljs](../../e2e/fs_write_mv_test.cljs) | Assertion fail | 2 | 47 |
| FS mv: rejects path traversal target names | [fs_write_mv_test.cljs](../../e2e/fs_write_mv_test.cljs) | Assertion fail | 2 | 23 |
| Inject: Reagent library files injected | [inject_test.cljs](../../e2e/inject_test.cljs) | Timeout | 2 | 0 |
| Inject: INJECTING_LIBS event emitted when script has inject | [inject_test.cljs](../../e2e/inject_test.cljs) | Timeout | 1 | 0 |
| Popup Core: blank slate hints | [popup_core_test.cljs](../../e2e/popup_core_test.cljs) | Assertion fail | 1 | 63 |
| Auto-Run Revocation: panel save | [script_autorun_revocation_test.cljs](../../e2e/script_autorun_revocation_test.cljs) | Unknown | 1 | 63 |
| Panel Eval: Ctrl+Enter selection | [panel_eval_test.cljs](../../e2e/panel_eval_test.cljs) | Unknown | 1 | 63 |
| FS UI errors: failed operation rejects | [fs_ui_errors_test.cljs](../../e2e/fs_ui_errors_test.cljs) | Assertion fail | 3 | 3 |

**Column definitions:**
- **Flakes**: Total times this test has flaked (increment on each occurrence)
- **Clean Runs**: Consecutive full parallel runs without this flake (resets to 0 when flake occurs)

**When updating from testrunner report:**
1. For each flaked test: increment Flakes, reset Clean Runs to 0
2. For all non-flaked tests: increment Clean Runs by number of runs in batch
3. Low Clean Runs = recent/persistent = prioritize for investigation

---

## Root Cause Hypotheses

Architectural or timing issues that could explain one or more symptoms. Each hypothesis should be testable.

### Active Hypotheses

#### RCH-1: Non-atomic storage writes cause event loss

**Status:** Confirmed mechanism, fix applied, but may not cover all cases

**Mechanism:** `log-event!` in test_logger.cljs uses non-atomic read-modify-write:
```clojure
(.get storage ["test-events"]
  (fn [result]
    (.push events entry)    ;; race: concurrent readers see stale array
    (.set storage {:test-events events} resolve)))
```

**Could explain:** Any test relying on event ordering or completeness

**Applied fix:** Write queue in log-event!

**Open question:** Are there other non-atomic storage patterns in the codebase?

#### RCH-2: Tab activation event timing

**Status:** Under investigation - fixes attempted but symptoms persist

**Mechanism:** Tests call `.bringToFront` then immediately check icon state. Chrome's `onActivated` may fire asynchronously, creating race between:
- Test opening popup and polling for ICON_STATE_CHANGED
- Background worker processing activation and updating icon

**Could explain:** Popup Icon tests (L205, L208)

**Applied fixes:**
- `e2e/activate-tab` helper
- `e2e/update-icon` force call

**Open question:** If fixes applied, why do popup icon tests keep flaking?

#### RCH-3: Storage event propagation across contexts

**Status:** Not yet tested

**Mechanism:** Storage change events may arrive at different times in popup vs panel vs background. Write completes in one context before listener fires in another.

**Could explain:** FS write tests, popup count mismatches after delete

#### RCH-4: Parallel test resource contention

**Status:** Not yet tested

**Mechanism:** Docker sharded execution may cause contention when multiple tests manipulate extension storage or WebSocket connections simultaneously.

**Could explain:** Tests that pass in serial but fail in parallel

#### RCH-5: Background initialization race

**Status:** Partial fix applied

**Mechanism:** Operations run before `ensure-initialized!` completes, causing empty storage reads.

**Could explain:** Builtin protection bypass, save failures

**Applied fix:** Await ensure-initialized! in save-script handler

#### RCH-6: FS mv path traversal test may run without source script

**Status:** Proposed

**Mechanism:** In e2e/fs_write_mv_test.cljs, the path traversal test reuses a single source script. If the source script is missing due to prior test state or parallel contention, mv! rejects with "Script not found", causing expectation mismatch.

**Could explain:** "FS Sync mv operations" flakes (expectation mismatch)

#### RCH-8: Error message assertion fragility via pr-str string matching

**Status:** Insufficient - fix applied but symptoms persist

**Mechanism:** Tests use overly permissive OR assertions that accept multiple error messages, combined with fragile string matching in pr-str output:

1. Tests poll `(pr-str @!result)` → string like `"{:rejected \"message\"}"`
2. Assertions use `.includes result-str "substring"` on the pr-str output
3. String comparison can fail due to:
   - Whitespace/escaping variations in pr-str output
   - JavaScript .message property transformations
   - Encoding differences across contexts

**Example:** Built-in rejection test accepts ANY of:
- "built-in"
- "reserved namespace"
- "Cannot save built-in scripts"
- "Cannot overwrite built-in scripts"

**Could explain:** All 5 recent flakes (all error message assertion failures):
- FS save: rejects builtin
- FS save: rejects reserved namespace
- FS save: rejects when exists
- FS save: rejects path traversal
- FS UI errors: failed operation rejects

**Root issue:** Test quality problem, not product bug. Error construction is synchronous - no timing issues.


### Archived Hypotheses

#### RCH-7: Shared nREPL server contention across parallel shards

**Status:** Disproved

**Conclusion:** Shards run in separate Docker containers, each with its own relays. Playwright config uses workers: 1, so no intra-shard parallelism.

---

## Experiments Log

Each investigation attempt with quantitative results. One experiment per entry.

| ID | Hypothesis | Change | Before | After | Conclusion |
|----|------------|--------|--------|-------|------------|
| E01 | RCH-1 | Write queue in log-event! | Unknown | 3/3 parallel, 2/2 serial | Monitoring - may not be only cause |
| E02 | RCH-2 | activate-tab helper | Unknown | 5/5 serial, 2/3 parallel | Insufficient - still flakes |
| E03 | RCH-2 | update-icon force call | 2/3 parallel | 3/3 parallel, 5/5 serial | Appeared fixed, but symptoms recurred |
| E04 | RCH-5 | Await ensure-initialized! | Unknown | 2/3 parallel | Partial improvement |
| E05 | RCH-5 | e2e/ensure-builtin helper | 2/3 parallel | 3/3 parallel, 3/3 serial | Appeared fixed; monitoring |
| E06 | Popup delete timing | Replace banner wait with toHaveCount timeout | Unknown | 3/3 parallel, 3/3 serial | Workaround, not root cause fix |
| E07 | rm existed flag | Chain save->rm, use normalized name | Unknown | 3/3 parallel, 3/3 serial | Appeared fixed; monitoring |
| E08 | RCH-2 | Force icon update before wait | Unknown | 3/3 serial, 3/3 parallel | Monitoring |
| E09 | RCH-6 | Re-seed mv path source per attempt | Unknown | 3/3 serial, 3/3 parallel | Monitoring |
| E10 | RCH-8 | Replace OR assertions with canonical error messages | 5 tests flaking in baseline | 3/3 serial, 3/3 parallel | Insufficient - symptoms persist |
| E11 | RCH-8 | Replace pr-str map string matching with direct rejected message polling in fs_write_save_test.cljs | Unknown | 3/3 serial (targeted), 2/3 parallel | Insufficient - other pr-str assertions still flake |
| E12 | RCH-8 | Replace pr-str map string matching with direct rejected message polling in remaining fs_write_save_test.cljs rejection tests | Unknown | 3/3 serial, 3/3 parallel | Monitoring |
| E13 | RCH-2 | Add e2e/get-icon-display-state and poll it in popup_icon_test.cljs instead of relying on ICON_STATE_CHANGED events | Unknown | 3/3 serial (Popup Icon), 9/10 parallel (Popup Icon) | Insufficient - popup icon still flakes in parallel |
| E14 | RCH-8 | Replace pr-str map string matching in fs_write_mv_test.cljs path traversal (and related mv rejection tests) with direct rejected polling | Unknown | 3/3 serial (REPL FS: mv), 10/10 parallel | Monitoring |
| E15 | RCH-2 | Increase Popup Icon test timeout to 10s to account for multi-step workflow in parallel | Unknown | 3/3 serial (Popup Icon), 10/10 parallel (Popup Icon) | Monitoring |
| E16 | RCH-8 | Replace pr-str map string matching in fs_ui_errors_test.cljs with direct rejected polling | Unknown | 3/3 serial (FS UI errors), 3/3 parallel (FS UI errors) | Monitoring - inject flake occurred in parallel run |
| E17 | RCH-3 | Wait for LIBS_INJECTED event with reagent file before asserting DOM script tags in inject_test.cljs | Unknown | 3/3 serial (Inject), 3/3 parallel (Inject) | Monitoring |

**Before/After format:** X/Y = passed runs / total runs

**Conclusion values:**
- **Disproved** - Hypothesis ruled out by evidence
- **Insufficient** - Some improvement but symptoms persist
- **Workaround** - Masks issue without fixing root cause
- **Monitoring** - Passed verification but needs sustained evidence

**Note:** We never mark experiments as "Confirmed" - you can't prove the absence of black swans by counting white ones. Only the Resolved Causes section (with strict 10+ runs + 1+ week criteria) represents sustained confidence.

---

## Resolved Causes

Only list here when:
1. Root cause mechanism is understood
2. Fix addresses mechanism (not just symptoms)
3. 10+ parallel runs pass without recurrence
4. No new symptoms appear for 1+ week

| Root Cause | Fix | Verified | Date |
|------------|-----|----------|------|
| _(none yet meet criteria)_ | | | |

---

## Verification Protocol

### For Experiments

1. Record baseline failure rate if known
2. Apply single change
3. Run `bb test:e2e --serial -- --grep "test"` (3 runs)
4. Run `bb test:e2e` (3 runs, parallel)
5. Record results in Experiments Log
6. Do NOT mark as resolved based on verification alone

### For Resolution

A root cause is resolved when:
- [ ] Mechanism understood and documented
- [ ] Fix addresses mechanism directly
- [ ] 10+ parallel runs pass
- [ ] 1+ week without recurrence in CI/development
- [ ] Moved to Resolved Causes table

---

## References

- [testing-e2e.md](testing-e2e.md) - E2E testing principles
- [testing.md](testing.md) - Testing philosophy
- [fixtures.cljs](../../e2e/fixtures.cljs) - Test helper library
