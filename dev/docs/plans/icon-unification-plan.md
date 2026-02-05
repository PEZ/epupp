# Icon Unification Plan

Unify the Epupp icon system: generate all toolbar PNGs from the `epupp-logo` hiccup component in [icons.cljc](../../../src/icons.cljc), reduce visual states to two (white/gold), and make UI icons reactive to REPL connection state.

## Key Decisions

- Gold accent = REPL connected and ready; white = everything else (including "injected but not connected")
- FS operation badge (checkmark/exclamation) remains orthogonal to connection state
- Toolbar uses generated PNGs; popup/panel use hiccup component directly

## Prerequisites

- `rsvg-convert` installed (done: `brew install librsvg`)

## Current State

**Toolbar icons** (PNG in `extension/icons/`):
- Three states: `disconnected` (white), `injected` (yellow), `connected` (green)
- Manually created files, not derived from source

**UI icon** (`icons/epupp-logo` in `icons.cljc`):
- Already has `connected?` parameter
- White accent when false, gold (#FFDC73) when true
- Used in popup header at size 28

## Target State

**Toolbar icons**:
- Two states: `disconnected` (white accent), `connected` (gold accent)
- Generated from `epupp-logo` hiccup via Babashka script
- Sizes: 16, 32, 48, 128 PNG

**UI icons**:
- All logo appearances use `icons/epupp-logo` with `connected?` prop
- Reactive to REPL connection state

---

## Steps

### 1. Create icon generation script

Create [scripts/icon.clj](../../../scripts/icon.clj):

- [x] Add `hiccup/hiccup` dep to [bb.edn](../../../bb.edn)
- [x] Require `icons/epupp-logo`, render with `hiccup.core/html` for both `connected? true/false`
- [x] Write `icon-disconnected.svg` and `icon-connected.svg` to [extension/icons/](../../../extension/icons/)
- [x] Shell out to `rsvg-convert` for PNGs at 16, 32, 48, 128
- [x] E2E tests green
- [ ] Verified by PEZ

### 2. Add `bb gen-icons` task

Add task to [bb.edn](../../../bb.edn) that runs the script.

- [x] Add `gen-icons` task to bb.edn
- [x] Task runs successfully and produces icons
- [x] E2E tests green
- [x] Verified by PEZ

### 3. Simplify toolbar icon states

Update [background_utils.cljs](../../../src/background_utils.cljs):

- [x] `get-icon-paths` returns only `"disconnected"` or `"connected"` paths
- [x] Remove `"injected"` from icon selection logic
- [x] Update E2E tests that assert on icon states
- [x] E2E tests green
- [x] Verified by PEZ

### 4. Update display icon computation

Update [bg_icon.cljs](../../../src/bg_icon.cljs):

- [x] `compute-display-icon-state` returns only `"disconnected"` or `"connected"`
- [x] Unit tests updated if needed
- [x] E2E tests green
- [ ] Verified by PEZ

### 5. Wire popup logo to REPL state

Update [popup.cljs](../../../src/popup.cljs):

- [x] Pass `connected?` prop from subscription to `epupp-logo`
- [x] E2E tests green
- [x] Verified by PEZ

### 6. Wire panel logo to REPL state

Update [panel.cljs](../../../src/panel.cljs) to match popup pattern:

- [x] Use `[icons/epupp-logo]` with `connected?` prop like popup does
- [x] E2E tests green
- [ ] Verified by PEZ

### 7. Remove obsolete icon files

Clean up [extension/icons/](../../../extension/icons/):

- [x] Delete `icon-injected-*.png`
- [x] Delete `icon.svg`, `old-icon.svg`
- [x] Keep only generated files
- [x] E2E tests green
- [x] Verified by PEZ

### 8. Verify extension loads

Manifest references `icons/` paths which remain unchanged.

- [x] Extension loads correctly with new icons
- [x] E2E tests green
- [x] Verified by PEZ

---

## Verification

- `bb gen-icons` produces SVG+PNG in `extension/icons/`
- Visual inspection of generated icons at all sizes
- Unit tests pass (`bb test`)
- E2E tests pass (`bb test:e2e`)
- Manual test: connect/disconnect REPL, observe icon changes in toolbar and popup

---

## Implementation Workflow

When working with implementing this plan:

1. **Size up the work** into manageable items (see Steps above, each is one item)

2. **Load your todo list** with:
   - `run bb test:e2e` (establish baseline)
   - Items 1-8 from Steps above

3. **For each item**:
   - **Delegate** to the `epupp-doer` subagent with context:
     - The specific item to implement
     - That baseline is green E2E passes
     - That it must end with running `bb test:e2e` and fixing any test failures
     - Hand over with green E2E and unit test slate
   - **Request** a brief, succinct report of what was done and how
   - **Tick off checklist items** as they are completed (except "Verified by PEZ")
   - **Report progress** to the user succinctly

4. **After completing all steps**:
   - Report completion to the user
   - User verifies each step and ticks off "Verified by PEZ"
   - Steps are not fully complete until user verification, but work continues without waiting

5. **Report completion** of the whole plan to the user

---

## Files to Modify

| File | Change |
|------|--------|
| [scripts/icon.clj](../../../scripts/icon.clj) | New - icon generation script |
| [bb.edn](../../../bb.edn) | Add hiccup dep, add `gen-icons` task |
| [src/background_utils.cljs](../../../src/background_utils.cljs) | Simplify `get-icon-paths` to 2 states |
| [src/bg_icon.cljs](../../../src/bg_icon.cljs) | Update `compute-display-icon-state` |
| [src/popup.cljs](../../../src/popup.cljs) | Wire logo `connected?` to REPL state |
| [src/panel.cljs](../../../src/panel.cljs) | Use hiccup logo with `connected?` |
| [extension/icons/*](../../../extension/icons/) | Generated icons; delete obsolete |

---

## Outstanding Work

Items to address after initial implementation if not covered:

- [x] E2E tests for toolbar icon state changes on connect/disconnect (covered by popup_icon_test.cljs)

---

## Post-Implementation Fixes

Issues found during verification:

### Fix 1: Manifest default icon should be gold (connected)

The manifest default icons (`icon-*.png`) should use the connected (gold) version, not disconnected. The gold icon is the brand identity.

- [x] Update `scripts/icon.clj` to copy connected icons to manifest defaults
- [x] Also generate `icon.svg` for web installer
- [x] E2E tests green
- [x] Verified by PEZ

### Fix 2: Panel logo not reactive to disconnect

Panel logo starts with connected state and doesn't react when REPL disconnects.

- [x] Added `:editor/ax.handle-ws-close` action to reset scittle-status
- [x] Panel listens for `connections-changed` messages
- [x] Resets to white when inspected tab disconnects
- [x] E2E tests green
- [ ] Verified by PEZ - **STILL BROKEN**: Shows connected on start, then inverts (shows disconnected on connect)

### Fix 3: Toolbar icon doesn't return to disconnected

Toolbar icon reacts to connect but stays connected even when all tabs disconnect.

- [x] Fixed bg_ws.cljs to use `:disconnected` instead of removed `:injected` state
- [x] Fixed bg_inject.cljs to use `:disconnected` when Scittle is injected but not connected
- [x] Updated unit tests to use `:disconnected`
- [x] Fixed `close-ws!` to update icon state (root cause: onclose handler was cleared but icon wasn't updated)
- [x] E2E tests green
- [ ] Verified by PEZ - Previous fix incomplete, added icon update to close-ws!

### Reference: Popup icon behavior (works correctly)

Popup icon works correctly and should be used as reference:
- Shows connected (gold) on connected tabs
- Shows disconnected (white) on tabs that are not connected
- This is the correct tab-specific behavior

---

## Original Plan-producing Prompt

Create a plan for icon unification in Epupp:

1. Create `scripts/icon.clj` - Babashka script that uses `epupp-logo` from `icons.cljc`, produces all toolbar icons (SVG + PNG) to `extension/icons/` using hiccup library and `rsvg-convert`

2. Make all UI icons (popup, panel) use the hiccup component from `icons.cljc`; toolbar uses generated PNGs

3. Simplify icon states to two: white accent (not connected), gold accent (connected)

4. Make icons REPL connection-aware in popup and panel

Include an implementation workflow:
- Size up work into manageable items
- Load todo list with "run `bb test:e2e`" plus items
- For each item: delegate to epupp-doer subagent (baseline is green E2E, must end with green E2E/unit tests, request brief report), then report progress
- Report plan completion when done
