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

- [ ] **Popup: Connect status message** ([popup.cljs#L844](../../src/popup.cljs#L844))
  - Replace inline status text with a system banner message for calmer UI
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Script list items** ([popup.cljs#L509-L558](../../src/popup.cljs#L509-L558))
  - Items appear/disappear abruptly when scripts are added/deleted
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Script edit hint** ([popup.cljs#L570](../../src/popup.cljs#L570))
  - Replace inline hint with a system banner message for calmer UI
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: No scripts empty state** ([popup.cljs#L576-L588](../../src/popup.cljs#L576-L588))
  - Empty state swaps with list abruptly
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Connected tabs list** ([popup.cljs#L783-L800](../../src/popup.cljs#L783-L800))
  - Tab items appear/disappear abruptly when connections change
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: No connections empty state** ([popup.cljs#L800-L803](../../src/popup.cljs#L800-L803))
  - Empty state swaps with list abruptly
  - [ ] addressed in code
  - [ ] verified (human)

- [x] **Popup: Add origin error message** ([popup.cljs#L697](../../src/popup.cljs#L697))
  - Replace inline error with a system banner message for calmer UI
  - [x] addressed in code
  - [x] verified (human)

- [ ] **Popup: Copy feedback** ([popup.cljs#L456](../../src/popup.cljs#L456))
  - Button should grow/shrink smoothly when "Copied!" text appears
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Origins lists** ([popup.cljs#L650-L678](../../src/popup.cljs#L650-L678))
  - User origin items appear/disappear abruptly when added/removed
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Section badge count** ([popup.cljs#L467](../../src/popup.cljs#L467), [popup.css#L536-L542](../../extension/popup.css#L536-L542))
  - Badge number changes without transition
  - [ ] addressed in code
  - [ ] verified (human)

### Panel Elements

- [x] **Panel: System banners** ([panel.cljs#L372-L374](../../src/panel.cljs#L372-L374), [panel.css#L55-L80](../../extension/panel.css#L55-L80))
  - Banners slide in but have no exit animation
  - [x] addressed in code
  - [x] verified (human)

- [x] **Panel: Refresh banner** ([panel.cljs#L368-L372](../../src/panel.cljs#L368-L372), [panel.css#L40-L52](../../extension/panel.css#L40-L52))
  - Banner appears without animation
  - [x] addressed in code
  - [x] verified (human)

- [ ] **Panel: Results area items** ([panel.cljs#L184-L199](../../src/panel.cljs#L184-L199))
  - Result items appear abruptly when code is evaluated
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Empty results state** ([panel.cljs#L201-L208](../../src/panel.cljs#L201-L208))
  - Empty state swaps with results abruptly
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Save status message** ([panel.cljs#L611-L615](../../src/panel.cljs#L611-L615))
  - Replace inline status text with a system banner message for calmer UI (banner may already exist, just remove inline)
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Rename button appearance** ([panel.cljs#L596-L609](../../src/panel.cljs#L596-L609))
  - Rename button appears/disappears abruptly when name changes
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Unknown keys warning** ([panel.cljs#L239-L245](../../src/panel.cljs#L239-L245), [panel.css#L307-L323](../../extension/panel.css#L307-L323))
  - Warning appears/disappears abruptly when manifest changes
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Invalid requires warning** ([panel.cljs#L259-L267](../../src/panel.cljs#L259-L267))
  - Warning appears/disappears abruptly
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: No manifest message** ([panel.cljs#L269-L275](../../src/panel.cljs#L269-L275), [panel.css#L326-L350](../../extension/panel.css#L326-L350))
  - Message swaps with metadata table abruptly
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Metadata table rows** ([panel.cljs#L222-L237](../../src/panel.cljs#L222-L237))
  - Field hints appear/disappear abruptly
  - [ ] addressed in code
  - [ ] verified (human)

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

- [ ] **Panel: Save status fadeIn CSS** ([panel.css#L249-L257](../../extension/panel.css#L249-L257))
  - Remove after replacing inline save status with banner
  - [ ] addressed in code
  - [ ] verified (human)

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

## Notes

- **Performance**: Prefer `transform` and `opacity` over `height` when possible
- **Accessibility**: Don't animate `display: none` - use `visibility` instead
- **Testing**: CSS animations are not testable via E2E - human verification required
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
