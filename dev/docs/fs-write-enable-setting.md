# FS REPL Sync - Implementation Complete

This document documents the completed FS REPL Sync feature. All core functionality is implemented and tested.

## Mandatory reading index

Read these before working on REPL FS Sync:

- [architecture/overview.md](architecture/overview.md)
- [architecture/message-protocol.md](architecture/message-protocol.md)
- [architecture/repl-fs-sync.md](architecture/repl-fs-sync.md)
- [userscripts-architecture.md](userscripts-architecture.md)
- [docs/repl-fs-sync.md](../docs/repl-fs-sync.md)

## Test Infrastructure

E2E tests use `e2e/set-storage` runtime message to enable the setting before running write tests. See [testing-e2e.md](testing-e2e.md) for details.
## Completion Summary

- **Core infrastructure complete** - Toggle in Settings, write protection, read operations always available
- **All unit tests pass** - 15 tests in `background_actions_test.cljs`
- **All E2E tests pass** - 14 tests in `fs_write_test.cljs`
- **Documentation updated** - `user-guide.md` (REPL File System API section), `repl-fs-sync.md`

## Current State

UI updates not done yet.

The core infrastructure is in place:
- Toggle in Settings section (default: disabled)
- When disabled: `save!`, `mv!`, `rm!` return clear error
- When enabled: operations execute immediately
- `ls` and `show` always work regardless of setting
- `ls` hides built-in scripts by default - use `:fs/ls-hidden?` to include them
- Panel save bypasses setting (trusted UI)

## Fixed Bugs

...

## Remaining Issues

### UI Feedback

- [ ] Error banner in panel when FS sync disabled
- [ ] Error banner in popup when FS sync disabled
- [ ] Extension icon error badge
- [ ] Panel updates when showing a file modified via REPL
- [ ] "Update" banner briefly shown in popup after REPL modifications
- [ ] "Update" banner briefly shown in panel after REPL modifications
- [ ] Extension icon briefly shows "update" badge

### API

- [x] `ls` should hide built-in scripts unless `:fs/ls-hidden?` is provided

### Documentation

- [x] User guide section on FS REPL Sync - DONE (in `user-guide.md` REPL File System API section)
- [x] Dev docs on the message protocol for FS operations - see [architecture/message-protocol.md](architecture/message-protocol.md)

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
| rm! bulk rejects when any missing | PASS - fixed Jan 17, 2026 (was flaky in serial shard 1/6) |
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

### E2E Flakiness Notes (Observed Jan 18, 2026)

**Symptom:** Rare failure in `epupp.fs/rm! rejects deleting built-in scripts` with output showing a resolved result instead of rejection.

**Observed log snippet:**

```
Expected: true
Received: false

expect(result_str.includes("rejected")).toBe(true)
```

**Likely root cause:** Storage can contain a user script whose `:script/name` matches the built-in display name. The delete handler resolves if it finds a non-built-in script with that name. Because `find-script-by-name` returns the first match and the built-in guard only checks ID prefix, a stale user script can be deleted instead of rejecting. This is consistent with older runs that allowed saving a script named "GitHub Gist Installer (Built-in)" before the built-in name guard was added.

**Flow involved:**
`epupp.fs/rm!` - page -> content bridge -> background `delete-script` -> `background-actions/handle-action` `:fs/ax.delete-script` -> `find-script-by-name` -> `builtin-script?` guard.

**Why rare:** It depends on persistent storage state and script ordering. If a stale user script with the built-in name exists and appears before the built-in entry, the delete succeeds and the test sees a resolved result.

**Mitigation:** Added a test helper to clear non-built-in scripts during E2E setup. `clear-fs-scripts` preserves built-ins while removing user scripts to prevent leftover state from prior runs.

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
- [ ] `ls` should hide built-in scripts unless `:fs/ls-hidden?` is provided
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
- [x] `save!` - rejects for built-in script names
- [s] `save!` with `:fs/force?` - rejects when trying to overwrite built-in
- [x] `save!` bulk - creates multiple scripts
- [ ] Panel should update when showing a file that was modified via REPL
- [x] `mv!` - renames script
- [x] `mv!` - rejects when source doesn't exist
- [x] `mv!` - rejects when target already exists (second rename)
- [x] `mv!` - rejects when trying to rename built-in (verified: "Cannot rename built-in scripts")
- [x] `rm!` - deletes script
- [x] `rm!` - rejects for non-existent
- [x] `rm!` bulk - deletes existing files, then rejects for non-existent (Unix behavior)
- [x] `rm!` - rejects when trying to delete built-in
- [s] `rm!` bulk - handles mixed existing/non-existing
- [x] `rm!` bulk with built-in - fails early for built-in (verified: "Cannot delete built-in scripts")
- [ ] "Update" banner should appear briefly in panel (where it shows the extension-updated banner)
- [ ] "Update" banner should appear briefly in popup
- [ ] Extension icon should show briefly show "update" badge

