# Uniflow Compliance Fix Plan

Fix direct state mutations and effect state access violations identified in the Uniflow compliance audit.

## Priority Overview

| Priority | Category | Impact |
|----------|----------|--------|
| 1 | Critical state mutations | Direct `swap!` bypasses dispatch - untestable, breaks data flow |
| 2 | Effect state access | Effects reading `@!state` - couples effects to global state |
| 3 | Persistence pattern | Watcher side effects - minor, but inconsistent with fx system |

---

## Standard

- All edits delegated to **Clojure-editor subagent**
- Before each batch: delegate to **epupp-testrunner subagent** for baseline
- After each batch: delegate to **epupp-testrunner subagent** for verification
- REPL experiments to verify Squint assumptions before editing
- Tick checkboxes without inserting commentary blocks

---

## Required Reading

### Architecture Docs
- [dev/docs/architecture/uniflow.md](architecture/uniflow.md) - Core Uniflow patterns
- [dev/docs/architecture/state-management.md](architecture/state-management.md) - State ownership rules

### Source Files to Modify
- [src/panel.cljs](../../src/panel.cljs) - Panel view with critical violations
- [src/panel_actions.cljs](../../src/panel_actions.cljs) - Panel action handlers
- [src/popup.cljs](../../src/popup.cljs) - Popup view with one critical violation
- [src/popup_actions.cljs](../../src/popup_actions.cljs) - Popup action handlers

### Reference Files
- [src/event_handler.cljs](../../src/event_handler.cljs) - Dispatch implementation
- [src/view_elements.cljs](../../src/view_elements.cljs) - Clean view patterns (reference)

---

## Checklist

### Priority 1: Critical State Mutations

#### 1.1 Panel: `check-version!` direct mutation
Location: `panel.cljs` line ~586

Convert `(swap! !state assoc :panel/needs-refresh? true)` to dispatch.

- [ ] addressed in code
- [ ] verified by tests

**Actions needed:**
- Add `:editor/ax.set-needs-refresh` action to `panel_actions.cljs`
- Replace `swap!` with `dispatch!` call in `check-version!`

#### 1.2 Panel: `on-page-navigated` direct mutation
Location: `panel.cljs` lines ~592-595

Convert state reset to dispatch.

- [ ] addressed in code
- [ ] verified by tests

**Actions needed:**
- Add `:editor/ax.reset-for-navigation` action to `panel_actions.cljs`
- Replace `swap!` with `dispatch!` in `on-page-navigated`

#### 1.3 Panel: `init!` version storage
Location: `panel.cljs` line ~607

Convert `(swap! !state assoc :panel/init-version ...)` to dispatch.

- [ ] addressed in code
- [ ] verified by tests

**Actions needed:**
- Add `:editor/ax.set-init-version` action to `panel_actions.cljs`
- Replace `swap!` with `dispatch!` in `init!`

#### 1.4 Panel: FS message listener bulk name tracking
Location: `panel.cljs` lines ~660-661

Convert bulk name `swap!` calls to dispatch.

- [ ] addressed in code
- [ ] verified by tests

**Actions needed:**
- Add `:editor/ax.track-bulk-name` action to `panel_actions.cljs`
- Add `:editor/ax.clear-bulk-names` action to `panel_actions.cljs`
- Replace `swap!` calls with `dispatch!` in message listener

#### 1.5 Popup: `init!` brave detection
Location: `popup.cljs` line ~809

Convert `(swap! !state assoc :browser/brave? ...)` to dispatch.

- [ ] addressed in code
- [ ] verified by tests

**Actions needed:**
- Add `:popup/ax.set-brave-detected` action to `popup_actions.cljs`
- Replace `swap!` with `dispatch!` in `init!`

---

### Priority 2: Effect State Access

#### 2.1 Panel: `:editor/fx.eval-in-page` reads state
Location: `panel.cljs` line ~149

Effect reads `@!state` for requires. Should receive via args.

- [ ] addressed in code
- [ ] verified by tests

**Actions needed:**
- Modify `:editor/ax.eval` and `:editor/ax.eval-selection` to pass requires to effect
- Update effect to use arg instead of state dereference

#### 2.2 Panel: `:editor/fx.inject-and-eval` reads state
Location: `panel.cljs` line ~163

Same issue as 2.1 - effect reads state for requires.

- [ ] addressed in code
- [ ] verified by tests

**Actions needed:**
- Modify `:editor/ax.eval` and related actions to pass requires
- Update effect to use arg

#### 2.3 Panel: `:editor/fx.clear-persisted-state` reads state
Location: `panel.cljs` line ~211

Effect reads hostname from state.

- [ ] addressed in code
- [ ] verified by tests

**Actions needed:**
- Modify `:editor/ax.new-script` to pass hostname to effect
- Update effect signature to receive hostname as arg

---

### Priority 3: Persistence Pattern (Optional)

#### 3.1 Panel: Persistence watcher
Location: `panel.cljs` lines ~617-627

Watcher calls `save-panel-state!` directly instead of through fx.

- [ ] addressed in code
- [ ] verified by tests

**Actions needed:**
- Consider adding `:editor/fx.persist-panel-state` effect
- Or document as acceptable pattern (watcher-triggered persistence is common)

**Note:** This is lower priority and may be intentionally left as-is if the team decides watcher-based persistence is acceptable for this use case.

---

## Batch Execution Order

**Batch A: Panel Critical Mutations (1.1-1.4)**
1. Run testrunner baseline
2. Add all new panel actions to `panel_actions.cljs`
3. Update `panel.cljs` to use dispatch for all critical mutations
4. Run testrunner verification

**Batch B: Popup Critical Mutation (1.5)**
1. Run testrunner baseline
2. Add new popup action to `popup_actions.cljs`
3. Update `popup.cljs` init
4. Run testrunner verification

**Batch C: Effect State Access (2.1-2.3)**
1. Run testrunner baseline
2. Update actions to pass data to effects
3. Update effects to receive data via args
4. Run testrunner verification

**Batch D: Persistence Pattern (3.1) - Optional**
1. Assess if change is warranted
2. If yes, implement via fx pattern
3. Run testrunner verification

---

## Original Plan-producing Prompt

Thanks! Please crate a dev/docs plan for the fixing of the Uniflow violations you have identified, in priority order.

**Plan structure requirements:**

1. Include a "Required Reading" section listing all relevant dev docs and source files that are mandatory to read before working with implementation (between Standard and Checklist sections)

2. Use a straight checklist format where each item has dual checkboxes:
   - [ ] addressed in code
   - [ ] verified by tests

Verified by tests means at a minimum that existing tests pass after the change. So when a batch of changes is conducted, it starts with delegating to the testrunner subagent and after the batch of changes the testrunner subagent is consulted again.

The plan should also mandate that changes are planned by you, including repl experiments to verify squint assumptions, and actual edits carried out by the clojure editor subagent.

The plan should be structured so that every time something is done, items are simply ticked off without inserting blocks of "this was done" commentary.

The plan should end with a section `## Original Plan-producing Prompt` with this very prompt.
