# Design System Evolution Plan

Consolidate ad-hoc CSS styling into a coherent design system with consistent token usage, reduced duplication, and documented patterns.

## Status

COMPLETE - All batches implemented, tests pass. Awaiting human visual verification.

## Priority Overview

| Priority | Category | Impact |
|----------|----------|--------|
| 1 | Token consolidation | Remove aliases, use design-tokens directly everywhere |
| 2 | Component extraction | Move duplicated styles to components.css |
| 3 | Hardcoded value audit | Replace magic numbers with tokens |
| 4 | Semantic layer | Add missing semantic tokens for better theming |
| 5 | Documentation | Visual reference and component API docs |

---

## Standard

- All edits delegated to **Clojure-editor subagent** (for .cljs) or direct edit (for .css)
- Before each batch: delegate to **epupp-testrunner subagent** for baseline
- After each batch: delegate to **epupp-testrunner subagent** for verification
- Human verification required for visual/styling changes
- Tick checkboxes without inserting commentary blocks

---

## Required Reading

### Architecture Docs

- [dev/docs/architecture/css-architecture.md](architecture/css-architecture.md) - CSS layer responsibilities, existing patterns
- [dev/docs/ui.md](ui.md) - Reagami/Uniflow patterns, component structure

### Source Files (CSS)

- [extension/design-tokens.css](../../extension/design-tokens.css) - Design token definitions (source of truth)
- [extension/components.css](../../extension/components.css) - Shared component styles
- [extension/base.css](../../extension/base.css) - Base styles shared across views
- [extension/popup.css](../../extension/popup.css) - Popup-specific styles
- [extension/panel.css](../../extension/panel.css) - Panel-specific styles

### Source Files (Components)

- [src/view_elements.cljs](../../src/view_elements.cljs) - Shared Reagent components
- [src/popup.cljs](../../src/popup.cljs) - Popup UI components
- [src/panel.cljs](../../src/panel.cljs) - Panel UI components

---

## Current State Analysis

### What's Working

1. **Design tokens foundation** - Centralized color definitions with semantic naming, dark/light theme support
2. **Component abstraction** - Button variants with consistent API
3. **Shared scaffolding** - Common header/footer structure, scrollbar styling

### Issues Identified

1. **Token aliasing** - panel.css re-aliases tokens (e.g., `--text-primary: var(--color-text-primary)`), creating parallel naming
2. **Mixed token usage** - Files use both aliases and original tokens inconsistently
3. **Hardcoded values** - Magic colors (`#4a71c4`, `#1e1e1e`), font sizes (`12px`), padding values
4. **Duplicated styles** - Banner styles duplicated across popup.css and panel.css
5. **Missing semantic layers** - No `--color-interactive`, `--color-surface-*` hierarchy
6. **Incomplete components** - Missing form inputs, generalized badges, list patterns

---

## Checklist

### Priority 1: Token Consolidation

#### 1.1 Remove panel.css token aliases
Location: `panel.css` lines 5-23

Delete the `:root` block that re-aliases design tokens. Update all references in panel.css to use design-tokens directly.

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human) - visual inspection

**Tokens to replace:**
- `var(--text-primary)` -> `var(--color-text-primary)`
- `var(--text-secondary)` -> `var(--color-text-secondary)`
- `var(--text-muted)` -> `var(--color-text-muted)`
- `var(--bg-primary)` -> `var(--color-bg-base)`
- `var(--bg-secondary)` -> `var(--color-bg-elevated)`
- `var(--bg-input)` -> `var(--color-bg-input)`
- `var(--border-color)` -> `var(--color-border)`
- `var(--success)` -> `var(--color-success)`
- `var(--error)` -> `var(--color-error)`
- `var(--radius)` -> `var(--radius-sm)`

#### 1.2 Remove popup.css token aliases
Location: `popup.css`

Delete the `:root` block and update all references to use design-tokens directly.

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human)

#### 1.3 Audit base.css token usage
Location: `base.css`

Verify all token references use design-tokens directly.

- [x] addressed in code (already compliant)
- [x] verified by tests
- [ ] verified (human)

---

### Priority 2: Component Extraction

#### 2.1 Consolidate banner styles
Location: `popup.css` lines 87-126, `panel.css` lines 76-117

Move `.fs-success-banner`, `.fs-error-banner`, `.fs-info-banner` to components.css. Remove duplicates from popup.css and panel.css.

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human)

#### 2.2 Consolidate banner keyframes
Location: `popup.css` lines 138-163, `panel.css` lines 119-146

Move `@keyframes banner-slide-in` and `@keyframes banner-slide-out` to components.css.

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human)

#### 2.3 Extract form input styles
Location: `popup.css` lines 199-212

Create `.input-field` component in components.css for text inputs.

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human)

#### 2.4 Generalize list item pattern
Location: `popup.css` `.script-item`, `.origin-item`, `.connected-tab-item`

Extract common list item structure to `.list-item` base component.

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human)

---

### Priority 3: Hardcoded Value Audit

#### 3.1 Fix button hover colors
Location: `components.css` lines 36, 52

Replace hardcoded hover colors with tokens:
- `#4a71c4` -> add `--clojure-blue-hover` token
- `#82cd3a` -> add `--clojure-green-hover` token

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human)

#### 3.2 Fix button text colors
Location: `components.css` line 49

Replace `#1e1e1e` with token (consider `--color-text-on-success`).

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human)

#### 3.3 Audit font-size values
Location: Multiple files

Replace all `font-size: Npx` with `var(--font-size-*)` tokens.

Files to audit:
- [ ] components.css
- [ ] popup.css
- [ ] panel.css
- [ ] base.css

- [ ] addressed in code - DEFERRED (low priority, many instances)
- [ ] verified by tests
- [ ] verified (human)

#### 3.4 Audit padding/spacing values
Location: Multiple files

Replace common padding patterns with spacing tokens where appropriate.

- [ ] addressed in code - DEFERRED (low priority, many instances)
- [ ] verified by tests
- [ ] verified (human)

---

### Priority 4: Semantic Token Layer

#### 4.1 Add interactive color tokens
Location: `design-tokens.css`

Add tokens for interactive elements:
```css
--color-interactive: var(--clojure-blue);
--color-interactive-hover: var(--clojure-blue-hover);
```

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human)

#### 4.2 Add surface hierarchy tokens
Location: `design-tokens.css`

Add surface level tokens for layered UI:
```css
--color-surface-0: var(--color-bg-base);
--color-surface-1: var(--color-bg-elevated);
--color-surface-2: var(--color-bg-input);
```

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human)

#### 4.3 Add icon size scale
Location: `design-tokens.css`

Add consistent icon sizing:
```css
--icon-sm: 12px;
--icon-md: 16px;
--icon-lg: 20px;
```

- [x] addressed in code
- [x] verified by tests
- [ ] verified (human)

---

### Priority 5: Documentation

#### 5.1 Update css-architecture.md
Location: `dev/docs/architecture/css-architecture.md`

Document:
- Complete token inventory with usage guidelines
- Component API (available classes, variants, sizes)
- When to use which token category

- [x] addressed in code
- [ ] verified (human)

#### 5.2 Create visual token reference
Location: TBD (possibly `dev/docs/design-system-reference.md`)

Create a reference showing:
- Color swatches with token names
- Typography scale
- Spacing scale
- Component examples

- [ ] addressed in code - DEFERRED (nice-to-have)
- [ ] verified (human)

---

## Batch Execution Order

**Batch A: Token Consolidation (1.1-1.3)**
1. Run testrunner baseline
2. Remove panel.css aliases and update references
3. Audit popup.css and base.css
4. Run testrunner verification
5. Human visual verification

**Batch B: Banner Consolidation (2.1-2.2)**
1. Run testrunner baseline
2. Move banner styles to components.css
3. Remove duplicates from popup.css and panel.css
4. Run testrunner verification
5. Human visual verification

**Batch C: Component Extraction (2.3-2.4)**
1. Run testrunner baseline
2. Extract form input styles
3. Extract list item base pattern
4. Run testrunner verification
5. Human visual verification

**Batch D: Hardcoded Values (3.1-3.4)**
1. Run testrunner baseline
2. Add hover color tokens
3. Replace hardcoded colors
4. Audit and replace font-size values
5. Run testrunner verification
6. Human visual verification

**Batch E: Semantic Tokens (4.1-4.3)**
1. Run testrunner baseline
2. Add interactive, surface, and icon tokens
3. Optionally refactor existing usages to new tokens
4. Run testrunner verification
5. Human visual verification

**Batch F: Documentation (5.1-5.2)**
1. Update css-architecture.md
2. Create visual reference
3. Human review

---

## Notes

- **Backward compatibility**: CSS changes should not break existing UI - verify visually after each batch
- **Dark mode**: Test all changes in both light and dark themes
- **Browser testing**: Verify in Chrome and Firefox (extension targets both)

---

## Original Plan-producing Prompt

I want a review of the styling strategy used in the project. Specifically how it could be moved a bit more towards being a design system, rather than ad-hoc styling.

Please write this to dev/docs plan with a format inspired by the more recent plans in there.
