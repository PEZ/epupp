# REPL FS API - Issues and Testing Notes

## Overview
This document tracks issues discovered while manually testing the REPL File System API (`epupp.fs/*`) and highlights gaps in automated coverage.

### What’s left to do (next moves)
1. **Keep it green:** continue to run `bb test:e2e` as changes land, and treat any sharded failures as blockers.
2. **Maintenance (last priority):** refactor the REPL FS E2E tests away from atom-based result harvesting (see "Testing process issues").
3. **Release scrutiny:** as new behaviors are discovered during manual testing, add them as new entries under "Known issues" with a crisp failing E2E repro.

## Fixing process (E2E-first, TDD-friendly)
When a REPL FS issue is user-visible, prioritize an E2E test that reproduces it. This project’s E2E suite is designed to validate real extension workflows, avoid timing flake, and support fast TDD cycles.

Read these first:
- [testing.md](testing.md) - test philosophy and what goes where
- [testing-e2e.md](testing-e2e.md) - patterns for effective E2E testing (no sleeps, fast polling, log-powered assertions)

### Step-by-step workflow
1. **Name the user journey**
	- Write the scenario in one sentence: "From REPL, call `epupp.fs/save!` and verify the script appears in the popup list."

2. **Find the closest existing E2E test file and extend it**
	- Start by searching under `e2e/` for keywords: `fs`, `save!`, `pending-confirmation`, `popup`.
	- Prefer adding to an existing journey test over creating a brand new file - it keeps coverage cohesive.
	- Run a tight loop while developing: use `bb test:e2e --serial -- --grep "fs"` (or similar) to focus.

3. **Make the bug fail deterministically (no sleeps)**
	- Use Playwright’s built-in waiting assertions or the fixture wait helpers.
	- Assert on what a user would see (UI visibility/state), and when needed, add a log-powered assertion for internal events.
	- Keep timeouts short (~500ms) while doing TDD so failures are fast and actionable.

4. **Add a minimal failing test before changing implementation**
	- Don’t “fix and hope”. First, lock in the intended behavior with a failing test.
	- Keep the test’s setup minimal: only the actions needed to reproduce.

5. **Debug by observing signals, not by guessing**
	- Confirm whether the data is queued but UI doesn’t render, vs UI renders but is hidden.
	- Inspect state transitions via existing event logs where possible.
	- If you need to inspect DOM: assert for presence and visibility separately (`present` vs `visible`) to avoid false conclusions.

6. **Fix the root cause in `src/` and keep the change small**
	- Prefer pure, unit-testable helpers when extracting logic.
	- Avoid “forward declare” structure problems - definition order matters.

7. **Green the focused E2E test, then run the full E2E suite**
	- First: the single grep’d test.
	- Then: `bb test:e2e` to ensure you didn’t introduce timing regressions elsewhere.

8. **Normalize E2E describe structure before edits**
	- If the file has deeply nested or long `describe` forms, extract anonymous functions first.
	- Use the structure in [e2e/fs_ui_reactivity_test.cljs](../../e2e/fs_ui_reactivity_test.cljs) as the model.
	- Confirm tests still pass after only the refactor; then proceed with logic changes.

### What makes an issue-fix super effective
- **One crisp failing test** that matches the user-facing symptom.
- **A minimal repro script** you can paste into the REPL (and keep in the issue log).
- **Fast feedback loop** (serial + grep, short timeouts, no fixed sleeps).
- **Evidence-driven debugging**: capture events, check DOM presence vs visibility, verify state transitions.
- **Small, reviewable patches**: one behavior change per commit-sized slice.
- **Close the loop**: update this doc’s “Known bugs” + “Test coverage gaps” once the regression is covered.

## Known issues
Add new, currently failing issues here. If something is fixed, move it to "Resolved issues" and add an entry to the "Fixed log".

### `mv!` allows renaming to existing script name, creates duplicate names

**Severity:** High - causes data corruption

**Symptom:** Calling `(epupp.fs/mv! "script_a.cljs" "script_b.cljs")` when `script_b.cljs` already exists succeeds with `:fs/success true` instead of rejecting. This creates two scripts with the same name but different internal IDs.

**Expected:** The operation should reject with an error like "Destination script already exists: script_b.cljs"

**Repro:**
```clojure
;; Create two test files
(-> (epupp.fs/save! ["{:epupp/script-name \"test-file-1\" :epupp/site-match \"*\"}"
                     "{:epupp/script-name \"test-file-2\" :epupp/site-match \"*\"}"]
                    {:fs/force? true})
    (p/then #(js/console.log "Created:" (pr-str %))))

;; Rename file 1 to file 2 (should fail, but doesn't)
(-> (epupp.fs/mv! "test_file_1.cljs" "test_file_2.cljs" {:fs/force? true})
    (p/then #(js/console.log "Result:" (pr-str %)))
    (p/catch #(js/console.log "Error:" (str %))))
;; => {:fs/success true, :fs/from-name "test_file_1.cljs", :fs/to-name "test_file_2.cljs", :fs/error nil}

;; Now ls shows TWO scripts named test_file_2.cljs with different timestamps
(-> (epupp.fs/ls)
    (p/then #(println (filter (fn [s] (clojure.string/includes? (:fs/name s) "test_file")) %))))
```

## Resolved issues


## Test coverage gaps
Unknown


