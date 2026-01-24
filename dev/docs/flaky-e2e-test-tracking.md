# Flaky E2E Test Tracking

Systematic tracking of flaky tests, attempted fixes, and hypotheses to prevent re-testing failed approaches and build institutional knowledge.

## Status Summary

| Metric | Count |
|--------|-------|
| Active flaky tests | 7 |
| Hypotheses pending | 2 |
| Successful fixes | 3 |

**Note:** Extension startup event test added Jan 2026 - race condition causing event loss.

---

## Flaky Test Log

| Test | File | Pattern | First Observed |
|------|------|---------|----------------|
| FS Sync save operations | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Intermittent timeout | Pre-Jan 2026 |
| FS Sync mv operations | [fs_write_mv_test.cljs](../../e2e/fs_write_mv_test.cljs) | Intermittent timeout | Pre-Jan 2026 |
| FS Sync rm: existed flag | [fs_write_rm_test.cljs](../../e2e/fs_write_rm_test.cljs#L317) | Intermittent timeout | Jan 2026 |
| Popup Icon: tab-local state | [popup_icon_test.cljs](../../e2e/popup_icon_test.cljs#L118) | Rare flake | Jan 2026 |
| FS Sync save: rejects builtin | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs#L283) | Rare flake (CI) | Jan 2026 |
| Panel Save: create new script | [panel_save_create_test.cljs](../../e2e/panel_save_create_test.cljs) | UI locator timeout | Jan 2026 |
| REPL manifest loads Replicant | [repl_ui_spec.cljs](../../e2e/repl_ui_spec.cljs#L247) | Assertion fail | Jan 2026 |

**Pattern types:** Intermittent timeout, race condition, state pollution, resource contention, timing sensitivity

---

## Attempted Fixes Log

Track each investigation to prevent re-testing failed approaches.

| Date | Hypothesis | Implementation | Outcome | Notes |
|------|-----------|----------------|---------|-------|
| Jan 2026 | H0: test-logger race condition | Added write queue in log-event! | SUCCESS | Verified: 3 parallel + 2 serial passes |
| Jan 2026 | Silent timeout in rm test setup | Added proper error throw on timeout | SUCCESS | First polling loop used when-when instead of if-throw |
| Jan 2026 | FS save builtin wait fix | Add stabilization delay after wait-for-builtin-script! | SUCCESS | 5 full runs, 0 failures |

**Outcome values:** SUCCESS, FAILED, PARTIAL, INCONCLUSIVE

---

## Hypotheses Tracker

Active hypotheses to investigate. Move to Attempted Fixes Log when tested.

### High Priority

#### H0: test-logger race condition (CONFIRMED)
- [x] Tested
- [x] Outcome documented

The `log-event!` function in [test_logger.cljs](../../src/test_logger.cljs#L34) uses non-atomic read-modify-write:
```clojure
(.get storage ["test-events"]
  (fn [result]
    (.push events entry)    ;; race: concurrent readers see stale array
    (.set storage {:test-events events} resolve)))
```

When `EXTENSION_STARTED` and `GET_CONNECTIONS_RESPONSE` log concurrently, last writer wins. Fix: serialize `log-event!` calls or use atomic storage update pattern.

#### H1: Storage event timing across extension contexts
- [ ] Tested
- [ ] Outcome documented

Storage change events may arrive at different times in popup vs panel vs background. FS write tests may have race conditions between storage write completion and UI update verification.

#### H2: Parallel test execution resource contention
- [ ] Tested
- [ ] Outcome documented

Docker sharded execution may cause contention for extension storage or WebSocket connections when multiple tests manipulate the same resources.

#### H3: Tab activation event timing
- [ ] Tested
- [ ] Outcome documented

The `test_injected_state_is_tab_local` test calls `.bringToFront` to switch tabs, then immediately opens a popup and waits for ICON_STATE_CHANGED event. Race condition: Chrome's `onActivated` may fire after the test starts waiting, or the async icon update hasn't logged yet when polling begins.

### Medium Priority

_(Add hypotheses here as discovered)_

### Low Priority

_(Add hypotheses here as discovered)_

---

## Resolution Process

Follow this workflow when investigating flaky tests.

### Before Starting

1. **Check this document** - Review Attempted Fixes Log to avoid re-testing
2. **Run baseline** - `bb test:e2e --serial -- --grep "test name"` (serial isolates timing issues)
3. **Select hypothesis** - Pick from Hypotheses Tracker or formulate new one
4. **Document start** - Add entry to Attempted Fixes Log with date and hypothesis

### During Investigation

1. **Delegate to experts** - Use `epupp-e2e-expert` for test code analysis and modifications
2. **Use REPLs** - Test assumptions in `squint` or `scittle-dev-repl`
3. **Isolate variables** - Test one hypothesis at a time
4. **Document findings** - Update Notes column with observations

### After Investigation

1. **Mark hypothesis** - Check "Tested" and "Outcome documented" boxes
2. **Record outcome** - Update Attempted Fixes Log with result
3. **Update metrics** - Adjust Status Summary if resolved
4. **Extract patterns** - Add to Emerging Patterns if reusable insight discovered
5. **Move hypothesis** - If tested, move from Tracker to Attempted Fixes Log

### Verification Standard

A fix is **SUCCESS** only when:
1. `bb test:e2e` passes 3 consecutive runs (full suite, parallel)
2. `bb test:e2e --serial` passes 2 consecutive runs
3. No new regressions introduced

A fix is **PARTIAL** when flakiness reduced but not eliminated.

---

## Emerging Patterns

Learnings that should inform [testing-e2e.md](testing-e2e.md) and test writing practices.

| Pattern | Source | Recommendation |
|---------|--------|----------------|
| Non-atomic storage writes cause event loss | H0 investigation | Use promise queue for chrome.storage read-modify-write operations |

---

## References

- [testing-e2e.md](testing-e2e.md) - E2E testing principles and patterns
- [testing.md](testing.md) - Overall testing philosophy
- [issues.md](issues.md#L52) - Original flakiness note
- [fixtures.cljs](../../e2e/fixtures.cljs) - Test helper library

---

## Original Plan-producing Prompt

Create a systematic flaky E2E test tracking system with:
1. A tracking document with: flaky test log, attempted fixes log, hypotheses tracker with checkboxes, resolution process, emerging patterns section
2. Pre-populate with known flaky fs_write tests
3. Format following succinct plan doc style (tables, checkboxes, minimal prose)
4. Hypotheses should be mostly high-level, sometimes detailed case-by-case
