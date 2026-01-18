# FS REPL Sync - Remaining Work

The FS REPL Sync setting is implemented. This document tracks remaining bugs and enhancements.

## Current State

The core infrastructure is in place:
- Toggle in Settings section (default: disabled)
- When disabled: `save!`, `mv!`, `rm!` return clear error
- When enabled: operations execute immediately
- `ls` and `show` always work regardless of setting
- Panel save bypasses setting (trusted UI)

## Fixed Bugs

### FIXED: `save!` allows overwriting built-in scripts

**Severity:** High - security issue

**Problem:** When calling `(save! "GitHub Gist Installer (Built-in)" code)`, the name gets normalized to `"github_gist_installer_built_in.cljs"` before reaching the action handler. The handler then can't match it against the builtin's display name, so protection was bypassed.

**Fix:** Added `name-matches-builtin?` helper in `script-utils.cljs` that checks if a normalized name would match any builtin's display name. The action handler now calls this check before allowing save.

**Tests:**
- Unit: `test/background_actions_test.cljs` - "rejects when creating script with normalized builtin name" (PASS)
- E2E: `e2e/fs_write_test.cljs` - "save! rejects built-in script names" (PASS)

### FIXED: `save!` without force overwrites existing scripts

**Severity:** Medium - data loss risk

**Problem:** `(save! "existing-script" code)` should reject when a script with that name already exists (unless `:fs/force? true`).

**Fix:** Changed REPL save path in `background.cljs` to always generate fresh script IDs. This ensures the action handler correctly detects create operations and applies the "name already exists" check.

**Tests:**
- E2E: `e2e/fs_write_test.cljs` - "save! rejects when script already exists" (PASS)

## Remaining Enhancements

### UI Feedback (Lower Priority)

These provide better UX but aren't blocking:

- [ ] Error banner in panel when FS sync disabled
- [ ] Error banner in popup when FS sync disabled
- [ ] Extension icon error badge
- [ ] Panel updates when showing a file modified via REPL
- [ ] "Update" banner briefly shown after REPL modifications
- [ ] Extension icon briefly shows "update" badge

### Documentation

- [ ] User guide section on FS REPL Sync
- [ ] Dev docs on the message protocol for FS operations

## Test Status

### Unit Tests (background_actions_test.cljs)

| Test | Status |
|------|--------|
| rename-script: rejects when source not found | PASS |
| rename-script: rejects when source is builtin | PASS |
| rename-script: rejects when target exists | PASS |
| rename-script: allows rename when target free | PASS |
| rename-script: updates modified timestamp | PASS |
| delete-script: rejects when not found | PASS |
| delete-script: rejects when builtin | PASS |
| delete-script: allows delete | PASS |
| save-script: rejects when updating builtin | PASS |
| save-script: rejects when name exists (no force) | PASS |
| save-script: allows create when name new | PASS |
| save-script: allows update by ID | PASS |
| save-script: allows overwrite with force | PASS |
| save-script: rejects normalized builtin name | PASS |
| save-script: rejects normalized builtin even with force | PASS |

### E2E Tests (fs_write_test.cljs)

| Test | Status |
|------|--------|
| save! creates new script | PASS |
| save! with disabled creates disabled script | PASS |
| save! bulk returns map of results | PASS - fixed Jan 17, 2026 (was flaky in serial shard 1/6) |
| save! rejects when script already exists | PASS |
| save! rejects built-in script names | PASS |
| save! with force rejects built-in names | PASS |
| mv! renames a script | PASS |
| mv! returns from/to names | PASS |
| mv! rejects when target exists | PASS |
| mv! rejects renaming built-in | PASS |
| rm! deletes a script | PASS |
| rm! rejects deleting built-in | PASS |
| rm! bulk returns map of results | PASS - fixed Jan 17, 2026 (was flaky in serial shard 1/6) |
| rm! returns existed flag | PASS |

### E2E Flakiness Notes (Resolved Jan 17, 2026)

Observed intermittent failures in serial shard 1/6 when running:

- `bb test:e2e --serial -- --shard=1/6`

Failures observed across three consecutive runs:

- `epupp.fs/save! with vector returns map of per-item results`
- `epupp.fs/rm! with vector returns map of per-item results`

In failing runs, the test log showed:

- `=== Bulk save result === ":pending"`
- `=== Bulk rm result === ":pending"`

Fixes applied:

- Test: normalize quoted `pr-str` values when polling bulk results in [e2e/fs_write_test.cljs](e2e/fs_write_test.cljs)
- Implementation: avoid REPL save script ID collisions with UUID-based IDs in [src/background.cljs](src/background.cljs)

Re-run results:

- `bb test:e2e --serial -- --shard=1/6` passed twice in a row after the fixes.

## Code Locations

| Component | File | Purpose |
|-----------|------|---------|
| API surface | `extension/bundled/epupp/fs.cljs` | `save!`, `mv!`, `rm!`, `ls`, `show` |
| Action handlers | `src/background_actions.cljs` | Pure state transition logic |
| Setting gate | `src/background.cljs` | `fs-repl-sync-enabled?` check |
| Name normalization | `src/script_utils.cljs` | `normalize-script-name` |
| Popup toggle | `src/popup.cljs` | Settings UI |

## Manual Testing Results

Note: Only upddate these after manual testing.

Tested via [test-data/tampers/fs_api_exercise.cljs](../../test-data/tampers/fs_api_exercise.cljs):

### Read Operations (always work)
- [x] `ls` - lists all scripts
- [x] `show` - returns code for existing script
- [x] `show` - returns nil for non-existent script
- [x] `show` bulk - returns map of name to code (nil for missing)

### Write Operations - Setting Disabled
- [x] All write operations reject when FS REPL Sync is disabled
- [ ] Error banner should appear in panel (where it shows the extension-updated banner)
- [ ] Error banner should appear in popup
- [ ] Extension icon should show error badge

### Write Operations - Setting Enabled
- [x] `save!` - creates new script
- [x] `save!` - rejects when script with same name exists (no force)
- [x] `save!` with `:fs/force?` - overwrites existing script
- [ ] **BUG**: `save!` - should reject for built-in script names (currently creates normalized name file)
- [ ] `save!` with `:fs/force?` - should reject when trying to overwrite built-in
- [x] `save!` bulk - creates multiple scripts
- [ ] Panel should update when showing a file that was modified via REPL
- [x] `mv!` - renames script
- [x] `mv!` - rejects when source doesn't exist
- [x] `mv!` - rejects when target already exists (second rename)
- [x] `mv!` - rejects when trying to rename built-in (verified: "Cannot rename built-in scripts")
- [x] `rm!` - deletes script, returns `:fs/existed? true`
- [x] `rm!` - succeeds for non-existent with `:fs/existed? false`
- [x] `rm!` - rejects when trying to delete built-in
- [x] `rm!` bulk - handles mixed existing/non-existing
- [x] `rm!` bulk with built-in - fails early for built-in (verified: "Cannot delete built-in scripts")
- [ ] "Update" banner should appear briefly in panel (where it shows the extension-updated banner)
- [ ] "Update" banner should appear briefly in popup
- [ ] Extension icon should show briefly show "update" badge

## Test Infrastructure

E2E tests use `e2e/set-storage` runtime message to enable the setting before running write tests. See [testing-e2e.md](testing-e2e.md) for details.
