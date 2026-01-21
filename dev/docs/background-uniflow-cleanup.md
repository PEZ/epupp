> **Note**: This document describes historical architecture that has been significantly refactored. The approval system described here has been removed. See remove-approval-layer-plan.md for details.

# Background Module Uniflow Cleanup Plan

This document outlines a phased approach to bring `src/background.cljs` into proper Uniflow compliance.

## Current State Analysis

### The Problem

The background module has grown organically with multiple patterns for state management:

1. **Four mini-dispatch functions** - Each domain (ws, icon, history, approval) has its own dispatch function duplicating the same pattern
2. **Inline effect execution** - Effects are executed inline in each dispatch function rather than through a unified effect handler
3. **Massive message handler** - 300+ lines with message parsing, decision logic, and async effects interleaved
4. **Fragmented state views** - Each dispatch extracts only its slice, losing the holistic state view

### Code Locations

| Pattern | Lines | Count |
|---------|-------|-------|
| `swap!` / `reset!` | 58, 60, 105, 170, 187, 338 | 6 |
| Mini-dispatch functions | 99-110, 162-177, 180-192, 330-355 | 4 |
| Inline async effects | Throughout message handler | Many |

## Three Key Areas for Focused Remediation

### 1. Unified Dispatch (Priority: High, Effort: Medium)

**Problem:** Four near-identical dispatch functions duplicate the pattern:
- Extract state slice
- Call handler
- Swap result back
- Execute effects inline

```clojure
;; Current: dispatch-ws-action!, dispatch-icon-action!, dispatch-history-action!, dispatch-approval-action!
;; Each does: state slice -> bg-actions/handle-action -> swap! -> inline effect loop
```

**Remedy:**
1. Create a single `dispatch!` function that works with full state
2. Modify `background-actions/handle-action` to return full db updates (not slices)
3. Create `perform-effect!` handler for background-specific effects
4. Route all actions through unified dispatch

**Outcome:** Single dispatch point, consistent effect handling, simpler mental model.

**Files to modify:**
- `src/background.cljs` - Create unified dispatch, remove mini-dispatchers
- `src/background_actions.cljs` - Return full state updates
- `src/background_actions/*.cljs` - Adjust return shapes

### 2. Message Handler Decomposition (Priority: High, Effort: High)

**Problem:** The `chrome.runtime.onMessage` handler (lines ~900-1250) is a monolithic case statement with:
- Message parsing
- Validation logic
- Async operation orchestration
- Error handling
- Response management

Each case branch contains inline async IIFEs performing side effects.

**Remedy:**
1. Extract message parsing to pure functions
2. Create action types for each message type
3. Move async orchestration to effect handlers
4. Keep the listener thin - just parse and dispatch

**Target structure:**
```clojure
;; Listener becomes thin dispatcher
(js/chrome.runtime.onMessage.addListener
  (fn [message sender send-response]
    (let [action (parse-message message sender)]
      (dispatch! [action])
      (when (requires-async-response? action)
        true))))

;; Effects handle async work
(case effect
  :message/fx.connect-repl
  (let [[tab-id port callback] args]
    ;; async work here
    ))
```

**Files to modify:**
- `src/background.cljs` - Thin listener, new effect handlers
- `src/background_actions.cljs` - Message-derived actions
- New: `src/background_actions/message_actions.cljs` (optional)

### 3. Background Effect Handler (Priority: Medium, Effort: Low)

**Problem:** Effects are executed inline with ad-hoc case statements in each mini-dispatcher. No reusable effect handler exists.

**Remedy:**
1. Create `perform-effect!` function in background.cljs
2. Register all background-specific effects
3. Use `event-handler/dispatch!` pattern from popup/panel

**Effect registry:**
```clojure
(defn perform-effect! [dispatch [effect & args]]
  (case effect
    ;; WS effects
    :ws/fx.broadcast-connections-changed! (broadcast-connections-changed!)

    ;; Icon effects
    :icon/fx.update-toolbar! (let [[tab-id] args] (update-icon-now! tab-id))

    ;; Approval effects
    :approval/fx.update-badge-for-tab! (let [[tab-id] args] (update-badge-for-tab! tab-id))
    :approval/fx.update-badge-active! (update-badge-for-active-tab!)
    :approval/fx.log-pending! (let [[info] args] (log/info "Background" "Approval" ...))

    ;; History effects (none currently)

    ;; FS effects
    :fs/fx.persist-scripts! (storage/save-scripts! ...)
    :fs/fx.broadcast-event! (broadcast-fs-event! ...)

    ;; Fallback
    :uf/unhandled-fx))
```

**Files to modify:**
- `src/background.cljs` - Create `perform-effect!`

## Execution Order

| Phase | Area | Dependency |
|-------|------|------------|
| 1 | Effect Handler | None - can be done first |
| 2 | Unified Dispatch | Needs effect handler |
| 3 | Message Decomposition | Needs unified dispatch |

## Constraints

1. **Service worker lifecycle** - Background is a service worker that can be terminated. State must be recoverable.
2. **Async nature** - Chrome APIs are heavily async. Effect handlers must support async execution.
3. **Test coverage** - E2E tests must continue passing after each phase.
4. **Incremental migration** - Each phase should leave the system working.

## Success Criteria

- [ ] Zero `swap!` calls outside of `dispatch!`
- [ ] Single `dispatch!` function for all actions
- [ ] Single `perform-effect!` function for all effects
- [ ] Message handler under 50 lines
- [ ] All E2E tests passing
- [ ] Unit tests for new pure action handlers

## Non-Goals (This Phase)

- Splitting background.cljs into multiple files (future refactor)
- Adding new features
- Changing the Chrome extension architecture

## References

- [architecture.md](architecture.md) - Overall system design
- [uniflow.md](architecture/uniflow.md) - Uniflow pattern documentation
- [state-management.md](architecture/state-management.md) - State management patterns
