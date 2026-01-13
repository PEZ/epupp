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

The content bridge ([content_bridge.cljs](../../../src/content_bridge.cljs)) is the sole gateway from page context to background. It explicitly handles only:

| Source | Message Type | Forwarded To | Purpose |
|--------|--------------|--------------|---------|
| `epupp-page` | `ws-connect` | Background | WebSocket relay for REPL |
| `epupp-page` | `ws-send` | Background | WebSocket relay for REPL |
| `epupp-userscript` | `install-userscript` | Background | Script installation (with origin validation) |

**Any message type not in this whitelist is silently dropped.** This prevents userscripts from spoofing popup/panel messages like `pattern-approved` or `evaluate-script`.

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

## Per-Pattern Approval

Despite having `<all_urls>` host permission (required for `scripting.executeScript`), we implement additional user control:

1. Each script tracks `:script/approved-patterns`
2. New URL patterns require explicit user approval
3. Disabling a script revokes all pattern approvals
4. Badge shows count of pending approvals

## Injection Guards

Scripts guard against multiple injections using global window flags:

| Module | Flag | Purpose |
|--------|------|---------|
| `content_bridge.cljs` | `window.__browserJackInBridge` | Prevent duplicate content bridge |
| `ws_bridge.cljs` | `window.__browserJackInWSBridge` | Prevent duplicate WS bridge |
