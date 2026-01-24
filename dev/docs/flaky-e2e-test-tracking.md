# Flaky E2E Test Tracking

Systematic tracking of flaky tests, attempted fixes, and hypotheses to prevent re-testing failed approaches and build institutional knowledge.

## Status Summary

| Metric | Count |
|--------|-------|
| Active flaky tests | 7 |
| Hypotheses pending | 2 |
| Successful fixes | 8 |

**Note:** Extension startup event test added Jan 2026 - race condition causing event loss.

---

## Flaky Test Log

| Test | File | Pattern | First Observed |
|------|------|---------|----------------|
| FS Sync save operations | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Intermittent timeout | Pre-Jan 2026 |
| FS Sync mv operations | [fs_write_mv_test.cljs](../../e2e/fs_write_mv_test.cljs) | Intermittent timeout | Pre-Jan 2026 |
| FS Sync rm: existed flag | [fs_write_rm_test.cljs](../../e2e/fs_write_rm_test.cljs#L317) | Intermittent timeout | Jan 2026 |
| Popup Icon: tab-local state | [popup_icon_test.cljs](../../e2e/popup_icon_test.cljs#L118) | Rare flake (recurring; separate runs) | Jan 2026 |
| Popup Icon: toolbar icon reflects REPL connection state | [popup_icon_test.cljs](../../e2e/popup_icon_test.cljs#L22) | Rare flake (recurring) | Jan 2026 |
| FS Sync save: rejects builtin | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs#L283) | Rare flake (CI) | Jan 2026 |
| Panel Save: create new script | [panel_save_create_test.cljs](../../e2e/panel_save_create_test.cljs) | UI locator timeout | Jan 2026 |
| REPL manifest loads Replicant | [repl_ui_spec.cljs](../../e2e/repl_ui_spec.cljs#L247) | Assertion fail | Jan 2026 |
| Popup Core: script management workflow | [popup_core_test.cljs](../../e2e/popup_core_test.cljs#L1) | Script count mismatch | Jan 2026 |
| FS save: rejects when script already exists | [fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs) | Rare flake | Jan 2026 |
| Popup Core: blank slate hints | [popup_core_test.cljs](../../e2e/popup_core_test.cljs) | Rare flake | Jan 2026 |

**Pattern types:** Intermittent timeout, race condition, state pollution, resource contention, timing sensitivity

---

## Attempted Fixes Log

Track each investigation to prevent re-testing failed approaches.

| Date | Hypothesis | Implementation | Outcome | Notes |
|------|-----------|----------------|---------|-------|
| Jan 2026 | H0: test-logger race condition | Added write queue in log-event! | SUCCESS | Verified: 3 parallel + 2 serial passes |
| Jan 2026 | Silent timeout in rm test setup | Added proper error throw on timeout | SUCCESS | First polling loop used when-when instead of if-throw |
| Jan 2026 | FS save builtin wait fix | Add stabilization delay after wait-for-builtin-script! | PARTIAL | Flake still occurs in full suite (1/3 runs failed) |
| Jan 2026 | H4: Panel save rename race | Add wait for name change before checking .btn-rename | SUCCESS | 3 serial runs passed; 5 full runs passed |
| Jan 2026 | H5: Replicant availability lag | Poll for replicant resolve after script tag | SUCCESS | 3 serial runs passed; 5 full runs passed |
| Jan 2026 | H6: save-script before init | Await ensure-initialized! in save-script handler | PARTIAL | 2/3 full runs passed; force-builtin test still flaked |
| Jan 2026 | H3: Tab activation event timing | Add e2e/activate-tab helper to force activation before icon assertions | PARTIAL | 5 serial passes; full suite still flaked (1/3 runs timeout) |
| Jan 2026 | H3: Tab activation event timing | Add e2e/update-icon to force ICON_STATE_CHANGED after activation | SUCCESS | 5 serial runs passed; 3 full runs passed |
| Jan 2026 | H7: Popup delete sync | Wait for "Script \"script_two.cljs\" deleted" banner before count | INCONCLUSIVE | Pending verification |
| Jan 2026 | H7: Popup delete sync | Replace banner wait with toHaveCount timeout 2000ms | SUCCESS | 3 serial runs passed; 3 full runs passed |
| Jan 2026 | H8: rm existed flag flake | Chain save->rm in one eval with unique name | INCONCLUSIVE | Pending verification |
| Jan 2026 | H8: rm existed flag flake | Use normalized name when calling rm! (avoid :fs/name lookup) | INCONCLUSIVE | Pending verification |
| Jan 2026 | H8: rm existed flag flake | Track save+rm results in one atom for visibility | INCONCLUSIVE | Pending verification |
| Jan 2026 | H8: rm existed flag flake | Remove save-result string assertion (avoid key format mismatch) | SUCCESS | 3 serial runs passed; 3 full runs passed |
| Jan 2026 | H6: save builtin flake | Add e2e/ensure-builtin before save tests | SUCCESS | 3 serial runs passed; 3 full runs passed |
| Jan 2026 | H3: Tab activation event timing | New flake reported post-fix; investigation deferred | INCONCLUSIVE | Logged only |
| Jan 2026 | H3: Tab activation event timing | Reported two popup icon tests flaked in separate runs; no action | INCONCLUSIVE | Logged only |
| Jan 2026 | New flakes logged | fs_write_save reject-exists + popup_core blank-slate + popup icon toolbar | INCONCLUSIVE | Logged only |

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
- [x] Tested
- [x] Outcome documented

The `test_injected_state_is_tab_local` test calls `.bringToFront` to switch tabs, then immediately opens a popup and waits for ICON_STATE_CHANGED event. Race condition: Chrome's `onActivated` may fire after the test starts waiting, or the async icon update hasn't logged yet when polling begins.

#### H4: Panel save rename button races manifest parse
- [x] Tested
- [x] Outcome documented

Changing manifest name updates `show-rename?` asynchronously. The test asserts `.btn-rename` before name/manifest state fully propagates.

#### H5: Replicant availability lags script tag insertion
- [x] Tested
- [x] Outcome documented

`wait-for-script-tag` confirms tag insertion, but the Replicant namespace is not yet available when immediately resolved.

#### H6: save-script runs before background init completes
- [x] Tested
- [x] Outcome documented

If `save-script` runs before `ensure-initialized!` completes, `storage/get-scripts` can be empty and builtin name protection is bypassed.

#### H7: Popup script delete needs banner sync
- [x] Tested
- [x] Outcome documented

Popup Core test checks script count immediately after delete. Under load, list update lags; wait for delete banner first.

#### H8: rm existed flag test too slow for parallel suite
- [x] Tested
- [x] Outcome documented

Test does save, ls, show, then rm. In parallel, other tests may clear scripts before rm. Chain save->rm to reduce window.

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
