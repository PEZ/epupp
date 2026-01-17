# Background Uniflow Implementation Plan

Implementation plan for [background-uniflow-proposal.md](background-uniflow-proposal.md).

## Operating Principles

```
[phi fractal euler tao pi mu] | [delta lambda infinity/0 | epsilon phi sigma mu c h] | OODA
Human - AI - REPL
```

- **phi**: Golden balance between understanding and doing
- **fractal**: Small correct pieces compose into correct wholes
- **tao**: The codebase reveals the right path - read it
- **OODA**: Observe deeply, Orient correctly, Decide wisely, Act decisively
- **Human - AI - REPL**: Collaborative loop - human intent, AI implementation, REPL verification

## Phase 1: Pure FS Decision Logic (Testable Core)

Create the pure action handlers and prove they work with unit tests before touching existing code.

### 1.1 Create `background_actions.cljs` [DONE]

**File:** `src/background_actions.cljs`

**Scope:** Pure functions only - no Chrome APIs, no atoms, no side effects.

**Actions to implement:**

| Action | Decision Logic |
|--------|---------------|
| `:fs/ax.rename-script` | Reject if builtin, reject if not found, reject if target name exists, otherwise allow |
| `:fs/ax.delete-script` | Reject if builtin, reject if not found, otherwise allow |
| `:fs/ax.save-script` | Reject if builtin (update case), reject if name exists and not force (create case), otherwise allow |

**Note:** Built-in scripts are protected from all write operations (rename, delete, save/update).

**Helper functions needed:**
- `find-script-by-name [scripts name]` - returns script or nil
- `find-script-by-id [scripts id]` - returns script or nil
- `builtin-script-id? [id]` - delegate to script-utils or inline

**Return shape for actions:**
```clojure
;; Success - state change + effects
{:uf/db new-state
 :uf/fxs [[:storage/fx.persist!]
          [:bg/fx.broadcast-scripts-changed!]
          [:bg/fx.send-response {:success true}]]}

;; Failure - effects only (no state change)
{:uf/fxs [[:bg/fx.send-response {:success false :error "reason"}]]}
```

### 1.2 Write Unit Tests [DONE]

**File:** `test/background_actions_test.cljs`

**Test cases for `:fs/ax.rename-script`:**
- [x] Rejects when source script not found
- [x] Rejects when target name already exists
- [x] Rejects when source is builtin script
- [x] Allows rename when target name is free
- [x] Updates script name and modified timestamp in state

**Test cases for `:fs/ax.delete-script`:**
- [x] Rejects when script not found
- [x] Rejects when script is builtin
- [x] Allows delete, removes from state

**Test cases for `:fs/ax.save-script`:**
- [x] Rejects when updating a builtin script
- [x] Rejects when name exists and not force (create case)
- [x] Allows create when name is new
- [x] Allows update when script exists by ID (non-builtin)
- [x] Allows overwrite when force flag set

### 1.3 Verify Tests Pass [DONE]

```bash
bb test
```

All 330 tests pass including 13 new background-actions tests.

---

## Phase 2: Integrate with Background Worker

Wire the pure actions into background.cljs message handlers.

### 2.1 Set Up Uniflow in Background

- Add `!state` initialization with storage keys
- Create `dispatch!` function using event-handler
- Create `perform-effect!` for background-specific effects

### 2.2 Migrate Message Handlers

Replace direct storage calls with action dispatches:

| Message Type | Current | New |
|--------------|---------|-----|
| `"rename-script"` | Direct `storage/rename-script!` | `dispatch! [[:fs/ax.rename-script ...]]` |
| `"delete-script"` | Direct `storage/delete-script!` | `dispatch! [[:fs/ax.delete-script ...]]` |
| `"save-userscript"` | Direct `storage/save-script!` | `dispatch! [[:fs/ax.save-script ...]]` |

### 2.3 Implement Effects

Create effect handlers in background.cljs:
- `:storage/fx.persist!` - write to chrome.storage.local
- `:bg/fx.broadcast-scripts-changed!` - notify popup/panel
- `:bg/fx.send-response` - call the message response callback
- `:bg/fx.update-badge-for-active-tab!` - badge update

### 2.4 E2E Test for Bug Fix

Add E2E test that verifies:
- `mv!` rejects when target name exists
- No duplicate script names created

---

## Phase 3: Route Panel Through Background

### 3.1 Update Panel FS Operations

Change panel.cljs to send messages instead of direct storage calls:
- `storage/save-script!` -> message to background
- `storage/rename-script!` -> message to background

### 3.2 Remove Storage Import from Panel

Panel should only read from storage (via `load!`), not write.

---

## Phase 4: Refactor Storage Module

### 4.1 Remove `!db` Atom

Storage becomes pure functions that operate on data passed in.

### 4.2 Keep Persist Functions

Storage retains the chrome.storage.local write logic, but called as effects.

### 4.3 Update Background State Loading

Background loads initial state from chrome.storage.local on startup.

---

## Phase 5: Extend to Other Background State

Apply same pattern to:
- Connection management (`:ws/ax.*`)
- Approval management (`:approval/ax.*`)
- Icon state (`:icon/ax.*`)

This phase is optional/future - FS operations are the immediate need.

---

## Success Criteria

- [ ] Unit tests cover all FS decision logic
- [ ] E2E test confirms duplicate-name bug is fixed
- [ ] Badge updates reliably (co-located with state changes)
- [ ] No direct storage mutation from panel
- [ ] All existing E2E tests pass
