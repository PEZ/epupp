# REPL FS API - Issues and Testing Notes

## Overview
This document tracks issues discovered while manually testing the REPL File System API (`epupp.fs/*`) and highlights gaps in automated coverage.

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

### What makes an issue-fix super effective
- **One crisp failing test** that matches the user-facing symptom.
- **A minimal repro script** you can paste into the REPL (and keep in the issue log).
- **Fast feedback loop** (serial + grep, short timeouts, no fixed sleeps).
- **Evidence-driven debugging**: capture events, check DOM presence vs visibility, verify state transitions.
- **Small, reviewable patches**: one behavior change per commit-sized slice.
- **Close the loop**: update this doc’s “Known bugs” + “Test coverage gaps” once the regression is covered.

## Current status
- Force-path operations work: `save!`, `mv!`, `rm!` succeed with `{:fs/force? true}`.
- Confirmation UI is working in the centralized panel.

## Release scrutiny
Everything about the REPL FS API must be scrutinized before release. Return maps and their semantics must be treated as stable and cannot change after release.

## Known bugs
### Confirmation UI interactions (centralized panel)
**Symptom:** Centralized confirmation panel lacks per-item interactions (highlight, reveal, inspect) to relate confirmations to the affected scripts.

**Expected:** Keep the centralized confirmation panel, but:
- Items being updated are highlighted in the list (same style as approvals).
- A reveal button exists that scrolls the affected list item into view and briefly highlights it.
- Confirmation cards include inspect buttons that populate the panel:
	- Delete: inspect the file being deleted.
	- Rename: inspect the file being renamed.
	- Update: two inspect buttons, for the `from` and `to` versions.
	- Create: inspect the file that would be created.
- When more than one confirmation is pending, show "Confirm all" and "Cancel all" buttons.

### Confirmations should cancel on content changes
**Symptom:** When a script changes (code or manifest metadata such as name, match, run-at), any pending confirmation for that script remains.

**Expected:** If a script is modified while a confirmation is pending, the confirmation should be cancelled and removed.

### Pending-confirmation badge does not update
**Symptom:** Pending-confirmation badge does not reflect the current count of queued confirmations.

**Expected:** Badge increments for each queued confirmation and decrements when a confirmation is accepted or denied.

### Promise does not reject on failure
**Symptom:** Operations that fail still resolve, so `p/then` executes with `{:fs/success false ...}` instead of rejecting.

**Example:** `(epupp.fs/mv! "test.cljs" "test2.cljs")` resolves with `{:fs/success false :fs/error "Script not found: test.cljs" ...}` and does not trigger `p/catch`.

**Expected:** Promise should reject (or be rejected via `p/rejected`) on failure, or a dedicated helper should be documented for checking `:fs/success` before `p/then`.

## Test coverage gaps
- E2E tests should explicitly assert confirmation UI state in the centralized panel (not just force paths).

## Hypotheses
- The confirmation UI may be present in the DOM but not visible (hidden container or CSS mismatch).
- The popup may not refresh after the pending confirmation is queued.

## Bug log
Add new findings here as testing continues.

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
