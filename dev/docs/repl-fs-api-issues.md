# REPL FS API - Issues and Testing Notes

## Overview
This document tracks issues discovered while manually testing the REPL File System API (`epupp.fs/*`) and highlights gaps in automated coverage.

## Snapshot (2026-01-16)
### Reality check
- All user-visible issues currently tracked in this doc are marked **Resolved** and are backed by E2E coverage.
- The sharded E2E run is currently green: `bb test:e2e` passes with 6 shards.

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
	- Write the scenario in one sentence: “From REPL, call `epupp.fs/save!` without force, then confirm Allow/Deny in popup.”

2. **Find the closest existing E2E test file and extend it**
	- Start by searching under `e2e/` for keywords: `fs`, `save!`, `pending-confirmation`, `approval`, `popup`, `Allow`, `Deny`.
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

## Current status
These are the stable behaviors we currently rely on:
- Force-path operations work: `save!`, `mv!`, `rm!` succeed with `{:fs/force? true}`.
- Confirmation UI is working in the centralized panel.
- Badge count updates are working.
- FS API rejects failed operations (Promise rejects on failure).

E2E confidence:
- Sharded E2E suite is green: `bb test:e2e` passes (6 shards).
- Shard 2 contents confirmed via `bb test:e2e --serial -- --list --shard=2/6` (currently the REPL FS write tests).

## Release scrutiny
Everything about the REPL FS API must be scrutinized before release. Return maps and their semantics must be treated as stable and cannot change after release.

## Known issues
Add new, currently failing issues here. If something is fixed, move it to "Resolved issues" and add an entry to the "Fixed log".

## Resolved issues
### Confirmation UI interactions (centralized panel)
**Status:** Resolved - centralized confirmation UI now supports per-item reveal/highlight/inspect controls and bulk confirm/cancel.

**Covered by E2E:** See [e2e/fs_ui_reactivity_test.cljs](../../e2e/fs_ui_reactivity_test.cljs) for:
- reveal + temporary highlight
- inspect from/to versions
- bulk confirm all / cancel all

### Confirmations should cancel on content changes
**Status:** Resolved - confirmations cancel when code or manifest metadata changes.

### Pending-confirmation badge does not update
**Status:** Resolved - badge count increments for each queued confirmation and decrements when a confirmation is accepted or denied.

### Promise does not reject on failure
**Status:** Resolved - failed operations now reject (Promise rejection) and trigger `p/catch`.

## Test coverage gaps
None known at the moment - E2E asserts confirmation UI state for non-force paths.

## Testing process issues
### E2E describe blocks are too deep for AI edits
**Symptom:** Some E2E files have long, deeply nested `describe` forms, which makes automated edits fragile.

**Fix:** Extract anonymous functions so the `describe` form reads like a top-level recipe. Use [e2e/fs_ui_reactivity_test.cljs](../../e2e/fs_ui_reactivity_test.cljs) as the template.

**Process:** When updating an E2E file, first apply this refactor and re-run the tests to confirm no behavior changes. Only then make changes to test logic.

### FS API REPL E2E tests use atom-based result harvesting
**Symptom:** REPL FS API E2E tests use complex atom-based patterns with later inspection, which is harder to read and maintain.

**Fix (last priority):** Refactor to the same `promesa` `p/let` pattern used in [test-data/tampers/repl_fs.cljs](../../test-data/tampers/repl_fs.cljs), with inline `def`s.

## Bug log
Add new findings here as testing continues.

Tip: If the suite is green but you find a manual bug, add it under "Known issues" first, then write an E2E test that fails deterministically (no sleeps).

## Fixed log
- Confirmation UI now works in centralized panel
- Confirmation UI interactions: reveal/highlight/inspect + bulk confirm/cancel
- Badge count updates for pending confirmations
- FS API rejects failed operations (Promise rejection)
- Sharded E2E failure (shard 2) resolved
- Confirmations cancel on code and metadata changes

### 2026-01-16
- Shard 2 contents confirmed via `bb test:e2e --serial -- --list --shard=2/6` (REPL FS write tests).
- `bb test:e2e` passes with 6 shards.

### Template
- **Date:** YYYY-MM-DD
- **Scenario:**
- **Steps:**
- **Expected:**
- **Actual:**
- **Notes:**

### 2026-01-15
- **Scenario:** Confirmation UI for non-force REPL FS operations
- **Steps:** Run `save!` without `:fs/force?` and open the popup
- **Expected:** Confirmation card is visible in the centralized confirmation panel
- **Actual:** No confirmation UI visible (since fixed)
- **Notes:** Historical issue - resolved; E2E coverage now exists
