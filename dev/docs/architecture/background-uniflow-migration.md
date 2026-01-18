# Background Uniflow Migration Plan

Migration plan to make Uniflow the single source of truth for background state updates and effect execution.

## Current State

The REPL FS actions (`:fs/ax.save-script`, `:fs/ax.rename-script`, `:fs/ax.delete-script`) already use the Uniflow pattern:

1. **Pure action handlers** in `background_actions.cljs` - no side effects, just state transitions
2. **Dispatch through** `bg-fs-dispatch/dispatch-fs-action!`
3. **Effects executed** after pure handler returns
4. **Comprehensive unit tests** in `test/background_actions_test.cljs`

This pattern has proven itself - decisions are testable, state transitions are pure, and effects are explicitly declared.

Icon state, connection history, pending approvals, and WebSocket connection state are now routed through Uniflow actions and effects with unit tests in place. End-to-end verification has passed.

## Migration Candidates

Analysis of `!state` mutations in `background.cljs` reveals five logical subsystems:

| Priority | Subsystem | State Keys | Complexity | Impact |
|----------|-----------|------------|------------|--------|
| 1 | Icon State | `:icon/states` | Low | High - foundation for others |
| 2 | Approvals | `:pending/approvals` | Medium | High - user-visible feature |
| 3 | Connection History | `:connected-tabs/history` | Low | Medium - auto-reconnect |
| 4 | WebSocket Management | `:ws/connections` | High | High - core feature |
| 5 | Badge Management | (derived) | Low | Medium - UX feedback |

## Candidate Details

### 1. Icon State Management (Recommended First)

**Current code pattern:**
```clojure
(swap! !state assoc-in [:icon/states tab-id] state)
(swap! !state update :icon/states dissoc tab-id)
(swap! !state update :icon/states (fn [states] (select-keys states valid-ids)))
```

**Why migrate first:**
- Simplest state transitions (just assoc/dissoc)
- No async complexity in decisions
- Clear effects: update toolbar icon
- Foundation for other subsystems (WS connect triggers icon change)

**Actions:**
- `:icon/ax.set-state [tab-id state]` - set icon state for tab
- `:icon/ax.clear [tab-id]` - remove state for tab
- `:icon/ax.prune [valid-tab-ids]` - remove stale entries

**Effects:**
- `:icon/fx.update-toolbar! [tab-id]` - call `js/chrome.action.setIcon`

### 2. Pending Approvals Management

**Current code pattern:**
```clojure
(swap! !state update :pending/approvals assoc approval-id context)
(swap! !state update :pending/approvals dissoc approval-id)
```

**Why migrate:**
- Clear business rules (add/remove pending, sync with storage)
- User-visible feature (badge shows pending count)
- Decision logic can be tested (what should be pruned)

**Actions:**
- `:approval/ax.request [script pattern tab-id]` - add pending approval
- `:approval/ax.clear [script-id pattern]` - remove specific pending
- `:approval/ax.sync` - prune stale entries based on storage state

**Effects:**
- `:approval/fx.update-badge! [tab-id]` - recalculate and set badge

### 3. Connection History (Auto-Reconnect)

**Current code pattern:**
```clojure
(swap! !state assoc-in [:connected-tabs/history tab-id] {:port port})
(swap! !state update :connected-tabs/history dissoc tab-id)
```

**Why migrate:**
- Simple state (just track tab-id -> port mapping)
- Enables testable decisions (should we reconnect this tab?)
- No async complexity in the state transitions

**Actions:**
- `:history/ax.track [tab-id port]` - add tab to history
- `:history/ax.forget [tab-id]` - remove tab from history

**Effects:** None - pure state tracking for read by other systems

### 4. WebSocket Connection Management

**Current code pattern:**
```clojure
(swap! !state assoc-in [:ws/connections tab-id] {:ws/socket ws ...})
(swap! !state update :ws/connections dissoc tab-id)
```

**Why migrate later:**
- Complex async operations (WebSocket lifecycle)
- Event handlers (`onopen`, `onclose`, `onerror`, `onmessage`)
- Effects need the actual WebSocket object reference
- Interleaved with icon state updates

**Actions:**
- `:ws/ax.register [tab-id connection-info]` - record connection info
- `:ws/ax.unregister [tab-id]` - remove connection

**Effects:**
- `:ws/fx.create-websocket! [tab-id port]` - create and wire WS
- `:ws/fx.close! [tab-id]` - close existing WS
- `:ws/fx.broadcast-changed!` - notify popup/panel

### 5. Badge Management (Derived State)

The badge is derived from pending approvals and current tab URL. Not a separate state to migrate, but the calculation logic can become a pure function tested separately.

**Current flow:**
1. Count scripts needing approval for URL
2. Set badge text and color

**Migration approach:**
- Extract `count-pending-for-url` (already pure)
- Make badge update an effect triggered by approval changes

## Recommended Migration Order

**Critical:** Run `bb test:e2e` before and after each phase to ensure no regressions.

```
Phase 1: Icon State
    │
  ├── bb test:e2e (baseline - all tests must pass)
  ├── Create background_actions/icon_actions.cljs (pure)
  ├── Write unit tests
  ├── Wire into Uniflow dispatch
  ├── Update background.cljs callers
  └── bb test:e2e (verify no regressions)

Phase 2: Connection History
    │
  ├── bb test:e2e (baseline)
  ├── Create background_actions/history_actions.cljs (pure)
  ├── Unit tests
  ├── Wire dispatch
  └── bb test:e2e (verify)

Phase 3: Pending Approvals
    │
  ├── bb test:e2e (baseline)
  ├── Create background_actions/approval_actions.cljs
  ├── Unit tests
  ├── Wire dispatch
  ├── Integrate badge effects
  └── bb test:e2e (verify)

Phase 4: WebSocket Management
    │
  ├── bb test:e2e (baseline)
  ├── Create background_actions/ws_actions.cljs
  ├── Unit tests
  ├── Wire dispatch
  ├── Keep lifecycle management in background.cljs
  └── bb test:e2e (verify)
```

## Implementation Pattern

Follow the established FS pattern:

### 1. Create Pure Action Module

```clojure
;; src/background_actions/icon_actions.cljs
(ns background-actions.icon-actions)

(defn set-state
  "Set icon state for a tab."
  [{:icon/keys [states tab-id new-state]}]
  {:uf/db {:icon/states (assoc states tab-id new-state)}
   :uf/fxs [[:icon/fx.update-toolbar! tab-id]]})
```

### 2. Register in Main Action Handler

```clojure
;; src/background_actions.cljs
(defn handle-action [state uf-data [action & args]]
  (case action
    ;; Existing FS actions...

    :icon/ax.set-state
    (let [[tab-id new-state] args]
      (icon-actions/set-state
       {:icon/states (:icon/states state)
        :icon/tab-id tab-id
        :icon/new-state new-state}))

    :uf/unhandled-ax))
```

### 3. Add Effects to Dispatcher

```clojure
;; src/bg_dispatch.cljs (renamed from bg_fs_dispatch.cljs)
(defn- perform-effect! [send-response [effect & args]]
  (case effect
    ;; Existing FS effects...

    :icon/fx.update-toolbar!
    (let [[tab-id] args]
      (bg/update-icon-now! tab-id))

    :uf/unhandled-fx))
```

### 4. Write Unit Tests

```clojure
(describe ":icon/ax.set-state"
  (fn []
    (test "updates state and triggers toolbar effect"
      (fn []
        (let [result (bg-actions/handle-action
                       {:icon/states {}}
                       uf-data
                       [:icon/ax.set-state 123 :connected])]
          (-> (expect (get-in result [:uf/db :icon/states 123]))
              (.toBe :connected))
          (-> (expect (some #(= [:icon/fx.update-toolbar! 123] %) (:uf/fxs result)))
              (.toBeTruthy)))))))
```

## Success Criteria

- [ ] All `swap! !state` calls in background.cljs go through dispatch
- [ ] Unit tests cover all action decision logic
- [ ] Effects are explicitly declared, not inline
- [ ] Badge updates are tied to state changes, not scattered
- [ ] Existing E2E tests continue to pass

## Current Progress

- [x] Icon state migrated to Uniflow actions and effects
- [x] Connection history migrated to Uniflow actions
- [x] Pending approvals migrated to Uniflow actions and badge effects
- [x] WebSocket connection state migrated to Uniflow actions
- [x] Unit tests cover icon, history, approval, and ws actions
- [x] E2E tests pass (bb test:e2e)

## References

- [uniflow.md](uniflow.md) - Event system documentation
- [background-uniflow-implementation.md](background-uniflow-implementation.md) - FS implementation plan (Phase 1-2 complete)
- [repl-fs-sync.md](repl-fs-sync.md) - FS architecture context
- [state-management.md](state-management.md) - Current state documentation
