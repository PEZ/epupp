# API Consistency Plan - Pre-1.0 Release

Align manifest format, storage schema, and `epupp.fs` return shapes for consistency before 1.0 release. Breaking changes acceptable since we're pre-release in the userscripts branch.

## Summary of Changes

| Area | Before | After |
|------|--------|-------|
| Manifest key | `:epupp/site-match` | `:epupp/auto-run-match` |
| Return key | `:fs/match` | `:fs/auto-run-match` |
| Boolean naming | `:fs/enabled` | `:fs/enabled?` |
| No-pattern value | `[]` | `:fs/no-auto-run` sentinel |
| `save!` return | `{:fs/success :fs/name :fs/error}` | Base info + `:fs/newly-created?` |
| Scripts without match | Created enabled | Created disabled |

## Rationale

1. **Naming clarity**: `:epupp/auto-run-match` makes intent obvious - patterns control automatic script execution
2. **Manifest-return alignment**: Same concepts use same names (minus namespace)
3. **Boolean convention**: `?` suffix for boolean keywords (`:fs/enabled?`, `:fs/newly-created?`)
4. **Semantic correctness**: Scripts without auto-run patterns shouldn't have `:fs/enabled?` field
5. **Consistent returns**: All `epupp.fs` functions returning script info use the same base shape

## Progress Checklist

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

### Phase 2: Return Shape Consistency

- [x] **repl_fs_actions.cljs**: Add `script->base-info` builder function
- [x] **repl_fs_actions.cljs**: Update `list-scripts` to use builder
- [x] **repl_fs_actions.cljs**: Update `save-script` response to include base info + `:fs/newly-created?`
- [x] **repl_fs_actions.cljs**: Update `rename-script` response to include base info + `:fs/from-name` + `:fs/to-name`
- [x] **repl_fs_actions.cljs**: Update `delete-script` response to include base info + `:fs/existed?`
- [x] **content_bridge.cljs**: Forward all response properties via `js/Object.assign`
- [x] **bundled/epupp/fs.cljs**: Transform `:success` → `:fs/success` for all operations
- [x] **E2E tests**: Updated to expect namespaced keys (`:fs/success`, `:fs/enabled?`, etc.)

### Phase 3: Boolean Naming Convention

- [x] **bg_fs_dispatch.cljs**: Use `:fs/enabled?` in builder
- [x] **bundled/epupp/fs.cljs**: Update docstrings
- [x] **popup.cljs**: Update any `:fs/enabled` references
- [x] **Unit tests**: Update assertions for new key names
- [x] **E2E tests**: Update assertions for new key names

### Phase 4: Auto-Run Behavior

- [x] **storage.cljs**: Scripts without match created with `enabled: false`
- [x] **bg_fs_dispatch.cljs**: Omit `:fs/enabled?` when no auto-run patterns
- [x] **bg_fs_dispatch.cljs**: Use `:fs/no-auto-run` sentinel value
- [x] **panel_actions.cljs**: Verify save behavior for scripts without match
- [x] **Unit tests**: Test enabled default based on match presence
- [x] **E2E tests**: Verify behavior for manual-only scripts

### Phase 5: Documentation

- [x] **README.md**: Update manifest examples
- [x] **docs/user-guide.md**: Update all code examples
- [x] **docs/repl-fs-sync.md**: Update return shape examples
- [x] **dev/docs/api-surface-catalog.md**: Update API catalog
- [x] **dev/docs/userscripts-architecture.md**: Update manifest format

### Phase 6: Verification

- [ ] **Unit tests pass**: `bb test`
- [ ] **E2E tests pass**: `bb test:e2e`
- [ ] **Manual verification**: Test full workflow in browser

## Base Script Info Shape

```clojure
;; Built by script->base-info in bg_fs_dispatch.cljs
{:fs/name "script.cljs"                    ; Always present
 :fs/modified "2026-01-24T..."             ; Always present
 :fs/created "2026-01-20T..."              ; Always present
 :fs/auto-run-match ["pattern"]            ; Always present (or :fs/no-auto-run)
 :fs/enabled? true                         ; Only if auto-run-match exists
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
| `src/storage.cljs` | Update field names, conditional enabled default |
| `src/background.cljs` | Update manifest references |
| `src/bg_fs_dispatch.cljs` | Add `script->base-info`, update all responses |
| `src/popup.cljs` | Update `:fs/enabled` → `:fs/enabled?` |
| `src/panel.cljs` | Update match field references |
| `src/panel_actions.cljs` | Update default template, save logic |
| `extension/bundled/epupp/fs.cljs` | Update response parsing, docstrings |
| `extension/bundled/userscripts/*.cljs` | Update manifest keys |
| `test/*.cljs` | Update all assertions |
| `e2e/*.cljs` | Update all assertions |
| `docs/*.md` | Update all examples |

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
             :fs/created (:script/created script)
             :fs/auto-run-match (if has-auto-run? match :fs/no-auto-run)}
      has-auto-run?
      (assoc :fs/enabled? (:script/enabled script))

      (seq (:script/description script))
      (assoc :fs/description (:script/description script))

      (seq (:script/run-at script))
      (assoc :fs/run-at (:script/run-at script))

      (seq (:script/inject script))
      (assoc :fs/inject (:script/inject script)))))
```

### Error Handling

All write operations (`save!`, `mv!`, `rm!`) throw on failure (reject promise). No `:fs/success false` returns. The base info is only returned on success.

## Verification

1. Create script WITH `:epupp/auto-run-match`, verify `:fs/enabled?` in return
2. Create script WITHOUT match, verify `:fs/no-auto-run` and NO `:fs/enabled?`
3. Verify `ls` returns consistent base info for all scripts
4. Verify `save!` includes `:fs/newly-created?` boolean
5. Verify `mv!` includes `:fs/from-name` string
6. Verify `rm!` returns deleted script's base info
7. Verify all docs have correct examples

## Original Plan-Producing Prompt

Create an implementation plan for the decided API changes before 1.0 release:

1. Rename `:epupp/site-match` → `:epupp/auto-run-match` (clearer intent)
2. Align manifest and return map naming (`:epupp/X` → `:fs/X`)
3. Use `?` suffix for boolean keywords (`:fs/enabled?`, `:fs/newly-created?`)
4. Scripts without auto-run patterns: create disabled, omit `:fs/enabled?` from return
5. `:fs/auto-run-match` always present, use `:fs/no-auto-run` sentinel for manual-only scripts
6. Consistent `epupp.fs` return shapes: all functions use shared base info builder
7. Include `:fs/description`, `:fs/run-at`, `:fs/inject` (if in manifest, independent of auto-run)
8. Change `save!` from `{:fs/success :fs/name :fs/error}` to base info + `:fs/newly-created?`
9. Change `mv!` to return base info + `:fs/from-name`
10. Change `rm!` to return deleted script's base info

Breaking changes acceptable - pre-1.0 in userscripts branch. Use plan format from recent dev/docs plans (checklist, files table, implementation notes, verification).
