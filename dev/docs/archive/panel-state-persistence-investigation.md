# Panel State Persistence Investigation

**Status**: RESOLVED
**Created**: 2026-01-11
**Resolved**: 2026-01-11
**Issue**: Panel state saves appear to succeed but data isn't found on restore

## ⚠️ Workflow Reminder

**ALWAYS use `bb <task>` over direct shell commands.** The bb tasks encode project-specific configurations. Check `bb tasks` for available commands.

**ALWAYS check lint/problem reports after edits.** Use `get_errors` tool to verify no syntax or bracket errors before running tests.

**ALWAYS use the `edit` subagent for file modifications.** The clojure-editor subagent specializes in Clojure/Squint structural editing and avoids bracket balance issues. Provide it with complete context: file paths, line numbers, and the exact changes needed.


- `bb test` - Compile and run unit tests
- `bb test:e2e` - Compile and run E2E tests (Docker)

## Observed Facts

1. `PANEL_SAVE_WRITING` and `PANEL_SAVE_COMPLETE` events fire with `success: true`
2. Save uses key `panelState:test.example.com`
3. When Phase 2 panel opens ~30ms later, `PANEL_RESTORE_ALL_KEYS` shows 8 keys - none is `panelState:test.example.com`
4. The `chrome.storage.local.set` callback fires (that's how we log `PANEL_SAVE_COMPLETE`)
5. Both panels use same browser context (same `context` variable in test)

## Hypotheses

| # | Hypothesis | Status | Result |
|---|------------|--------|--------|
| H1 | Storage write succeeds but read happens in different context | Not tested | |
| H2 | `wait-for-panel-state-saved` passes incorrectly (wrong pattern match) | Tested | Pattern was wrong, fixed to `(println "persisted")` - still fails |
| H3 | Panel pages have isolated storage (unlikely for same extension) | Not tested | |
| H4 | Storage is cleared between Phase 1 close and Phase 2 open | Not tested | |
| H5 | The `js/chrome.storage.local.set` callback fires before write completes | Not tested | |
| H6 | `:editor/fx.clear-persisted-state` effect deletes panel state after save | **CONFIRMED** | See EXP-1 results |

## Root Cause Found

**BUG**: In `panel_actions.cljs` line 155, the `:editor/ax.save-script` action includes `[:editor/fx.clear-persisted-state]` in its effects. This deliberately deletes `panelState:hostname` from storage after saving.

**User clarification**: This is NOT intended behavior. The panel should keep its content after saving a script.

**Fix required**: Remove `[:editor/fx.clear-persisted-state]` from the save-script action's effects.

## Experiments

### EXP-1: Verify storage write actually persists
**Goal**: Confirm the write succeeded by reading immediately after in same page context
**Method**: After `PANEL_SAVE_COMPLETE`, immediately read storage and log what we find
**Status**: COMPLETE
**Results**:
- Two `PANEL_SAVE_VERIFY` events observed
- First verify: `found: true`, `code-len: 148` - save succeeded
- Second verify: `found: false`, `code-len: null` - data was deleted!
- Both writes had `code-len: 148`, but second verify found nothing
- Timeline: WRITING → COMPLETE → VERIFY(found) → WRITING → COMPLETE → VERIFY(not found)

**Conclusion**: The first save persists correctly. Something triggers a second save cycle where the data gets cleared. This is the `:editor/fx.clear-persisted-state` effect running as part of the save-script action.

### EXP-2: Check if storage cleared on panel close
**Goal**: Determine if closing panel page clears extension storage
**Method**: Add logging to `clear-storage` or check if it's called unexpectedly
**Status**: Superseded by EXP-1 findings

### EXP-3: Read storage from popup in Phase 1 before closing panel
**Goal**: Cross-verify storage state from different extension page
**Method**: Open popup, read storage keys, close - all before closing Phase 1 panel
**Status**: Superseded by EXP-1 findings

### EXP-4: Check wait-for-panel-state-saved actual behavior
**Goal**: Verify the helper is actually finding the key (not timing out silently)
**Method**: Add console.log inside the helper to show when it resolves
**Status**: Superseded by EXP-1 findings

## Progress Log

- 2026-01-11: Created investigation plan
- 2026-01-11: Ran EXP-1 - discovered two save cycles, second one loses data
- 2026-01-11: Found root cause - `:editor/fx.clear-persisted-state` in save action deletes panel state
- 2026-01-11: User confirmed this is a bug - panel should NOT clear after save
- Next: Remove the clear-persisted-state effect from save-script action

## Current Status

**RESOLVED** - Root cause was `:editor/fx.clear-persisted-state` effect being called after every save, which deleted the panel state. Removed the effect from save-script action. Also fixed test assertion to check for original manifest content rather than normalized name.
