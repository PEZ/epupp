# Optional Site-Match Implementation Plan

Make `:epupp/site-match` optional in userscript manifests. When absent, the script is treated as "manual-only" - it never auto-runs but can still be evaluated via Panel or Popup "Run" button.

## Rationale

Users may want scripts that:
- Are utility libraries loaded via `:epupp/require`
- Are development/debugging helpers run manually
- Don't target specific sites

Currently the system substitutes fake patterns like `https://example.com/*` which is confusing.

## Progress Checklist

- [x] **Unit tests**: Add tests for save without site-match
- [x] **panel_actions.cljs**: Remove `(empty? script-match)` from save validation
- [x] **panel.cljs**: Remove `(empty? script-match)` from `save-disabled?`
- [x] **panel.cljs**: Update UI to show "No auto-run" when no site-match
- [x] **panel.cljs**: Update tooltip/hint for missing site-match
- [x] **panel_actions.cljs**: Update default script template (remove fake match)
- [x] **background.cljs**: Make `site-match` optional in `install-userscript!`
- [x] **popup.cljs**: Change "URL Pattern" language to "Auto-run" terminology
- [x] **popup.cljs**: Handle display of scripts without auto-run patterns
- [x] **E2E tests**: Verify full workflow with optional site-match
- [ ] **Manual verification**: Test in browser

## Files to Modify

| File | Changes |
|------|---------|
| `src/panel_actions.cljs` | Remove match requirement from save, update default template |
| `src/panel.cljs` | Update UI for optional match display |
| `src/background.cljs` | Make site-match optional in install API |
| `src/popup.cljs` | Rename "URL Pattern" to "Auto-run", handle empty |
| `test/panel_actions_test.cljs` | Add tests for save without match |

## Implementation Notes

### What Already Works

The core infrastructure handles empty patterns correctly:
- `normalize-match-patterns` converts `nil` to `[]`
- `url-matches-any-pattern?` returns `false` for empty arrays
- Scripts with empty match are naturally excluded from auto-injection

### UI Language Change

**Before**: "URL Pattern", "Match Pattern"
**After**: "Auto-run", "Auto-run Pattern", "No auto-run"

This makes it clearer that the field controls automatic execution, not just matching.

## Verification

1. Create script without `:epupp/site-match` in Panel
2. Verify it saves successfully
3. Verify it does NOT auto-inject on page loads
4. Verify it CAN be run manually via Popup "Run" button
5. Verify Panel shows "No auto-run" indicator
6. Verify Popup shows appropriate state for scripts without auto-run
