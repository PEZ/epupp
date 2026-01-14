# Log-Powered Test Reorganization Plan

**Date:** January 13, 2026
**Status:** In Progress

## Problem

Tests in `e2e/log_powered_test.cljs` are categorized by implementation technique ("log-powered") rather than by the feature they test. This makes it harder to find tests for specific functionality.

## Solution

Reorganize tests by area of concern, keeping the log-powered testing technique available via fixtures.

## Test Distribution

### 1. New: `e2e/userscript_test.cljs`

Userscript injection, timing, and related functionality:

| Test | Notes |
|------|-------|
| "userscript injects on matching URL and logs SCRIPT_INJECTED" | Core injection |
| "document-start script runs before page scripts" | Timing verification |
| "generate performance report from events" | Performance tooling |
| "userscript injection produces no uncaught errors" | Error checking |
| "gist installer shows Install button and installs script" | Built-in userscript |

### 2. Move to `e2e/popup_test.cljs`

Auto-connect, connection tracking, and REPL settings UI:

| Test | Notes |
|------|-------|
| "auto-connect REPL triggers Scittle injection on page load" | Settings behavior |
| "SPA navigation does NOT trigger REPL reconnection" | Settings behavior |
| "toolbar icon reflects REPL connection state" | UI indicator |
| "injected state is tab-local, connected state is global" | State management |
| "get-connections returns active REPL connections" | Connection API |
| "popup UI displays active REPL connections" | Connection UI |
| "popup UI updates immediately after connecting" | Live updates |
| "popup UI updates when connected page is reloaded" | Reconnect behavior |
| "auto-reconnect triggers Scittle injection on page reload" | Auto-reconnect |
| "auto-reconnect does NOT trigger for tabs never connected" | Auto-reconnect |
| "disabled auto-reconnect does NOT trigger on page reload" | Auto-reconnect |

### 3. New: `e2e/extension_test.cljs`

Extension infrastructure and startup:

| Test | Notes |
|------|-------|
| "extension starts and emits startup event" | Startup verification |
| "dev log button works and captures console output" | Test infrastructure |
| "extension startup produces no uncaught errors" | Error checking |

### 4. Keep in `e2e/fixtures.cljs`

- `assert-no-errors!` helper (already used by z_final_error_check_test.cljs)
- `code-with-manifest` helper (duplicated in multiple files, consolidate)

### 5. Delete

- `e2e/log_powered_test.cljs` - once all tests redistributed

## Documentation Updates

Update `dev/docs/testing-e2e.md`:
- Add new test files to the table
- Remove log_powered_test.cljs entry
- Keep log-powered testing technique documentation

## Execution Order

1. [x] Write this plan
2. [ ] Create `e2e/userscript_test.cljs` with userscript tests
3. [ ] Create `e2e/extension_test.cljs` with infrastructure tests
4. [ ] Add connection/settings tests to `e2e/popup_test.cljs`
5. [ ] Update `dev/docs/testing-e2e.md`
6. [ ] Delete `e2e/log_powered_test.cljs`
7. [ ] Run `bb test:e2e` to verify all tests pass
8. [ ] Commit changes

## Notes

- The `code-with-manifest` helper is duplicated in popup_test, panel_test, integration_test, log_powered_test, and require_test. Consider consolidating to fixtures.cljs.
- The `assert-no-errors!` function exists in both log_powered_test.cljs and z_final_error_check_test.cljs. The one in z_final should be the canonical version.
