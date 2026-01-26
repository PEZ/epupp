# Flaky E2E Test Tracking

Systematic tracking of flaky test symptoms, root cause hypotheses, and experiments. Designed for scientific backtracking and controlled forward progress.

---

## Symptom Log

Observable flaky test occurrences. Facts only - no conclusions about causes.

| Test | File | Failure Pattern | Flakes | Clean Runs |
|------|------|-----------------|--------|------------|
| FS Sync save operations | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Timeout | 1 | 8 |
| FS Sync mv operations | [fs_write_mv_test.cljs](../../e2e/fs_write_mv_test.cljs) | Timeout | 2 | 3 |
| FS Sync rm: existed flag | [fs_write_rm_test.cljs](../../e2e/fs_write_rm_test.cljs) | Timeout | 1 | 8 |
| Popup Icon: tab-local state | [popup_icon_test.cljs](../../e2e/popup_icon_test.cljs) | Assertion fail | 1 | 8 |
| Popup Icon: toolbar icon REPL state | [popup_icon_test.cljs](../../e2e/popup_icon_test.cljs) | Assertion fail | 2 | 3 |
| FS Sync save: rejects builtin | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | CI flake | 1 | 8 |
| Panel Save: create new script | [panel_save_create_test.cljs](../../e2e/panel_save_create_test.cljs) | UI locator timeout | 1 | 8 |
| REPL manifest loads Replicant | [repl_ui_spec.cljs](../../e2e/repl_ui_spec.cljs) | Assertion fail | 1 | 8 |
| Popup Core: script management | [popup_core_test.cljs](../../e2e/popup_core_test.cljs) | Count mismatch | 1 | 8 |
| FS save: rejects when exists | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Timeout | 2 | 0 |
| FS save: rejects reserved namespace | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Assertion fail | 1 | 0 |
| FS save: rejects path traversal names | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Assertion fail | 1 | 0 |
| FS mv: rejects rename to reserved namespace | [fs_write_mv_test.cljs](../../e2e/fs_write_mv_test.cljs) | Assertion fail | 1 | 0 |
| Inject: Reagent library files injected | [inject_test.cljs](../../e2e/inject_test.cljs) | Timeout | 1 | 0 |
| Popup Core: blank slate hints | [popup_core_test.cljs](../../e2e/popup_core_test.cljs) | Assertion fail | 1 | 8 |
| Auto-Run Revocation: panel save | [script_autorun_revocation_test.cljs](../../e2e/script_autorun_revocation_test.cljs) | Unknown | 1 | 8 |
| Panel Eval: Ctrl+Enter selection | [panel_eval_test.cljs](../../e2e/panel_eval_test.cljs) | Unknown | 1 | 8 |

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

#### RCH-7: Shared nREPL server contention across parallel shards

**Status:** Proposed

**Mechanism:** E2E tests share the same nREPL relay ports during parallel runs. Concurrent eval requests can interleave responses, causing `eval-in-browser` to receive mismatched or partial results, leading to assertion failures in REPL FS save tests.

**Evidence:** 5 parallel runs filtered to "REPL FS: save" passed cleanly, while full parallel suites still showed intermittent failures.

**Could explain:** FS save rejection tests (fs_write_save_test.cljs) flaking only in full parallel runs.

### Archived Hypotheses

_(Move here when conclusively ruled out with evidence)_

---

## Experiments Log

Each investigation attempt with quantitative results. One experiment per entry.

| ID | Hypothesis | Change | Before | After | Conclusion |
|----|------------|--------|--------|-------|------------|
| E01 | RCH-1 | Write queue in log-event! | Unknown | 3/3 parallel, 2/2 serial | Mechanism confirmed; may not be only cause |
| E02 | RCH-2 | activate-tab helper | Unknown | 5/5 serial, 2/3 parallel | Insufficient - still flakes |
| E03 | RCH-2 | update-icon force call | 2/3 parallel | 3/3 parallel, 5/5 serial | Appeared fixed, but symptoms recurred |
| E04 | RCH-5 | Await ensure-initialized! | Unknown | 2/3 parallel | Partial improvement |
| E05 | RCH-5 | e2e/ensure-builtin helper | 2/3 parallel | 3/3 parallel, 3/3 serial | Appeared fixed; monitoring |
| E06 | Popup delete timing | Replace banner wait with toHaveCount timeout | Unknown | 3/3 parallel, 3/3 serial | Workaround, not root cause fix |
| E07 | rm existed flag | Chain save->rm, use normalized name | Unknown | 3/3 parallel, 3/3 serial | Appeared fixed; monitoring |
| E08 | RCH-2 | Force icon update before wait | Unknown | 3/3 serial, 3/3 parallel | Monitoring |
| E09 | RCH-6 | Re-seed mv path source per attempt | Unknown | 3/3 serial, 3/3 parallel | Monitoring |

**Before/After format:** X/Y = passed runs / total runs

**Conclusion values:**
- **Confirmed** - Hypothesis validated, root cause found
- **Disproved** - Hypothesis ruled out
- **Insufficient** - Some improvement but symptoms persist
- **Workaround** - Masks issue without fixing root cause
- **Monitoring** - Passed verification but needs more data

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
