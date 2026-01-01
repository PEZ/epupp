# Browser Jack-in - AI Coding Agent Instructions

## Project Overview

**Browser Jack-in** is a browser extension that injects a [Scittle](https://github.com/babashka/scittle) REPL server into web pages, enabling ClojureScript evaluation directly in the browser DOM via nREPL. This bridges your Clojure editor (or AI agent) with the browser's execution environment through a **Babashka relay server**.

Mandatory reads:
* [README.md](../README.md) - Usage and high-level architecture
* [Developer docs](../docs/dev.md)

**Architecture in brief:**
`Editor/AI nREPL client` ↔ `Babashka browser-nrepl (ports 12345/12346)` ↔ `Extension background worker` ↔ `Content bridge script` ↔ `Page WebSocket bridge` ↔ `Scittle REPL` ↔ `DOM`

## Critical Build System Understanding

### Project Structure

```
src/           # ClojureScript source files only
extension/     # Static assets + compiled .mjs (Squint output)
build/         # Bundled .js files (esbuild output)
dist/          # Final browser extension zips
```

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

**Never edit `.mjs` files directly** — they're generated. Edit `.cljs` source in `src/` only.

### Scittle CSP Patching

The vendored [extension/vendor/scittle.js](extension/vendor/scittle.js) is **patched** during `bb bundle-scittle` to remove `eval()` usage that violates strict Content Security Policies on sites like GitHub/YouTube.

**Original code:**
```javascript
globalThis["import"]=eval("(x) => import(x)");
```

**Patched to:**
```javascript
globalThis["import"]=function(x){return import(x);};
```

See [tasks.clj:patch-scittle-for-csp](scripts/tasks.clj). **Always run `bb bundle-scittle` after updating Scittle version.**

## Key Developer Workflows

### Development Cycle

```bash
# Watch mode (auto-recompile on changes)
bb watch  # or: npx squint watch

# Full build for all browsers
bb build  # creates zips in dist/

# Build for specific browser
bb build:chrome
bb build:firefox
```

**Note:** Safari doesn't work yet. We'll return to it later.

### Testing the Extension Locally

1. Build: `bb build` (builds all browsers — quick enough, and better to test all targets)
2. Unzip `dist/browser-jack-in-chrome.zip` (or firefox)
3. Chrome → `chrome://extensions` → Enable Developer mode → Load unpacked → select `chrome/` folder
4. On any web page: click extension icon → follow 1-2-3 steps

Use `bb build:chrome` or `bb build:firefox` only when debugging browser-specific issues.

**REPL Connection:**
1. Copy Babashka command from extension popup (Step 1)
2. Run in terminal: `bb browser-nrepl --nrepl-port 12345 --websocket-port 12346`
3. Connect editor nREPL client to `localhost:12345`
4. Click "Connect REPL" in extension (Step 3)

### Babashka Tasks Reference

```bash
bb browser-nrepl         # Start relay server (options: --nrepl-port, --websocket-port)
bb bundle-scittle        # Download + patch Scittle vendor files
bb build [browsers...]   # Build for chrome/firefox (default: all)
```

See [bb.edn](bb.edn) for complete task definitions.

## Architecture Deep Dive

### Message Flow: Three-Layer Bridge

1. **Background Service Worker** ([background.cljs](src/background.cljs))
   - Runs in extension context (immune to page CSP)
   - Manages WebSocket connections to Babashka relay (`ws://localhost:12346/_nrepl`)
   - Relays messages to/from content scripts via `chrome.runtime.sendMessage`

2. **Content Bridge** ([content_bridge.cljs](src/content_bridge.cljs))
   - Runs in ISOLATED world (content script)
   - Relays between background worker and page via `postMessage`
   - Implements keepalive pings to prevent service worker termination

3. **WebSocket Bridge** ([ws_bridge.cljs](src/ws_bridge.cljs))
   - Runs in MAIN world (page context, injected script)
   - **Intercepts WebSocket constructor** for `/_nrepl` URLs
   - Provides virtual WebSocket API to Scittle using `postMessage`

**Why three layers?** CSP restrictions prevent page scripts from making WebSocket connections. The background worker bypasses this, while bridges tunnel messages through allowed channels (`postMessage`, `chrome.runtime`).

### Browser-Specific Manifest Adjustments

See [tasks.clj:adjust-manifest](scripts/tasks.clj). Key differences:

- **Firefox:** Uses `background.scripts` (not `service_worker`), adds `content_security_policy` for `ws://localhost:*`
- **Chrome/Safari:** Standard Manifest V3 service worker
- **All:** Icons, permissions (`activeTab`, `scripting`)

## Project-Specific Conventions

### State Management

Prefer a single `!state` atom with namespaced keys over multiple separate atoms. This provides a consistent pattern and makes it clear where to add new state:

```clojure
;; Preferred: single !state atom with namespaced keys
(def !state (atom {:ws/connections {}      ; per-tab WebSocket map
                   :bridge/connected? false
                   :bridge/keepalive-interval nil}))

;; Access with namespaced keys
(get-in @!state [:ws/connections tab-id])
(swap! !state assoc :bridge/connected? true)
```

Even when tracking just one thing, use `!state` with a namespaced key — it signals where future state belongs.

### Namespace Naming

- **Files:** Use underscores: `content_bridge.cljs`, `ws_bridge.cljs`
- **Namespaces:** Use hyphens: `(ns content-bridge ...)`, `(ns ws-bridge ...)`
- **Compiled output:** Underscores preserved in `.mjs`, hyphens in bundled `.js`

### Message Protocol

All cross-context messages use `#js {:type "..." ...}` objects with these types:

**Page → Bridge:**
- `ws-connect` (with `:port`)
- `ws-send` (with `:data`)

**Bridge ↔ Background:**
- `ws-connect`, `ws-send`, `ws-close`
- `ping` (keepalive to prevent worker termination)

**Bridge → Page:**
- `ws-open`, `ws-message` (with `:data`), `ws-error`, `ws-close`

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

Single atom with namespaced keys, rendered via `add-watch`:

```clojure
(defonce !state
  (atom {:ports/nrepl "1339"
         :ports/ws "1340"
         :ui/status nil
         :ui/has-connected false
         :browser/brave? false}))

;; Re-render on any state change
(add-watch !state ::render (fn [_ _ _ _] (render!)))
```

### Dispatch Pattern

Actions dispatched as vectors `[action & args]`, handled in central `dispatch!` function:

```clojure
(defn dispatch! [[action & args]]
  (case action
    :set-nrepl-port (let [[port] args] (swap! !state assoc :ports/nrepl port))
    :connect (-> (get-active-tab) (.then ...))
    :copy-command (-> (js/navigator.clipboard.writeText cmd) ...)))

;; Usage in UI
[:button {:on-click #(dispatch! [:connect])} "Connect"]
```

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

## Common Pitfalls

1. **Don't edit `.mjs` files** — they're generated by Squint. Edit `.cljs` source.
2. **Run `bb bundle-scittle`** after Scittle version updates — the CSP patch is critical.
3. **Test on CSP-strict sites** (GitHub, YouTube) to verify Scittle patch works.
4. **WebSocket readyState management** — set to `3` (CLOSED) in `ws-close` handler ([ws_bridge.cljs](src/ws_bridge.cljs)) to prevent reconnection loops.
5. **Firefox CSP** — `content_security_policy` in manifest must allow `ws://localhost:*` for local connections.

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

**Future:** Core/pure logic (game logic, data transformations) could be extracted to separate `.cljs` files testable with [nbb](https://github.com/babashka/nbb) for automated testing.

## Release Process

**Automated via `bb publish`:**
1. Checks: clean git, on master, CHANGELOG has [Unreleased] content
2. Bumps manifest version (e.g., `0.0.3.0` → `0.0.3`)
3. Updates CHANGELOG with release date
4. Commits, tags `vN.N.N`, pushes
5. Bumps to next dev version (e.g., `0.0.4.0`)
6. GitHub Actions builds zips and creates draft release

**Manual store submission:**
- Chrome: Upload zip to [Chrome Web Store Developer Dashboard](https://chrome.google.com/webstore/devconsole)
- Firefox: Upload to [Firefox Add-on Developer Hub](https://addons.mozilla.org/developers/)

See [tasks.clj:publish](scripts/tasks.clj) for full workflow.
