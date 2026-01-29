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
{:uf/fxs [[:log/fx.log :info "Loading data..."]                     ; fire-and-forget
          [:uf/await :data/fx.fetch script-id]                      ; await, returns data
          [:uf/await :data/fx.process :uf/prev-result]]}            ; receives previous result
```

The `:uf/prev-result` placeholder is substituted with the return value of the most recent awaited effect. Fire-and-forget effects don't update `:uf/prev-result`.

**Recipe-style actions:**

This pattern enables "recipe-style" actions - declarative sequences where each step flows into the next:

```clojure
:data/ax.load-and-process
(let [[resource-id] args]
  {:uf/fxs [[:log/fx.log :info "Starting data load" resource-id]
            [:uf/await :data/fx.fetch resource-id]
            [:uf/await :data/fx.transform :uf/prev-result]
            [:uf/await :data/fx.save :uf/prev-result]]})
```

This reads as: "Log the operation, then fetch the data, then transform it, then save the result."

**Benefits:**
- Actions read like recipes with clear intent
- Shallow event chains - no need for intermediate callback actions
- Effects behave like function calls with return values
- Single list maintains declaration order

**Error handling:** The effect sequence is wrapped in try/catch. If any awaited effect throws, execution stops and the error propagates.

### Result Threading to Deferred Actions (`:uf/dxs`)

The `:uf/prev-result` placeholder also works in `:uf/dxs` (deferred actions). This enables the "gather-then-decide" pattern - effects gather async data, then dispatch to pure decision actions:

```clojure
:nav/ax.check-connection
(let [[tab-id] args]
  {:uf/fxs [[:uf/await :nav/fx.gather-context tab-id]]
   :uf/dxs [[:nav/ax.decide-connection :uf/prev-result]]})
```

**How it works:**
1. All `:uf/fxs` execute first (in order)
2. The final `prev-result` from awaited effects is captured
3. `:uf/prev-result` placeholders in `:uf/dxs` are substituted with that value
4. The deferred actions dispatch with the resolved data

**Gather-then-decide pattern:**

This separates concerns cleanly:
- **Gathering effects** - Handle async Chrome APIs, fetch data, call services
- **Decision actions** - Pure functions that receive gathered data and decide what to do

```clojure
;; Gathering effect (impure - calls Chrome API)
:nav/fx.gather-context
(let [[tab-id] args
      tab (js-await (chrome.tabs.get tab-id))]
  {:url (.-url tab)
   :injected? (get-in @!state [:injections tab-id])})

;; Decision action (pure - testable without Chrome)
:nav/ax.decide-connection
(let [[context] args
      {:keys [url injected?]} context]
  (cond
    (not injected?)
    {:uf/fxs [[:nav/fx.inject-first]]}

    (allowed-origin? url)
    {:uf/fxs [[:nav/fx.connect]]}

    :else
    {:uf/db (assoc state :nav/error "Cannot connect to this page")}))
```

**Benefits:**
- Decision logic is pure and testable without mocking Chrome APIs
- Clear separation: effects gather, actions decide
- Single action returns the complete "recipe" - effects + follow-up decision
- Reduces deep callback nesting and intermediate action chains

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

Actions receive `uf-data` with framework-provided context. Generate it with project-specific data:

```clojure
(defn- make-uf-data []
  {:config/deps-string (.-depsString config)
   :config/allowed-origins (or (.-allowedScriptOrigins config) [])})

(defn dispatch! [actions]
  (event-handler/dispatch! !state popup-actions/handle-action perform-effect! actions (make-uf-data)))
```

The framework currently adds `:system/now` timestamp. Extend with whatever your actions need.

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
| Pass effect result to decision action | `:uf/prev-result` in `:uf/dxs` (gather-then-decide) |
| Sync follow-up actions | Action returning `:uf/dxs` |
| Conditional no-op | Action returning `nil` |
| Async callback needs dispatch | Effect with `dispatch` parameter |
| Delayed dispatch | `:uf/fx.defer-dispatch` effect |
| Animated UI updates | Shadow list watchers (`:shadow-path` mode) |
| Content change detection | Shadow watchers (same ID, different content) |

## Shadow List Watchers (Animated UI Updates)

List watchers support a **shadow mode** for implementing animated UI transitions. Instead of comparing old-state vs new-state, shadow mode compares the source list (`:scripts/list`) to a shadow list (`:ui/scripts-shadow`) in the **current state**.

### Shadow Item Shape

Shadow items wrap the original item with animation state:

```clojure
{:item {:script/id "a" :script/name "foo.cljs"}  ; Original item
 :ui/entering? true   ; Item is animating in
 :ui/leaving? false}  ; Item is animating out
```

### Shadow Watcher Configuration

```clojure
:uf/list-watchers {:scripts/list {:id-fn :script/id
                                  :shadow-path :ui/scripts-shadow
                                  :on-change :ui/ax.sync-scripts-shadow}}
```

When the source list changes, the framework:
1. Compares source IDs to active shadow IDs (excluding `:ui/leaving?` items)
2. Detects additions, removals, AND content changes (same ID, different content)
3. Dispatches the `:on-change` action with `{:added-items [...] :removed-ids #{...}}`

### Content Change Detection

Shadow watchers detect when an item's content changes (same ID, different value):

```clojure
;; Source updated
:scripts/list [{:script/id "a" :script/code "new code"}]

;; Shadow has old content
:ui/scripts-shadow [{:item {:script/id "a" :script/code "old code"} ...}]

;; Watcher fires even though IDs are identical
```

This ensures UI updates when scripts are modified via REPL or storage changes.

### Example: popup.cljs Setup

```clojure
(defonce !state
  (atom {;; Source of truth
         :scripts/list []

         ;; Shadow for rendering with animation state
         :ui/scripts-shadow []

         ;; Watcher: compare source to shadow, trigger sync
         :uf/list-watchers {:scripts/list {:id-fn :script/id
                                           :shadow-path :ui/scripts-shadow
                                           :on-change :ui/ax.sync-scripts-shadow}}}))
```

### Example: Sync Action Handler

```clojure
:ui/ax.sync-scripts-shadow
(let [[{:keys [added-items removed-ids]}] args
      shadow (:ui/scripts-shadow state)
      source-list (:scripts/list state)
      source-by-id (into {} (map (fn [s] [(:script/id s) s]) source-list))

      ;; Mark removed items as leaving, update existing items' content
      shadow-with-updates (mapv (fn [s]
                                  (let [id (get-in s [:item :script/id])]
                                    (cond
                                      (contains? removed-ids id)
                                      (assoc s :ui/leaving? true)

                                      (contains? source-by-id id)
                                      (assoc s :item (get source-by-id id))

                                      :else s)))
                                shadow)

      ;; Add new items with entering flag
      new-shadow-items (mapv (fn [item] {:item item :ui/entering? true :ui/leaving? false})
                             added-items)
      updated-shadow (into shadow-with-updates new-shadow-items)
      added-ids (set (map :script/id added-items))]

  {:uf/db (assoc state :ui/scripts-shadow updated-shadow)
   :uf/fxs [[:uf/fx.defer-dispatch [[:ui/ax.clear-entering-scripts added-ids]] 50]
            [:uf/fx.defer-dispatch [[:ui/ax.remove-leaving-scripts removed-ids]] 250]]})
```

**Benefits:**
- Decouples source data from UI animation state
- Prevents duplicate removal triggers (`:ui/leaving?` items are ignored)
- Enables CSS-based animations via entering/leaving flags
- Automatic content update detection for smooth REPL-driven workflows

## Key Principles

1. **Actions are pure** - Given same state + uf-data + action, same result
2. **Effects are impure** - Side effects, async ops, external APIs
3. **Actions decide, effects execute** - Factor decision logic into actions for testability
4. **Batch for composition** - Small actions, combine in dispatch calls
5. **Fallback for reuse** - Generic handlers via `:uf/unhandled-*`

## Current Limitations

**Cross-subsystem calls not supported.** Each module has its own handlers; one subsystem cannot dispatch actions handled by another. The generic fallback (`:uf/unhandled-*`) provides shared utilities, but not cross-module communication.

If the app grows to need this, consider adding a registry pattern where subsystems register their handlers. Adapt Uniflow to fit - that's the point.

## Testing Patterns

Action handlers are pure functions - easy to test without mocking Chrome APIs:

```clojure
(test "handles :db/ax.assoc with multiple key-value pairs"
  (fn []
    (let [state {:existing "value"}
          result (event-handler/handle-action state {} [:db/ax.assoc :a 1 :b 2])]
      (expect (get (:uf/db result) :a) .toBe 1)
      (expect (get (:uf/db result) :b) .toBe 2))))
```

For list watchers, test the detection logic directly:

```clojure
(test "detects items in source but not in shadow (additions)"
  (fn []
    (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                   :shadow-path :ui/scripts-shadow
                                                   :on-change :ax.sync}}
                 :scripts/list [{:script/id "a"} {:script/id "c"}]
                 :ui/scripts-shadow [{:item {:script/id "a"} :ui/entering? false :ui/leaving? false}]}
          result (event-handler/get-list-watcher-actions state state)
          [action-key payload] (first result)]
      (expect (count (:added-items payload)) .toBe 1)
      (expect (:script/id (first (:added-items payload))) .toBe "c"))))
```

See `test/event_handler_test.cljs` for comprehensive test patterns.

## Related

- [state-management.md](state-management.md) - State atoms and action/effect tables per module
- [components.md](components.md) - Source file map showing where Uniflow is used
