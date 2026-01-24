# Panel Name-Based Script Tracking Plan

Simplify panel script tracking by removing internal ID usage. Adopt the same name-based approach used by the FS Sync API, where scripts are identified solely by name and builtins are tracked via explicit metadata.

## Current Model (ID-based)

| State Key | Purpose |
|-----------|---------|
| `:panel/script-id` | Internal ID for create/update logic |
| `:panel/original-name` | Name when loaded (for rename detection) |
| `:panel/script-name` | Current name from manifest |

**Problems:**
- ID can go stale (script deleted, panel reopened with persisted ID)
- Reload-from-storage uses name lookup but state tracks ID - mismatch
- Complex hybrid matching in system-banner handler (matches by ID OR name OR from-name)
- Builtin detection uses ID prefix, fragile coupling
- Panel generates IDs that background may override anyway

## New Model (Name-based)

| State Key | Purpose |
|-----------|---------|
| `:panel/original-name` | Name when loaded (nil = new script) |
| `:panel/script-name` | Current name from manifest |

**Key changes:**
1. Remove `:panel/script-id` entirely from panel state
2. Use `original-name` presence to detect "editing existing" vs "new script"
3. Let background handle all ID generation and management
4. Track builtins via explicit `:script/builtin?` metadata, not ID prefix
5. Match system-banner events by name only (like FS Sync does)

## Benefits

- **Simpler mental model**: Names are the user-visible identity
- **Aligns with FS Sync**: Same API contract as `epupp.fs/save!`, `epupp.fs/show`, etc.
- **No stale state**: Names are always current; no phantom IDs
- **Cleaner builtin handling**: Explicit metadata instead of ID prefix convention
- **Less code**: Remove ID tracking, generation, and matching logic from panel

---

## Standard

- All edits delegated to **Clojure-editor subagent**
- Before each batch: delegate to **epupp-testrunner subagent** for baseline
- After each batch: delegate to **epupp-testrunner subagent** for verification
- REPL experiments to verify Squint assumptions before editing
- Tick checkboxes without inserting commentary blocks

---

## Required Reading

### Architecture Docs
- [dev/docs/architecture/repl-fs-sync.md](architecture/repl-fs-sync.md) - FS Sync patterns (the model to follow)
- [dev/docs/architecture/uniflow.md](architecture/uniflow.md) - State management patterns
- [dev/docs/architecture/message-protocol.md](architecture/message-protocol.md) - System-banner message format
- [docs/repl-fs-sync.md](../../docs/repl-fs-sync.md) - User-facing FS API (name-only interface)

### Source Files to Modify
- [src/panel.cljs](../../src/panel.cljs) - Panel view and effects
- [src/panel_actions.cljs](../../src/panel_actions.cljs) - Panel action handlers
- [src/script_utils.cljs](../../src/script_utils.cljs) - Builtin detection functions

### Source Files to Review
- [extension/bundled/epupp/fs.cljs](../../extension/bundled/epupp/fs.cljs) - FS Sync API (reference for name-only pattern)
- [src/background_actions/repl_fs_actions.cljs](../../src/background_actions/repl_fs_actions.cljs) - Background handles IDs internally
- [src/storage.cljs](../../src/storage.cljs) - Script storage and builtin handling

### Test Files
- [test/panel_actions_test.cljs](../../test/panel_actions_test.cljs) - Panel action unit tests
- [e2e/panel_save_test.cljs](../../e2e/panel_save_test.cljs) - Panel save E2E tests
- [e2e/panel_save_rename_test.cljs](../../e2e/panel_save_rename_test.cljs) - Panel rename E2E tests

---

## Checklist

### Phase 1: Add Builtin Metadata

Add explicit `:script/builtin?` metadata to builtin scripts so detection doesn't rely on ID prefix.

#### 1.1 Update builtin script definitions
Location: `storage.cljs` builtin script definitions

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Add `:script/builtin? true` to each builtin script definition
- Ensure builtin scripts have this flag set on storage initialization

#### 1.2 Add name-based builtin check function
Location: `script_utils.cljs`

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Add `builtin-script?` function that checks `:script/builtin?` metadata
- Keep existing `builtin-script-id?` temporarily for backward compatibility
- Add `name-matches-builtin?` check using script name lookup

#### 1.3 Update panel to use metadata-based builtin check
Location: `panel.cljs` line ~507

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Replace `(script-utils/builtin-script-id? script-id)` with name-based lookup
- Fetch script by name from storage to check `:script/builtin?`

---

### Phase 2: Remove Script ID from Panel State

#### 2.1 Remove `:panel/script-id` from initial state
Location: `panel.cljs` line ~25

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `:panel/script-id nil` from `!state` atom definition

#### 2.2 Update `save-panel-state!` to not persist ID
Location: `panel.cljs` lines ~55-65

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `script-id` from destructuring
- Remove `scriptId` from `state-to-save` object

#### 2.3 Update `restore-panel-state!` to not restore ID
Location: `panel.cljs` lines ~70-90

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `script-id` extraction from saved state
- Remove `:script-id` from dispatch args

#### 2.4 Update `save-script-section` UI logic
Location: `panel.cljs` lines ~490-540

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Replace `script-id` checks with `original-name` presence checks
- Update `editing-builtin?` to use name-based lookup
- Update header title logic: `(if original-name "Edit Userscript" "Save as Userscript")`

#### 2.5 Update debug info display
Location: `panel.cljs` line ~651

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `script-id` from debug info display
- Or replace with `original-name` for debugging

---

### Phase 3: Update Panel Actions

#### 3.1 Update `:editor/ax.save-script` action
Location: `panel_actions.cljs` lines ~131-165

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `script-id` from destructuring
- Remove ID generation logic (let background handle it)
- Use `original-name` presence for create vs update detection
- Remove `id` from effect args (background generates)

#### 3.2 Update `:editor/ax.handle-save-response` action
Location: `panel_actions.cljs` lines ~167-190

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `:panel/script-id` from state updates
- Keep `original-name` update on success

#### 3.3 Update `:editor/ax.load-script-for-editing` action
Location: `panel_actions.cljs` lines ~205-220

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `id` parameter
- Remove `:panel/script-id` from state update
- Keep `:panel/original-name` update

#### 3.4 Update `:editor/ax.initialize-editor` action
Location: `panel_actions.cljs` lines ~230-250

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `script-id` from args destructuring
- Remove `:panel/script-id` from state update

#### 3.5 Update `:editor/ax.new-script` action
Location: `panel_actions.cljs` lines ~255-270

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `:panel/script-id nil` from state reset

---

### Phase 4: Update System-Banner Handler

#### 4.1 Simplify event matching to name-only
Location: `panel.cljs` lines ~740-810

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `script-id` extraction from message
- Remove `current-id` from state reading
- Remove `matches-id?` logic
- Keep only name-based matching (`matches-name?`, `matches-from?`)
- Simplify `affects-current?` to use name matching only

---

### Phase 5: Update Effects

#### 5.1 Update `:editor/fx.save-script` effect
Location: `panel.cljs` lines ~195-215

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `id` from effect args
- Don't send ID to background (let background generate)

#### 5.2 Update `:editor/fx.reload-script-from-storage` effect
Location: `panel.cljs` lines ~275-290

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Already uses name-based lookup - verify it works without ID
- Update dispatch to not pass ID to `load-script-for-editing`

#### 5.3 Update `:editor/fx.check-editing-script` effect
Location: `panel.cljs` lines ~260-275

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `(.-id script)` from dispatch args
- Dispatch with name only

---

### Phase 6: Update Tests

#### 6.1 Update panel action unit tests
Location: `test/panel_actions_test.cljs`

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove script-id from test state setups
- Update assertions to not check for script-id
- Add tests for name-based builtin detection

#### 6.2 Verify E2E tests pass
Location: `e2e/panel_save_test.cljs`, `e2e/panel_save_rename_test.cljs`

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Run E2E tests to verify panel workflows still work
- Update any assertions that check for script-id

---

### Phase 7: Cleanup

#### 7.1 Remove deprecated ID functions (if unused)
Location: `script_utils.cljs`

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Check if `builtin-script-id?` is still used anywhere
- Check if `generate-script-id` is still used in panel (should only be in background)
- Remove or deprecate unused functions

**Result:**
- `builtin-script-id?` kept as internal function (used by `builtin-script?` for backward compat, exported for Scittle)
- All direct calls to `builtin-script-id?` in storage.cljs and repl_fs_actions.cljs replaced with `builtin-script?` on full script objects
- `generate-script-id` correctly only used in background (repl_fs_actions.cljs)

#### 7.2 Update persistence watcher
Location: `panel.cljs` lines ~710-720

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Remove `script-id` comparison from change detection

---

### Phase 8: Script List Awareness for Reliable Rename

**Problem:** When renaming a script to a name that already exists, the panel offers "Create Script" and "Rename" buttons. "Create Script" doesn't make sense (it would fail with "already exists"), and "Rename" will also fail. The panel is guessing based on stale information.

**Solution:** The panel needs an always up-to-date listing of all scripts in storage. With this list, the panel can:
- Detect when the new name conflicts with an existing script
- Offer appropriate choices based on actualities, not guesses
- Show clear feedback like "Name 'foo.cljs' already exists - choose a different name or overwrite"

#### 8.1 Add script list to panel state
Location: `panel.cljs`

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Add `:panel/scripts-list` to panel state
- Load script list on panel initialization
- Subscribe to storage changes to keep list current

#### 8.2 Create effect to load/refresh script list
Location: `panel.cljs`

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Add `:editor/fx.load-scripts-list` effect
- Read from `chrome.storage.local` scripts key
- Dispatch action to update state with parsed list

#### 8.3 Add storage change listener for scripts
Location: `panel.cljs`

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Listen for `chrome.storage.onChanged` events
- When `scripts` key changes, refresh the list
- Ensure list stays current as scripts are added/removed/renamed elsewhere

#### 8.4 Update save UI logic to use script list
Location: `panel.cljs` save-script-section

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- Check if normalized name exists in `:panel/scripts-list`
- Differentiate: editing same script vs. name collision with different script
- Show appropriate UI:
  - Same name as original: "Save Script" (update)
  - New name, doesn't exist: "Create Script" (create new)
  - New name, exists: Show conflict warning, offer "Overwrite" or "Cancel"

#### 8.5 Handle rename-to-existing case
Location: `panel_actions.cljs`

- [x] addressed in code
- [x] verified by tests

**Actions needed:**
- When name changed and target exists:
  - If user confirms overwrite: delete old, save as new with existing's ID
  - If user cancels: stay in edit mode
- Update `:editor/ax.save-script` to accept `:force?` flag for overwrite

**Result:**
- Added `:editor/ax.save-script-overwrite` action that sets `:script/force? true`
- Added `:force` property to `script->js` conversion
- Updated background `panel-save-script` handler to extract force flag
- UI shows "Overwrite" button when name conflicts with existing script

---

## Batch Execution Order

**Batch A: Builtin Metadata (1.1-1.3)** - COMPLETE
1. Run testrunner baseline
2. Add `:script/builtin?` to storage
3. Add name-based builtin check
4. Update panel to use new check
5. Run testrunner verification

**Batch B: Remove ID from Panel State (2.1-2.5)** - COMPLETE
1. Run testrunner baseline
2. Remove ID from state definition and persistence
3. Update UI logic to use original-name
4. Run testrunner verification

**Batch C: Update Panel Actions (3.1-3.5)** - COMPLETE
1. Run testrunner baseline
2. Update all panel actions to not use ID
3. Run testrunner verification

**Batch D: Update System-Banner Handler (4.1)** - COMPLETE
1. Run testrunner baseline
2. Simplify to name-only matching
3. Run testrunner verification

**Batch E: Update Effects (5.1-5.3)** - COMPLETE
1. Run testrunner baseline
2. Remove ID from effect signatures
3. Run testrunner verification

**Batch F: Update Tests (6.1-6.2)** - COMPLETE
1. Run testrunner baseline
2. Update unit tests
3. Run full E2E verification

**Batch G: Cleanup (7.1-7.2)** - COMPLETE
1. Run testrunner baseline
2. Remove deprecated code
3. Final testrunner verification

**Batch H: Script List Awareness (8.1-8.5)** - COMPLETE
1. Run testrunner baseline
2. Add scripts list to panel state
3. Add storage change listener
4. Update save UI to detect conflicts
5. Handle rename-to-existing with proper UX
6. Run testrunner verification

---

## Verification

1. Create new script in Panel - verify it saves without panel generating ID
2. Edit existing script - verify `original-name` tracks identity
3. Rename script - verify workflow completes successfully
4. Delete script via REPL FS - verify panel clears correctly
5. Rename script via REPL FS - verify panel reloads by name
6. Try to save over builtin - verify rejection works (via `:script/builtin?`)
7. Close and reopen panel - verify state restores without stale ID
8. Rename to existing filename - verify panel shows appropriate conflict UI
9. Run all E2E tests - verify no regressions

---

## Original Plan-producing Prompt

The FS Sync is not useing Ids at all. Can we do the same in the panel?

Please create a dev/docs plan document for this, with the plan structure inspired by the latest plans you find there. Include that we should be tracking built-ins by an explicit metadata on the file, not by id, nor by name.
