# Code Review: 2026-01-11

Comprehensive code review focusing on bugs, errors, and potential issues.

## ⚠️ Workflow Reminder

**ALWAYS use `bb <task>` over direct shell commands.** The bb tasks encode project-specific configurations. Check `bb tasks` for available commands.

**ALWAYS check lint/problem reports after edits.** Use `get_errors` tool to verify no syntax or bracket errors before running tests.

**ALWAYS use the `edit` subagent for file modifications.** The Clojure-editor subagent specializes in Clojure/Squint structural editing and avoids bracket balance issues. Provide it with complete context: file paths, line numbers, and the exact changes needed.

- `bb test` - Compile and run unit tests
- `bb test:e2e` - Compile and run E2E tests (Docker)

Before reporting a task as done:

1. Run unit ad e2e tests
2. Build the extension for manual testing, `bb build:dev`

## Summary

| Severity | Count | Description |
|----------|-------|-------------|
| Critical | 0 | - |
| High | 0 | All resolved |
| Medium | 0 | All resolved |
| Low | 0 | All resolved |

## Resolved Issues

### 1. Panel State Restoration: Uniflow Violation - FIXED

**Status:** Resolved January 11, 2026

**Original problem:** `restore-panel-state!` used direct `swap!` to set `current-hostname` outside Uniflow, then dispatched other state via `[:editor/ax.initialize-editor]`. This violated the Uniflow pattern.

**Fix:** Modified `:editor/ax.initialize-editor` action to accept `hostname` in its data map. The action handler now sets `:panel/current-hostname` along with other panel state, keeping all state updates within Uniflow.

---

### 2. Missing Error Handling in `save-panel-state!` - FIXED

**Status:** Resolved January 11, 2026

**Original problem:** `save-panel-state!` wrote to `chrome.storage.local` without checking for errors. If storage quota is exceeded or write fails, it failed silently.

**Fix:** Added callback to `chrome.storage.local.set` that checks `chrome.runtime.lastError` and logs errors:
```clojure
(js/chrome.storage.local.set
 (js-obj key state-to-save)
 (fn []
   (when js/chrome.runtime.lastError
     (js/console.error "[Panel] Failed to save state:"
                       (.-message js/chrome.runtime.lastError)))))
```

---

### 4. Icon State Memory Leak - FIXED

**Status:** Resolved January 11, 2026

**Original problem:** Icon states stored with tab IDs as keys. Cleanup happened in `onRemoved` listener, but if the service worker was asleep when a tab closed, cleanup didn't fire. Over time, orphaned entries could accumulate.

**Fix:** Added `prune-icon-states!` function that queries existing tabs and removes entries for tabs that no longer exist. Called during `ensure-initialized!` on service worker wake:
```clojure
(defn ^:async prune-icon-states!
  "Remove icon states for tabs that no longer exist."
  []
  (let [tabs (js-await (js/chrome.tabs.query #js {}))
        valid-ids (set (map #(.-id %) tabs))]
    (swap! !state update :icon/states
           (fn [states]
             (select-keys states valid-ids)))))
```

---

### 5. Missing Validation in `approve-pattern!` - FIXED

**Status:** Resolved January 11, 2026

**File:** [storage.cljs](../../src/storage.cljs#L176-L196)
**Category:** Logic
**Severity:** Medium

**Original problem:** `approve-pattern!` didn't validate that the script exists. If called with invalid script-id, it silently did nothing and still persisted, potentially hiding bugs.

**Fix:** Added validation and early return with warning:
```clojure
(defn approve-pattern!
  "Add a pattern to a script's approved-patterns list.
   Logs warning and returns nil if script doesn't exist."
  [script-id pattern]
  (if-not (get-script script-id)
    (js/console.warn "[Storage] approve-pattern! called for non-existent script:" script-id)
    (do
      (swap! !db update :storage/scripts ...)
      (persist!))))
```

---

### 6. `send-to-tab` Swallows Errors - FIXED

**Status:** Resolved January 11, 2026

**File:** [background.cljs](../../src/background.cljs#L91-L95)
**Category:** Async/Message
**Severity:** Medium

**Original problem:** `send-to-tab` logged errors but didn't propagate them to callers. This could hide failures when the content script isn't injected or tab is closed.

**Fix:** Return the promise directly, let callers decide on error handling:
```clojure
(defn send-to-tab
  "Send message to content script in a tab.
   Returns the promise - callers should handle errors as appropriate."
  [tab-id message]
  (js/chrome.tabs.sendMessage tab-id (clj->js message)))
```

---

### 7. Manifest Parser Doesn't Validate Description Type - FIXED

**Status:** Resolved January 11, 2026

**File:** [manifest_parser.cljs](../../src/manifest_parser.cljs#L48-L49)
**Category:** Logic
**Severity:** Medium

**Original problem:** The manifest parser extracted `description` without validating it's a string. If a user provides a map or vector, it would be stored as-is and cause display issues.

**Fix:** Added type validation:
```clojure
description (let [d (aget parsed "epupp/description")]
              (when (string? d) d))
```

---

### 9. Test Logger Potentially Installed Multiple Times - FIXED

**Status:** Resolved January 11, 2026

**File:** [test_logger.cljs](../../src/test_logger.cljs#L77-L99)
**Category:** Logic
**Severity:** Low

**Original problem:** `install-global-error-handlers!` could add duplicate event listeners if called multiple times in the same context.

**Fix:** Added guard using a flag on the global object:
```clojure
(defn install-global-error-handlers!
  "Install global error handlers that log to test events.
   Guards against double-installation using a flag on global-obj."
  [context-name global-obj]
  (when (and (test-mode?)
             (not (aget global-obj "__epupp_error_handlers_installed")))
    (aset global-obj "__epupp_error_handlers_installed" true)
    ;; ... rest of function
  ))
```

---

## Low Severity Issues (Resolved)

### 8. Inconsistent Error Message Prefixes - FIXED

**File:** Multiple files
**Category:** Consistency
**Severity:** Low
**Status:** Resolved January 11, 2026

**Original problem:** Console logging prefixes were inconsistent across modules (`[Background]`, `[Bridge]`, `[Check Status]`, `[Epupp]`, etc.), making filtering and searching harder.

**Fix:** Created `src/log.cljs` module with helper functions (`log/info`, `log/warn`, `log/error`) that produce consistent prefixes in format `[Epupp:Module]` or `[Epupp:Module:Context]`.

**Files updated:**

| File | Status | Notes |
|------|--------|-------|
| `src/log.cljs` | Created | Helper module |
| `src/background.cljs` | Done | Uses `log/*` functions |
| `src/storage.cljs` | Done | Uses `log/*` functions |
| `src/panel.cljs` | Done | Uses `log/*` functions |
| `src/content_bridge.cljs` | Done | Uses `log/*` functions |
| `src/ws_bridge.cljs` | Done | Manual prefix update (MAIN world, can't use module) |
| `src/popup.cljs` | Done | Uses `log/*` functions |
| `src/devtools.cljs` | Done | Uses `log/*` functions |
| `src/panel_actions.cljs` | Skipped | Intentional demo log output |
| `src/event_handler.cljs` | Skipped | Generic fx handler |
| `src/test_logger.cljs` | Skipped | Test infrastructure |

All logging now uses consistent `[Epupp:Module]` format for easy filtering with `grep "[Epupp:"`.

---

## Logging System Design

Epupp uses two logging systems with distinct purposes:

### 1. Console Logging (Human-readable)

For developer debugging via browser DevTools console.

**Standard Format:** `[Epupp:Module]` or `[Epupp:Module:Context]`

| Module | Context | Example |
|--------|---------|---------|
| `Background` | (none) | `[Epupp:Background] Initialization complete` |
| `Background` | `WS` | `[Epupp:Background:WS] Connected for tab: 123` |
| `Background` | `Inject` | `[Epupp:Background:Inject] Executing 2 approved scripts` |
| `Storage` | (none) | `[Epupp:Storage] Loaded 5 scripts` |
| `Panel` | (none) | `[Epupp:Panel] Initializing...` |
| `Popup` | (none) | `[Epupp:Popup] Init!` |
| `Bridge` | (none) | `[Epupp:Bridge] Content script loaded` |
| `Bridge` | `WS` | `[Epupp:Bridge:WS] WebSocket connected` |
| `WsBridge` | (none) | `[Epupp:WsBridge] Installing WebSocket bridge` |

**Design Principles:**
- All prefixes start with `Epupp:` for easy filtering (`grep "\[Epupp:"`)
- Module name matches the source file/namespace
- Context is optional, for logical sub-areas within a module
- No spaces in prefix (avoids `[Check Status]` style)

**Current → Proposed Mapping:**

| Current | Proposed |
|---------|----------|
| `[Background]` | `[Epupp:Background]` |
| `[Epupp Background]` | `[Epupp:Background]` |
| `[Auto-inject]` | `[Epupp:Background:Inject]` |
| `[Userscript]` | `[Epupp:Background:Inject]` |
| `[Approval]` | `[Epupp:Background:Approval]` |
| `[Install]` | `[Epupp:Background:Install]` |
| `[Storage]` | `[Epupp:Storage]` |
| `[Panel]` | `[Epupp:Panel]` |
| `[Check Status]` | `[Epupp:Popup]` |
| `[Bridge]` | `[Epupp:Bridge]` |
| `[Epupp Bridge]` | `[Epupp:Bridge]` |
| `[Epupp]` | `[Epupp:WsBridge]` |

### 2. Test Logger (Structured Events)

For E2E test assertions via `chrome.storage.local`. Already consistent.

**Event Format:**
```javascript
{event: "SCREAMING_SNAKE_CASE", ts: Date.now(), perf: performance.now(), data: {...}}
```

**Naming Convention:** `VERB_NOUN` or `NOUN_VERB` in `SCREAMING_SNAKE_CASE`

**Current Events (from architecture.md):**

| Event | Module | Purpose |
|-------|--------|---------|
| `EXTENSION_STARTED` | background | Baseline timing |
| `SCITTLE_LOADED` | background | Load performance |
| `SCRIPT_INJECTED` | background | Injection tracking |
| `BRIDGE_READY_CONFIRMED` | background | Bridge setup overhead |
| `WS_CONNECTED` | background | REPL connection tracking |
| `NAVIGATION_STARTED` | background | Auto-injection pipeline |
| `NAVIGATION_PROCESSED` | background | Script matching diagnostics |
| `BRIDGE_READY` | content_bridge | Bridge initialization |
| `PANEL_RESTORE_START` | panel | Panel state restoration |
| `ICON_STATE_CHANGED` | background | Icon state transitions |
| `UNCAUGHT_ERROR` | test_logger | Global error handler |
| `UNHANDLED_REJECTION` | test_logger | Promise rejection handler |

**When to Add Test Events:**
- Observable state transitions (connected, loaded, injected)
- Error conditions that tests should verify
- Timing-critical operations for performance assertions
- NOT for routine debug logging (use console for that)

### Implementation Notes

**For future implementation:**

1. Update all `js/console.log/warn/error` calls to use new prefix format
2. Consider a logging helper function for consistency:
   ```clojure
   (defn log [level module context & args]
     (let [prefix (if context
                    (str "[Epupp:" module ":" context "]")
                    (str "[Epupp:" module "]"))]
       (apply (case level
                :log js/console.log
                :warn js/console.warn
                :error js/console.error)
              prefix args)))
   ```
3. Changes should be batched per-file to minimize churn

---

### 10. Panel State `save-panel-state!` Reads from Atom - FIXED

**Status:** Resolved January 11, 2026

**File:** [panel.cljs](../../src/panel.cljs#L47-L63)
**Category:** Architecture
**Severity:** Low

**Original problem:** `save-panel-state!` read state directly from `@!state` including hostname, creating architectural inconsistency with the Uniflow pattern where state should flow explicitly.

**Fix:** Changed `save-panel-state!` to accept state as a parameter. The `add-watch` callback now passes `new-state` directly:

```clojure
(defn save-panel-state!
  "Persist editor state per hostname. Receives state snapshot to ensure consistency."
  [state]
  (when-let [hostname (:panel/current-hostname state)]
    ...))

;; In add-watch:
(add-watch !state :panel/persist
  (fn [_ _ old-state new-state]
    (when (fields-changed? old-state new-state)
      (save-panel-state! new-state))))
```

This ensures state flows explicitly through the system rather than being read from the atom inside side-effecting functions.

---

## Verified Non-Issues

These items were flagged during review but confirmed as correct behavior or intentionally removed. Documented here to avoid re-analysis in future reviews.

### `valid-run-at-values` Set Usage

The code correctly uses `contains?` to check set membership, not calling the set as a function (which would fail in Squint). This is the correct pattern per [squint.instructions.md](../../.github/squint.instructions.md#6-sets-are-not-callable).

### Firefox Registration on Startup

`ensure-initialized!` does call `sync-registrations!`, so Firefox registrations are correctly re-created on service worker startup. Not a bug.

### `normalize-match-array` - Removed

**Status:** Removed January 2026

This function attempted to flatten nested arrays in match patterns. Analysis confirmed nested arrays cannot occur in normal operation:

- Panel save: manifest -> `normalize-match-patterns` -> always flat vector
- Background install: string -> `[site-match]` -> flat vector
- Built-in scripts: hardcoded flat vectors

The only path for nested arrays would be malformed import JSON. Since the userscripts feature was pre-release with no external users, this defensive code added complexity without protecting against a real scenario. Removed rather than maintained.

See [userscripts-architecture.md](userscripts-architecture.md#data-integrity-invariants) for the documented invariant.
