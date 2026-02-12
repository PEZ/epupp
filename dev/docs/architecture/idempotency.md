# Idempotency in Epupp

**Created**: January 13, 2026
**Status**: Documenting guarantees and test coverage

This document catalogs Epupp's idempotency guarantees - the property that repeated operations produce the same result as a single operation.

## Injection Points Overview

Epupp injects several scripts into web pages. Each has different idempotency characteristics.

| Injection Point | Guard Mechanism | Test Coverage | Notes |
|-----------------|-----------------|---------------|-------|
| Content Bridge | `window.__browserJackInBridge` flag | ✅ E2E (implicit) | Guards entire init block |
| WebSocket Bridge | `window.__browserJackInWSBridge` flag | ✅ E2E (implicit) | Guards entire init block |
| Script Tags (via `inject-script-tag!`) | `window.__epuppInjectedScripts` Set | ✅ E2E | URL tracked, skips duplicates |
| Scittle Core | `check-scittle-fn` check | ✅ Implicit | Only injects if not present |
| Test Logger Error Handlers | `__epupp_error_handlers_installed` flag | ✅ Unit | Per-context guard |
| Userscripts | `clear-userscripts` before inject | ❌ No explicit test | Removes old tags, may re-inject |

## Detailed Analysis

### Content Bridge (`content_bridge.cljs`)

**Guard**: Line 271-272
```clojure
(when-not js/window.__browserJackInBridge
  (set! js/window.__browserJackInBridge true)
  ;; ... initialization
```

**Behavior**: Entire initialization block is skipped on re-injection. The bridge sets up message listeners once. Multiple `chrome.scripting.executeScript` calls with `content-bridge.js` are safe.

**Test coverage**: Implicitly tested via all E2E tests that connect REPL multiple times or navigate between pages. No explicit idempotency test.

### WebSocket Bridge (`ws_bridge.cljs`)

**Guard**: Line 121-122
```clojure
(when-not js/window.__browserJackInWSBridge
  (set! js/window.__browserJackInWSBridge true)
  ;; ... WebSocket override
```

**Behavior**: WebSocket override happens once. The original `WebSocket` is stored in `window._OriginalWebSocket`. Multiple injections are safe.

**Test coverage**: Implicitly tested via REPL connection tests. No explicit idempotency test.

### Script Tag Injection (`inject-script-tag!`)

**Guard**: `window.__epuppInjectedScripts` Set (content_bridge.cljs lines 150-171)
```clojure
(when-not js/window.__epuppInjectedScripts
  (set! js/window.__epuppInjectedScripts (js/Set.)))
(if (.has js/window.__epuppInjectedScripts url)
  (do (log/info "Bridge" nil "Script already injected, skipping:" url)
      (send-response #js {:success true :skipped true}))
  ;; ... inject
```

**Behavior**: Each URL is tracked. Duplicate injection requests return `{:success true :skipped true}` without adding another script tag.

**Test coverage**: ✅ Explicit E2E test in `repl_ui_spec.cljs` - "epupp.repl/manifest! is idempotent"

### Scittle Core (`ensure-scittle!`)

**Guard**: `check-scittle-fn` checks `window.scittle && window.scittle.core`
```clojure
(when-not (and status (.-hasScittle status))
  ;; ... inject scittle.js
```

**Behavior**: Only injects if Scittle isn't already present. Safe to call multiple times.

**Test coverage**: Implicitly tested. No explicit idempotency test.

### Test Logger Error Handlers

**Guard**: `__epupp_error_handlers_installed` flag per context
```clojure
(when (and (test-mode?)
           (not (aget global-obj "__epupp_error_handlers_installed")))
  (aset global-obj "__epupp_error_handlers_installed" true)
  ;; ... install handlers
```

**Behavior**: Error handlers installed once per context (background, popup, panel, content-bridge).

**Test coverage**: Implicit - test mode code only.

### Userscripts

**Current behavior**: `clear-userscripts` message removes all `script[type='application/x-scittle'][id^='userscript-']` tags, then new ones are injected. This is NOT idempotent - it's intentionally re-executable for navigation/reload scenarios.

**Guard mechanism**: None needed for idempotency; clearing is the intended behavior.

## Test Gap Analysis

### Missing Explicit Tests

1. **Content Bridge idempotency** - Should test that calling `inject-content-script` twice doesn't double-register listeners
2. **WebSocket Bridge idempotency** - Should test that ws-bridge injection twice doesn't break WebSocket override
3. **Scittle Core idempotency** - Should test `ensure-scittle!` called twice doesn't add duplicate script tags

### Covered by Current Tests

- Script tag injection (`inject-script-tag!`) - explicit E2E test
- REPL connection flow - implicit coverage via multi-step tests

## Recommendations

### Priority: LOW

The current guards are sufficient for production use. The missing explicit tests would primarily serve as regression protection and documentation. The implicit coverage from existing E2E tests catches most real-world scenarios.

### If Adding Tests

Add to `e2e/integration_test.cljs` or a new `e2e/idempotency_test.cljs`:

1. **Content bridge double-injection test**
   - Inject content-bridge.js twice
   - Verify only one message listener is active

2. **Full REPL connect cycle test**
   - Connect REPL, disconnect, connect again
   - Verify single Scittle instance
   - Verify single ws-bridge instance

## Related

- [../architecture.md](../architecture.md) - Component overview and injection flows
