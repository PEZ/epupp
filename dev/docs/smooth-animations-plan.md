# Smooth UI Transitions Plan

This document tracks the implementation of smooth 0.25s transitions for all dynamic UI elements in the popup and panel.

## Standard

- **Duration:** 0.25s (standardized via `--transition-duration` token)
- **Easing:** `ease-out` for most transitions
- **Properties:** height, max-height, opacity, transform

## Required Reading

Before implementing, read these documents and source files:

### Architecture Documentation

- [css-architecture.md](architecture/css-architecture.md) - CSS layer responsibilities, token usage, component patterns
- [ui.md](ui.md) - Reagami/Uniflow patterns, state management, TDD workflow

### Source Files (CSS)

- [design-tokens.css](../../extension/design-tokens.css) - Design token definitions (add transition token here)
- [components.css](../../extension/components.css) - Shared component styles (buttons, status indicators)
- [popup.css](../../extension/popup.css) - Popup-specific styles
- [panel.css](../../extension/panel.css) - Panel-specific styles
- [base.css](../../extension/base.css) - Base styles shared across views

### Source Files (Components)

- [view_elements.cljs](../../src/view_elements.cljs) - Shared Reagent components (action-button, status-text, empty-state)
- [popup.cljs](../../src/popup.cljs) - Popup UI components and state
- [panel.cljs](../../src/panel.cljs) - Panel UI components and state

## Checklist

### Design Tokens

- [x] **Add transition duration token** ([design-tokens.css](../../extension/design-tokens.css))
  - [x] addressed in code
  - [x] verified (human)

### Popup Elements

- [ ] **Popup: Collapsible section content** ([popup.cljs#L458](../../src/popup.cljs#L458), [popup.css#L510-L525](../../extension/popup.css#L510-L525))
  - Section content appears/disappears abruptly when toggled - CSS not working, needs investigation
  - [x] addressed in code
  - [x] verified (human)

- [x] **Popup: System banners** ([popup.cljs#L757](../../src/popup.cljs#L757), [popup.css#L82-L107](../../extension/popup.css#L82-L107))
  - Banners slide in (existing animation) but have no exit animation
  - [x] addressed in code
  - [x] verified (human)

- [x] **Popup: Connect status message** ([popup.cljs#L844](../../src/popup.cljs#L844))
  - Replace inline status text with a system banner message for calmer UI
  - [x] addressed in code
  - [x] verified (human)

- [ ] **Popup: Script list items** ([popup.cljs#L509-L558](../../src/popup.cljs#L509-L558))
  - List should grow/shrink smoothly when scripts added/deleted (CSS cannot animate height:auto - requires JS)
  - [ ] addressed in code - DEFERRED (CSS limitation)
  - [ ] verified (human)

- [x] **Popup: Script edit hint** ([popup.cljs#L570](../../src/popup.cljs#L570))
  - Replace inline hint with a system banner message for calmer UI
  - [x] addressed in code
  - [x] verified (human)

- [ ] **Popup: No scripts empty state** ([popup.cljs#L576-L588](../../src/popup.cljs#L576-L588))
  - Section height should change smoothly (CSS cannot animate height:auto - requires JS)
  - [ ] addressed in code - DEFERRED (CSS limitation)
  - [ ] verified (human)

- [ ] **Popup: Connected tabs list** ([popup.cljs#L783-L800](../../src/popup.cljs#L783-L800))
  - REPL Connect section should grow/shrink smoothly (CSS cannot animate height:auto - requires JS)
  - [ ] addressed in code - DEFERRED (CSS limitation)
  - [ ] verified (human)

- [ ] **Popup: No connections empty state** ([popup.cljs#L800-L803](../../src/popup.cljs#L800-L803))
  - Section height should change smoothly (CSS cannot animate height:auto - requires JS)
  - [ ] addressed in code - DEFERRED (CSS limitation)
  - [ ] verified (human)

- [x] **Popup: Add origin error message** ([popup.cljs#L697](../../src/popup.cljs#L697))
  - Replace inline error with a system banner message for calmer UI
  - [x] addressed in code
  - [x] verified (human)

- [ ] **Popup: Copy feedback** ([popup.cljs#L456](../../src/popup.cljs#L456))
  - Button jumps between states - fixed with fixed width (75px)
  - [x] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Origins lists** ([popup.cljs#L650-L678](../../src/popup.cljs#L650-L678))
  - Section should grow/shrink smoothly (CSS cannot animate height:auto - requires JS)
  - [ ] addressed in code - DEFERRED (CSS limitation)
  - [ ] verified (human)

- [x] **Popup: Section badge count** ([popup.cljs#L467](../../src/popup.cljs#L467), [popup.css#L536-L542](../../extension/popup.css#L536-L542))
  - Badge number changes without transition - marked unimportant
  - [x] addressed in code
  - [x] verified (human) - skipped, unimportant

### Panel Elements

- [x] **Panel: System banners** ([panel.cljs#L372-L374](../../src/panel.cljs#L372-L374), [panel.css#L55-L80](../../extension/panel.css#L55-L80))
  - Banners slide in but have no exit animation
  - [x] addressed in code
  - [x] verified (human)

- [x] **Panel: Refresh banner** ([panel.cljs#L368-L372](../../src/panel.cljs#L368-L372), [panel.css#L40-L52](../../extension/panel.css#L40-L52))
  - Banner appears without animation
  - [x] addressed in code
  - [x] verified (human)

- [x] **Panel: Results area items** ([panel.cljs#L184-L199](../../src/panel.cljs#L184-L199))
  - Result items appear abruptly when code is evaluated
  - [x] addressed in code
  - [x] verified (human)

- [ ] **Panel: Empty results state** ([panel.cljs#L201-L208](../../src/panel.cljs#L201-L208))
  - Results container height should change smoothly (CSS cannot animate height:auto - requires JS)
  - [ ] addressed in code - DEFERRED (CSS limitation)
  - [ ] verified (human)

- [x] **Panel: Save status message** ([panel.cljs#L611-L615](../../src/panel.cljs#L611-L615))
  - Replace inline status text with a system banner message for calmer UI (banner may already exist, just remove inline)
  - [x] addressed in code
  - [x] verified (human)

- [x] **Panel: Rename button appearance** ([panel.cljs#L596-L609](../../src/panel.cljs#L596-L609))
  - Rename button appears/disappears abruptly when name changes - marked unimportant
  - [x] addressed in code
  - [x] verified (human) - skipped, unimportant

- [x] **Panel: Unknown keys warning** ([panel.cljs#L239-L245](../../src/panel.cljs#L239-L245), [panel.css#L307-L323](../../extension/panel.css#L307-L323))
  - Warning appears/disappears abruptly when manifest changes - marked unimportant
  - [x] addressed in code
  - [x] verified (human) - skipped, unimportant

- [x] **Panel: Invalid requires warning** ([panel.cljs#L259-L267](../../src/panel.cljs#L259-L267))
  - Warning appears/disappears abruptly - marked unimportant
  - [x] addressed in code
  - [x] verified (human) - skipped, unimportant

- [x] **Panel: No manifest message** ([panel.cljs#L269-L275](../../src/panel.cljs#L269-L275), [panel.css#L326-L350](../../extension/panel.css#L326-L350))
  - Message swaps with metadata table abruptly - marked unimportant
  - [x] addressed in code
  - [x] verified (human) - skipped, unimportant

- [x] **Panel: Metadata table rows** ([panel.cljs#L222-L237](../../src/panel.cljs#L222-L237))
  - Field hints appear/disappear abruptly - marked unimportant
  - [x] addressed in code
  - [x] verified (human) - skipped, unimportant

### Shared Components

- [x] **Components: status-text fadeIn** ([components.css#L147-L152](../../extension/components.css#L147-L152))
  - Currently uses 0.2s - standardize to 0.25s
  - [x] addressed in code
  - [x] verified (human)

- [x] **Components: All button transitions** ([components.css#L11-L80](../../extension/components.css#L11-L80))
  - Currently uses 0.15s - standardize to 0.25s
  - [x] addressed in code
  - [x] verified (human)

### Existing Animations to Standardize

- [x] **Popup: Banner slide-in** ([popup.css#L109-L119](../../extension/popup.css#L109-L119))
  - Currently 0.2s - standardize to 0.25s
  - [x] addressed in code
  - [x] verified (human)

- [x] **Popup: Chevron rotation** ([popup.css#L545-L551](../../extension/popup.css#L545-L551))
  - Currently 0.2s - standardize to 0.25s
  - [x] addressed in code
  - [x] verified (human)

- [x] **Popup: Various button/input transitions** (multiple locations)
  - Port inputs, copy button, connect button, etc. use 0.15s
  - [x] addressed in code
  - [x] verified (human)

- [x] **Panel: Banner slide-in** ([panel.css#L82-L96](../../extension/panel.css#L82-L96))
  - Currently 0.2s - standardize to 0.25s
  - [x] addressed in code
  - [x] verified (human)

- [x] **Panel: Button/input transitions** (multiple locations)
  - Various buttons use 0.15s
  - [x] addressed in code
  - [x] verified (human)

- [x] **Panel: Save status fadeIn CSS** ([panel.css#L249-L257](../../extension/panel.css#L249-L257))
  - Remove after replacing inline save status with banner
  - [x] addressed in code
  - [x] verified (human) - CSS removed, cleanup complete

## Process

1. **Agent** implements the CSS/code changes for a carefully sized batch of items, has testrunner subagent run tests, and builds dev extension
2. **Agent** ticks "addressed in code" checkboxes
3. **Agent** hands off to the human with a brief list of what exists to test
4. **Human** manually tests in browser (load extension, trigger the UI change)
5. **Human** ticks "verified (human)" checkboxes for the items that work correctly
6. If issues found, human describes problem, agent fixes, repeat from step 1
7. **After verification**, update [css-architecture.md](architecture/css-architecture.md) to document:
   - The `--transition-duration` token (once added)
   - Any new animation patterns or CSS classes introduced
   - Updates to existing component documentation if patterns changed

## Implementation Patterns

### Height Transitions (for collapsible content)

Use `max-height` with `overflow: hidden`:

```css
.section-content {
  max-height: 0;
  overflow: hidden;
  transition: max-height var(--transition-duration) ease-out;
}

.collapsible-section:not(.collapsed) .section-content {
  max-height: 1000px; /* Large enough for content */
}
```

### Opacity Transitions (for appearing/disappearing elements)

Combine with `visibility` for accessibility:

```css
.status-message {
  opacity: 0;
  visibility: hidden;
  transition: opacity var(--transition-duration) ease-out,
              visibility var(--transition-duration);
}

.status-message.visible {
  opacity: 1;
  visibility: visible;
}
```

### List Item Transitions (for adding/removing items)

Use CSS classes applied during mount/unmount:

```css
.list-item {
  opacity: 1;
  transform: translateY(0);
  transition: opacity var(--transition-duration) ease-out,
              transform var(--transition-duration) ease-out;
}

.list-item.entering {
  opacity: 0;
  transform: translateY(-10px);
}
```

### Banner Exit Animation

Add `@keyframes` for exit or use CSS classes:

```css
.banner.leaving {
  animation: banner-slide-out var(--transition-duration) ease-in forwards;
}

@keyframes banner-slide-out {
  from {
    opacity: 1;
    transform: translateY(0);
  }
  to {
    opacity: 0;
    transform: translateY(-100%);
  }
}
```

## List Item Animations

This section tracks the specific requirements and implementation status for animating list items (scripts, connections, origins) when they are added or removed.

### Requirements

| ID | Requirement | Status |
|----|-------------|--------|
| Must-1 | When an item is added to a list, it should smoothly grow in | Not working |
| Must-2 | When an item is removed from a list, it should smoothly shrink away | Partially working |
| Must-3 | No extra list growing/shrinking animation before or after item animations | Working |
| Must-4 | Animations should happen regardless of the source adding or removing the item | Not working |

### Current Status

#### Working

| ID | Description |
|----|-------------|
| Works-1 | Removing a connection via the connected-tab disconnect button |
| Works-2 | Removing a script via the script item delete button |
| Works-3 | Removing an origin via the origin item delete button |

#### Not Working

| ID | Description |
|----|-------------|
| Not-working-1 | Smooth grow when adding a connection from any source |
| Not-working-2 | Smooth grow when adding a script from any source |
| Not-working-3 | Smooth grow when adding an origin from any source |
| Not-working-4 | Smooth shrink when removing a connection via any means other than the connected-tab button |
| Not-working-5 | Smooth shrink when removing a script via any means other than the script item delete button |
| Not-working-6 | Smooth shrink when removing an origin via any means other than the origin item button |

### Current Implementation

**Exit animations (two-phase deletion pattern):**

1. State tracks items pending removal via `:ui/leaving-scripts`, `:ui/leaving-origins`, `:ui/leaving-tabs` sets in [popup.cljs#L38-L40](../../src/popup.cljs#L38-L40)

2. Actions in [popup_actions.cljs](../../src/popup_actions.cljs) implement a two-phase pattern:
   - Phase 1: Mark item as leaving (add to leaving set), schedule deferred dispatch for 250ms
   - Phase 2: When action fires again and item is in leaving set, perform actual removal

3. Components check if item ID is in leaving set and apply `.leaving` CSS class

4. CSS in [popup.css](../../extension/popup.css) defines `.leaving` state for each item type:
   - `.script-item.leaving` - max-height: 0, opacity: 0, padding: 0
   - `.connected-tab-item.leaving` - same pattern
   - `.origin-item.leaving` - same pattern

**Enter animations (removed):**

- Previously used CSS `@starting-style` to animate items growing in on DOM insertion
- Removed because Reagami re-renders after item removal caused remaining items to re-trigger `@starting-style`, creating unwanted re-grow animations
- Current state: items appear instantly (no enter animation)

**Why only button-triggered removals work:**

The two-phase pattern is only wired for actions triggered by UI buttons within the list items themselves:
- `:popup/ax.delete-script` - triggered by script item delete button
- `:popup/ax.remove-origin` - triggered by origin item delete button
- `:popup/ax.disconnect-tab` - triggered by connected-tab disconnect button

Other sources of item removal (REPL FS sync, storage changes, external disconnects) bypass the two-phase pattern and remove items directly from state, causing instant disappearance.

### Design Challenge

To satisfy all requirements, we need:

1. **Enter animations** that don't re-trigger on sibling removal
2. **Enter animations** that work regardless of adding source
3. **Exit animations** that work regardless of removal source

This likely requires:
- A different approach for enter animations (not `@starting-style`)
- Intercepting all state changes that affect lists, not just specific actions

### Blocking Issues

**Failing E2E test** - Must be fixed before merging:
- Test: `popup_connection_test.mjs:210:8 › Popup Connection › Popup Connection: disconnect button disconnects current tab`
- Likely cause: The two-phase deletion pattern introduces a 250ms delay before actual disconnect, which may break test timing expectations

## Notes

- **Accessibility**: Don't animate `display: none` - use `visibility` instead
- **Testing**: E2E tests must enclose the chages, even if CSS animations are often not testable via E2E - so human verification is also required
- **Focus states**: Ensure animations don't interfere with focus management

## Token Definition

Add to [design-tokens.css](../../extension/design-tokens.css):

```css
:root {
  /* Animation */
  --transition-duration: 0.25s;
}
```

## Original Plan-producing Prompt

The following prompt was used to generate this plan:

---

I need your CSS design expertise. Generally, for both panel and popup, elements are inserted, expanded, collapsed and so on in a very unsmooth way. I want smooth grow/shrink over 0.25s everywhere where anything is inserted, removed, collapsed, expanded, or changes height.

Please investigate and create a dev/docs plan with full coverage of all instances that need attention, and the suggested remedies.

**Plan structure requirements:**

1. Include a "Required Reading" section listing all relevant dev docs and source files that are mandatory to read before working with implementation (between Standard and Checklist sections)

2. Use a straight checklist format where each item has dual checkboxes:
   - [ ] addressed in code
   - [ ] verified (human)

3. Include a Process section that clearly states:
   - Agent ticks "addressed in code" after implementation
   - Human ticks "verified (human)" after visual confirmation in browser
   - After human verification, update css-architecture.md to document any new tokens, patterns, or classes introduced

The plan should be structured so that every time something is done, items are simply ticked off without inserting blocks of "this was done" commentary.

---
