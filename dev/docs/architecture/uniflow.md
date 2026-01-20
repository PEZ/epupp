# Uniflow Event System

Uniflow is a minimal, Re-frame-inspired unidirectional data flow system for Squint/ClojureScript. Unlike Re-frame, it's intentionally small and meant to be adapted per project. Recreate it to fit like a glove.

## Data Flow

```
UI event → dispatch!([actions]) → handle-actions → state update → dxs dispatch → effects
```

## Core Concepts

### Actions (ax) - Pure State Transitions

Actions are pure functions that take current state and return new state (plus optional effects/follow-up actions). **Actions decide, effects execute.** Keep decision logic in actions where it can be tested.

```clojure
(defn handle-action [state uf-data [action & args]]
  (case action
    :editor/ax.set-code
    (let [[code] args]
      {:uf/db (assoc state :panel/code code)})

    :uf/unhandled-ax))  ; Fall back to generic handler
```

**Return values:**
- **Map with keys** - `:uf/db` (new state), `:uf/fxs` (effects, optionally with `:uf/await`), `:uf/dxs` (follow-up actions)
- **`nil`** - Explicit no-op ("I decided to do nothing"). The framework skips state/effect updates.
- **`:uf/unhandled-ax`** - Delegate to generic handler

```clojure
;; Conditional no-op pattern
:editor/ax.eval
(let [code (:panel/code state)]
  (if (empty? code)
    nil  ; Nothing to do - return nil
    {:uf/db (assoc state :panel/evaluating? true)
     :uf/fxs [[:editor/fx.eval-in-page code]]}))
```

### Effects (fx) - Side Effects

Effects execute decisions made by actions: API calls, storage, DOM manipulation. **Keep decision logic in actions** - effects should be straightforward execution.

```clojure
(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :editor/fx.save-script
    (let [[script] args]
      (storage/save-script! script))

    :uf/unhandled-fx))  ; Fall back to generic handler
```

Effects receive `dispatch` for async callbacks that need to trigger more actions.

### Async Effects (Squint)

In Squint, effects often need async/await for Chrome APIs. Mark the effect handler with `^:async` and use `js-await`:

```clojure
(defn ^:async perform-effect! [dispatch [effect & args]]
  (case effect
    :popup/fx.connect
    (let [[port] args
          tab (js-await (get-active-tab))]
      (js/chrome.runtime.sendMessage
       #js {:type "connect-tab" :tabId (.-id tab) :wsPort port}
       (fn [response]
         (dispatch [[:db/ax.assoc :ui/status (if (.-success response) "Connected!" "Failed")]]))))

    :uf/unhandled-fx))
```

The callback-based `dispatch` bridges async completion back to the action system.

### Unified Effect Execution with `:uf/await`

Effects in `:uf/fxs` execute in declared order. By default, effects are fire-and-forget. To await an effect and optionally chain its result to subsequent effects, wrap it with the `:uf/await` sentinel.

**Syntax:**
```clojure
;; Fire-and-forget (default)
[:fx.name arg1 arg2]

;; Awaited effect
[:uf/await :fx.name arg1 arg2]
```

**Result threading with `:uf/prev-result`:**

When an awaited effect returns a value, subsequent effects can access it via `:uf/prev-result`:

```clojure
{:uf/fxs [[:msg/fx.clear-pending-approval script-id pattern]        ; fire-and-forget
          [:uf/await :msg/fx.get-data script-id]                    ; await, returns data
          [:uf/await :msg/fx.process-data :uf/prev-result]]}        ; receives previous result
```

The `:uf/prev-result` placeholder is substituted with the return value of the most recent awaited effect. Fire-and-forget effects don't update `:uf/prev-result`.

**Recipe-style actions:**

This pattern enables "recipe-style" actions - declarative sequences where each step flows into the next:

```clojure
:msg/ax.pattern-approved
(let [[script-id pattern] args]
  {:uf/fxs [[:msg/fx.clear-pending-approval script-id pattern]
            [:uf/await :msg/fx.get-pattern-approved-data script-id]
            [:uf/await :msg/fx.execute-approved-script :uf/prev-result]]})
```

This reads as: "Clear the pending approval, then get the approved data, then execute the script with that data."

**Benefits:**
- Actions read like recipes with clear intent
- Shallow event chains - no need for intermediate callback actions
- Effects behave like function calls with return values
- Single list maintains declaration order

**Error handling:** The effect sequence is wrapped in try/catch. If any awaited effect throws, execution stops and the error propagates.

### Dispatching

```clojure
;; Dispatch batched actions (processed in order, each sees updated state)
(dispatch! [[:editor/ax.set-code "hello"]
            [:editor/ax.eval]])

;; Single action works too
(dispatch! [[:editor/ax.clear-results]])
```

## Naming Conventions

| Type | Pattern | Example |
|------|---------|---------|
| Actions | `:domain/ax.verb` | `:editor/ax.set-code`, `:db/ax.assoc` |
| Effects | `:domain/fx.verb` | `:editor/fx.eval-in-page`, `:uf/fx.defer-dispatch` |
| Framework | `:uf/...` | `:uf/db`, `:uf/fxs`, `:uf/dxs`, `:uf/unhandled-ax` |

## Framework Context (`uf-data`)

Actions receive `uf-data` with framework-provided context:

```clojure
{:system/now 1735920000000}  ; Current timestamp
```

Extend with project-specific data as needed.

## Subsystem Setup Pattern

Each module defines its own handlers and creates a local `dispatch!`:

```clojure
(ns my-module
  (:require [event-handler :as event-handler]))

(defonce !state (atom {:my/value ""}))

(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :my/fx.do-thing (do-thing! (first args))
    :uf/unhandled-fx))

(defn handle-action [state uf-data [action & args]]
  (case action
    :my/ax.set-value {:uf/db (assoc state :my/value (first args))}
    :uf/unhandled-ax))

(defn dispatch! [actions]
  (event-handler/dispatch! !state handle-action perform-effect! actions))
```

## Separating Actions for Testability

For testable code, separate pure action handlers into their own module (no browser dependencies):

```clojure
;; my_module_actions.cljs - Pure, testable
(ns my-module-actions)

(defn handle-action [state uf-data [action & args]]
  (case action
    :my/ax.set-value {:uf/db (assoc state :my/value (first args))}
    :uf/unhandled-ax))
```

```clojure
;; my_module.cljs - Has browser dependencies
(ns my-module
  (:require [event-handler :as event-handler]
            [my-module-actions :as actions]))

(defonce !state (atom {:my/value ""}))

(defn ^:async perform-effect! [dispatch [effect & args]]
  (case effect
    :my/fx.load-data
    (let [data (js-await (fetch-from-api))]
      (dispatch [[:db/ax.assoc :my/data data]]))
    :uf/unhandled-fx))

(defn dispatch! [actions]
  (event-handler/dispatch! !state actions/handle-action perform-effect! actions))
```

This pattern keeps decision logic testable without mocking Chrome APIs.

## Background FS Usage

The background worker applies the same Uniflow pattern for REPL FS write
operations. Message handlers in `background.cljs` gate requests and dispatch
`[:fs/ax.* ...]` actions through `bg-fs-dispatch/dispatch-fs-action!`, which
invokes the pure decision logic in `background-actions/handle-action` and
executes `:uf/fxs` effects for persistence and responses. This is a scoped
Uniflow usage focused on script mutation decisions.

## Generic Handlers

Return `:uf/unhandled-ax` or `:uf/unhandled-fx` to delegate to generic handlers in `event-handler.cljs`:

**Built-in actions:**
- `:db/ax.assoc` - `(dispatch! [[:db/ax.assoc :key value :key2 value2]])`

**Built-in effects:**
- `:uf/fx.defer-dispatch` - `[:uf/fx.defer-dispatch [actions] timeout-ms]`
- `:log/fx.log` - `[:log/fx.log :error "message" data]`

## When to Use What

| Scenario | Use |
|----------|-----|
| Pure state change | Action returning `:uf/db` |
| Fire-and-forget effects | `[:fx.name ...]` in `:uf/fxs` |
| Await effect completion | `[:uf/await :fx.name ...]` in `:uf/fxs` |
| Chain effect results | `:uf/prev-result` placeholder in subsequent effect |
| Sync follow-up actions | Action returning `:uf/dxs` |
| Conditional no-op | Action returning `nil` |
| Async callback needs dispatch | Effect with `dispatch` parameter |
| Delayed dispatch | `:uf/fx.defer-dispatch` effect |

## Key Principles

1. **Actions are pure** - Given same state + uf-data + action, same result
2. **Effects are impure** - Side effects, async ops, external APIs
3. **Actions decide, effects execute** - Factor decision logic into actions for testability
4. **Batch for composition** - Small actions, combine in dispatch calls
5. **Fallback for reuse** - Generic handlers via `:uf/unhandled-*`

## Current Limitations

**Cross-subsystem calls not supported.** Each module has its own handlers; one subsystem cannot dispatch actions handled by another. The generic fallback (`:uf/unhandled-*`) provides shared utilities, but not cross-module communication.

If the app grows to need this, consider adding a registry pattern where subsystems register their handlers. Adapt Uniflow to fit - that's the point.

## Related

- [state-management.md](state-management.md) - State atoms and action/effect tables per module
- [components.md](components.md) - Source file map showing where Uniflow is used
