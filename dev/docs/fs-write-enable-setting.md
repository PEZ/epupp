# FS Write Enable Setting - Implementation Plan

Replace the confirmation UI with a simple "Enable FS REPL Sync" toggle that controls whether REPL connections can write to userscripts.

## Current State Analysis

### Confirmation System Overview

The current system queues destructive operations (save, rename, delete) from the REPL for user confirmation in the popup:

1. **epupp.fs functions** (in page context) send queue-* messages by default
2. **Background worker** stores pending confirmations in `:pending/fs-confirmations`
3. **Popup UI** displays confirmation cards with Confirm/Cancel buttons
4. **Badge** shows count of pending confirmations

### Code Locations

| Component | Files | Purpose |
|-----------|-------|---------|
| API surface | `extension/bundled/epupp/fs.cljs` | `save!`, `mv!`, `rm!` with `:fs/force?` option |
| Queue handlers | `src/background.cljs` L1020-1080 | `queue-save-script`, `queue-delete-script`, `queue-rename-script` |
| Confirmation state | `src/background.cljs` L315-360 | `add-fs-confirmation!`, `remove-fs-confirmation!`, persistence |
| Confirm/cancel handlers | `src/background.cljs` L1097-1175 | `confirm-fs-operation`, `cancel-fs-operation`, bulk variants |
| Popup UI | `src/popup.cljs` L890-960 | `confirmation-item`, `fs-confirmations-section` |
| Popup state | `src/popup.cljs` L40 | `:pending/fs-confirmations` |
| Popup effects | `src/popup.cljs` L397-435 | `load-fs-confirmations`, `confirm-fs-operation`, `cancel-fs-operation` |
| Popup actions | `src/popup_actions.cljs` L159-175 | Action handlers for confirm/cancel |
| Content bridge | `src/content_bridge.cljs` L219-267 | Forwards queue-* messages, handles `pending-confirmation` response |
| Badge | `src/background.cljs` L282 | Counts fs-confirmations in badge |

### Message Types to Remove

- `queue-save-script` → use `save-script` directly
- `queue-delete-script` → use `delete-script` directly
- `queue-rename-script` → use `rename-script` directly
- `confirm-fs-operation`
- `confirm-all-fs-operations`
- `cancel-fs-operation`
- `cancel-all-fs-operations`
- `get-fs-confirmations`

## Proposed Design

### New Setting: "Enable FS REPL Sync"

**Storage key:** `fsReplSyncEnabled` (boolean, default: `false`)

**Behavior:**
- **Enabled:** REPL operations (`save!`, `mv!`, `rm!`) execute immediately (same as current `:fs/force? true`)
- **Disabled:** REPL operations return error `{:fs/success false :fs/error "FS REPL Sync is disabled"}`

### API Changes

**epupp.fs functions simplified:**

```clojure
;; Before: complicated queue/force logic
(epupp.fs/save! code)               ; queues for confirmation
(epupp.fs/save! code {:fs/force? true}) ; immediate

;; After: simple enabled/disabled check
(epupp.fs/save! code)  ; works if enabled, error if disabled
```

The `:fs/force?` option is repurposed for overwrite semantics (like Unix `-f` flag):
- `(mv! "a.cljs" "b.cljs")` - fails if "b.cljs" exists
- `(mv! "a.cljs" "b.cljs" {:fs/force? true})` - overwrites "b.cljs" if it exists
- Same pattern for `save!` when a script with that name already exists

### Read Operations Unaffected

These remain always available:
- `epupp.fs/ls` - list scripts
- `epupp.fs/show` - get script code

## Implementation Phases

### Phase 1: Add Setting Infrastructure

1. **Storage key:** Add `fsReplSyncEnabled` to chrome.storage.local
2. **Background getter:** `get-fs-sync-enabled` async function
3. **Popup state:** `:settings/fs-repl-sync-enabled`
4. **Popup effects:** `load-fs-sync-setting`, `save-fs-sync-setting`
5. **Popup UI:** Toggle in Settings section with reminder text ("Remember to disable when done editing from the REPL")

### Phase 2: Gate Write Operations

1. **Background check:** Before executing `save-script`, `rename-script`, `delete-script`:
   - Check `fsReplSyncEnabled` setting
   - If disabled, reject the promise with error "FS REPL Sync is disabled"
   - If enabled, execute normally via Uniflow dispatch

2. **Panel unaffected:** Panel saves via `panel-save-script` bypass this check (panel is trusted UI)

### Phase 3: Remove Confirmation System

1. **Remove message handlers:**
   - `queue-save-script`, `queue-delete-script`, `queue-rename-script`
   - `confirm-fs-operation`, `confirm-all-fs-operations`
   - `cancel-fs-operation`, `cancel-all-fs-operations`
   - `get-fs-confirmations`

2. **Remove state:**
   - `:pending/fs-confirmations` from `!state`
   - `pending-fs-confirmations-storage-key`
   - All `*-fs-confirmation!` functions

3. **Remove UI:**
   - `confirmation-item` component
   - `fs-confirmations-section` component
   - Popup state `:pending/fs-confirmations`
   - Badge fs-confirmation count logic

4. **Update content bridge:**
   - Remove `queue-*` message forwarding
   - Remove `pending-confirmation` response handling

5. **Simplify epupp.fs:**
   - Remove queue-* message paths
   - Direct operations only
   - Keep `:fs/force?` for overwrite semantics

### Phase 4: Update Tests

1. **Unit tests:** Add tests for setting check in action handlers
2. **E2E tests:**
   - Remove confirmation workflow tests
   - Add tests for enabled/disabled setting behavior
   - Test that read operations always work

## UI Design

### Settings Section Addition

```
┌─────────────────────────────────────────────────────────────┐
│ Settings (collapsed by default)                              │
├─────────────────────────────────────────────────────────────┤
│ ☐ Auto-connect REPL to all pages                            │
│   Warning: Enabling this will inject...                     │
│                                                             │
│ ☐ Enable FS REPL Sync                                       │
│   Allow connected REPLs to create, modify, and delete       │
│   userscripts. When disabled, REPL can only read scripts.   │
│                                                             │
│ Allowed Userscript-install Base URLs                        │
│ ...                                                         │
└─────────────────────────────────────────────────────────────┘
```

## Security Considerations

### Simpler Mental Model

The confirmation UI created a false sense of security:
- Users could accidentally confirm operations
- Queued operations persisted across sessions
- Complex state to reason about

The toggle is clearer:
- **Disabled:** REPL is read-only, no risk
- **Enabled:** REPL has full write access, user accepts this

### Trust Boundaries

- **Panel:** Always trusted (user is actively editing)
- **REPL:** Trusted only when setting enabled
- **Popup:** Always trusted (user is actively managing)

## Migration Notes

**No backward compatibility needed.** The REPL FS sync feature is unreleased. We remove the confirmation system completely - no migration code, no cleanup of old storage keys. Clean slate.

## Success Criteria

- [ ] All confirmation UI code, documentation, and tests completely removed (as if never existed)
- [ ] User and dev documentation updated to describe the setting and behavior
- [ ] Toggle appears in Settings section
- [ ] Default is disabled (safe)
- [ ] When disabled: `save!`, `mv!`, `rm!` return clear error
- [ ] When enabled: operations execute immediately
- [ ] `ls` and `show` always work regardless of setting
- [ ] Panel save unaffected by setting
- [ ] No confirmation UI anywhere
- [ ] Badge shows only script-needs-approval count (no fs-confirmation count)
- [ ] All E2E tests pass (after updates)
- [ ] Setting persists across sessions
