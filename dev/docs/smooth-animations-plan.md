# Smooth UI Transitions Plan

This document tracks the implementation of smooth 0.25s transitions for all dynamic UI elements in the popup and panel.

## Standard

- **Duration:** 0.25s (standardized via `--transition-duration` token)
- **Easing:** `ease-out` for most transitions
- **Properties:** height, max-height, opacity, transform

## Checklist

### Design Tokens

- [ ] **Add transition duration token** ([design-tokens.css](../../extension/design-tokens.css))
  - [ ] addressed in code
  - [ ] verified (human)

### Popup Elements

- [ ] **Popup: Collapsible section content** ([popup.cljs#L458](../../src/popup.cljs#L458), [popup.css#L510-L525](../../extension/popup.css#L510-L525))
  - Section content appears/disappears abruptly when toggled
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: FS event banners** ([popup.cljs#L757](../../src/popup.cljs#L757), [popup.css#L82-L107](../../extension/popup.css#L82-L107))
  - Banners slide in (existing animation) but have no exit animation
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Connect status message** ([popup.cljs#L704](../../src/popup.cljs#L704))
  - Status text appears/disappears abruptly (Connecting.../Connected!/Failed:)
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Script list items** ([popup.cljs#L509-L558](../../src/popup.cljs#L509-L558))
  - Items appear/disappear abruptly when scripts are added/deleted
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Script edit hint** ([popup.cljs#L556](../../src/popup.cljs#L556), [popup.css#L309-L315](../../extension/popup.css#L309-L315))
  - Hint panel appears/disappears abruptly below script item
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: No scripts empty state** ([popup.cljs#L576-L588](../../src/popup.cljs#L576-L588))
  - Empty state swaps with list abruptly
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Connected tabs list** ([popup.cljs#L633-L652](../../src/popup.cljs#L633-L652))
  - Tab items appear/disappear abruptly when connections change
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: No connections empty state** ([popup.cljs#L654-L657](../../src/popup.cljs#L654-L657))
  - Empty state swaps with list abruptly
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Add origin error message** ([popup.cljs#L428](../../src/popup.cljs#L428), [popup.css#L729-L733](../../extension/popup.css#L729-L733))
  - Error message appears/disappears abruptly below input
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Copy feedback** ([popup.cljs#L456](../../src/popup.cljs#L456))
  - "Copied!" text appears/disappears abruptly
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Origins lists** ([popup.cljs#L395-L420](../../src/popup.cljs#L395-L420))
  - Origin items appear/disappear abruptly when added/removed
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Section badge count** ([popup.cljs#L467](../../src/popup.cljs#L467), [popup.css#L536-L542](../../extension/popup.css#L536-L542))
  - Badge number changes without transition
  - [ ] addressed in code
  - [ ] verified (human)

### Panel Elements

- [ ] **Panel: FS event banners** ([panel.cljs#L372-L374](../../src/panel.cljs#L372-L374), [panel.css#L55-L80](../../extension/panel.css#L55-L80))
  - Banners slide in but have no exit animation
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Refresh banner** ([panel.cljs#L368-L372](../../src/panel.cljs#L368-L372), [panel.css#L40-L52](../../extension/panel.css#L40-L52))
  - Banner appears without animation
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Results area items** ([panel.cljs#L184-L199](../../src/panel.cljs#L184-L199))
  - Result items appear abruptly when code is evaluated
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Empty results state** ([panel.cljs#L201-L208](../../src/panel.cljs#L201-L208))
  - Empty state swaps with results abruptly
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Save status message** ([panel.cljs#L343-L347](../../src/panel.cljs#L343-L347), [panel.css#L243-L257](../../extension/panel.css#L243-L257))
  - Status text appears/disappears abruptly after save
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Rename button appearance** ([panel.cljs#L330-L340](../../src/panel.cljs#L330-L340))
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

- [ ] **Components: status-text fadeIn** ([components.css#L147-L152](../../extension/components.css#L147-L152))
  - Currently uses 0.2s - standardize to 0.25s
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Components: All button transitions** ([components.css#L11-L80](../../extension/components.css#L11-L80))
  - Currently uses 0.15s - standardize to 0.25s
  - [ ] addressed in code
  - [ ] verified (human)

### Existing Animations to Standardize

- [ ] **Popup: Banner slide-in** ([popup.css#L109-L119](../../extension/popup.css#L109-L119))
  - Currently 0.2s - standardize to 0.25s
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Chevron rotation** ([popup.css#L545-L551](../../extension/popup.css#L545-L551))
  - Currently 0.2s - standardize to 0.25s
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Popup: Various button/input transitions** (multiple locations)
  - Port inputs, copy button, connect button, etc. use 0.15s
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Banner slide-in** ([panel.css#L82-L96](../../extension/panel.css#L82-L96))
  - Currently 0.2s - standardize to 0.25s
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Button/input transitions** (multiple locations)
  - Various buttons use 0.15s
  - [ ] addressed in code
  - [ ] verified (human)

- [ ] **Panel: Save status fadeIn** ([panel.css#L249-L257](../../extension/panel.css#L249-L257))
  - Currently 0.2s - standardize to 0.25s
  - [ ] addressed in code
  - [ ] verified (human)

## Process

1. **Agent** implements the CSS/code changes for an item
2. **Agent** ticks "addressed in code" checkbox
3. **Human** manually tests in browser (load extension, trigger the UI change)
4. **Human** ticks "verified (human)" checkbox if working correctly
5. If issues found, human describes problem, agent fixes, repeat from step 1

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
