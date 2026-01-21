# CSS Architecture

Epupp uses a layered CSS architecture following SMACSS/ITCSS conventions.

## File Structure

```
extension/
├── design-tokens.css   # CSS custom properties (variables)
├── components.css      # Reusable UI component styles
├── base.css            # Resets, scrollbars, layout scaffolding
├── popup.css           # Popup-specific styles
└── panel.css           # Panel-specific styles
```

**Import order matters** - each layer builds on the previous:

```html
<link rel="stylesheet" href="design-tokens.css">
<link rel="stylesheet" href="components.css">
<link rel="stylesheet" href="base.css">
<link rel="stylesheet" href="popup.css">  <!-- or panel.css -->
```

## Layer Responsibilities

| Layer | File | Purpose |
|-------|------|---------|
| Tokens | `design-tokens.css` | Variables: colors, spacing, typography, theming |
| Components | `components.css` | Reusable UI widgets: buttons, badges, status bars, cards |
| Base | `base.css` | Reset, scrollbars, app header/footer layout |
| View | `popup.css`, `panel.css` | Layout and styles specific to each view |

## Design Tokens

All colors, spacing, and typography are defined as CSS custom properties in `design-tokens.css`.

### Naming Convention

```css
--color-{semantic}-{variant}   /* Colors */
--space-{size}                 /* Spacing: xs, sm, md, lg, xl */
--radius-{size}                /* Border radius: sm, md */
--font-{type}                  /* Typography: sans, mono */
--font-size-{size}             /* Font sizes: xs, sm, base, md */
```

### Key Semantic Colors

| Token | Purpose |
|-------|---------|
| `--color-text-primary` | Main text |
| `--color-text-secondary` | Muted text |
| `--color-bg-base` | Page background |
| `--color-bg-elevated` | Headers, footers, sections |
| `--color-bg-input` | Form inputs, code areas |
| `--color-border` | Default borders |
| `--color-success` | Success states |
| `--color-error` | Error states |

### Theming

Light/dark themes are handled via `prefers-color-scheme`:

```css
:root {
  /* Light theme defaults */
  --color-bg-base: #ffffff;
}

@media (prefers-color-scheme: dark) {
  :root {
    --color-bg-base: #1e1e1e;
  }
}
```

## Component Classes

### Buttons

```css
.btn                    /* Base button */
.btn-primary            /* Blue action (Connect, Eval) */
.btn-secondary          /* Ghost/outline (Clear, Copy) */
.btn-success            /* Green (Save, Allow) */
.btn-danger             /* Red (Delete, Deny) */
.btn-icon               /* Icon-only button */
```

### Status Indicators

```css
.status-bar             /* Left-border indicator container */
.status-bar--success    /* Green left border */
.status-bar--error      /* Red left border */
.status-bar--warning    /* Amber left border */

.status-text            /* Inline status message */
.status-text--success   /* Green text */
.status-text--error     /* Red text */
```

### Badges

```css
.badge                  /* Small pill badge */
.badge-count            /* Numeric badge */
.run-at-badge           /* Timing badge (document-start, etc.) */
```

### Cards

```css
.card                   /* Elevated container with border */
.card-subtle            /* Muted background */
.card-dashed            /* Dashed border for empty states */
```

## Shared Hiccup Components

CSS is consumed by Hiccup components in `view_elements.cljs`. Prefer these over raw class usage:

### `action-button`

```clojure
(action-button {:button/variant :primary    ; :primary, :secondary, :success, :danger
                :button/size :small         ; :small (default), :large
                :button/icon [some-icon]    ; optional icon component
                :button/disabled? false
                :button/class "extra-class" ; additional CSS class
                :button/on-click handler}
               "Label")
```

### `status-text`

```clojure
(status-text {:status/type :success}  ; :success, :error, :warning, :info
             "Operation completed")
```

### `empty-state`

```clojure
(empty-state {:empty/title "No results"
              :empty/message "Run some code to see results"
              :empty/icon [some-icon]})
```

## Where to Add Styles

Decision tree for new styles:

1. **Is it a color, spacing, or typography value?**
   → Add to `design-tokens.css`

2. **Is it a reusable UI widget (button, badge, card)?**
   → Add to `components.css`

3. **Is it shared layout scaffolding (scrollbars, header/footer)?**
   → Add to `base.css`

4. **Is it specific to popup or panel layout?**
   → Add to `popup.css` or `panel.css`

5. **Should there be a Hiccup component?**
   → Add to `view_elements.cljs` with corresponding CSS

## Background Layering

When nesting containers, maintain visual hierarchy:

| Context | Use Token |
|---------|-----------|
| Page body | `--color-bg-base` |
| Headers, footers, section wrappers | `--color-bg-elevated` |
| Items inside elevated containers | `--color-bg-base` (to pop) |
| Form inputs | `--color-bg-input` |

This ensures items have contrast against their containers.

## Related

- [Migration history](../css-consolidation-plan.md) - How we got here
- [Components source](components.md) - ClojureScript source file map
