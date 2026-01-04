# Scittle Tamper - AI Coding Agent Instructions

<principles>
  <style>No emojis. No em dashes - use hyphens or colons instead.</style>

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
</principles>

## Source Code: Squint ClojureScript

This is a **Squint** project. All application logic lives in `src/*.cljs` files.

**Source of truth:** `src/*.cljs` (ClojureScript)

**Ignore when reading code** (compiled artifacts, not source):
- `extension/*.mjs` - Squint compiler output
- `build/*.js` - esbuild bundled output

Only read `.mjs` or `build/*.js` files when debugging compilation issues. Never edit them.

## Project Overview

**Scittle Tamper** is a browser extension that injects a [Scittle](https://github.com/babashka/scittle) REPL server into web pages, enabling ClojureScript evaluation directly in the browser DOM via nREPL. This bridges your Clojure editor (or AI agent) with the browser's execution environment through a **Babashka relay server**.

Mandatory reads:
* [README.md](../README.md) - Usage and high-level architecture
* [Developer docs](../dev/docs/dev.md)
* [Architecture reference](../dev/docs/architecture.md) - Message protocol, state management, injection flows
* [Userscript architecture](../dev/docs/userscripts-architecture.md)
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

See [dev.md](../dev/docs/dev.md) for build commands, loading extension locally, and release process.

**Quick reference:**
- `bb watch` - Watch mode (auto-recompile)
- `bb build:dev` - Dev build (bumps version)
- `bb build` - Release build
- `bb browser-nrepl` - Start relay server

**Important:** After building, wait for the user to test before committing changes.

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

**Icon sources (MIT/ISC licensed):**
- [Heroicons](https://heroicons.com/) — Primary source for UI icons
- [Lucide](https://lucide.dev/) — Alternative/backup source

**Pattern:**
```clojure
;; In icons.cljs
(defn pencil
  ([] (pencil {}))
  ([{:keys [size] :or {size 16}}]
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :width size :height size
          :viewBox "0 0 20 20"
          :fill "currentColor"}
    [:path {:d "..."}]]))

;; Usage in components
(:require [icons :as icons])
[:button.edit [icons/pencil]]
[:button.edit [icons/pencil {:size 18}]]  ; custom size
```

**Guidelines:**
- Use `fill "currentColor"` for icons that should inherit text color
- Provide sensible default size, allow override via `:size` prop
- Keep icons small and optimized (remove unnecessary attributes from raw SVG)
- Add new icons to `icons.cljs` rather than inline in components

## Common Pitfalls

1. **Treat `.mjs` as binary** — These are Squint-compiled output. Never edit, and only read when debugging compilation. Edit `.cljs` source in `src/`.
2. **Squint ≠ ClojureScript** — see [squint.instructions.md](squint.instructions.md) for gotchas (keywords are strings, missing `name` function, mutable data).
3. **Run `bb bundle-scittle`** after Scittle version updates — the CSP patch is critical.
4. **Test on CSP-strict sites** (GitHub, YouTube) to verify Scittle patch works.
5. **WebSocket readyState management** — set to `3` (CLOSED) in `ws-close` handler ([ws_bridge.cljs](src/ws_bridge.cljs)) to prevent reconnection loops.
6. **Firefox CSP** — `content_security_policy` in manifest must allow `ws://localhost:*` for local connections.

## Testing Strategy

Currently manual testing workflow:
1. Build extension for target browser
2. Load unpacked in browser (reload extension after rebuilds)
3. Start `bb browser-nrepl`
4. Reload the target web page
5. Use popup UI to connect REPL
6. Evaluate test expressions in page context from your editor

**Tamper scripts:** The `test-data/tampers/` directory contains pre-cooked evaluation scripts for testing against specific sites (e.g., `github.cljs`). These serve as manual test cases and examples.

**Key test cases:**
- Connection establishment on CSP-strict sites (GitHub, YouTube)
- Multiple tab connections (background worker stores per-tab WebSockets)
- Service worker keepalive (5s ping interval in content bridge)
- Clean disconnect/reconnect cycles
- Userscript auto-injection with per-pattern approval flow
- Pending approvals badge count and Allow/Deny workflow

**Future:** Core/pure logic (game logic, data transformations) could be extracted to separate `.cljs` files testable with [nbb](https://github.com/babashka/nbb) for automated testing.

## Committing Changes

Use the `commit` subagent.

## Release Process

See [dev.md](../dev/docs/dev.md#release-process) for the full `bb publish` workflow and store submission details.
