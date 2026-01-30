# Gist Installer Fixes Plan

Two issues with the gist installer that need addressing:

1. **E2E test coverage**: Manual-only scripts (no `:epupp/auto-run-match`) can now be installed via gist, but this path lacks E2E test coverage.

2. **Duplicate script bug**: Installing a script via gist creates a duplicate when a script with the same name already exists, instead of replacing it.

## Root Cause Analysis

### Issue 2: Duplicate Scripts

The `install-userscript!` function in `background.cljs` has its own save logic that differs from the REPL FS save path. It looks up existing scripts by **ID** (using normalized name as ID), but scripts created through other paths use timestamp-based IDs.

**The deeper problem:** Two separate code paths for saving scripts, leading to behavioral drift.

### Better Solution: Single Save Path

Instead of fixing `install-userscript!` to duplicate the name-lookup logic, we should have it **use the same FS save action** after URL validation and code fetch:

```
Current flow:
  gist-installer → install-userscript → storage/save-script! (separate logic)

Better flow:
  gist-installer → install-userscript → FS save action → unified save logic
```

This keeps:
- **Origin validation** in `install-userscript!` (security boundary)
- **Code fetching** in `install-userscript!`
- **ALL save logic** in ONE place: `repl_fs_actions.cljs:handle-fs-save`

### REPL Investigation Evidence

Via epupp-github REPL, found two scripts with same name:

```clojure
(filterv #(= (:fs/name %) "pez/selector_inspector.cljs") all-scripts)
;; =>
[{:fs/name "pez/selector_inspector.cljs", :fs/created "2026-01-25T20:04:49.223Z" ...}  ; original
 {:fs/name "pez/selector_inspector.cljs", :fs/created "2026-01-28T20:15:47.610Z" ...}] ; gist install
```

Re-installing via gist did NOT create a third copy because:
- Lookup by ID `"pez/selector_inspector.cljs"` found the gist-created script
- Updated that script (not original which has timestamp ID)

---

## Standard

- All edits delegated to **Clojure-editor subagent**
- **Unit tests preferred** - cover behavior at the action/function level where possible
- E2E tests only when unit tests cannot adequately verify (UI flows, cross-component integration)
- E2E tests delegated to **epupp-e2e-expert subagent**
- Before each batch: delegate to **epupp-testrunner subagent** for baseline
- After each batch: delegate to **epupp-testrunner subagent** for verification
- Tick checkboxes without inserting commentary blocks

---

## Required Reading

### Architecture Docs
- [dev/docs/testing-e2e.md](testing-e2e.md) - E2E test patterns and polling assertions

### Source Files
- [src/background.cljs](../../src/background.cljs) - `install-userscript!` function (lines 320-360)
- [src/background_actions/repl_fs_actions.cljs](../../src/background_actions/repl_fs_actions.cljs) - `handle-fs-save` - the unified save logic (lines 210-280)
- [src/storage.cljs](../../src/storage.cljs) - `get-script`, `save-script!` functions

### Test Files
- [test/repl_fs_actions_test.cljs](../../test/repl_fs_actions_test.cljs) - Unit tests for FS save logic
- [e2e/userscript_test.cljs](../../e2e/userscript_test.cljs) - Existing gist installer E2E tests
- [test-data/pages/mock-gist.html](../../test-data/pages/mock-gist.html) - Mock gist page for E2E

---

## Checklist

### Issue 1: Test Coverage for Manual-Only Gist Install

**Why E2E is required:** Manual-only gist install involves TWO manifest parsers that can drift:
- `manifest_parser.cljs` (Squint) - used by background/FS actions
- `gist_installer.cljs:extract-manifest` (SCI/Scittle) - runs in page context

Unit tests cover each parser separately, but E2E is needed to verify the full flow through both parsers works correctly for manual-only scripts.

#### 1.1 Add unit test for manual-only script save
Location: `test/repl_fs_actions_test.cljs`

Unit test `handle-fs-save` with script that has no `:epupp/auto-run-match`:
- Save succeeds
- Script stored with empty match array

- [x] addressed in code
- [x] verified by tests

#### 1.2 Add E2E test for manual-only gist install
Location: `e2e/userscript_test.cljs`

E2E test needed because two parsers can drift. Test that:
- Add mock gist with manual-only script to `test-data/pages/mock-gist.html`
- Gist installer shows Install button for manual-only script
- Install succeeds
- Script appears in popup with empty match / "No auto-run"

- [ ] addressed in code
- [ ] verified by tests

---

### Issue 2: Fix Duplicate Script Creation

#### 2.1 Refactor install-userscript! to use FS save action
Location: `src/background.cljs`, `install-userscript!` function

After origin validation and code fetch, dispatch `:fs/ax.save` action instead of calling `storage/save-script!` directly:

```clojure
;; After validation and fetch:
(dispatch! [[:fs/ax.save {:fs/script {:script/code code :script/force? true}
                          :fs/now-iso (js/Date.now)}]])
```

Benefits:
- Single source of truth for save logic
- Gets name-based lookup for free
- No drift between install and FS save paths

- [x] addressed in code
- [x] verified by tests

#### 2.2 Remove duplicate validation from install-userscript!
Location: `src/background.cljs`

Remove the manual ID assignment, builtin check, and `storage/save-script!` call since FS save handles all of this.

- [x] addressed in code
- [x] verified by tests

#### 2.3 Add unit tests for force-overwrite by name
Location: `test/repl_fs_actions_test.cljs` (ALREADY EXISTS)

Test already exists (`test-save-force-overwrite-preserves-existing-script-id`) verifying:
- Save with `force? true` overwrites existing script by name
- Preserves existing script's ID when overwriting
- Works correctly when existing script has timestamp-based ID

- [x] addressed in code (test pre-existed)
- [x] verified by tests

#### 2.4 Assess if E2E test is needed

After unit tests are in place, evaluate:
- Do unit tests adequately cover the gist install → save path?
- Is there cross-component integration that unit tests miss?
- Decision: E2E test needed? [ ] Yes / [x] No (unit tests sufficient)

**Assessment:** Unit test `test-save-force-overwrite-preserves-existing-script-id` covers the force-overwrite behavior. Existing E2E test `test_gist_installer_shows_button_and_installs` proves the message flow works. After Batch A refactoring unified the save path, gist install uses the same action handler as REPL/panel, so unit tests adequately cover the behavior.

- [x] assessment complete
- [x] addressed in code (via unit tests + refactoring)
- [x] verified by tests

---

## Execution Order

**Batch A: Issue 2 Fix - Unified Save Path**
1. Run testrunner baseline
2. Refactor `install-userscript!` to dispatch FS save action after validation
3. Remove duplicate validation/save logic
4. Run testrunner verification

**Batch B: Unit Tests**
1. Run testrunner baseline
2. Add unit tests for force-overwrite preserving ID
3. Add unit tests for manual-only script save
4. Run testrunner verification

**Batch C: E2E Assessment**
1. Review unit test coverage
2. Decide if E2E tests add value beyond unit tests
3. If yes, delegate to epupp-e2e-expert for implementation
4. Run testrunner verification (if E2E added)

---

## Original Plan-producing Prompt

Thanks. Good solution for the anti-drift. Now I think we may need a mini plan doc anyway.

1. I want to cover this base with an E2E test
2. Another issue turned up: I installed the script "pez/selector_inspector.cljs" with the gist installer and expected it to replace the script with the same name I had already installed. But instead I now have two scripts with the same name. That shouldn't really be possible if we have the protection of the file storage integrity at the same place.

Please create a plan doc for these two issues. Inspire the structure of the plan from our latest plans in dev/docs.

**Refinement:** Whatever API that the gist-installer script uses should use the same storage access functions as installing with the repl-fs, or via the panel does. Instead of creating yet another place where we have drift...

**Refinement 2:** Add to the plan that we should cover with unit tests wherever possible. We may not need E2E tests if unit tests protect enough. The plan should include consideration of whether this is the case, or if E2E tests will have to step in.
