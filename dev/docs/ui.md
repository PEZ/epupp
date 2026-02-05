# UI Architecture Guide

This guide orients developers to quickly understand, navigate, and make surgical changes to Epupp's UI layer using TDD workflows.

## Quick Reference

| What you need | Where to look |
|--------------|---------------|
| Popup logic & state | [src/popup.cljs](../../src/popup.cljs), [src/popup_actions.cljs](../../src/popup_actions.cljs) |
| Panel logic & state | [src/panel.cljs](../../src/panel.cljs), [src/panel_actions.cljs](../../src/panel_actions.cljs) |
| Shared components | [src/view_elements.cljs](../../src/view_elements.cljs) |
| Event system core | [src/event_handler.cljs](../../src/event_handler.cljs) |
| Icons | [src/icons.cljc](../../src/icons.cljc) |
| Styling | [extension/design-tokens.css](../../extension/design-tokens.css), [css-architecture.md](architecture/css-architecture.md) |
| Unit tests | [test/popup_actions_test.cljs](../../test/popup_actions_test.cljs), [test/panel_test.cljs](../../test/panel_test.cljs) |
| E2E tests | [e2e/popup_*.cljs](../../e2e/), [e2e/panel_*.cljs](../../e2e/) |

## Architecture Overview

Epupp's UI uses a **Uniflow** pattern - a minimal, Re-frame-inspired unidirectional data flow:

```
User Event → dispatch!([actions]) → handle-action (pure) → state update → effects
                                          ↓
                                    add-watch triggers render
```

### Key Architectural Decisions

1. **Action handlers are pure** - All decision logic lives in `*_actions.cljs` files, which have zero browser dependencies and are fully unit-testable.

2. **Effects are impure** - Side effects (Chrome APIs, storage, DOM) live in the main UI files (`popup.cljs`, `panel.cljs`) inside `perform-effect!`.

3. **State drives rendering** - A single atom per view, watched via `add-watch` to trigger re-renders.

4. **Reactive, source-agnostic updates** - Popup and panel subscribe to storage changes and refresh regardless of origin (REPL FS, panel edits, popup edits, background).

5. **Reagami for hiccup** - A minimal React-like library (not Reagent) for rendering hiccup vectors to DOM.

## File Organization

### Per-View Pattern

Each view (popup, panel) follows this structure:

```
src/popup.cljs          # View: components, effects, render, init
src/popup_actions.cljs  # Pure: action handlers (no browser deps)
src/popup_utils.cljs    # Pure: helper functions

test/popup_actions_test.cljs  # Unit tests for actions
e2e/popup_*.cljs              # E2E tests for full workflows
```

### The Split Explained

| File | Contains | Dependencies | Testable |
|------|----------|--------------|----------|
| `popup.cljs` | Components, effects, init | Chrome APIs, DOM | E2E only |
| `popup_actions.cljs` | Action handlers | None | Unit tests |
| `popup_utils.cljs` | Pure helpers | None | Unit tests |

This separation enables fast TDD cycles on business logic without browser mocking.

## State Management

### Popup State

```clojure
{:ports/nrepl "1339"
 :ports/ws "1340"
 :ui/connect-status nil           ; Connection progress/error
 :ui/sections-collapsed {...}      ; Collapsible section states
 :scripts/list []                  ; All userscripts
 :scripts/current-url nil          ; For pattern matching
 :repl/connections []              ; Active REPL connections
 :settings/auto-connect-repl false ; Auto-connect setting
 ...}
```

### Panel State

```clojure
{:panel/code ""                   ; Editor content
 :panel/results []                ; Eval results history
 :panel/evaluating? false         ; Loading state
 :panel/scittle-status :unknown   ; :checking, :loading, :loaded
 :panel/script-name ""            ; From manifest
 :panel/script-match ""           ; From manifest
 :panel/manifest-hints nil        ; Parsed manifest metadata
 :panel/selection nil             ; Text selection for partial eval
 ...}
```

## Uniflow Actions and Effects

### Action Naming Convention

```
:domain/ax.verb-noun
```

Examples:
- `:popup/ax.connect` - Initiate REPL connection
- `:editor/ax.set-code` - Update editor content
- `:db/ax.assoc` - Generic state update (built-in)

### Effect Naming Convention

```
:domain/fx.verb-noun
```

Examples:
- `:popup/fx.save-ports` - Persist ports to storage
- `:editor/fx.eval-in-page` - Execute code in page context

### Action Handler Pattern

```clojure
(defn handle-action [state uf-data [action & args]]
  (case action
    :popup/ax.set-nrepl-port
    (let [[port] args]
      {:uf/db (assoc state :ports/nrepl port)
       :uf/fxs [[:popup/fx.save-ports (select-keys state [:ports/nrepl :ports/ws])]]})

    :uf/unhandled-ax))  ; Delegate to generic handler
```

**Return keys:**
- `:uf/db` - New state (triggers re-render)
- `:uf/fxs` - Effects to execute
- `:uf/dxs` - Follow-up actions to dispatch
- `nil` - Explicit no-op
- `:uf/unhandled-ax` - Delegate to generic handler

### Dispatching Actions

```clojure
;; Single action
(dispatch! [[:editor/ax.clear-results]])

;; Batched actions (processed in order, each sees updated state)
(dispatch! [[:editor/ax.set-code "new code"]
            [:editor/ax.eval]])
```

## Component Patterns

### Reagami Basics

Components are functions returning hiccup vectors:

```clojure
(defn greeting [{:keys [name]}]
  [:div.greeting
   [:h1 "Hello, " name "!"]])

;; Event handlers
[:button {:on-click #(dispatch! [[:some/ax.action]])} "Click"]

;; Conditional rendering
(when visible?
  [:div.content "Shown"])

;; Lists with keys
(for [item items]
  ^{:key (:id item)}
  [:li (:name item)])
```

### Shared Components (`view_elements.cljs`)

Use these instead of raw CSS classes:

```clojure
;; Buttons
[view-elements/action-button
 {:button/variant :primary    ; :primary, :secondary, :success, :danger
  :button/icon icons/play
  :button/disabled? loading?
  :button/on-click handler}
 "Label"]

;; Status text
[view-elements/status-text
 {:status/type :success}      ; :success, :error
 "Saved!"]

;; Empty state
[view-elements/empty-state {:empty/class "no-results"}
 "No results yet"]
```

## TDD Workflow

### Unit Testing Actions

Action handlers are pure functions - test them directly:

```clojure
(test ":popup/ax.set-nrepl-port updates port and triggers save"
  (fn []
    (let [result (popup-actions/handle-action initial-state uf-data
                                               [:popup/ax.set-nrepl-port "12345"])]
      ;; Verify state change
      (-> (expect (:ports/nrepl (:uf/db result)))
          (.toBe "12345"))
      ;; Verify effect triggered
      (-> (expect (first (first (:uf/fxs result))))
          (.toBe :popup/fx.save-ports)))))
```

**Run unit tests:**
```bash
bb test           # Run once
bb test:watch     # Watch mode for TDD
```

### E2E Testing UI Flows

E2E tests verify full user journeys:

```clojure
(test "Popup: script management workflow"
  (^:async fn []
    (let [context (js-await (launch-browser))
          ext-id (js-await (get-extension-id context))]
      (try
        (let [popup (js-await (create-popup-page context ext-id))]
          ;; Interact with real UI
          (js-await (.fill popup "#nrepl-port" "9999"))
          (js-await (.click popup "button:has-text(\"Connect\")"))
          ;; Assert visible state
          (js-await (-> (expect (.locator popup ".status"))
                        (.toContainText "Connected")))
          (js-await (assert-no-errors! popup)))
        (finally
          (js-await (.close context)))))))
```

**Run E2E tests:**
```bash
bb test:e2e                    # Parallel in Docker (~16s)
bb test:e2e -- --grep "popup"  # Filter by pattern
```

### TDD Cycle

1. **Write failing unit test** for the action handler
2. **Run `bb test:watch`** to see it fail
3. **Implement action** in `*_actions.cljs`
4. **Verify test passes**
5. **Add effect implementation** in main UI file if needed
6. **Write E2E test** for the full flow
7. **Run `bb test:e2e`** to verify integration

## Making Surgical Changes

### Adding a New Feature

1. **Design the state shape** - What data needs to be tracked?
2. **Write action tests first** - Define expected state transitions
3. **Implement actions** - Pure logic in `*_actions.cljs`
4. **Add effect** - Side effects in `perform-effect!`
5. **Add component** - UI in main file or `view_elements.cljs`
6. **Write E2E test** - Full user journey

### Modifying Existing Behavior

1. **Find the action** - Search for `:domain/ax.action-name`
2. **Read existing tests** - Understand current behavior
3. **Update tests first** - TDD: change expected behavior
4. **Modify action handler** - Make tests pass
5. **Update E2E if needed** - Verify integration

### Adding Styles

See [css-architecture.md](architecture/css-architecture.md) for the layered CSS system:

1. Colors/spacing → `design-tokens.css`
2. Reusable widgets → `components.css`
3. View-specific → `popup.css` or `panel.css`

## Key Files Reference

### Source Files

| File | Purpose |
|------|---------|
| `src/popup.cljs` | Popup view: components, effects, initialization |
| `src/popup_actions.cljs` | Popup action handlers (pure, testable) |
| `src/popup_utils.cljs` | Popup helper functions |
| `src/panel.cljs` | Panel view: components, effects, initialization |
| `src/panel_actions.cljs` | Panel action handlers (pure, testable) |
| `src/view_elements.cljs` | Shared UI components |
| `src/event_handler.cljs` | Uniflow dispatch system |
| `src/icons.cljc` | SVG icon components |

### Test Files

| File | Tests |
|------|-------|
| `test/popup_actions_test.cljs` | Popup action state transitions |
| `test/panel_test.cljs` | Panel action state transitions |
| `test/popup_utils_test.cljs` | Popup utility functions |
| `e2e/popup_core_test.cljs` | REPL setup, scripts, settings |
| `e2e/popup_connection_test.cljs` | Connection tracking |
| `e2e/popup_autoconnect_test.cljs` | Auto-connect behavior |
| `e2e/panel_eval_test.cljs` | Evaluation workflows |
| `e2e/panel_save_test.cljs` | Save/create/rename |
| `e2e/panel_state_test.cljs` | Panel initialization |

## Related Documentation

- [uniflow.md](architecture/uniflow.md) - Full Uniflow event system docs
- [state-management.md](architecture/state-management.md) - State atoms and action tables
- [css-architecture.md](architecture/css-architecture.md) - CSS layering and tokens
- [testing.md](testing.md) - Testing philosophy overview
- [testing-e2e.md](testing-e2e.md) - E2E test patterns and fixtures
