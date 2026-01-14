# CSS Consolidation Plan: Styling Nirvana

A step-by-step plan to unify popup and panel styling into a coherent design system.

## Current State Analysis

### Files
| File | Lines | Purpose |
|------|-------|---------|
| [popup.css](../../extension/popup.css) | ~650 | Popup-specific styles + duplicated base styles |
| [panel.css](../../extension/panel.css) | ~380 | Panel-specific styles + duplicated base styles |
| [shared.css](../../extension/shared.css) | ~90 | Header/footer components only |

### Key Observations

**Duplication found:**
1. **CSS Variables** - Both files declare `:root` variables with similar but inconsistent names
   - popup: `--bg-body`, `--bg-subtle`, `--border-light`
   - panel: `--bg-primary`, `--bg-secondary`, `--border-color`
2. **Scrollbar styles** - Identical in both files (~25 lines each)
3. **Button patterns** - Similar styling with different class names
4. **Status/result styling** - Similar border-left patterns for success/error states

**Naming inconsistencies:**
| Concept | popup.css | panel.css |
|---------|-----------|-----------|
| Main background | `--bg-body` | `--bg-primary` |
| Secondary background | `--bg-subtle` | `--bg-secondary` |
| Border color | `--border-light` | `--border-color` |
| Success color | `--clojure-green` | `--success` |
| Error border | `--status-failed-border` | `--error` |

**Theme handling:**
- popup.css: Light theme default, dark via `@media (prefers-color-scheme: dark)`
- panel.css: Dark theme default, light via `@media (prefers-color-scheme: light)`

### Shared Components (view_elements.cljs)
Currently shared:
- `app-header` - Uses classes: `.app-header-wrapper`, `.app-header`, `.app-header-title`, `.app-header-status`
- `app-footer` - Uses classes: `.app-footer`, `.footer-logos`, `.footer-powered`, `.footer-credits`

View-specific wrappers:
- popup: `.popup-header-wrapper`, `.popup-header`, `.popup-footer`
- panel: `.panel-header-wrapper`, `.panel-header`, `.panel-footer`

---

## Hiccup Components: The CSS Consumers

The Hiccup/Reagent components are **first-class citizens** in maintaining styling consistency. CSS classes are consumed by these files:

| File | Role | Key Components |
|------|------|----------------|
| [view_elements.cljs](../../src/view_elements.cljs) | Shared components | `app-header`, `app-footer` |
| [popup.cljs](../../src/popup.cljs) | Popup view | `script-item`, `collapsible-section`, buttons, forms |
| [panel.cljs](../../src/panel.cljs) | Panel view | `result-item`, `code-input`, `save-script-section` |
| [icons.cljs](../../src/icons.cljs) | SVG icons | Icon components with `size` and `class` props |

### Current Component Patterns

**Good patterns to preserve:**
- `view_elements.cljs` accepts `:elements/wrapper-class` for view-specific overrides
- Icons accept `:class` prop for contextual styling
- Components use semantic class names (`.script-item`, `.result-item`)

**Patterns to improve:**
- Some inline styles could be classes
- Similar components in popup/panel use different class names
- No shared component for buttons, inputs, status indicators

### Component Consolidation Opportunities

| Concept | popup.cljs | panel.cljs | Candidate shared component |
|---------|------------|------------|---------------------------|
| Action button | `button#connect`, `.copy-btn` | `.btn-eval`, `.btn-clear` | `action-button` in view_elements |
| Status message | `.status`, `.connect-status` | `.save-status` | `status-indicator` |
| Empty state | `.no-scripts`, `.no-connections` | `.empty-results` | `empty-state` |
| List item | `.script-item` | `.result-item` | Keep separate (different semantics) |

### Hiccup Migration Strategy

When extracting CSS components, also consider:

1. **Create shared Hiccup components** in `view_elements.cljs` for common patterns
2. **Use consistent prop naming** - `:class` for additional classes, `:variant` for style variants
3. **Document component API** - What props each component accepts

Example shared button component:
```clojure
(defn action-button
  "Reusable button component.
   Options:
   - :button/variant - :primary, :secondary, :success, :danger
   - :button/disabled? - boolean
   - :button/icon - optional icon component
   - :button/on-click - click handler"
  [{:button/keys [variant disabled? icon on-click]} label]
  [:button {:class (str "btn btn-" (name (or variant :secondary)))
            :disabled disabled?
            :on-click on-click}
   (when icon [icon {:size 14}])
   (when (and icon label) " ")
   label])
```

---

## Scrollbar Stability: Popup as Model

The popup has carefully crafted scrollbar handling to prevent layout shift. This behavior should be the **canonical pattern** for the panel.

### Popup's Scrollbar Solution (the model)

From [popup.css](../../extension/popup.css):

```css
body {
  /* Reserve space for scrollbar to prevent layout shift */
  scrollbar-gutter: stable;
  overflow-y: auto;
}

/* Firefox-specific fix */
@supports (-moz-appearance: none) {
  html {
    scrollbar-gutter: stable;
  }
  body {
    overflow-y: scroll;
    scrollbar-width: auto;
  }
}
```

**Why this matters:**
- Without `scrollbar-gutter: stable`, content jumps when scrollbar appears/disappears
- Firefox has quirks that require html-level handling
- The popup's solution was battle-tested for both browsers

### Panel's Current Problem

The panel uses `overflow: hidden` on body and `overflow-y: auto` on `.panel-content`, but lacks the stability handling. This can cause:
- Layout shift when results accumulate
- Inconsistent spacing near the right edge

### Migration: Apply Popup Pattern to Panel

The scrollbar extraction should include the stability pattern:

```css
/* shared.css - Scrollbar with stability */

/* Base scrollbar appearance */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
::-webkit-scrollbar-track {
  background: var(--scrollbar-track);
}
::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
  border-radius: 4px;
}
::-webkit-scrollbar-thumb:hover {
  background: var(--scrollbar-thumb-hover);
}

/* Firefox scrollbar */
* {
  scrollbar-width: thin;
  scrollbar-color: var(--scrollbar-thumb) var(--scrollbar-track);
}

/* Stability mixin - apply to scrollable containers */
.scrollable-stable {
  scrollbar-gutter: stable;
  overflow-y: auto;
}

/* Firefox stability fix */
@supports (-moz-appearance: none) {
  .scrollable-stable {
    overflow-y: scroll;
    scrollbar-width: auto;
  }
}
```

Then apply `.scrollable-stable` to both popup's `body` and panel's `.panel-content`.

---

## Design System Foundation

### Phase 1: Unified CSS Variables

Create a single source of truth for design tokens.

**New file:** `extension/design-tokens.css`

```css
/* Design Tokens - Single source of truth for the Epupp design system */

:root {
  /* Brand colors */
  --clojure-blue: #5881d8;
  --clojure-blue-light: #99B5F9;
  --clojure-green: #91dc47;

  /* Semantic colors - light theme defaults */
  --color-text-primary: #1a1a1a;
  --color-text-secondary: #666;
  --color-text-muted: #999;

  --color-bg-base: #ffffff;
  --color-bg-elevated: #f8f9fa;
  --color-bg-input: #ffffff;
  --color-bg-accent: #707070;
  --color-fg-accent: #fafafa;

  --color-border: #e5e7eb;
  --color-border-focus: var(--clojure-blue);

  --color-success: #4ec9b0;
  --color-error: #e53935;
  --color-warning: #f59e0b;

  /* Spacing scale */
  --space-xs: 4px;
  --space-sm: 8px;
  --space-md: 12px;
  --space-lg: 16px;
  --space-xl: 20px;

  /* Border radius */
  --radius-sm: 4px;
  --radius-md: 6px;

  /* Typography */
  --font-sans: system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
  --font-mono: ui-monospace, SFMono-Regular, "SF Mono", Menlo, monospace;
  --font-size-xs: 10px;
  --font-size-sm: 11px;
  --font-size-base: 12px;
  --font-size-md: 13px;

  /* Scrollbar */
  --scrollbar-thumb: #c0c0c0;
  --scrollbar-thumb-hover: #a0a0a0;
  --scrollbar-track: var(--color-bg-base);
}

/* Dark theme */
@media (prefers-color-scheme: dark) {
  :root {
    --color-text-primary: #e8e8e8;
    --color-text-secondary: #a0a0a0;
    --color-text-muted: #707070;

    --color-bg-base: #1e1e1e;
    --color-bg-elevated: #252526;
    --color-bg-input: #2d2d2d;
    --color-bg-accent: #8f8f8f;
    --color-fg-accent: #ffffff;

    --color-border: #3c3c3c;

    --color-success: #4ec9b0;
    --color-error: #f14c4c;

    --scrollbar-thumb: #5a5a5a;
    --scrollbar-thumb-hover: #6a6a6a;
    --scrollbar-track: var(--color-bg-base);
  }
}
```

### Phase 2: Component Library

Extract reusable patterns into `extension/components.css`.

**Components to extract:**

1. **Buttons**
   - `.btn` - Base button styles
   - `.btn-primary` - Blue action buttons (Connect, Eval)
   - `.btn-secondary` - Ghost/outline buttons (Clear, Copy)
   - `.btn-success` - Green buttons (Save, Allow)
   - `.btn-danger` - Red buttons (Delete, Deny)
   - `.btn-icon` - Icon-only buttons with hover states

2. **Form elements**
   - `.input` - Text/number inputs
   - `.checkbox-label` - Checkbox + label combo
   - `.textarea` - Multi-line input (panel code area)

3. **Cards/containers**
   - `.card` - Elevated container with border
   - `.card-subtle` - Subtle background container
   - `.card-dashed` - Dashed border for empty states

4. **Status indicators**
   - `.status-bar` - Left-border status indicator
   - `.status-bar--success`, `--error`, `--warning`, `--pending`

5. **Lists**
   - `.list` - Vertical list container
   - `.list-item` - Individual list item
   - `.list-item--builtin` - Special styling for built-ins

6. **Badges**
   - `.badge` - Small pill badge
   - `.badge-count` - Numeric badge

7. **Section headers**
   - `.section-header` - Uppercase, muted header text
   - `.collapsible-header` - Clickable expand/collapse

8. **Scrollbar** (shared utility)

### Phase 3: Layout Utilities

**File:** `extension/utilities.css`

```css
/* Flexbox utilities */
.flex { display: flex; }
.flex-col { flex-direction: column; }
.flex-1 { flex: 1; }
.items-center { align-items: center; }
.justify-between { justify-content: space-between; }
.gap-xs { gap: var(--space-xs); }
.gap-sm { gap: var(--space-sm); }
.gap-md { gap: var(--space-md); }

/* Spacing */
.p-sm { padding: var(--space-sm); }
.p-md { padding: var(--space-md); }
.mt-sm { margin-top: var(--space-sm); }

/* Typography */
.font-mono { font-family: var(--font-mono); }
.text-muted { color: var(--color-text-muted); }
.text-sm { font-size: var(--font-size-sm); }
.truncate {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
```

---

## Migration Steps

**CRITICAL: Manual Visual Testing Required**

After each step that touches CSS, run `bb build:dev` and ask the human to:
1. Open both popup and panel in light and dark themes
2. Verify no visual regressions (backgrounds, borders, spacing, colors)
3. E2E tests verify behavior, not appearance - human eyes are essential!

### Step 1a: Create design tokens (non-breaking) ✅ DONE
1. ✅ Create `design-tokens.css`
2. ✅ Import at top of popup.css and panel.css
3. ✅ No visual changes yet
4. ✅ **CHECKPOINT: Ask human to verify visually before proceeding**

### Step 1b: Regression Analysis - Background Layering ✅ DONE

**Problem discovered during Step 4 (scrollbar extraction):**

When migrating to shared variables, naive substitution caused visual regressions because the design token system lacks sufficient background layers for nested UI contexts.

**Current token layering (insufficient):**
| Token | Light | Dark | Usage |
|-------|-------|------|-------|
| `--color-bg-base` | #ffffff | #1e1e1e | Body, main content areas |
| `--color-bg-elevated` | #f8f9fa | #252526 | Headers, footers, sections |
| `--color-bg-input` | #ffffff | #2d2d2d | Form inputs, code areas |

**The problem:** When a container uses `--bg-elevated` and items inside also use `--bg-elevated` or `--bg-input` (which equals base in light theme), items don't pop.

**Specific regressions found:**
1. `.panel-footer` was changed to `--bg-primary` (base) - lost muted appearance
2. `.results-area` was changed to `--bg-secondary` (elevated) - result items blended in

**Root cause:** The original CSS used context-specific values that created visual hierarchy through deliberate contrast. Consolidating to fewer tokens loses this nuance.

**Solution: Add result-specific background tokens:**

```css
/* Result item backgrounds - ensure contrast against results-area */
--color-bg-result-input: var(--color-bg-base);   /* Light: white, Dark: base */
--color-bg-result-output: var(--color-bg-base);  /* Was --bg-secondary, needs contrast */
```

**Guiding principle for consolidation:**
- **Containers** (results-area, footer, header) use elevated backgrounds
- **Items within containers** (result-input, result-output) use base backgrounds to pop
- **Interactive elements** (buttons, inputs) use their own contextual tokens

**Action items:** ✅ DONE
1. ✅ Add `--color-bg-result-input` and `--color-bg-result-output` to design-tokens.css
2. ✅ Update panel.css result items to use these tokens
3. ✅ Keep `.results-area` with elevated background for section distinction
4. ✅ Verify visual hierarchy: base < elevated < items-pop-on-elevated

### Step 2: Migrate popup.css to new variables ✅ DONE
1. ✅ Replace old variable references with new tokens
2. ✅ Keep old variable declarations as aliases temporarily
3. ✅ Test visually

### Step 3: Migrate panel.css to new variables ✅ DONE
1. ✅ Same process as popup
2. ✅ Verify both views look consistent

### Step 4: Extract scrollbar styles with stability pattern ✅ DONE
1. ✅ Move scrollbar CSS to shared.css (already imported by both)
2. ✅ **Include popup's scrollbar-gutter stability pattern**
3. ✅ Create `.scrollable-stable` utility class
4. ✅ Apply to popup body and panel's scrollable container
5. ✅ Test scrollbar appear/disappear doesn't cause layout shift
6. ✅ Remove duplicates from popup.css and panel.css

### Step 5: Create components.css
1. Extract button styles
2. Extract form elements
3. Extract status indicators
4. Update HTML classes as needed

### Step 6: Create shared Hiccup components
1. Add `action-button` to view_elements.cljs
2. Add `status-indicator` to view_elements.cljs
3. Add `empty-state` to view_elements.cljs
4. Migrate popup.cljs and panel.cljs to use shared components
5. Ensure consistent prop naming (`:variant`, `:class`, etc.)

### Step 7: View-specific refinement
1. popup.css: Only popup-specific layout/sizing
2. panel.css: Only panel-specific layout/sizing
3. shared.css: All shared components

### Step 8: Cleanup
1. Remove duplicate code
2. Remove old variable aliases
3. Document the design system

---

## File Structure (Target)

```
extension/
├── design-tokens.css   # CSS variables / design tokens
├── components.css      # Reusable component styles
├── utilities.css       # Helper classes (optional)
├── shared.css          # Header, footer, shared layout
├── popup.css           # Popup-specific overrides only
└── panel.css           # Panel-specific overrides only
```

**HTML imports:**
```html
<!-- popup.html -->
<link rel="stylesheet" href="design-tokens.css">
<link rel="stylesheet" href="components.css">
<link rel="stylesheet" href="shared.css">
<link rel="stylesheet" href="popup.css">

<!-- panel.html -->
<link rel="stylesheet" href="design-tokens.css">
<link rel="stylesheet" href="components.css">
<link rel="stylesheet" href="shared.css">
<link rel="stylesheet" href="panel.css">
```

---

## Benefits

1. **Single source of truth** - Variables defined once
2. **Consistency** - Same components look the same everywhere
3. **Maintainability** - Change once, update everywhere
4. **Smaller files** - No duplication
5. **Framework-y** - Easy to add new views/components
6. **Theme support** - Dark/light handled consistently

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Visual regressions | Test each step manually, E2E tests exist |
| Breaking changes | Incremental migration with aliases |
| Over-engineering | Keep utilities minimal, prefer explicit classes |

---

## Testing Checklist

For each migration step:
- [ ] Popup light theme
- [ ] Popup dark theme
- [ ] Panel light theme
- [ ] Panel dark theme
- [ ] Firefox compatibility
- [ ] Chrome compatibility
- [ ] Collapsible sections expand/collapse
- [ ] Button hover states
- [ ] Input focus states
- [ ] Scrollbar appearance
- [ ] Status messages (success/error/warning)

### Scrollbar Stability Tests (Critical)
- [ ] Popup: Add/remove scripts - no horizontal layout shift
- [ ] Popup: Expand/collapse sections - content width stays constant
- [ ] Panel: Add evaluation results until scrollbar appears - no jump
- [ ] Panel: Clear results until scrollbar disappears - no jump
- [ ] Firefox popup: Same stability as Chrome
- [ ] Firefox panel: Same stability as Chrome

### Hiccup Component Tests
- [ ] Shared `action-button` renders correctly in popup
- [ ] Shared `action-button` renders correctly in panel
- [ ] Button variants (primary/secondary/success/danger) look consistent
- [ ] Status indicators show correct colors/icons
- [ ] Empty states display appropriately

---

## Notes

- The popup has a fixed 380px width constraint
- Panel fills DevTools panel space (flexible)
- Both support system theme detection
- Scrollbar styling differs between Chrome/Firefox
