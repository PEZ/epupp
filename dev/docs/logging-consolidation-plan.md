# Logging Consolidation Plan

Reduce verbose browser console output from Epupp extension components. The bridge logs in particular clutter the user's browser DevTools, making it harder to see their own application logs.

## Current State (2026-02-06)

### The Problem

Epupp logs liberally to the browser's console with no level filtering. Every `log/info` call emits in dev, test, and prod. The noisiest sources:

| Source | Log calls | Impact |
|--------|-----------|--------|
| `ws_bridge.cljs` | 11 direct `console.log` | Logs every WS message type received - fires per nREPL exchange |
| `content_bridge.cljs` | 29 `log/info` | Logs every forwarded FS message, every script inject step |
| `bg_fs_dispatch.cljs` | 11 `log/info` | Logs every FS dispatch with entry/exit/timing |
| `background.cljs` | 16 `log/info` | Logs init, navigation, connections |

### Four Logging Layers

| Layer | Namespace | Gate | Output | Consumer |
|-------|-----------|------|--------|----------|
| Console logging | `log/*` | None (always on) | `console.*` with `[Epupp:Module:Context]` prefix | Browser DevTools |
| Direct console | `js/console.*` | None (always on) | Raw `console.*` | Browser DevTools |
| Test events | `test-logger/log-event!` | `EXTENSION_CONFIG.test` | `chrome.storage.local["test-events"]` | Playwright E2E tests |
| System banners | `bg-icon/broadcast-system-banner!` | None (always on) | `chrome.runtime.sendMessage` + badge flash | Popup/panel UI |

### Log Namespace Has No Filtering

`src/log.cljs` is a thin prefix formatter. It has no config awareness, no log level gates - it always emits:

```clojure
(defn log [level module context & args]
  (let [prefix (if context
                 (str "[Epupp:" module ":" context "]")
                 (str "[Epupp:" module "]"))]
    (apply (case level :log js/console.log :warn js/console.warn :error js/console.error
                 js/console.log)
           prefix args)))
```

The two-parameter subsystem (module + context) is awkward - 70% of callers pass `nil` for context. A single subsystem string is cleaner:

```clojure
;; Before: 70% of calls look like this
(log/info "Bridge" nil "Forwarding list-scripts request")
(log/info "Background" "WS" "Closing connection")

;; After: single subsystem, colon-separated when needed
(log/info "Bridge" "Forwarding list-scripts request")
(log/info "Background:WS" "Closing connection")
```

Same prefix output: `[Epupp:Background:WS]`. One fewer arg, no `nil` noise.

### Uniflow `:log/fx.log` Bypasses Log Namespace

The `:log/fx.log` effect in `event_handler.cljs` calls `console.*` directly instead of routing through the `log` namespace:

```clojure
:log/fx.log
(let [[level & ms] args]
  (apply (case level :debug js/console.debug :log js/console.log ...)
         ms))
```

This means Uniflow logging bypasses any formatting, gating, or future routing that `log.cljs` provides. It should use the log infrastructure.

### ws_bridge Constraint

`ws_bridge.cljs` runs in MAIN world (page context) and cannot import Squint modules. It uses direct `console.log` with manual `[Epupp:WsBridge]` prefixes. However, it already communicates with content_bridge via `postMessage` (e.g., `{source: "epupp-page", type: "ws-connect", ...}`), so it can send log messages through the same channel and have content_bridge route them through the `log` namespace.

### Infrastructure That Already Exists

- **Test logger** (`test_logger.cljs`): Structured events to `chrome.storage.local`, gated by `:test` config. Well-designed write queue. Purpose-built for E2E assertions.
- **System banner broadcasts** (`bg_icon.cljs`): Routes FS notifications from background to popup/panel. Fire-and-forget. Designed for user-visible notifications.
- **Uniflow `:log/fx.log` effect**: Routes log calls through `console.*` but bypasses `log.cljs`. Should route through the log namespace.

---

## Approach: Gated Debug Logging via Settings

**Core insight:** Developers often run DevTools in verbose mode while debugging their own apps - browser's `console.debug` filtering doesn't help because it's a global toggle. If we downgrade to `console.debug`, Epupp noise just moves from "default visible" to "visible when debugging", which is still pollution.

**Solution:** Add an Epupp setting "Enable debug logging" (default OFF). The `log` namespace checks this setting before emitting debug-level messages. Users can enable it when reporting issues to us.

This gives us:
- Clean browser console by default (even in verbose mode)
- Debug info available on demand via Epupp settings
- Single toggle for users to flip when reporting issues
- Works across all build configs

**Implementation:**
- Store setting in `chrome.storage.local` under key `"settings/debug-logging"`
- Background worker caches the setting in memory at startup and on storage change
- `log/debug` checks the cached flag before emitting
- For content scripts: they also listen to storage changes to stay in sync
- For ws_bridge (MAIN world): route logs through content_bridge via `postMessage`, which applies the gate before emitting

For `ws_bridge.cljs` (MAIN world), we route logs through content_bridge via `postMessage`, so they flow through the unified `log` namespace. A small helper in ws_bridge sends `{source: "epupp-page", type: "log", ...}` messages, and a new handler in content_bridge's message listener calls `log/*` on receipt. This means all extension logging goes through one system, with consistent gating.

---

## Standard

- All edits delegated to **Clojure-editor subagent**
- Before each batch: delegate to **epupp-testrunner subagent** for baseline
- After each batch: delegate to **epupp-testrunner subagent** for verification
- Tick checkboxes without inserting commentary blocks

---

## Required Reading

### Source Files
- [src/log.cljs](../../src/log.cljs) - Log namespace (add `debug` function here)
- [src/ws_bridge.cljs](../../src/ws_bridge.cljs) - MAIN world, direct `console.log` (11 calls)
- [src/content_bridge.cljs](../../src/content_bridge.cljs) - Content script bridge (29 `log/info` calls)
- [src/bg_fs_dispatch.cljs](../../src/bg_fs_dispatch.cljs) - FS dispatch logging (11 `log/info` calls)
- [src/background.cljs](../../src/background.cljs) - Background worker (16 `log/info` calls)
- [src/bg_inject.cljs](../../src/bg_inject.cljs) - Injection logging (6 `log/info` calls)
- [src/bg_ws.cljs](../../src/bg_ws.cljs) - Background WS (8 `log/info` calls)
- [src/event_handler.cljs](../../src/event_handler.cljs) - `:log/fx.log` effect, Uniflow framework tracing
- [src/registration.cljs](../../src/registration.cljs) - Content script registration logging (13 calls, uses context arg)

### Build Config
- [config/dev.edn](../../config/dev.edn) - `:dev true`
- [config/test.edn](../../config/test.edn) - `:dev true, :test true`
- [config/prod.edn](../../config/prod.edn) - `:dev false`

---

## Checklist

### 1. Add debug logging setting to storage and popup settings UI

**1a. Add storage key and background caching**

Location: `src/background.cljs`, `src/storage.cljs`

- Add `"settings/debug-logging"` to storage schema (default: `false`)
- Background caches the value in `!app-db` at startup
- Listen to `chrome.storage.onChanged` to update cache when setting changes

- [ ] addressed in code
- [ ] verified by tests

**1b. Add settings toggle to popup**

Location: `src/popup.cljs`, `src/popup_actions.cljs`

- Add checkbox in Settings section: "Enable debug logging"
- Checkbox reads from and writes to `"settings/debug-logging"`
- Include helper text: "Show verbose Epupp logs in browser console (for troubleshooting)"

- [ ] addressed in code
- [ ] verified by tests

### 2. Simplify log API and add gated `debug`

Location: `src/log.cljs`

**API Change:** Simplify from `(log level module context & args)` to `(log level subsystem & messages)`. Analysis showed ~70% of callers pass `nil` for context. The few that use context can combine: `(log/info "Module" "Context" ...)` becomes `(log/info "Module:Context" ...)`.

**Gate Debug:** Add `!debug-enabled?` atom with setter. The `debug` function checks it before emitting.

```clojure
(def !debug-enabled? (atom false))

(defn set-debug-enabled! [enabled?]
  (reset! !debug-enabled? enabled?))

(defn log
  "Log message with subsystem prefix. Format: [Epupp:Subsystem] message"
  [level subsystem & messages]
  (let [prefix (str "[Epupp:" subsystem "]")]
    (apply (case level
             :debug js/console.debug
             :info js/console.info
             :warn js/console.warn
             :error js/console.error
             js/console.log)
           prefix messages)))

(defn debug
  "Log debug message. Only emits if debug logging is enabled in settings."
  [subsystem & messages]
  (when @!debug-enabled?
    (apply log :debug subsystem messages)))

;; Convenience functions (optional - keeps call sites clean)
(defn info [subsystem & messages] (apply log :info subsystem messages))
(defn warn [subsystem & messages] (apply log :warn subsystem messages))
(defn error [subsystem & messages] (apply log :error subsystem messages))
```

**Migration:** Update existing callers:
- `(log/info "Module" nil ...)` → `(log/info "Module" ...)`
- `(log/info "Module" "Context" ...)` → `(log/info "Module:Context" ...)`

Background and content scripts call `log/set-debug-enabled!` on startup and when storage changes.

- [ ] addressed in code
- [ ] verified by tests

### 2b. Route `:log/fx.log` effect through log namespace

Location: `src/event_handler.cljs`

The `:log/fx.log` effect currently calls `console.*` directly. Update to use the log namespace functions:

```clojure
:log/fx.log
(fn [{:keys [level subsystem messages]}]
  (apply (case level
           :debug log/debug
           :info log/info
           :warn log/warn
           :error log/error
           log/info)
         subsystem messages))
```

This ensures all logging goes through the gated infrastructure.

- [ ] addressed in code
- [ ] verified by tests

### 3. Route ws_bridge logs through content_bridge

ws_bridge runs in MAIN world and cannot import the log namespace. We use the existing postMessage channel to send log messages to content_bridge, which routes them through the gated `log` namespace.

**3a. Add log helper to ws_bridge**

Location: `src/ws_bridge.cljs`

Add a helper that sends log messages via postMessage:

```clojure
(defn- bridge-log [level & args]
  (.postMessage js/window
               #js {:source "epupp-page"
                    :type "log"
                    :level (name level)
                    :subsystem "WsBridge"
                    :messages (to-array args)}
               "*"))
```

Replace all direct `console.log` calls with `bridge-log`:

| Current | Change to |
|---------|-----------|
| `console.log` "Bridge is ready" | `(bridge-log :debug "Bridge is ready")` |
| `console.log` "Creating bridged WebSocket" | `(bridge-log :debug "Creating bridged WebSocket for:" url)` |
| `console.log` "Removing old message handler" | `(bridge-log :debug "Removing old message handler")` |
| `console.log` "Received message type:" | Remove entirely (per-message noise, negligible value) |
| `console.log` "WebSocket OPEN" | `(bridge-log :debug "WebSocket OPEN")` |
| `console.log` "WebSocket ERROR" | `(bridge-log :error "WebSocket ERROR")` |
| `console.log` "WebSocket CLOSED" | `(bridge-log :debug "WebSocket CLOSED")` |
| `console.log` "readyState is now:" | `(bridge-log :debug "readyState is now:" ...)` |
| `console.log` "Installing WebSocket bridge" | `(bridge-log :debug "Installing WebSocket bridge")` |
| `console.log` "Intercepting nREPL WebSocket:" | `(bridge-log :debug "Intercepting nREPL WebSocket:" url)` |
| `console.log` "WebSocket bridge installed" | `(bridge-log :debug "WebSocket bridge installed")` |

- [ ] addressed in code
- [ ] verified by tests

**3b. Add log message handler to content_bridge**

Location: `src/content_bridge.cljs`

Add a handler for `"log"` message type in the existing page message listener:

```clojure
"log"
(let [level (keyword (.-level msg))
      subsystem (.-subsystem msg)
      messages (js->clj (.-messages msg))]
  (apply log/log level subsystem messages))
```

This routes ws_bridge logs through the unified `log` namespace with the same gating as all other logs.

- [ ] addressed in code
- [ ] verified by tests

### 4. Downgrade content_bridge forwarding logs

Location: `src/content_bridge.cljs`

Change operational/forwarding logs to `log/debug`. Keep error and context invalidation logs at current levels.

**Change to `log/debug`** (routine operational messages):
- "Requesting connection to port:" (line 81)
- "Forwarding list-scripts request" (line 95)
- "Forwarding save-script request" (line 116)
- "Forwarding rename-script request" (line 143)
- "Forwarding delete-script request" (line 166)
- "Forwarding get-script request" (line 191)
- "Forwarding manifest request" (line 213)
- "Forwarding install request" (line 247)
- "Forwarding save-script request from userscript" (line 270)
- "Script already injected, skipping:" (line 306)
- "Script loaded:" (line 314)
- "Clearing N old userscript tags" (line 331)
- "Injected userscript:" (line 342)
- "Responding to ping" (line 350)
- "WebSocket connected" (line 378)
- "WebSocket closed" (line 409)
- "Injecting script:" (line 324)
- "Content script loaded" (line 426)

**Keep at `log/info` or `log/error`** (error conditions):
- "Extension context invalidated" (lines 57, 110, 137, 160, 185, 207, 227, 264, 288) - downgrade to `log/debug`
- "Script load error:" (line 318) - already `log/error`
- "WebSocket error:" (line 397) - already `log/error`

- [ ] addressed in code
- [ ] verified by tests

### 5. Downgrade bg_fs_dispatch operational logs

Location: `src/bg_fs_dispatch.cljs`

**Change to `log/debug`:**
- "perform-fs-effect! START:" (line 12)
- "Sending response:" (line 27)
- "dispatch-fs-action! START:" (line 39)
- "Got scripts, count:" (line 43)
- "Handler result keys:" (line 46)
- "Effects to execute:" (line 48)
- "dispatch-fs-action! DONE:" (line 63)

**Keep at current level:**
- "Unknown FS effect:" (line 30) - `log/warn`
- "SLOW effect" (line 33) - `log/warn`
- "SLOW dispatch" (line 65) - `log/error`
- "dispatch-fs-action! ERROR:" (line 67) - `log/error`

- [ ] addressed in code
- [ ] verified by tests

### 6. Downgrade background.cljs operational logs

Location: `src/background.cljs`

Review 16 `log/info` calls. Downgrade routine operational messages to `log/debug`, keep significant lifecycle and error logs at `info`/`warn`/`error`.

Candidates for `log/debug`:
- Tab-close cleanup messages
- WS-closing-on-navigation messages
- Script change notifications
- Connection list details

Keep at `log/info`:
- Service worker start
- Extension installed/started
- Initialization complete/failed

- [ ] addressed in code
- [ ] verified by tests

### 7. Review remaining files for downgrade opportunities

Review and downgrade where appropriate:
- `bg_inject.cljs` (6 `log/info` calls)
- `bg_ws.cljs` (8 `log/info` calls)
- `panel.cljs` (5 `log/info` calls)
- `storage.cljs` (5 `log/info` calls)

Apply same principle: routine operational messages become `debug`, lifecycle/error messages stay at current level.

- [ ] addressed in code
- [ ] verified by tests

### 8. Verify E2E tests still pass

E2E tests may rely on console output for assertions (via Playwright `page.on('console')` listeners). Verify that downgrading to `console.debug` doesn't break any test assertions.

Note: Test logger events are stored via `chrome.storage.local`, not console, so they should be unaffected. But some tests may listen for console output directly.

- [ ] verified by tests

---

## Execution Order

### Batch A: Foundation - Settings and Log Namespace
1. Run testrunner baseline
2. Add debug logging setting to storage (#1a)
3. Add settings toggle to popup (#1b)
4. Simplify log API and add gated `debug` function (#2)
5. Route `:log/fx.log` effect through log namespace (#2b)
6. Migrate existing log callers to new API
7. Run testrunner verification

### Batch B: Unify ws_bridge Logging
1. Add log helper to ws_bridge (#3a)
2. Add log message handler to content_bridge (#3b)
3. Replace all direct console.log calls in ws_bridge with bridge-log
4. Run testrunner verification

### Batch C: Content Bridge
1. Downgrade content_bridge forwarding logs (#4)
2. Run testrunner verification

### Batch D: Background Components
1. Downgrade bg_fs_dispatch operational logs (#5)
2. Downgrade background.cljs operational logs (#6)
3. Review remaining files (#7)
4. Run testrunner verification

### Batch E: Final Verification
1. Full E2E test run (#8)
2. Manual verification - load extension, verify:
   - Debug setting OFF: browser console is clean during normal usage
   - Debug setting ON: debug logs appear in browser console

---

## Future Considerations

If a richer debugging experience is needed later:

- **Dev log viewer in panel**: Extend the test-logger pattern to store debug logs in `chrome.storage.local` (gated by the debug setting), add a searchable/filterable log viewer in the devtools panel. The storage + write queue infrastructure already exists in `test_logger.cljs`.
- **Per-module log filtering**: Add checkboxes for specific modules (Bridge, Background, FS, etc.) so users can enable/disable logging per component.
- **Log export**: Add a "Copy logs" button to export debug logs for issue reports.

---

## Original Plan-Producing Prompt

The logging to the browser console is very verbose. Especially things coming from `[Epupp bridge]`. Investigate what logging infrastructure we have in place (including what is used during E2E testing) and what options we have for consolidating Epupp app logs to the popup or background worker, instead of littering the browser/tab's console.

Create a plan document with findings and recommendations, structured like recent plan docs in dev/docs.

**Refinement 1:** Route ws_bridge logs through the content_bridge via postMessage instead of direct console.debug calls. ws_bridge already communicates with content_bridge via postMessage, so sending log messages through the same channel unifies all extension logging through the `log` namespace.

**Refinement 2:** Add a gated debug logging setting instead of relying on browser's `console.debug` filtering. Developers often run DevTools in verbose mode while debugging their own apps - Epupp debug logs shouldn't pollute their console. A setting in Epupp popup (default OFF) that users can enable when reporting issues to us gives proper control.

**Refinement 3:** Simplify log API from `(log level module context & args)` to `(log level subsystem & messages)`. Analysis showed ~70% of callers pass `nil` for context. The few that use context can combine module and context with a colon. Also route the `:log/fx.log` Uniflow effect through the log namespace instead of duplicating console dispatch logic.
