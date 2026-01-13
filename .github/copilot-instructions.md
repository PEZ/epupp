# Epupp - AI Coding Agent Instructions

## Quick Start for AI Agents

**Essential facts:**
- **Language:** Squint (ClojureScript-like, compiles to JS) - source in `src/*.cljs`
- **Never edit:** `extension/*.mjs` or `build/*.js` (compiled artifacts)
- **Testing:** Run `bb test:e2e` (headless by default, includes REPL integration)
- **Commands:** PREFER `bb <task>` - over direct `npx`/`npm` commands
- **Watchers:** Usually already running - check task output before building

**Start work setup:**
1. Read [testing.md](../dev/docs/testing.md) for test strategy
2. Read [architecture.md](../dev/docs/architecture.md) for system design
3. Check `clojure_list_sessions` to verify REPL availability (look for both `squint` and `scittle-dev-repl` sessions)

<principles>

  <epistemology>
    Assumptions are the enemy. Never guess numerical values - benchmark instead of estimating.
    When uncertain, measure. Say "this needs to be measured" rather than inventing statistics.
  </epistemology>

  <scaling>
    Validate at small scale before scaling up. Run a sub-minute version first to verify the
    full pipeline works. When scaling, only the scale parameter should change.
  </scaling>

  <ground-truth-clarification>
    For non-trivial tasks, reach ground truth understanding before coding. Simple tasks execute
    immediately. Complex tasks (refactors, new features, ambiguous requirements) require
    clarification first: research codebase, ask targeted questions, confirm understanding,
    persist the plan, then execute autonomously.
  </ground-truth-clarification>

  <delegation>
    You use subagents intelligently.
  </delegation>

  <style>No emojis. No em dashes - use hyphens or colons instead.</style>

  <bb-tasks>
    Always prefer `bb <task>` over direct `npx`/`npm` commands. The bb tasks in `bb.edn` encode
    project-specific configurations, output paths, and workflow decisions. Running tools directly
    bypasses these and often produces incorrect results (wrong output directories, missing flags).
    Check `bb tasks` for available commands before resorting to direct tool invocation.
  </bb-tasks>

  <babashka-utilities>
    Prefer Babashka built-in utilities over Python, shell scripts, or other external tools when
    functionality overlaps. The project uses Babashka extensively and has dependencies loaded.

    Common replacements:
    - HTTP server: Use `bb test:server` or `babashka.http-server` instead of `python -m http.server`
    - File operations: Use `babashka.fs` instead of shell commands (cp, mv, rm, find, etc.)
    - Process execution: Use `babashka.process` instead of raw shell scripts
    - HTTP requests: Use `babashka.http-client` instead of curl or wget

    Check bb.edn dependencies and existing tasks before reaching for external tools.
  </babashka-utilities>
</principles>

## Subagents

There are currently three subagents:

* commit: Give the commit subagent a summary of the task (the bigger picture) that has been carried out
* research: Give the research subagent context of what you are working with and need to know and instruct it how you want it to structure its report.
* edit: Give the edit subagent a complete task with files, linenumbers, code and what to do with it. It should be very much the same as you would have given to the edit tools if you used them yourself.

## Source Code: Squint ClojureScript

This is a **Squint** project. All application logic lives in `src/*.cljs` files.

**Source of truth:** `src/*.cljs` (ClojureScript)

**Ignore when reading code** (compiled artifacts, not source):
- `extension/*.mjs` - Squint compiler output
- `build/*.js` - esbuild bundled output

Only read `.mjs` or `build/*.js` files when debugging compilation issues. Never edit them.

## Project Overview

**Epupp** is a browser extension that injects a [Scittle](https://github.com/babashka/scittle) REPL server into web pages, enabling ClojureScript evaluation directly in the browser DOM via nREPL. This bridges your Clojure editor (or AI agent) with the browser's execution environment through a **Babashka relay server**.

Mandatory reads:
* [README.md](../README.md) - Usage and high-level architecture
* [Developer docs](../dev/docs/dev.md)
* [Testing](../dev/docs/testing.md) - Strategy, setup, and utilities
* [Architecture reference](../dev/docs/architecture.md) - Message protocol, state management, injection flows
* [Userscript architecture](../dev/docs/userscripts-architecture.md)
* [UI guide](../dev/docs/ui.md) - Script editor UX and ID behavior
* [Squint gotchas](squint.instructions.md) - Critical Squint-specific issues
* [Reagami](reagami.instructions.md) - Lightweight Reagent-like UI patterns
* [State and event management](uniflow.instructions.md) - Uniflow patterns

**Architecture in brief:**
`Editor/AI nREPL client` ↔ `Babashka browser-nrepl (ports 12345/12346)` ↔ `Extension background worker` ↔ `Content bridge script` ↔ `Page WebSocket bridge` ↔ `Scittle REPL` ↔ `DOM`

## Critical Build System Understanding

### Squint Compilation Model

This project uses **[Squint](https://github.com/squint-cljs/squint)** to compile ClojureScript to modern ESM JavaScript. Key points:

- **Source:** `src/*.cljs` files are compiled to `extension/*.mjs` (ES modules)
- **Bundling:** esbuild bundles `extension/*.mjs` → `build/*.js` (IIFE format)
- **Config:** [squint.edn](squint.edn) specifies paths and `.mjs` extension

**Build flow:**
```bash
npx squint compile  # src/*.cljs → extension/*.mjs
npx esbuild ...     # extension/*.mjs → build/*.js (IIFE bundles)
```

**CRITICAL: Treat `.mjs` files as compiled artifacts (like binaries).** Never edit them directly - they are auto-generated from `.cljs` source. Only read `.mjs` files when investigating Squint compilation issues. Always edit `.cljs` source in `src/`.

### Scittle CSP Patching

Scittle is patched to remove `eval()` for CSP compatibility. See [dev.md](../dev/docs/dev.md#patching-scittle-for-csp-compatibility) for details. **Always run `bb bundle-scittle` after updating Scittle version.**

## Key Developer Workflows

See [dev.md](../dev/docs/dev.md) for build commands, loading extension locally, and release process. Note that the the instructions there for setting up for local development are meant for the human.

The Squint REPL is useful for testing code and pure functions interactively before editing files. See [squint.instructions.md](squint.instructions.md#testing-code-in-squint-nrepl).

### Quick Command Reference

**For AI Agents (Prefer These):**

| Command | Purpose |
|---------|--------|
| `bb test` | Unit tests (fast, always run after changes) |
| `bb test:e2e` | E2E tests (headless in Docker, includes REPL integration) |
| `bb build:test` | Build for testing (dev config, no version bump) |
| `bb build:dev` | Dev build, when handing off to human for manual testing |

Note that the test tasks compile source as needed before running tests, so you do not need to build separately before testing.

**Other Commands, for the human (listed here for the AI's awareness only):**

| Command | Purpose | Notes |
|---------|---------|-------|
| `bb watch` | Auto-recompile | Usually already running |
| `bb test:watch` | Unit test watcher | Usually already running |
| `bb squint-nrepl` | Squint REPL | For testing pure functions |
| `bb test:e2e:headed` | E2E tests (visible browser) | **Avoid** - interrupts human |
| `bb test:e2e:ci` | E2E tests (CI mode) | For GitHub Actions only |

**Filtering tests:** Pass `--grep "pattern"` to any test command:
```bash
bb test:e2e --grep "popup"   # Run only popup tests
```

E2E tasks accept all Playwright options, so you should not need to resort to using Playwright directly.

**Critical:** After building or code changes, wait for user confirmation before committing.

### AI Development Workflow (Local - VS Code with Human)

**Before starting work:**
1. **Verify watchers are running** - check watcher task output for compilation/test status
2. **Check problem report** - review any existing lint errors
3. **Verify REPLs** - use `clojure_list_sessions` to confirm available sessions:
   - `squint` - Squint nREPL (port 1337) for testing pure functions in Node.js
   - `scittle-dev-repl` - Scittle Dev REPL (port 31337) for testing Scittle code in browser-like environment

**CRITICAL: Watcher verification is mandatory.** Use `get_task_output` with these task IDs:
- `shell: Squint Watch` - compilation status
- `shell: Unit Test Watch` - test status
- `shell: Scittle Dev REPL` - browser-nrepl relay status

If `get_task_output` returns "Terminal not found", **STOP and tell the user**:
> "I cannot find the watcher task outputs. This happens when VS Code's terminal tracking gets out of sync. Please restart the default build task (Cmd/Ctrl+Shift+B) to restore the watchers, then ask me to continue."

Do NOT proceed without watcher feedback - it's essential for verifying compilation and test results.

**While working:**
- Use the **Squint REPL** (`squint` session) to test pure functions in Node.js
- Use the **Scittle Dev REPL** (`scittle-dev-repl` session) to test Scittle-specific code in a browser-like environment
- Develop solutions incrementally in the appropriate REPL

**After editing files:**
1. **Check watcher output** - verify compilation succeeded and tests pass
2. **Check problem report** - fix any new lint errors
3. Address any issues before proceeding

### Remote Agent Workflow (GitHub Copilot Coding Agent)

The remote agent runs in an ephemeral GitHub Actions environment with pre-configured tools. The setup is defined in `.github/workflows/copilot-setup-steps.yml`.

**Pre-installed environment:**
- Node.js 20 with npm dependencies (`npm ci` already run)
- Babashka (bb) with cached dependencies
- Playwright with Chromium browser
- Squint watch running in background
- Squint nREPL server on port 1337
- Extension built for testing (`bb build:test` already run)
- Test files compiled to `build/test/` and `build/e2e/`

**Running tests:**
```bash
bb test              # Unit tests (Vitest)
bb test:e2e:ci       # All E2E tests including REPL integration (use xvfb-run)
```

**E2E tests require xvfb for headed Chrome:**
```bash
xvfb-run --auto-servernum bb test:e2e:ci
```

**Building after code changes:**
```bash
bb build:test   # Rebuild extension (dev config)
```

**Squint nREPL:** The REPL server is started during setup on port 1337. You can evaluate Squint code for testing pure functions, but note this runs in Node.js context (no browser APIs).

## Architecture Deep Dive

See [docs/architecture.md](../dev/docs/architecture.md) for the complete reference including:
- Message protocol (all message types and flows)
- State management (all atoms and their keys)
- Injection flows (REPL, userscript, panel evaluation)
- Module dependencies

### Quick Reference: Three-Layer Bridge

1. **Background Service Worker** - WebSocket management, script injection orchestration
2. **Content Bridge** (ISOLATED world) - Message relay, DOM injection, keepalive
3. **WebSocket Bridge** (MAIN world) - Virtual WebSocket API for Scittle

**Why three layers?** CSP restrictions prevent page scripts from making WebSocket connections. The background worker bypasses this, while bridges tunnel messages through allowed channels.

### Browser-Specific Manifest Adjustments

See [tasks.clj:adjust-manifest](scripts/tasks.clj). Key differences:

- **Chrome:** Standard Manifest V3 with `service_worker`
- **Firefox:** Uses `background.scripts` (not `service_worker`), adds `content_security_policy` for `ws://localhost:*`
- **Safari:** Uses `background.scripts` (Safari prefers this over `service_worker`), adds `content_security_policy` for `ws://localhost:*`
- **All:** Icons, permissions (`activeTab`, `scripting`)

## Project-Specific Conventions

### State Management

Prefer a single `!state` atom with namespaced keys over multiple separate atoms. See [docs/architecture.md](../dev/docs/architecture.md) for complete state schemas per module.

```clojure
;; Preferred: single !state atom with namespaced keys
(def !state (atom {:ws/connections {}      ; per-tab WebSocket map
                   :bridge/connected? false}))

;; Access with namespaced keys
(get-in @!state [:ws/connections tab-id])
(swap! !state assoc :bridge/connected? true)
```

Even when tracking just one thing, use `!state` with a namespaced key - it signals where future state belongs.

### Namespace Naming

- **Files:** Use underscores: `content_bridge.cljs`, `ws_bridge.cljs`
- **Namespaces:** Use hyphens: `(ns content-bridge ...)`, `(ns ws-bridge ...)`
- **Compiled output:** Underscores preserved in `.mjs`, hyphens in bundled `.js`

### Message Protocol

All cross-context messages use `#js {:type "..." ...}` objects. See [docs/architecture.md](../dev/docs/architecture.md) for the complete message protocol reference.

### Guard Against Multiple Injections

All injected scripts check global flags before initializing:
```clojure
(when-not js/window.__browserJackInBridge
  (set! js/window.__browserJackInBridge true)
  ; ... initialization)
```

## Popup UI Patterns

The popup UI ([popup.cljs](src/popup.cljs)) uses **Reagami** (a lightweight Reagent-like library) with patterns inspired by Scittle's [Replicant tic-tac-toe example](https://github.com/babashka/scittle/tree/main/resources/public/cljs/replicant_tictactoe).

### State Management Pattern

Single atom with namespaced keys, rendered via `add-watch`. See [architecture.md](../dev/docs/architecture.md#state-management) for complete state schemas per module.

```clojure
;; Re-render on any state change
(add-watch !state ::render (fn [_ _ _ _] (render!)))
```

Note: Pending approvals are managed in the **background worker**, not the popup.

### Dispatch Pattern

Actions dispatched as vectors of vectors (batched), using Uniflow pattern:

```clojure
;; Uniflow dispatch - actions are vectors within a vector
(defn dispatch! [actions]
  (event-handler/dispatch! !state handle-action perform-effect! actions))

;; Usage in UI - note the nested vector
[:button {:on-click #(dispatch! [[:popup/ax.connect]])} "Connect"]

;; Multiple actions in one dispatch
[:button {:on-click #(dispatch! [[:popup/ax.set-nrepl-port "1339"]
                                  [:popup/ax.check-status]])} "Reset"]
```

See [uniflow.instructions.md](uniflow.instructions.md) for full event system documentation.

See [architecture.md](../dev/docs/architecture.md#uniflow-event-system) for complete lists of popup and panel actions/effects.

### Page Script Injection Pattern

Functions that run in page context must be plain JS (no Squint runtime):

```clojure
;; js* for inline JS functions that get serialized to page
(def check-status-fn
  (js* "function() {
    return {
      hasScittle: !!(window.scittle && window.scittle.core),
      hasWsBridge: !!window.__browserJackInWSBridge
    };
  }"))

;; Execute in page context
(execute-in-page tab-id check-status-fn)
```

### UI Components

Hiccup-style components with Reagami:
- Use `[:div.class]` shorthand for CSS classes
- Event handlers: `:on-click`, `:on-input`
- Conditional rendering with `when`/`if`

```clojure
(defn port-input [{:keys [id label value on-change]}]
  [:span
   [:label {:for id} label]
   [:input {:type "number" :id id :value value
            :on-input (fn [e] (on-change (.. e -target -value)))}]])
```

### Icons

All icons are inline SVGs centralized in `icons.cljs`. This avoids external dependencies and font icon complexity.

**Icon source:**
- [VS Code Codicons](https://github.com/microsoft/vscode-codicons) (CC BY 4.0) - Primary source for visual consistency with VS Code

**Pattern:**
```clojure
;; In icons.cljs
(defn edit
  ([] (edit {}))
  ([{:keys [size class] :or {size 16}}]
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :width size :height size
          :viewBox "0 0 16 16"
          :fill "currentColor"
          :class class}
    [:path {:d "..."}]]))

;; Usage in components
(:require [icons :as icons])
[:button.edit [icons/edit]]
[:button.edit [icons/edit {:size 18}]]  ; custom size
```

**Guidelines:**
- Use `fill "currentColor"` for icons that should inherit text color
- Provide sensible default size, allow override via `:size` and `:class` props
- All icons use 16x16 viewBox (Codicons standard)
- Add new icons to `icons.cljs` rather than inline in components
- Browse available icons: https://microsoft.github.io/vscode-codicons/dist/codicon.html

## Common Pitfalls

1. **Treat `.mjs` as binary** — These are Squint-compiled output. Never edit, and only read when debugging compilation. Edit `.cljs` source in `src/`.
2. **Squint ≠ ClojureScript** — see [squint.instructions.md](squint.instructions.md) for gotchas (keywords are strings, missing `name` function, mutable data).
3. **Run `bb bundle-scittle`** after Scittle version updates — the CSP patch is critical.
4. **Test on CSP-strict sites** (GitHub, YouTube) to verify Scittle patch works.
5. **WebSocket readyState management** — set to `3` (CLOSED) in `ws-close` handler ([ws_bridge.cljs](src/ws_bridge.cljs)) to prevent reconnection loops.
6. **Firefox CSP** — `content_security_policy` in manifest must allow `ws://localhost:*` for local connections.

## Testing Strategy

**Test hierarchy** (fastest to slowest):
1. **Unit tests** (`bb test`) - Pure functions, action handlers
2. **E2E** (`bb test:e2e`) - Extension loading, popup/panel UI, full nREPL pipeline

**When to run tests:**
- **After ANY code change:** `bb test` (fast unit tests)
- **Changed UI/extension code:** `bb test:e2e`
- **Changed messaging/REPL:** `bb test:e2e`

**See [testing.md](../dev/docs/testing.md) for:** detailed strategy, test utilities, fixtures, and troubleshooting.

### Manual Testing

Manual testing workflow:
1. Build extension for target browser
2. Load unpacked in browser (reload extension after rebuilds)
3. Start `bb browser-nrepl`
4. Reload the target web page
5. Use popup UI to connect REPL
6. Evaluate test expressions in page context from your editor

**Tamper scripts:** The `test-data/tampers/` directory contains pre-cooked evaluation scripts for testing against specific sites (e.g., `github.cljs`). These serve as manual test cases and examples.

**Key manual test cases:**
- Connection establishment on CSP-strict sites (GitHub, YouTube)
- Multiple tab connections (background worker stores per-tab WebSockets)
- Service worker keepalive (5s ping interval in content bridge)
- Clean disconnect/reconnect cycles
- Userscript auto-injection with per-pattern approval flow
- Pending approvals badge count and Allow/Deny workflow

## Committing Changes

Use the `commit` subagent.

## Release Process

See [dev.md](../dev/docs/dev.md#release-process) for the full `bb publish` workflow and store submission details.
