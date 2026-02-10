# Epupp Security Model

## Message Origin Isolation

The extension uses Chrome's built-in isolation between execution contexts:

| Context | Can call `chrome.runtime.sendMessage`? | Examples |
|---------|---------------------------------------|----------|
| Extension pages | Yes | popup.html, panel.html |
| Content scripts (ISOLATED world) | Yes | content_bridge.js |
| Page scripts (MAIN world) | **No** | userscripts, ws_bridge.js |

This means page scripts (including userscripts) **cannot** directly send messages to the background worker. They can only communicate via `window.postMessage` to the content bridge, which explicitly whitelists what to forward.

## Content Bridge as Security Boundary

The content bridge ([content_bridge.cljs](../../../src/content_bridge.cljs)) is the sole gateway from page context to background. It uses a declarative message registry that controls which messages are forwarded. Every registered message declares its allowed sources and auth model. Unregistered types are silently dropped.

Key message categories:

| Auth Model | Messages | Purpose |
|------------|----------|---------|
| `:auth/none` | `ws-connect`, `load-manifest`, `check-script-exists`, `get-sponsored-username` | Open access (read-only or low-risk) |
| `:auth/connected` | `ws-send` | Requires active REPL connection |
| `:auth/fs-sync+ws` | `list-scripts`, `get-script`, `save-script`, `rename-script`, `delete-script` | Requires FS REPL Sync enabled AND active WebSocket connection |
| `:auth/domain-whitelist` | `web-installer-save-script` | Domain-gated save for web installer |
| `:auth/challenge-response` | `sponsor-status` | Background-initiated pre-authorization |

The registry in `content_bridge.cljs` is the authoritative whitelist - see it for the complete, always-current list.

**Domain whitelist for web installer**: The `web-installer-save-script` message only succeeds from whitelisted domains (github.com, gist.github.com, gitlab.com, codeberg.org, localhost, 127.0.0.1). Non-whitelisted domains trigger a copy-paste fallback in the installer UI.

When adding new forwarded message types, consider: "What if any page script could call this?" If the answer involves privilege escalation, don't forward it.

## CSP Bypass Strategy

Strict Content Security Policies (GitHub, YouTube) block:
- Inline scripts
- `eval()`
- WebSocket connections to localhost

Our solution:
1. **Background worker** makes WebSocket connections (extension context bypasses page CSP)
2. **Content bridge** in ISOLATED world can inject script tags
3. **Scittle patched** to remove `eval()` usage (see `bb bundle-scittle`)

## Injection Guards

Scripts guard against multiple injections using global window flags:

| Module | Flag | Purpose |
|--------|------|---------|
| `content_bridge.cljs` | `window.__browserJackInBridge` | Prevent duplicate content bridge |
| `ws_bridge.cljs` | `window.__browserJackInWSBridge` | Prevent duplicate WS bridge |
