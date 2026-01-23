# Uniflow List Watchers Plan

This document tracks the implementation of list-watchers - a Uniflow primitive for detecting list membership changes and dispatching actions in response.

## Motivation

Smooth list animations require detecting when items are added or removed from lists, regardless of the source of the change (UI buttons, REPL FS sync, storage listeners, message handlers). Rather than wiring animation logic into every mutation point, list-watchers provide a declarative way to react to list changes at the state layer.

## Standard

- **Declaration key:** `:uf/list-watchers` in state
- **Pure function:** `get-list-watcher-actions` computes triggered actions
- **Integration:** After state change in `dispatch!`, before effects
- **Pattern:** Inspired by when-actions (declarative, mechanical evaluation, no app logic in watcher)

## Required Reading

Before implementing, read these documents and source files:

### Architecture Documentation

- [uniflow.md](architecture/uniflow.md) - Uniflow event system overview
- [state-management.md](architecture/state-management.md) - State atoms and action/effect patterns

### Source Files

- [event_handler.cljs](../../src/event_handler.cljs) - Current Uniflow implementation
- [event_handler_test.cljs](../../test/event_handler_test.cljs) - Existing tests to understand patterns

### Related Context

- [smooth-animations-plan.md](smooth-animations-plan.md) - The use case driving this feature (List Item Animations section)

## Design

### Declaration Format

```clojure
{:uf/list-watchers {:scripts/list {:id-fn :script/id
                                   :on-change :ui/ax.scripts-changed}
                    :repl/connections {:id-fn :connection/tab-id
                                       :on-change :ui/ax.connections-changed}
                    :settings/user-origins {:id-fn identity
                                            :on-change :ui/ax.origins-changed}}}
```

### Action Payload

When changes are detected, the declared action receives:

```clojure
[:ui/ax.scripts-changed {:added #{"new-id"} :removed #{"old-id"}}]
```

### Integration Point

```clojure
(defn dispatch! [...]
  (let [old-state @!state  ; Capture before processing
        {:uf/keys [fxs dxs db]} (handle-actions old-state ...)]
    (when db
      (reset! !state db))
    ;; NEW: List watchers fire after state change
    (let [watcher-actions (get-list-watcher-actions old-state (or db old-state))]
      (when (seq watcher-actions)
        (dispatch! ... watcher-actions)))
    (when dxs
      (dispatch! ...))
    (when (seq fxs)
      (execute-effects! ...))))
```

### Comparison with when-actions

| Aspect | when-actions | list-watchers |
|--------|--------------|---------------|
| **Timing** | Before action processing | After state change |
| **Detects** | Condition keys becoming truthy | List membership changes |
| **Declaration** | `:db/when-actions` | `:uf/list-watchers` |
| **Pure function** | `get-triggered-when-actions` | `get-list-watcher-actions` |
| **Cleanup** | One-shot (removes after trigger) | Persistent (keeps watching) |

### Relationship with Reagami :on-render

Reagami provides an `:on-render` hook that fires on `:mount`, `:update`, and `:unmount` lifecycles. This affects our animation strategy:

| Animation Type | Mechanism | Why |
|----------------|-----------|-----|
| **Enter** | `:on-render :mount` | Component-local, fires when element added to DOM. Add "entering" class, remove after 1 frame to trigger CSS transition. |
| **Exit** | List-watchers | By the time `:on-render :unmount` fires, Reagami has already decided to remove the element. We need to intercept at the state level to delay removal. |

This means list-watchers are primarily needed for **exit animations** - detecting removals and keeping items in a "leaving" state while the animation plays. Enter animations can be handled more simply via `:on-render :mount`.

## Checklist

### Core Implementation

- [x] **Pure function: `get-list-watcher-actions`** ([event_handler.cljs](../../src/event_handler.cljs))
  - Compares old-state vs new-state for declared list paths
  - Returns actions with `{:added #{} :removed #{}}` payload
  - [x] addressed in code
  - [x] verified (tests)

- [x] **Integration into `dispatch!`** ([event_handler.cljs](../../src/event_handler.cljs))
  - Capture old-state before processing
  - Call `get-list-watcher-actions` after state reset
  - Dispatch triggered actions
  - [x] addressed in code
  - [x] verified (tests)

### Unit Tests

- [x] **Test: No watchers declared** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Returns empty when `:uf/list-watchers` not in state
  - [x] addressed in code
  - [x] verified (tests pass)

- [x] **Test: Watched list unchanged** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Returns empty when list contents are identical
  - [x] addressed in code
  - [x] verified (tests pass)

- [x] **Test: Detects added items** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Returns action with correct `:added` set
  - [x] addressed in code
  - [x] verified (tests pass)

- [x] **Test: Detects removed items** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Returns action with correct `:removed` set
  - [x] addressed in code
  - [x] verified (tests pass)

- [x] **Test: Detects both added and removed** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Single action with both sets populated
  - [x] addressed in code
  - [x] verified (tests pass)

- [x] **Test: Uses id-fn for complex items** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Extracts IDs using declared `:id-fn`
  - [x] addressed in code
  - [x] verified (tests pass)

- [x] **Test: Multiple watchers fire independently** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Each changed list triggers its own action
  - [x] addressed in code
  - [x] verified (tests pass)

- [x] **Test: Handles nil/missing lists gracefully** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Treats nil as empty list
  - [x] addressed in code
  - [x] verified (tests pass)

### Integration Tests

- [x] **Test: Watcher actions dispatched after state change** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Full dispatch! cycle triggers watcher actions
  - [x] addressed in code
  - [x] verified (tests pass)

- [x] **Test: Watcher actions can modify state** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Action handler can update `:ui/entering-*` sets
  - [x] addressed in code
  - [x] verified (tests pass)

- [x] **Test: Watcher actions can schedule effects** ([event_handler_test.cljs](../../test/event_handler_test.cljs))
  - Action handler can return `:uf/fxs` (e.g., defer-dispatch)
  - [x] addressed in code
  - [x] verified (tests pass)

### Application Integration (Popup)

- [x] **Declare list watchers in popup state** ([popup.cljs](../../src/popup.cljs))
  - Add `:uf/list-watchers` for scripts, connections, origins
  - [x] addressed in code
  - [x] verified (human)

- [x] **Implement change handler actions** ([popup_actions.cljs](../../src/popup_actions.cljs))
  - `:ui/ax.scripts-changed` - manages `:ui/entering-scripts`, `:ui/leaving-scripts`
  - `:ui/ax.connections-changed` - manages `:ui/entering-tabs`, `:ui/leaving-tabs`
  - `:ui/ax.origins-changed` - manages `:ui/entering-origins`, `:ui/leaving-origins`
  - [x] addressed in code
  - [x] verified (human) - Enter animations work for UI-initiated and REPL-initiated adds

- [x] **Restore two-phase pattern for delete actions** ([popup_actions.cljs](../../src/popup_actions.cljs))
  - `:popup/ax.delete-script` - two-phase: mark leaving, then remove after 250ms
  - `:popup/ax.remove-origin` - two-phase: mark leaving, then remove after 250ms
  - `:popup/ax.disconnect-tab` - two-phase: mark leaving, then disconnect after 250ms
  - Exit animations require items to stay in list while animating
  - [x] addressed in code
  - [x] verified (human) - Works for UI delete buttons; REPL deletions not animated (acceptable)

- [x] **Fix tab watcher id-fn** ([popup.cljs](../../src/popup.cljs))
  - Changed `:connection/tab-id` to `:tab-id` to match actual data structure
  - [x] addressed in code
  - [x] verified (human) - Connect tab now animates smoothly

- [x] **Add entering class support to components** ([popup.cljs](../../src/popup.cljs))
  - Components check `:ui/entering-*` sets and apply `.entering` CSS class
  - [x] addressed in code
  - [x] verified (human) - Enter works for scripts and origins

- [x] **Add CSS for entering state** ([popup.css](../../extension/popup.css))
  - `.script-item.entering`, `.connected-tab-item.entering`, `.origin-item.entering`
  - Transition from collapsed to expanded
  - [x] addressed in code
  - [x] verified (human) - CSS transitions work when class is applied

### Lesson Learned: List-watchers for Enter Only

**Key insight from manual testing:**

List-watchers are useful for **enter animations** but cannot handle **exit animations** because:

1. List-watchers fire AFTER state changes
2. By the time a watcher detects an item was removed, the item is already gone from the list
3. No element exists to animate

**Enter animations (list-watchers work):**
- Watcher detects item added → adds to entering set → item renders with `.entering` class → class cleared after 50ms → CSS transition animates expansion

**Exit animations (two-phase pattern required):**
- Delete action adds item to leaving set (item stays in list) → item renders with `.leaving` class → after 250ms delay, action re-fires → item removed from list

This means the original two-phase delete pattern was correct - it just needed the entering side handled via list-watchers.

### Cleanup

- [x] **E2E tests passing**
  - All 90 E2E tests pass after animation changes
  - [x] verified (tests pass)

- [ ] **Update documentation** ([uniflow.md](architecture/uniflow.md))
  - Document `:uf/list-watchers` declaration format
  - Document `get-list-watcher-actions` behavior
  - [ ] addressed in code
  - [ ] verified (human)

### Known Edge Cases (Acceptable)

The following scenarios intentionally do NOT animate:

1. **REPL-initiated deletions:** When scripts are deleted via REPL FS sync (`epupp/delete!`), they disappear immediately without exit animation. The two-phase pattern only applies to UI-initiated deletes via `:popup/ax.delete-script`.

2. **Script moving between lists:** When a script's match pattern changes, it may move from "Scripts for this page" to "Other scripts" or vice versa. This is a filter change, not an actual add/remove from the scripts collection - no animation needed.

3. **"Disconnect All" button:** The disconnect button in the connected tabs header uses a different code path than the per-item disconnect button. Only per-item disconnect animates.

These are acceptable because:
- REPL operations are programmatic, not user-initiated UI interactions
- List filtering is instant re-categorization, not a state transition
- Batch operations prioritize efficiency over visual feedback

## Process

1. **Agent** implements TDD - write failing tests first, then implementation
2. **Agent** ticks "addressed in code" checkboxes
3. **Agent** runs `bb test` to verify unit tests pass
4. **Agent** ticks "verified (tests)" or "verified (tests pass)" checkboxes
5. For application integration items, **agent** builds dev extension
6. **Human** manually tests animations in browser
7. **Human** ticks "verified (human)" checkboxes
8. If issues found, human describes problem, agent fixes, repeat

## Edge Cases to Consider

- **Rapid changes:** Multiple dispatches before animation completes - entering/leaving sets may accumulate
- **Same item added and removed:** Item removed then re-added in quick succession
- **Watcher action causes more list changes:** Potential infinite loop - may need cycle detection
- **Large lists:** Performance of set difference operations

## Notes

- Keep `get-list-watcher-actions` pure - no side effects, just computation
- Watcher actions are dispatched synchronously after state change, before effects
- The `:id-fn` allows flexibility for different list item shapes
- Consider adding `:enabled?` flag to watchers for conditional activation

## Original Plan-producing Prompt

The following prompt captures the design discussion that led to this plan:

---

Implement a list-watchers capability for Uniflow to detect list membership changes and dispatch actions in response.

**Context:**
- Smooth list animations require detecting when items are added/removed regardless of mutation source
- Current two-phase deletion pattern only works for specific UI button actions
- Need a declarative, state-level solution that doesn't put app logic in the atom watcher

**Design requirements:**
1. Declaration in state via `:uf/list-watchers` map
2. Pure function `get-list-watcher-actions` computes triggered actions by diffing old vs new state
3. Integration after state change in `dispatch!`, before effects
4. Inspired by when-actions pattern: declarative, mechanical evaluation, app logic in action handlers

**Declaration format:**
```clojure
{:uf/list-watchers {:path/to-list {:id-fn :item/id :on-change :action/key}}}
```

**Action payload:**
```clojure
[:action/key {:added #{ids} :removed #{ids}}]
```

---
