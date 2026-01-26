# API Consistency Plan - Pre-1.0 Release

Align manifest format, storage schema, and `epupp.fs` return shapes for consistency before 1.0 release. Breaking changes acceptable since we're pre-release in the userscripts branch.

## Rules of Engagement

* Avoid using `sed`. Instead ask the testrunner subagent to tell you what goes wrong when running e2e tests. And reading and searching files in the workspace can be done with your tools.

## Summary of Changes

| Area | Before | After |
|------|--------|-------|
| Manifest key | `:epupp/site-match` | `:epupp/auto-run-match` |
| Return key | `:fs/match` | `:fs/auto-run-match` |
| Return map envelope keys | `:requestId`, `:source`, `:type` included | Whitelist API keys only (no transport keys) |
| Boolean naming | `:fs/enabled` | `:fs/enabled?` |
| No-pattern value | `[]` | Key omitted |
| `save!` return | `{:fs/success :fs/name :fs/error}` | Base info + `:fs/newly-created?` |
| Scripts without match | Created enabled | Created disabled |
| **Removing match** | **Match persisted** | **Match cleared, disabled** |
| Built-in naming | `"GitHub Gist Installer (Built-in)"` | `epupp/gist_installer.cljs` |
| Built-in detection | By ID (`epupp-builtin-*`) | By metadata (`:script/builtin?`) ✅ |
| `epupp/` namespace | Unreserved | Reserved for system use |
| Panel persistence | `{code, scriptName, scriptMatch, ...}` | `{code}` only |
| Storage schema | Cached manifest fields | Derive from manifest on load |
| Schema versioning | None | `schemaVersion` field |
| Storage key naming | Mixed (`granted-origins` vs `userAllowedOrigins`) | camelCase (`grantedOrigins`) |

## Rationale

1. **Naming clarity**: `:epupp/auto-run-match` makes intent obvious - patterns control automatic script execution
2. **Manifest-return alignment**: Same concepts use same names (minus namespace)
3. **Boolean convention**: `?` suffix for boolean keywords (`:fs/enabled?`, `:fs/newly-created?`)
4. **Semantic correctness**: Scripts without auto-run patterns shouldn't have `:fs/enabled?` field
5. **Consistent returns**: All `epupp.fs` functions returning script info use the same base shape
6. **Transport isolation**: API returns should exclude message envelope fields (`:requestId`, `:source`, `:type`)
7. **Auto-run revocability**: Removing `:epupp/auto-run-match` from manifest MUST clear auto-run behavior entirely
8. **Namespace reservation**: `epupp/` prefix reserved for system scripts - prevents user conflicts
9. **Built-in identification**: Use metadata (`:script/builtin?`) not ID patterns for built-in detection
10. **Scheme reservation**: `epupp://` scheme reserved for future internal use
11. **Code is source of truth**: Panel persists only code, storage derives metadata from manifest
12. **Schema versioning**: Version field enables future migrations
13. **Storage key consistency**: camelCase for all storage keys (JS convention)

## Progress Checklist

Note: Do not edit or care about files that may be changed by someone else and are unrelated to this plan.

### Phase 1: Manifest Rename `:epupp/site-match` → `:epupp/auto-run-match`

- [x] **manifest_parser.cljs**: Update key extraction
- [x] **storage.cljs**: Update script creation and retrieval
- [x] **background.cljs**: Update manifest handling
- [x] **popup.cljs**: Update display logic
- [x] **panel.cljs**: Update editor UI
- [x] **panel_actions.cljs**: Update save logic and default template
- [x] **Built-in scripts**: Update bundled script manifests
- [x] **Unit tests**: Update all manifest-related tests
- [x] **E2E tests**: Update manifest assertions
- [x] **Human verified**: Confirmed Phase 1 changes in the UI

### Phase 2: Return Shape Consistency

- [x] **repl_fs_actions.cljs**: Add `script->base-info` builder function
- [x] **repl_fs_actions.cljs**: Update `list-scripts` to use builder
- [x] **repl_fs_actions.cljs**: Update `save-script` response to include base info + `:fs/newly-created?`
- [x] **repl_fs_actions.cljs**: Update `rename-script` response to include base info + `:fs/from-name` + `:fs/to-name`
- [x] **repl_fs_actions.cljs**: Update `delete-script` response to include base info + `:fs/existed?`
- [x] **content_bridge.cljs**: Forward all response properties via `js/Object.assign`
- [x] **bundled/epupp/fs.cljs**: Transform `:success` → `:fs/success` for all operations
- [x] **E2E tests**: Updated to expect namespaced keys (`:fs/success`, `:fs/enabled?`, etc.)
- [ ] **Human verified**: Confirmed Phase 2 changes in the UI

### Phase 2b: Return Map Envelope Filtering

**Problem discovered (2026-01-26)**: `epupp.fs` return maps include transport envelope keys (`:requestId`, `:source`, `:type`) because the page bridge forwards the full response and the REPL API converts it wholesale.

**Expected behavior**: REPL-facing return maps only include the whitelisted API keys (no transport metadata).

- [x] **extension/bundled/epupp/fs.cljs**: Replace response conversion with a whitelist (e.g., `select-keys`) to drop `:requestId`, `:source`, and `:type`
- [x] **Unit tests**: Add coverage to ensure return maps only includes explicit API facing keys
- [x] **E2E tests**: Verify REPL fs results contain only API keys
- [x] **Docs**: Note that transport envelope keys are not part of the REPL API contract
- [ ] **Human verified**: Confirmed Phase 2b behavior in the REPL

### Phase 3: Boolean Naming Convention

- [x] **bg_fs_dispatch.cljs**: Use `:fs/enabled?` in builder
- [x] **bundled/epupp/fs.cljs**: Update docstrings
- [x] **popup.cljs**: Update any `:fs/enabled` references
- [x] **Unit tests**: Update assertions for new key names
- [x] **E2E tests**: Update assertions for new key names
- [x] **Human verified**: Confirmed Phase 3 changes in the UI

### Phase 4: Auto-Run Behavior

- [x] **storage.cljs**: Scripts without match created with `enabled: false`
- [x] **bg_fs_dispatch.cljs**: Omit `:fs/enabled?` when no auto-run patterns
- [x] **bg_fs_dispatch.cljs**: Omit `:fs/auto-run-match` when no patterns
- [x] **panel_actions.cljs**: Verify save behavior for scripts without match
- [x] **Unit tests**: Test enabled default based on match presence
- [x] **E2E tests**: Verify behavior for manual-only scripts
- [x] **Human verified**: Confirmed Phase 4 changes in the UI

### Phase 5: Documentation

- [x] **README.md**: Update manifest examples
- [x] **docs/user-guide.md**: Update all code examples
- [x] **docs/repl-fs-sync.md**: Update return shape examples
- [x] **dev/docs/api-surface-catalog.md**: Update API catalog
- [x] **dev/docs/userscripts-architecture.md**: Update manifest format
- [ ] **Human verified**: Confirmed Phase 5 changes in the docs

### Phase 6: Auto-Run Revocation (Regression Fix)

**Problem discovered (2026-01-24)**: When updating a script and removing `:epupp/auto-run-match` from its manifest, the old match pattern persists. This happens because `save-script!` uses `merge`, which keeps existing values when new ones aren't provided.

**Expected behavior**: Removing `:epupp/auto-run-match` from a manifest should:
1. Clear `:script/match` entirely
2. Reset `:script/enabled` to `false`
3. Result in a manual-only script (no auto-run UI)

**Root cause**: `storage.cljs` `save-script!` merges incoming script with existing, preserving old `:script/match` when not explicitly set to nil/empty.

**Solution**: Centralize auto-run handling in `storage.cljs` so all entry points (panel save, REPL save, gist install, script import) behave consistently.

- [x] **storage.cljs**: Extract match from manifest in `save-script!`, explicitly handle nil/empty case
- [x] **storage.cljs**: When match is nil/empty, set `:script/match []` and `:script/enabled false`
- [x] **storage.cljs**: Add docstring explaining auto-run revocation behavior
- [x] **Unit tests**: Test auto-run → manual transition clears match and disables
- [x] **E2E tests**: Test panel save with match removed → script becomes manual-only
- [x] **E2E tests**: Test REPL save with match removed → script becomes manual-only
- [x] **Human verified**: Confirmed Phase 6 changes in the UI

**Entry points affected** (all go through `save-script!`):
- Panel save (via `panel_actions.cljs` → `background.cljs` → `storage.cljs`)
- REPL save (via `repl_fs_actions.cljs` → `storage.cljs`)
- Gist install (via `background.cljs` → `storage.cljs`)
- Script import (future, will use same path)

### Phase 7: `epupp/` Namespace Reservation

**Status**: The `epupp/` namespace should be reserved for system scripts. Users cannot create scripts with names starting with `epupp/`.

**Current state**:
- ✅ Built-in detection uses `:script/builtin?` metadata (not ID pattern)
- ❌ Built-in name is `"GitHub Gist Installer (Built-in)"` - should be `epupp/gist_installer.cljs`
- ❌ No validation prevents users from creating `epupp/` prefixed scripts

- **Verification failed (2026-01-25)**: Panel rename allowed a script to be renamed to `epupp/test.cljs` and the save/rename UI did not show a name error. The protection is incomplete or bypassed.

- **Path validation requirements (storage-level)**:
  - Reject names starting with `epupp/` (reserved namespace)
  - Reject names containing `./` or `../` anywhere (path traversal)
  - Reject names starting with `/` (absolute paths)
  - Normalize names at the storage level before any write operation (save and rename)
  - REPL fs error message for `epupp/` currently reports "Missing :epupp/script-name in manifest" - must be replaced with a clear reserved-namespace error
  - REPL fs currently allows renaming a script to start with `epupp/` - this must never be allowed (and won't once we stop this at the storage level)

- [x] **storage.cljs**: Rename built-in to `epupp/gist_installer.cljs`
- [x] **storage.cljs**: Add validation in `save-script!` to reject names starting with `epupp/`
- [x] **repl_fs_actions.cljs**: Return clear error when `epupp/` prefix attempted
- [x] **panel_actions.cljs**: Prevent save with `epupp/` prefix (or rely on storage validation)
- [x] **Unit tests**: Test validation rejects `epupp/` prefix
- [x] **Unit tests**: Test validation rejects `/` prefix
- [x] **Unit tests**: Test validation rejects `./` anywhere in the name
- [x] **E2E tests**: Test panel/REPL rejection of `epupp/` names
- [x] **Docs**: Document namespace reservation ([userscripts-architecture.md](userscripts-architecture.md#L97-L99), includes path traversal rules)
- [x] **E2E tests**: Panel rename to `epupp/` shows error and disables save/rename ([panel_save_rename_test.cljs](../../e2e/panel_save_rename_test.cljs#L356-L393))
- [x] **E2E tests**: REPL fs save rejects `epupp/` prefix with clear error ([namespace_reservation_test.cljs](../../e2e/namespace_reservation_test.cljs#L8-L38))
- [x] **E2E tests**: REPL fs save rejects `epupp/built-in/` (deep nesting) ([namespace_reservation_test.cljs](../../e2e/namespace_reservation_test.cljs#L40-L70))
- [x] **E2E tests**: REPL fs save rejects `epupp/` even with force flag ([namespace_reservation_test.cljs](../../e2e/namespace_reservation_test.cljs#L72-L102))
- [x] **storage.cljs**: Name validation integrated at storage level (validates before save/rename)
- [x] **background.cljs**: REPL save handler validates raw manifest name, returns clear error
- [x] **background_actions/repl_fs_actions.cljs**: Extracts manifest for raw name validation
- [x] **panel.cljs**: Panel shows name error and disables save/rename for invalid names (E2E verified)
- [x] **Unit tests**: Complete validation coverage in [script_utils_test.cljs](../../test/script_utils_test.cljs#L45-L115) and [background_actions_test.cljs](../../test/background_actions_test.cljs#L80-L130)
- [x] **Property tests**: Deterministic property-style tests (200 iterations each) in [script_utils_test.cljs](../../test/script_utils_test.cljs#L73-L115)
- [x] **E2E tests**: Complete coverage - panel and REPL save/rename rejections for all patterns ([fs_write_save_test.cljs](../../e2e/fs_write_save_test.cljs#L427-L500), [fs_write_mv_test.cljs](../../e2e/fs_write_mv_test.cljs#L396-L475), [panel_save_rename_test.cljs](../../e2e/panel_save_rename_test.cljs#L397-L462))
- [x] **Human verified**: Confirmed Phase 7 validation works in UI

### Phase 8: Built-in Reinstall Strategy

**Status**: ✅ Decided - Hybrid approach

**Decision**: Combine cleanup with save-style updates:

1. **Remove stale built-ins**: Any `:script/builtin? true` scripts in storage that are NOT bundled with the current extension version → delete them
2. **Update existing built-ins**: For built-ins that ARE bundled → use the same code path as "Save Script" from panel:
   - Code updates from bundle
   - Manifest-derived fields update (name, match, description, run-at, inject)
   - User preferences preserved (`:script/enabled`)
   - `:script/modified` timestamp updates
   - `:script/created` stays the same

**Benefits**:
- Cleans up obsolete built-ins automatically
- Users always get latest built-in code
- User's enabled/disabled choice is honored
- Consistent with how all other saves work

**Implementation**:
- [x] **storage.cljs**: On init, get list of bundled built-in IDs
- [x] **storage.cljs**: Remove any `:script/builtin? true` not in bundled list
- [x] **storage.cljs**: For each bundled built-in, call existing save path (not special-case)
- [x] **Unit tests**: Test stale built-in removal
- [x] **Unit tests**: Test built-in update preserves enabled state
- [x] **Human verified**: Confirmed Phase 8 changes in the UI

**E2E tests** (cover decided behavior):
- [x] **E2E**: Built-in appears with correct code after extension reload
- [x] **E2E**: User disables built-in → reload extension → built-in stays disabled
- [x] **E2E**: User enables built-in → reload extension → built-in stays enabled
- [x] **E2E**: Stale built-in (manually added with `builtin?: true`) removed on reload
- [x] **E2E**: Built-in code updates when bundle changes (verify modified timestamp changes)
- [x] **Human verified**: Confirmed Phase 8 E2E behavior in the UI

### Phase 9: Panel Persistence Simplification

**Status**: Panel over-caches metadata that can get out of sync with manifest in code.

**Current state**: Panel persistence saves `{code, scriptName, scriptMatch, scriptDescription, originalName}`

**Problem**: Metadata can drift from manifest - e.g., user edits `:epupp/script-name` in code but panel shows old cached name.

**Decision**: Only persist `{code}`, parse manifest on restore.

**Benefits**:
- Code is source of truth
- No sync issues between cached metadata and manifest
- Simpler migration (one field)
- Follows "parse, don't validate" principle

- [x] **panel_actions.cljs**: Update persistence to save only `{code}`
- [x] **panel_actions.cljs**: On restore, parse manifest to get name/match/description
- [x] **panel.cljs**: Handle restore from code-only persistence
- [x] **Unit tests**: Test restore parses manifest correctly
- [x] **E2E tests**: Test panel restore after code edit changes manifest
- [x] **Human verified**: Confirmed Phase 9 changes in the UI

### Phase 10: Storage Schema - Derive from Manifest

**Status**: Storage caches manifest-derived fields that can get out of sync.

**Current storage per script**:
```clojure
{:script/id "..."              ; Can't derive
 :script/code "..."            ; Source of truth
 :script/name "..."            ; Derivable from manifest
 :script/match [...]           ; Derivable from manifest
 :script/run-at "..."          ; Derivable from manifest
 :script/inject [...]          ; Derivable from manifest
 :script/description "..."     ; Derivable from manifest
 :script/enabled true          ; User preference (not in manifest)
 :script/created "..."         ; Metadata
 :script/modified "..."        ; Metadata
 :script/builtin? false        ; System flag
 :script/approved-patterns []} ; UNUSED, legacy - REMOVE
```

**Decision**: Store only non-derivable fields, derive rest on load:

**Store**:
```clojure
{:script/id "..."
 :script/code "..."
 :script/enabled true
 :script/created "..."
 :script/modified "..."
 :script/builtin? false}
```

**Derive on load** (from manifest in code):
- `:script/name` - from `:epupp/script-name`
- `:script/match` - from `:epupp/auto-run-match`
- `:script/run-at` - from `:epupp/run-at`
- `:script/inject` - from `:epupp/inject`
- `:script/description` - from `:epupp/description`

**Performance**: Parse manifests once at extension startup, cache in memory. No impact on page navigation.

- [x] **storage.cljs**: Update `persist!` to save only non-derivable fields
- [x] **storage.cljs**: Update `load!` to derive fields from manifest after loading
- [x] **storage.cljs**: Remove `:script/approved-patterns` (unused legacy field)
- [x] **script_utils.cljs**: Add `derive-script-fields` function
- [x] **Unit tests**: Test round-trip (save minimal, load with derived fields)
- [x] **E2E tests**: Verify minimal storage schema and manifest-derived fields
- [x] **E2E tests**: Full e2e test suite passes (rerun on flakyness suspicion)
- [ ] **Human verified**: Confirmed Phase 10 changes in the UI

### Phase 11: Schema Versioning and Key Naming

**Status**: No version field in storage - can't detect when migration needed. Also, storage keys use inconsistent naming.

**Decisions**:
1. Add `schemaVersion` field to storage root
2. Standardize all storage keys to **camelCase** (JS convention)

**Key renames**:
| Before | After |
|--------|-------|
| `granted-origins` | `grantedOrigins` |
| `scripts` | `scripts` (unchanged) |
| `userAllowedOrigins` | `userAllowedOrigins` (unchanged) |
| `autoConnectRepl` | `autoConnectRepl` (unchanged) |
| `autoReconnectRepl` | `autoReconnectRepl` (unchanged) |
| `fsReplSyncEnabled` | `fsReplSyncEnabled` (unchanged) |

**Version strategy**:
- Current schema (after Phase 10): version `1`
- On load, check version and run migrations if needed
- Migrations are one-way (old → new)
- Version 0 → 1 migration: rename `granted-origins` → `grantedOrigins`

**Storage structure (v1)**:
```javascript
{
  "schemaVersion": 1,
  "scripts": [...],
  "grantedOrigins": [...],
  "userAllowedOrigins": [...]
}
```

- [x] **storage.cljs**: Add `schemaVersion` field to storage
- [x] **storage.cljs**: Rename `granted-origins` → `grantedOrigins` in persist/load
- [x] **storage.cljs**: Check version on load, run migrations if needed
- [x] **storage.cljs**: Add migration framework (version 0 → 1)
- [x] **Unit tests**: Test migration from unversioned to version 1
- [x] **Unit tests**: Test `granted-origins` → `grantedOrigins` migration
- [x] **E2E tests**: Full e2e test suite passes (rerun on flakyness suspicion)
- [x] **Docs**: Document storage schema version
- [ ] **Human verified**: Confirmed Phase 11 changes in the UI

### Phase 12: Final Verification

- [ ] **Unit tests pass**: `bb test`
- [ ] **E2E tests pass**: `bb test:e2e`
- [ ] **Build dev version**: `bb build:dev` for human testing
- [ ] **Human verified**: Human has verified the changes
- [ ] **Manual verification**: Test full workflow in browser
  - [ ] Panel: Edit script with match → remove match → save → verify no auto-run UI
  - [ ] REPL: Update script removing match → verify no auto-run UI in popup
  - [ ] Both: Verify script works as manual-only (can run via play button)
  - [ ] Built-in appears as `epupp/gist_installer.cljs` in UI
  - [ ] Cannot create script named `epupp/anything.cljs` via panel
  - [ ] Cannot create script named `epupp/anything.cljs` via REPL
  - [ ] Panel restores code-only, parses manifest for metadata
  - [ ] Storage contains only non-derivable fields
  - [ ] Schema version is persisted and checked on load
  - [ ] Built-in: disable → reload extension → still disabled
  - [ ] Built-in: enable → reload extension → still enabled

## Base Script Info Shape

```clojure
;; Built by script->base-info in bg_fs_dispatch.cljs
{:fs/name "script.cljs"                    ; Always present
 :fs/modified "2026-01-24T..."             ; Always present
 :fs/created "2026-01-20T..."              ; Always present
 :fs/auto-run-match ["pattern"]            ; Only when has-auto-run?
 :fs/enabled? true                         ; Only when has-auto-run?
 :fs/description "..."                     ; If present in manifest
 :fs/run-at "document-idle"                ; If present in manifest
 :fs/inject ["scittle://..."]              ; If present in manifest}
```

## Function Return Shapes

| Function | Shape |
|----------|-------|
| `ls` | `[base-info ...]` |
| `show` | Code string or nil (unchanged) |
| `save!` | `(merge base-info {:fs/newly-created? bool})` |
| `mv!` | `(merge base-info {:fs/from-name "old.cljs"})` |
| `rm!` | `base-info` (deleted script's info) |

## Files to Modify

| File | Changes |
|------|---------|
| `src/manifest_parser.cljs` | Extract `:epupp/auto-run-match` |
| `src/storage.cljs` | Update field names, conditional enabled default, **auto-run revocation logic**, **`epupp/` validation**, **rename built-in**, **derive-from-manifest**, **schema versioning**, **remove approved-patterns** |
| `src/script_utils.cljs` | **Add `derive-script-fields` function** |
| `src/background.cljs` | Update manifest references |
| `src/bg_fs_dispatch.cljs` | Add `script->base-info`, update all responses |
| `src/popup.cljs` | Update `:fs/enabled` → `:fs/enabled?` |
| `src/panel.cljs` | Update match field references, **handle code-only restore** |
| `src/panel_actions.cljs` | Update default template, save logic, **code-only persistence** |
| `src/background_actions/repl_fs_actions.cljs` | **Return clear error for `epupp/` prefix** |
| `extension/bundled/epupp/fs.cljs` | Update response parsing, docstrings |
| `extension/bundled/userscripts/*.cljs` | Update manifest keys |
| `test/*.cljs` | Update all assertions, **add auto-run revocation tests**, **add `epupp/` validation tests**, **add schema migration tests** |
| `e2e/*.cljs` | Update all assertions, **add auto-run revocation E2E tests**, **add namespace rejection E2E tests**, **add panel restore E2E tests** |
| `docs/*.md` | Update all examples, **document namespace reservation**, **document schema version** |

## Implementation Notes

### Backward Compatibility (Not Required)

Since we're pre-1.0 in the userscripts branch, we do NOT need to:
- Support both old and new key names
- Migrate existing scripts
- Maintain fallback parsing

This is a clean break. Users updating the extension will need to update any saved scripts.

### `script->base-info` Builder

```clojure
(defn script->base-info
  "Build consistent base info map from script record."
  [script]
  (let [match (:script/match script)
        has-auto-run? (and match (seq match))]
    (cond-> {:fs/name (:script/name script)
             :fs/modified (:script/modified script)
             :fs/created (:script/created script)}
      has-auto-run?
      (assoc :fs/auto-run-match match
             :fs/enabled? (:script/enabled script))

      (seq (:script/description script))
      (assoc :fs/description (:script/description script))

      (seq (:script/run-at script))
      (assoc :fs/run-at (:script/run-at script))

      (seq (:script/inject script))
      (assoc :fs/inject (:script/inject script)))))
```

### Error Handling

All write operations (`save!`, `mv!`, `rm!`) throw on failure (reject promise). No `:fs/success false` returns. The base info is only returned on success.

### Auto-Run Revocation Logic (Phase 6)

The `save-script!` function in storage.cljs must be updated to:

```clojure
;; In save-script!, after extracting manifest:
(let [manifest-match (get manifest "auto-run-match")  ; from :epupp/auto-run-match
      ;; CRITICAL: If script has code with manifest, use manifest's match (even if nil/empty)
      ;; This allows removing auto-run by deleting the manifest key
      new-match (cond
                  ;; Manifest explicitly sets match (including empty)
                  (contains? manifest "auto-run-match")
                  (if (seq manifest-match) (vec manifest-match) [])

                  ;; No manifest or no code - preserve existing match
                  existing
                  (:script/match existing)

                  ;; New script without manifest - no match
                  :else [])
      has-auto-run? (seq new-match)
      ;; Enabled: preserve if has auto-run, reset to false if losing auto-run
      new-enabled (if has-auto-run?
                    (if existing
                      (:script/enabled existing)  ; preserve existing
                      default-enabled)             ; new script default
                    false)]                        ; no auto-run = disabled
  ;; Use new-match and new-enabled in the updated-script
  )
```

**Key insight**: The manifest is the source of truth. If a manifest is present and lacks `auto-run-match`, that's an explicit removal (set to empty), not "keep existing".

## Verification

1. Create script WITH `:epupp/auto-run-match`, verify `:fs/enabled?` in return
2. Create script WITHOUT match, verify NO `:fs/auto-run-match` and NO `:fs/enabled?`
3. Verify `ls` returns consistent base info for all scripts
4. Verify `save!` includes `:fs/newly-created?` boolean
5. Verify `mv!` includes `:fs/from-name` string
6. Verify `rm!` returns deleted script's base info
7. Verify all docs have correct examples
8. Verify REPL fs return maps exclude `:requestId`, `:source`, and `:type`
9. **Update auto-run script to remove match** → verify match cleared, enabled=false, no auto-run UI
10. **Panel save with match removed** → verify becomes manual-only script
11. **REPL save with match removed** → verify becomes manual-only script
12. **Built-in naming**: `epupp/gist_installer.cljs` appears in popup/panel
13. **Name validation**: Panel rejects `epupp/`, `./`, `../`, and leading `/` with clear error and disabled save/rename
14. **Name validation**: REPL rejects `epupp/`, `./`, `../`, and leading `/` with clear error message
15. **Panel restore**: Edit manifest in code, close/reopen panel → metadata reflects code
16. **Storage minimal**: Inspect storage, verify only `id`, `code`, `enabled`, `created`, `modified`, `builtin?`
17. **Schema version**: Storage has `schemaVersion: 1`
18. **Key naming**: Storage uses `grantedOrigins` (not `granted-origins`)
19. **Migration**: Old storage with `granted-origins` migrates to `grantedOrigins` on load
20. **Built-in preserved enabled**: Disable built-in → reload extension → still disabled
21. **Built-in preserved enabled**: Enable built-in → reload extension → still enabled
22. **Built-in stale removal**: Manually add fake built-in → reload → removed
23. **Built-in code updates**: Change built-in code in bundle → reload → new code, new modified date
24. **Build dev version**: Run `bb build:dev` for human testing
25. **Human verified**: Human has verified the changes

## Original Plan-Producing Prompt

Create an implementation plan for the decided API changes before 1.0 release:

1. Rename `:epupp/site-match` → `:epupp/auto-run-match` (clearer intent)
2. Align manifest and return map naming (`:epupp/X` → `:fs/X`)
3. Use `?` suffix for boolean keywords (`:fs/enabled?`, `:fs/newly-created?`)
4. Scripts without auto-run patterns: create disabled, omit `:fs/enabled?` from return
5. `:fs/auto-run-match` omitted for manual-only scripts (key absence = no auto-run)
6. Consistent `epupp.fs` return shapes: all functions use shared base info builder
7. Include `:fs/description`, `:fs/run-at`, `:fs/inject` (if in manifest, independent of auto-run)
8. Change `save!` from `{:fs/success :fs/name :fs/error}` to base info + `:fs/newly-created?`
9. Change `mv!` to return base info + `:fs/from-name`
10. Change `rm!` to return deleted script's base info
11. Filter REPL fs return maps to API whitelist (drop `:requestId`, `:source`, `:type`)
12. **Auto-run revocation**: Removing `:epupp/auto-run-match` from manifest MUST clear match and reset enabled to false (regression fix for merge-preserving-old-values bug)
13. **Reserve `epupp/` namespace**: Built-in scripts use `epupp/` prefix, users cannot create `epupp/*` scripts
14. **Built-in detection**: Use `:script/builtin?` metadata (already implemented), not ID patterns
15. **Reserve `epupp://` scheme**: For future internal use (document, no enforcement yet)
16. **Built-in reinstall strategy**: Decide between clean-reinstall vs update-if-changed
17. **Panel persistence simplification**: Persist only `{code}`, derive metadata from manifest on restore
18. **Storage schema simplification**: Store only non-derivable fields, derive rest from manifest on load
19. **Schema versioning**: Add `schemaVersion` field for future migrations
20. **Storage key naming**: Standardize to camelCase (`granted-origins` → `grantedOrigins`)
21. **Remove unused fields**: Delete legacy `:script/approved-patterns` field

Breaking changes acceptable - pre-1.0 in userscripts branch. Use plan format from recent dev/docs plans (checklist, files table, implementation notes, verification).
