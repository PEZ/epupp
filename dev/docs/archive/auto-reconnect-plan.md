# Auto-Reconnect REPL Feature Plan

**Created:** January 12, 2026
**Status:** Planning

## Overview

Add a new setting that automatically reconnects the REPL to tabs that were previously connected but lost connection due to page navigation (non-SPA reload). This is distinct from the existing "auto-connect to all pages" setting.

## Current State

**Existing "Auto-connect REPL on every page" setting:**
- Storage key: `autoConnectRepl`
- Behavior: When enabled, connects REPL to **every page** you navigate to
- Location: Settings section in popup
- Default: `false`

## Requested Feature

**New "Auto-reconnect to previously connected tabs" setting:**
- Automatically reconnect REPL to tabs that **were previously connected** but lost connection due to page navigation
- Default: `true` (enabled by default)
- Should appear **before** the current auto-connect setting in the UI

**Key distinction:**
- Auto-reconnect: Only affects tabs you've manually connected before
- Auto-connect-all: Affects ALL pages, even ones never connected

## Settings Interaction

When **auto-connect-all is ON**, it supersedes auto-reconnect because it connects to ALL pages regardless of connection history. The auto-reconnect setting only matters when auto-connect-all is OFF.

| Auto-reconnect | Auto-connect-all | Tab in history | Result |
|----------------|------------------|----------------|--------|
| ON | OFF | YES | Reconnect using saved port |
| ON | OFF | NO | No action |
| OFF | ON | * | Connect (auto-connect-all handles it) |
| ON | ON | * | Connect (auto-connect-all supersedes) |
| OFF | OFF | * | No action |

**Implementation logic in `handle-navigation!`:**
```clojure
(cond
  ;; Auto-connect-all supersedes everything
  auto-connect-all-enabled?
  (connect-tab! tab-id ws-port)

  ;; Auto-reconnect only for previously connected tabs
  (and auto-reconnect-enabled? (tab-in-history? tab-id))
  (connect-tab! tab-id (get-history-port tab-id))

  ;; Otherwise, no automatic connection
  :else nil)
```

## Technical Approach

The background worker already tracks active connections in `!state` with `:ws/connections`. When a WebSocket closes due to navigation, we currently just clean up. The new feature needs to:

1. Track which tabs have been "intentionally connected" (separate from active connections)
2. On navigation, check if tab was in the "previously connected" set
3. If setting is enabled, attempt reconnection after page loads

## Workflow

**ALWAYS use `bb <task>` over direct shell commands.** The bb tasks encode project-specific configurations. Check `bb tasks` for available commands.

**ALWAYS check lint/problem reports after edits.** Use `get_errors` tool to verify no syntax or bracket errors before running tests.

**ALWAYS use the `edit` subagent for file modifications.** The edit subagent specializes in Clojure/Squint structural editing and avoids bracket balance issues. Provide it with complete context: file paths, line numbers, and the exact changes needed.

- `bb test` - Compile and run unit tests
- `bb test:e2e` - Compile and run E2E tests (Docker)

## Implementation Plan

### Phase 1: Storage and State

**Files:** `popup.cljs`, `popup_actions.cljs`

| Task | Description |
|------|-------------|
| 1.1 | Add `:settings/auto-reconnect-repl` to popup state (default `true`) |
| 1.2 | Add storage key `autoReconnectRepl` for persistence |
| 1.3 | Add `:popup/ax.load-auto-reconnect-setting` action |
| 1.4 | Add `:popup/ax.toggle-auto-reconnect-repl` action |
| 1.5 | Add `:popup/fx.load-auto-reconnect-setting` and `:popup/fx.save-auto-reconnect-setting` effects |

### Phase 2: Background Worker Tracking

**Files:** `background.cljs`, `background_utils.cljs`

| Task | Description |
|------|-------------|
| 2.1 | Add `:connected-tabs/history` to `!state` - tracks tabs intentionally connected (tab-id -> ws-port) |
| 2.2 | Update `handle-ws-connect` to record tab in history when connection established |
| 2.3 | Add `get-auto-reconnect-settings` function to read the new setting |
| 2.4 | Clean up history entry when tab closes (`chrome.tabs.onRemoved`) |

### Phase 3: Reconnection Logic

**Files:** `background.cljs`

| Task | Description |
|------|-------------|
| 3.1 | Update `onBeforeNavigate` handler: when closing WS due to navigation, note the tab for potential reconnect |
| 3.2 | In `handle-navigation!`, check if tab is in connected-tabs history AND auto-reconnect is enabled |
| 3.3 | If conditions met, attempt reconnection using stored port |
| 3.4 | Handle reconnection failures gracefully (log warning, don't crash) |
| 3.5 | Ensure auto-connect-all check comes FIRST (supersedes auto-reconnect when enabled) |

### Phase 4: UI Updates

**Files:** `popup.cljs`

| Task | Description |
|------|-------------|
| 4.1 | Add new checkbox BEFORE the existing auto-connect setting in `settings-content` |
| 4.2 | Update label and warning text for both settings to clarify distinction |
| 4.3 | Load new setting in `init!` dispatch |

### Phase 5: Testing

**Files:** `e2e/log_powered_test.cljs`, `test/popup_actions_test.cljs`

| Task | Description |
|------|-------------|
| 5.1 | Add unit tests for new action handlers |
| 5.2 | Add E2E test: verify auto-reconnect triggers on page reload in previously connected tab |
| 5.3 | Add E2E test: verify auto-reconnect does NOT trigger for tabs never connected |
| 5.4 | Update existing auto-connect test descriptions for clarity |

## UI Text

**New setting (appears first):**
- Label: "Auto-reconnect REPL to previously connected tabs"
- Description: "When a connected tab navigates to a new page, automatically reconnect the REPL. REPL state will be lost but connection will be restored."

**Existing setting (appears second, update wording):**
- Label: "Auto-connect REPL to all pages"
- Warning: "Warning: Enabling this will inject the Scittle REPL on every page you visit, even tabs that were never connected. Only enable if you understand the implications."

## Edge Cases to Handle

1. **Tab closes before reconnect completes** - Check tab still exists before attempting
2. **Port no longer available** - Server stopped between disconnect and reconnect; log warning and fail gracefully
3. **Both settings enabled** - Auto-connect-all supersedes; skip auto-reconnect check entirely
4. **Multiple rapid navigations** - Each navigation closes previous WS; the final navigation's onCompleted triggers reconnect
5. **Service worker sleep** - See "Design Decisions" below

## Design Decisions

### In-Memory History (Not Persisted)

Connection history is stored in `!state` (in-memory) rather than `chrome.storage.local`. This is intentional:

**Why this is acceptable:**
- When the service worker sleeps (~30s inactivity), WebSocket connections close anyway (the worker holds them)
- If connections were lost due to worker sleep, the history being lost is consistent - tabs weren't connected anyway
- User can always manually reconnect after extended inactivity

**Trade-off:** If the worker sleeps while tabs are connected, those connections are lost and won't auto-reconnect when the worker wakes. This is a Manifest V3 limitation, not specific to our implementation.

### Visual Feedback

Use the existing connection status indicator in the popup for auto-reconnect feedback. No new UI elements needed - the "Connecting..." / "Connected!" status messages already exist.

### Memory Limits

No explicit limit on history size. Each connection requires its own browser-nrepl server and port, so practical limits (~50 tabs) are far below memory concerns.

## Related Files

- [popup.cljs](../../src/popup.cljs) - UI and effects
- [popup_actions.cljs](../../src/popup_actions.cljs) - Pure action handlers
- [background.cljs](../../src/background.cljs) - WebSocket management, navigation handling
- [background_utils.cljs](../../src/background_utils.cljs) - Pure utility functions
- [architecture.md](../architecture.md) - System design reference
