# Feature: Eval Selection in DevTools Panel

**Created:** January 12, 2026
**Completed:** January 12, 2026
**Status:** Done

## Overview

Add "Eval Selection" functionality to the DevTools panel - evaluate only selected text in the code editor. This aligns with standard Clojure interactive programming practices where developers examine and redefine specific expressions.

## Design Summary

### User-Facing Changes

| Element | Current | After |
|---------|---------|-------|
| Ctrl+Enter | Evaluates entire script | Evaluates selection (or entire script if no selection) |
| Eval button | "Eval" text | Play icon + "Eval script" label |
| Shortcut hint | "Ctrl+Enter to eval" | "Ctrl+Enter evals selection" |

### Behavior

- **With selection:** Evaluate only the selected text
- **Without selection:** Fall back to evaluating the entire script
- Button always evaluates the entire script (labeled "Eval script" with play icon)
- Ctrl+Enter evaluates selection (or full script if no selection)

## Testing Strategy

**Unit tests cover the decision logic** - action handlers are pure functions that decide *what code* to pass to effects. We test:
- Selection present → effect receives selection text
- Selection empty → effect receives full code
- Guard conditions (empty, already evaluating)

**E2E tests cover the integration** - textarea selection API, keyboard events, UI updates.

The actual Scittle evaluation happens in browser context via `chrome.devtools.inspectedWindow.eval` - that's inherently E2E territory.

## Workflow

**ALWAYS act informed.** You start by investigating the testing docs and the existing tests to understand patterns and available fixture.

**ALWAYS use `bb <task>` over direct shell commands.** The bb tasks encode project-specific configurations. Check `bb tasks` for available commands.

**ALWAYS check lint/problem reports after edits.** Use `get_errors` tool to verify no syntax or bracket errors before running tests.

**ALWAYS use the `edit` subagent for file modifications.** The edit subagent specializes in Clojure/Squint structural editing and avoids bracket balance issues. Provide it with complete context: file paths, line numbers, and the exact changes needed.

- `bb test` - Compile and run unit tests
- `bb test:e2e` - Compile and run E2E tests (Docker)

## TDD Implementation Plan

### Phase 1: Unit Tests for New Action Handler

File: [test/panel_test.cljs](../../test/panel_test.cljs)

**Test 1: `:editor/ax.eval-selection` with selection evaluates selected code**

```clojure
(test "eval-selection action evaluates selected code when selection present"
      (^:async fn []
        (let [state {:panel/code "(+ 1 2)\n(* 3 4)"
                     :panel/selection {:start 8 :end 16 :text "(* 3 4)"}
                     :panel/evaluating? false
                     :panel/scittle-status :loaded
                     :panel/results []}
              result (handle-action state {} [:editor/ax.eval-selection])]
          ;; Should evaluate only selection, not full code
          (-> (expect (get-in result [:uf/fxs 0 1]))
              (.toEqual "(* 3 4)")))))
```

**Test 2: `:editor/ax.eval-selection` without selection evaluates full script**

```clojure
(test "eval-selection action evaluates full script when no selection"
      (^:async fn []
        (let [state {:panel/code "(+ 1 2)"
                     :panel/selection nil  ; or {:text ""}
                     :panel/evaluating? false
                     :panel/scittle-status :loaded
                     :panel/results []}
              result (handle-action state {} [:editor/ax.eval-selection])]
          ;; Should fall back to full code
          (-> (expect (get-in result [:uf/fxs 0 1]))
              (.toEqual "(+ 1 2)")))))
```

**Test 3: `:editor/ax.set-selection` updates selection state**

```clojure
(test "set-selection action updates selection state"
      (^:async fn []
        (let [state {:panel/selection nil}
              result (handle-action state {} [:editor/ax.set-selection {:start 0 :end 5 :text "(+ 1)"}])]
          (-> (expect (:panel/selection (:uf/db result)))
              (.toEqual {:start 0 :end 5 :text "(+ 1)"})))))
```

### Phase 2: Implement Action Handlers

File: [src/panel_actions.cljs](../../src/panel_actions.cljs)

1. Add `:editor/ax.set-selection` action to update selection state
2. Add `:editor/ax.eval-selection` action that:
   - Checks if selection has text
   - Uses selection text if present, otherwise full code
   - Otherwise delegates to same logic as `:editor/ax.eval`

### Phase 3: UI Component Updates

File: [src/panel.cljs](../../src/panel.cljs)

1. Add `:panel/selection` to state atom (initially nil)
2. Track textarea selection via `selectionchange` event listener
3. Update `code-input` component:
   - Add `on-select` handler to track selection changes
   - Change Ctrl+Enter to dispatch `:editor/ax.eval-selection`
   - Update button to show play icon + "Eval script" text
   - Update shortcut hint to "Ctrl+Enter evals selection"

### Phase 4: E2E Tests

File: [e2e/panel_test.cljs](../../e2e/panel_test.cljs)

**Test: Selection evaluation workflow**

```clojure
(test "Panel: Ctrl+Enter evaluates selection when text is selected"
      (^:async fn []
        ;; 1. Enter code with multiple expressions
        ;; 2. Select a portion of code
        ;; 3. Press Ctrl+Enter
        ;; 4. Verify only selected code appears in results
        ;; 5. Click Eval script button
        ;; 6. Verify full script is evaluated
        ))
```

**Test: Button label and icon**

```clojure
(test "Panel: Eval button shows play icon and 'Eval script' label"
      (^:async fn []
        ;; Verify button has play icon SVG
        ;; Verify button text is "Eval script"
        ))
```

### Phase 5: Documentation Updates

1. [dev/docs/ui.md](ui.md) - Update Script Editor section
2. [dev/docs/architecture.md](architecture.md) - Add new action to Panel Actions table

## Implementation Checklist

- [x] Write unit tests (Phase 1)
- [x] Implement action handlers (Phase 2)
- [x] Update UI components (Phase 3)
- [x] Write E2E tests (Phase 4)
- [x] Update documentation (Phase 5)
- [x] Verify unit tests pass (`bb test`) - 273 tests pass
- [x] Verify E2E tests pass (`bb test:e2e`) - 52 tests pass
- [ ] Manual testing with real extension

## State Changes

### panel.cljs !state additions

```clojure
:panel/selection nil  ; {:start int :end int :text string} or nil
```

### New Actions

| Action | Args | Purpose |
|--------|------|---------|
| `:editor/ax.set-selection` | `[{:start :end :text}]` | Track current textarea selection |
| `:editor/ax.eval-selection` | - | Evaluate selection if present, else full script |

## UI Changes

### Button Component

```clojure
;; Before
[:button.btn-eval {:on-click ...} button-text]

;; After
[:button.btn-eval {:on-click #(dispatch! [[:editor/ax.eval]])}
  [icons/play {:size 14}]
  " Eval script"]
```

### Keyboard Handler

```clojure
;; Before
(when (and (or (.-ctrlKey e) (.-metaKey e))
           (= "Enter" (.-key e)))
  (dispatch! [[:editor/ax.eval]]))

;; After
(when (and (or (.-ctrlKey e) (.-metaKey e))
           (= "Enter" (.-key e)))
  (dispatch! [[:editor/ax.eval-selection]]))
```

### Shortcut Hint

```clojure
;; Before
[:span.shortcut-hint "Ctrl+Enter to eval"]

;; After
[:span.shortcut-hint "Ctrl+Enter evals selection"]
```

## Technical Notes

### Selection Tracking

The textarea selection can be tracked via the `selectionchange` document event or `select` event on the textarea. We need to capture:
- `selectionStart` - start index
- `selectionEnd` - end index
- Selected text (derived from code using indices)

### Edge Cases

1. **Empty selection** (cursor position) - `start === end`, treat as no selection
2. **Whitespace-only selection** - Evaluate it (let Scittle handle errors)
3. **Selection after code change** - Selection state may be stale; always re-read from textarea
4. **Loading/evaluating state** - Same disabled logic as current eval

## Related Files

- [src/panel.cljs](../../src/panel.cljs) - Main panel UI
- [src/panel_actions.cljs](../../src/panel_actions.cljs) - Action handlers
- [test/panel_test.cljs](../../test/panel_test.cljs) - Unit tests (if they exist)
- [e2e/panel_test.cljs](../../e2e/panel_test.cljs) - E2E tests
- [extension/panel.css](../../extension/panel.css) - Styles
- [src/icons.cljs](../../src/icons.cljs) - Icon components (has `play` icon)
