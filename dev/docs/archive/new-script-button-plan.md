# New Script Button Plan

**Status**: Planning
**Created**: 2026-01-11

## Overview

Add a "New Script" button to the DevTools panel that clears the current script state and starts fresh, while preserving evaluation results.

## UX Design

### Button Placement

The button should be placed in the `save-script-section` header, next to the section title. This is logical because:
1. It's the section dealing with script metadata
2. It relates directly to the Save/Create actions
3. It avoids cluttering the code actions area (which is for evaluation)

```
┌─────────────────────────────────────────────────────────────────┐
│ [Save as Userscript / Edit Userscript]        [+ New Script]    │
├─────────────────────────────────────────────────────────────────┤
│ Name: ...                                                       │
│ URL Pattern: ...                                                │
│ ...                                                             │
└─────────────────────────────────────────────────────────────────┘
```

**Button text**: "New Script" with a "+" icon prefix (using `icons/plus`)
**Button style**: Secondary/subtle button (not competing with Save button)

### Interaction Flow

1. User clicks "New Script"
2. **If there are unsaved changes**:
   - Show confirmation: "Discard current changes?" with "Discard" / "Cancel" buttons
   - "Discard" clears state and proceeds
   - "Cancel" does nothing
3. **If no unsaved changes**: Clear immediately

### What Gets Cleared

| Field | Action |
|-------|--------|
| Code textarea | Replace with `default-script` (same as fresh panel) |
| Script name | Clear (will be populated from default manifest) |
| Script match | Clear (will be populated from default manifest) |
| Script description | Clear (will be populated from default manifest) |
| Script ID | Clear (nil - not editing existing script) |
| Original name | Clear (nil - not tracking rename) |
| Manifest hints | Re-parse from default script |
| Save status | Clear |
| **Results area** | **PRESERVE** (as requested) |
| **Scittle status** | **PRESERVE** (connection state) |

### Detecting Unsaved Changes

A script has "unsaved changes" when:
1. Has a `script-id` (editing existing) AND code differs from last saved state, OR
2. No `script-id` (new script) AND code differs from default script

**Simpler approach**: Since we don't track "last saved code", we can check:
- If editing (has `script-id`): always confirm (user explicitly loaded a script)
- If creating new: confirm if code differs from default script

**Even simpler**: Always confirm if code is non-empty and differs from default script. This covers:
- User typed new code (loses work)
- User loaded a script and modified it (loses modifications)
- User loaded a script but didn't modify (minor inconvenience of extra click, but safer)

The exception: If code equals `default-script`, no confirmation needed (nothing to lose).

## Implementation Plan

### 1. Add "plus" icon to icons.cljc

The icons module needs a plus icon for the New Script button.

### 2. Add action in panel_actions.cljs

```clojure
:editor/ax.new-script
```

This action:
- Checks if confirmation is needed (code differs from default)
- If yes, returns effect to show confirmation dialog
- If no, returns state reset to defaults

Actually, for simplicity, the confirmation can be handled in the UI component (using `js/confirm`), and the action just does the reset unconditionally.

### 3. Add UI component in panel.cljs

- Add "New Script" button in `save-script-section` header
- Handle click: check if confirmation needed, show `js/confirm` if so
- Dispatch `:editor/ax.new-script` if confirmed

### 4. Add CSS styling in panel.css

- Style the header to accommodate the button
- Style the button itself (secondary appearance)

### 5. Clear persisted state effect

When starting a new script, we should also clear the persisted panel state so the next panel open starts fresh.

**Important reminder from archived investigation**: The `[:editor/fx.clear-persisted-state]` effect was mistakenly included in save-script action before. It should ONLY be called for explicit "new script" action, NOT after saving.

## E2E Test Updates

Add a new test to `e2e/panel_test.cljs`:

### Test: "Panel: New Script button clears editor state"

**Phases:**

1. **Create a script with manifest**
   - Fill textarea with test code
   - Save the script
   - Verify save succeeded

2. **Click "New Script"**
   - Verify confirmation dialog appears (or skip if code matches default)
   - Confirm the action
   - Verify:
     - Code area contains default script
     - Metadata fields show default script's manifest values
     - Results area is preserved (if there were any)
     - Script ID is nil (button says "Save Script" not "Create Script")

3. **New Script without unsaved changes**
   - Start with default script in editor
   - Click "New Script"
   - Verify no confirmation needed (immediate reset)

### Test: "Panel: New Script preserves evaluation results"

**Phases:**

1. **Evaluate some code**
   - Enter code, click Eval
   - Verify result appears in results area

2. **Click New Script**
   - Confirm if prompted
   - Verify results area still shows previous evaluation
   - Verify code area has default script

## Files to Modify

1. [src/icons.cljc](../../src/icons.cljc) - Add `plus` icon
2. [src/panel_actions.cljs](../../src/panel_actions.cljs) - Add `:editor/ax.new-script` action
3. [src/panel.cljs](../../src/panel.cljs) - Add New Script button to UI
4. [extension/panel.css](../../extension/panel.css) - Style the button and header
5. [test/panel_test.cljs](../../test/panel_test.cljs) - Add unit tests for new action
6. [e2e/panel_test.cljs](../../e2e/panel_test.cljs) - Add E2E tests

## Workflow Reminders (from archived investigation)

- **ALWAYS use `bb <task>` over direct shell commands**
- **ALWAYS check lint/problem reports after edits** - use `get_errors` tool
- **ALWAYS use the `edit` subagent for file modifications** when making structural changes

## Implementation Order

1. Add `plus` icon to icons.cljc
2. Add `:editor/ax.new-script` action to panel_actions.cljs
3. Add unit tests for the new action
4. Run `bb test` to verify
5. Add CSS for header layout and button
6. Add UI component to panel.cljs
7. Run `bb test` again
8. Build for testing: `bb build:test`
9. Add E2E test
10. Run E2E tests: `bb test:e2e --grep "New Script"`
