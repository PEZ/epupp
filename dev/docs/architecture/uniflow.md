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

An effect's only inputs are `dispatch` and the `args` the action provided. Effects never read `@!state` - all state-derived data must arrive through the effect's parameter vector.

```clojure
(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :editor/fx.save-script
    (let [[script] args]
      (storage/save-script! script))

    :uf/unhandled-fx))  ; Fall back to generic handler
```

Effects receive `dispatch` for async callbacks that need to trigger more actions.

**Passing state-derived data to effects:**

When an effect needs data from state, the action extracts it and passes it through:

```clojure
;; Action extracts what the effect needs from state
:ws/ax.broadcast
(let [connections (:ws/connections state)]
  {:uf/fxs [[:ws/fx.broadcast-connections-changed! connections]]})

;; Effect receives data as args - never touches the atom
:ws/fx.broadcast-connections-changed!
(let [[connections] args]
  (bg-ws/broadcast-connections-changed! connections))
```

This keeps the data flow unidirectional: state -> action -> effect args.

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
- **The originating action** passes any state-derived data the effect needs through effect params
- **Gathering effects** call external/async APIs only - they never read `@!state`
- **Decision actions** are pure functions that receive gathered data and decide what to do

```clojure
;; Originating action passes state-derived data to the gathering effect
:nav/ax.check-connection
(let [[tab-id] args
      injected? (get-in state [:injections tab-id])]
  {:uf/fxs [[:uf/await :nav/fx.gather-context tab-id injected?]]
   :uf/dxs [[:nav/ax.decide-connection :uf/prev-result]]})

;; Gathering effect - only calls external APIs, receives state data as args
:nav/fx.gather-context
(let [[tab-id injected?] args
      tab (js-await (chrome.tabs.get tab-id))]
  {:url (.-url tab)
   :injected? injected?})

;; Decision action (pure - testable without Chrome or atoms)
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

**Key constraint:** The gathering effect receives `injected?` from the action, not from `@!state`. The effect's job is to call the Chrome API - the one thing actions can't do. State data flows from action to effect through params.

**Benefits:**
- Decision logic is pure and testable without mocking Chrome APIs
- Gathering effects are testable too - no hidden atom dependencies
- Clear separation: actions provide state data, effects call external APIs, decision actions decide
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
   :config/installer-site-patterns (or (.-installerSitePatterns config) [])})

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

## Background Usage

The background worker applies the same Uniflow pattern across several domains:
- **FS mutations** - Script save, rename, delete, and validation via `[:fs/ax.* ...]` actions
- **WebSocket lifecycle** - Connection tracking and state transitions
- **Icon state** - Extension icon updates based on connection status
- **Navigation** - Tab lifecycle and connection decisions (gather-then-decide)
- **History** - Connected tab tracking

Message handlers in `background.cljs` dispatch actions rather than reading
state directly. The action handler (`background_actions/handle-action`) extracts
what it needs from state and declares effects with that data. Effects execute
the side effects using only the data actions provided.

FS mutations route through `bg-fs-dispatch/dispatch-fs-action!`, which invokes
the pure decision logic and executes `:uf/fxs` effects for persistence and
responses.

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

## The Single Access Point Rule

The event loop (`event_handler/dispatch!`) is the **only code** that may `deref` or `reset!` the state atom. Everything else receives state as data:

- **Actions** receive `state` as a plain map parameter. They return `{:uf/db new-state}`. They never touch the atom.
- **Effects** perform side effects using data passed to them by actions via `:uf/fxs` vectors. They never read `@!state`.
- **Helpers** called by effects inherit the same constraint - no transitive atom access.
- **Entry points** (message handlers, event listeners) dispatch actions instead of reading state directly.

When an effect needs state-derived data, the action that declares it must pass that data as parameters in the effect vector:

```clojure
;; CORRECT: Action passes data to effect
:ws/ax.broadcast
(let [connections (:ws/connections state)]
  {:uf/fxs [[:ws/fx.broadcast-connections-changed! connections]]})

;; WRONG: Effect reads atom
:ws/fx.broadcast-connections-changed!
(bg-ws/broadcast-connections-changed! (:ws/connections @!state))  ; violation
```

**Why this matters:**
- **Testability** - Actions are pure functions. Effects that depend only on their parameters are testable without mocking atoms.
- **Predictability** - No race conditions between state reads in effects and state writes from concurrent actions.
- **Traceability** - Every piece of data an effect uses traces back to the action that provided it.

### Guard and Utility Functions

Functions like access-control guards must be pure - they receive data as parameters, not atom access:

```clojure
;; CORRECT: Pure guard receiving data
(defn- fs-access-allowed? [sync-tab-id connections tab-id]
  (and (= tab-id sync-tab-id)
       (some? (bg-ws/get-ws connections tab-id))))

;; WRONG: Guard reading atom
(defn- fs-access-allowed? [tab-id]
  (and (= tab-id (:fs/sync-tab-id @!state))              ; violation
       (some? (bg-ws/get-ws (:ws/connections @!state) tab-id))))  ; violation
```

### Entry Points Dispatch

Message handlers and event listeners should dispatch actions rather than reading state directly. The action extracts what it needs from state and declares effects with that data:

```clojure
;; CORRECT: Handler dispatches, action extracts state
(defn- handle-ws-send [message tab-id dispatch!]
  (dispatch! [[:ws/ax.send tab-id (.-data message)]])
  false)

;; WRONG: Handler reads state, calls helper imperatively
(defn- handle-ws-send [message tab-id]
  (bg-ws/handle-ws-send (:ws/connections @!state) tab-id (.-data message))  ; violation
  false)
```

### Acceptable Exception: Pre-dispatch Initialization

During startup, before `dispatch!` is available, direct atom access may be necessary. This is the **only** acceptable exception:

- Must be documented explicitly as "pre-dispatch initialization only"
- Cannot be deferred to first action or effect
- Must have a clear comment explaining the constraint
- Must not occur during normal runtime

## Anti-Patterns

These patterns cause Uniflow violations. Each maps to a violation category from the state access review:

| Anti-Pattern | Category | Prevention |
|--------------|----------|------------|
| Effect reads `@!state` | A1 | Action passes data via `:uf/fxs` params |
| Helper called by effect reads `@!state` | A1 (transitive) | Refactor helper to accept data as parameter |
| Direct `swap!/reset!` outside event loop | B | Create action + dispatch instead |
| Guard function reads `@!state` | C | Refactor to pure function with data params |
| Message handler reads `@!state` | D | Dispatch action; action extracts from state |
| Effect gathers state then dispatches | A2 | Move state read into action, pass via effect params |

### How to Spot Violations

Search for these patterns in Uniflow-managed files:

- `@!state` anywhere except the event loop and view delivery (`render!`)
- `swap!` or `reset!` on the state atom outside the event loop
- Helpers that take no state parameters but internally deref atoms

## Compliance Status

The codebase has 100% Uniflow compliance. All `@!state` references outside the
event loop (`event_handler/dispatch!`) and view delivery (`render!`) have been
eliminated. There are zero direct `swap!` or `reset!` calls on the state atom
outside the event loop. Guard functions, message handlers, and effect helpers
all receive data as parameters rather than reading the atom.

This was achieved by fixing 30 violations across all categories:

- **A (Effects/helpers reading `@!state`)** - Refactored to receive data from
  actions via `:uf/fxs` params
- **B (Direct `swap!/reset!` outside event loop)** - Replaced with actions and
  `dispatch!` calls
- **C (Guard functions reading `@!state`)** - Converted to pure functions with
  data parameters
- **D (Message handlers reading `@!state`)** - Refactored to dispatch actions

To maintain compliance, search for the patterns listed in "How to Spot
Violations" above before merging new code.

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
