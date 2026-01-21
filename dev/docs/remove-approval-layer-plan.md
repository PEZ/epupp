# Remove Approval Layer - Simplify Auto-Run Model

Simplify the userscript permission model by removing the per-pattern approval layer. Replace with a cleaner two-concept model: **patterns** and **auto-run enabled**.

## Current Model (Three Concepts)

| Concept | Storage Key | Purpose |
|---------|-------------|---------|
| Auto-run patterns | `:script/match` | Which URLs can trigger auto-injection |
| Enabled | `:script/enabled` | Master on/off for the script |
| Approved patterns | `:script/approved-patterns` | Security confirmation per-pattern |

**Problems:**
- "Enabled" and "Allowed" sound similar, cause confusion
- Two permission gates for the same action (auto-run)
- Checkbox shown even for manual-only scripts (no patterns)

## New Model (Two Concepts)

| Concept | Storage Key | Purpose |
|---------|-------------|---------|
| Auto-run patterns | `:script/match` | Which URLs can trigger auto-injection |
| Auto-run enabled | `:script/enabled` | Whether auto-injection is active |

**Key changes:**
1. Remove `:script/approved-patterns` entirely
2. New scripts default to `enabled: false`
3. No checkbox for scripts without patterns (manual-only)
4. Checkbox tooltip: "Auto-run enabled" / "Auto-run disabled"

## Decision Flow

**Auto-Injection:**
```
Has patterns? ──No──→ Skip (manual-only)
     │
    Yes
     ↓
Auto-run enabled? ──No──→ Skip
     │
    Yes
     ↓
Pattern matches URL? ──No──→ Skip
     │
    Yes
     ↓
Auto-inject!
```

**Manual Run (popup Run button):**
```
Click Run → Execute immediately (no gates)
```

## Progress Checklist

### Phase 1: Remove Approval UI (Popup)
- [ ] Remove Allow/Deny buttons from `script-item`
- [ ] Remove `needs-approval` logic
- [ ] Remove approval-related CSS classes (`script-item-approval`)
- [ ] Update checkbox to only show when script has patterns
- [ ] Update checkbox title to "Auto-run enabled/disabled"

### Phase 2: Remove Approval Effects (Popup)
- [ ] Remove `:popup/fx.approve-script` effect
- [ ] Remove `:popup/fx.deny-script` effect (or repurpose as disable)
- [ ] Remove `:popup/ax.approve-script` action
- [ ] Remove `:popup/ax.deny-script` action
- [ ] Simplify `persist-and-notify-scripts!` (remove `:approved` case)
- [ ] Remove `pattern-approved` message type

### Phase 3: Remove Approval Logic (script-utils)
- [ ] Remove `pattern-approved?` function
- [ ] Remove approval-related parsing in `parse-scripts`
- [ ] Remove `approved-patterns` from `script->js`

### Phase 4: Remove Approval Logic (popup-utils)
- [ ] Remove `approve-pattern-in-list` function
- [ ] Simplify or remove `disable-script-in-list` if only used for deny

### Phase 5: Update Background Worker
- [ ] Remove `pattern-approved` message handler
- [ ] Simplify auto-injection check (no approval check)
- [ ] Remove approval badge logic (if any)

### Phase 6: Update Panel
- [ ] Ensure new scripts save with `enabled: false`
- [ ] No panel changes needed for approval (approval was popup-only)

### Phase 7: Update Tests
- [ ] Remove approval-related unit tests
- [ ] Remove approval-related E2E tests
- [ ] Add tests for: checkbox hidden when no patterns
- [ ] Add tests for: new scripts default to disabled
- [ ] Update existing tests that relied on approval flow

### Phase 8: Storage Migration (Optional)
- [ ] Consider migration to strip `approved-patterns` from existing scripts
- [ ] Or: ignore `approved-patterns` if present (backward compatible)

## Files to Modify

| File | Changes |
|------|---------|
| `src/popup.cljs` | Remove Allow/Deny buttons, conditional checkbox |
| `src/popup_actions.cljs` | Remove approve/deny actions |
| `src/popup_utils.cljs` | Remove `approve-pattern-in-list` |
| `src/script_utils.cljs` | Remove `pattern-approved?`, clean up parsing |
| `src/background.cljs` | Remove approval check from auto-inject |
| `src/panel_actions.cljs` | Default `enabled: false` for new scripts |
| `test/script_utils_test.cljs` | Remove approval tests |
| `e2e/popup_core_test.cljs` | Remove approval workflow tests |
| `e2e/userscript_test.cljs` | Update injection tests |

## UI Changes

### Before (script with patterns)
```
[x] my-script.cljs           [Allow] [Deny] [Inspect] [Run] [Delete]
    *://github.com/*
```

### After (script with patterns)
```
[x] my-script.cljs                          [Inspect] [Run] [Delete]
    *://github.com/*

Checkbox title: "Auto-run enabled"
```

### After (script without patterns)
```
    my-script.cljs                          [Inspect] [Run] [Delete]
    No auto-run (manual only)

No checkbox - script is always runnable manually
```

## Verification

1. Create script with patterns, verify checkbox shown, defaults to unchecked
2. Enable auto-run, verify script injects on matching pages
3. Disable auto-run, verify script does NOT inject
4. Create script without patterns, verify no checkbox shown
5. Run manual-only script via Run button, verify it executes
6. Verify no Allow/Deny buttons anywhere
7. Verify existing scripts with `approved-patterns` still work (ignored)

## Benefits

- **Simpler mental model**: Two concepts instead of three
- **Clearer UI**: Checkbox means exactly one thing
- **Safe by default**: New scripts never auto-run
- **Clean manual-only**: No confusing checkbox for pattern-less scripts
- **Less code**: Remove ~100 lines of approval logic
