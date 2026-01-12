# Cross-Browser @run-at Implementation Research

**Date**: January 8, 2026
**Purpose**: Research how TamperMonkey implements @run-at timing across Firefox, Safari, and Chrome
**Status**: Research Complete

## Executive Summary

TamperMonkey uses **manifest-based content scripts with `run_at` property** for early injection timing across all browsers. This is the standard WebExtensions API supported by Chrome, Firefox, and Safari. The key finding is that Chrome's `registerContentScripts` API is a MV3-specific enhancement, while the older manifest-based approach works cross-browser.

## Key Findings

### 1. TamperMonkey's Cross-Browser Approach

From TamperMonkey source code analysis:

```javascript
// manifest.json (Chrome, Firefox, Safari)
{
  "content_scripts": [{
    "js": ["registry.js", "convert.js", "helper.js", ..., "content.js"],
    "matches": ["file://*", "http://*/*", "https://*/*"],
    "run_at": "document_start",  // ← Key timing parameter
    "all_frames": true
  }]
}
```

**TamperMonkey's strategy**: Inject a *loader* at `document-start` which then:
1. Connects to background page to get matching scripts
2. Evaluates scripts at appropriate timing (start/body/end/idle)
3. Uses event listeners to handle different timing values

### 2. Browser-Specific APIs and Compatibility

#### Chrome (Manifest V3)

**Primary API**: `chrome.scripting.registerContentScripts()`
- Supports: `document_start`, `document_end`, `document_idle`
- Dynamic registration/unregistration
- Persists across browser restarts
- **Chrome-specific enhancement**, not standard WebExtensions API

**Fallback**: Manifest-based `content_scripts` (MV2 approach)
- Works in both MV2 and MV3
- Static patterns only
- Epupp currently uses this for userscript-loader

#### Firefox

**Primary API**: Manifest-based `content_scripts` with `run_at`
- Supports: `document_start`, `document_end`, `document_idle`
- Firefox does NOT support `chrome.scripting.registerContentScripts` as of Firefox 142
- Uses `browser.contentScripts.register()` for dynamic registration (Firefox-specific)

**Firefox-specific differences**:
- `browser.contentScripts.register()` is Firefox's alternative to Chrome's `registerContentScripts`
- Non-persistent - must re-register on extension restart
- Different API surface:
  ```javascript
  // Firefox approach
  browser.contentScripts.register({
    matches: ["*://example.com/*"],
    js: [{file: "script.js"}],
    runAt: "document_start"
  })
  ```

#### Safari

**Primary API**: Manifest-based `content_scripts` with `run_at`
- Supports: `document_start`, `document_end`, `document_idle`
- Safari adopted Chrome's WebExtensions API starting Safari 14
- `chrome.scripting.registerContentScripts` availability unclear (Safari docs are sparse)
- **Safest approach**: Use manifest-based content scripts

**Safari-specific considerations**:
- Content Security Policy requires explicit `connect-src` for WebSocket connections
- Background scripts use `background.scripts` instead of `service_worker`
- Extension must be converted with Xcode before distribution

### 3. Comparison Table

| Feature | Chrome MV3 | Firefox | Safari |
|---------|-----------|---------|--------|
| Manifest `content_scripts` + `run_at` | ✅ Yes | ✅ Yes | ✅ Yes |
| `chrome.scripting.registerContentScripts` | ✅ Yes | ❌ No | ❓ Unknown |
| `browser.contentScripts.register` | ❌ No | ✅ Yes | ❌ No |
| Dynamic registration persistence | ✅ Yes (MV3) | ❌ No | ❓ Unknown |
| `document_start` timing | ✅ Yes | ✅ Yes | ✅ Yes |
| `document_end` timing | ✅ Yes | ✅ Yes | ✅ Yes |
| `document_idle` timing | ✅ Yes | ✅ Yes | ✅ Yes |

### 4. Key Differences from Chrome

#### Timing Values

**All browsers support**:
- `document_start` - Before page scripts run
- `document_end` - At DOMContentLoaded
- `document_idle` - After page load (default)

**TamperMonkey-specific** (implemented in loader, not browser):
- `document-body` - When `<body>` exists
- `context-menu` - User-triggered

#### Dynamic Registration

**Chrome MV3**: `registerContentScripts` with persistence
**Firefox**: `browser.contentScripts.register()` without persistence
**Safari**: Unknown support, likely none

**Implication**: Cross-browser extensions should use manifest-based approach for static patterns, or implement per-browser registration logic.

## TamperMonkey's Implementation Strategy

Based on source code analysis (`content.js`, `environment.js`, `background.js`):

1. **Single loader script** injected at `document-start` via manifest
2. **Loader responsibilities**:
   - Establish connection to background page
   - Request matching userscripts for current URL
   - Set up event listeners for different timing values
   - Execute scripts at appropriate times

3. **Timing implementation** (from `environment.js`):
   ```javascript
   // document-start: Run immediately
   if (script.options.run_at == 'document-start') {
     TM_runASAP(scriptFn, script.id);
   }
   // document-body: Wait for <body> element
   else if (script.options.run_at == 'document-body') {
     TM_runBody(scriptFn, script.id);
   }
   // document-end: Wait for DOMContentLoaded
   else {
     TM_addLoadListener(scriptFn, script.id, script.name);
   }
   ```

4. **Event-based timing guards**:
   - Registers listeners for `DOMContentLoaded`, `load`, `DOMNodeInserted`
   - Buffers scripts if event already fired
   - Executes queued scripts immediately if page state advanced

## Recommended Implementation for Epupp

### Current State

Epupp already uses the cross-browser compatible approach:
- **Registration**: `chrome.scripting.registerContentScripts` (Chrome-specific API)
- **Loader**: `userscript-loader.js` runs at `document-start`
- **Timing**: Loader reads storage, injects Scittle + scripts

**Problem**: Current implementation only works in Chrome due to `registerContentScripts`.

### Recommended Cross-Browser Strategy

#### Option 1: Hybrid Approach (Recommended)

**For Chrome**: Continue using `chrome.scripting.registerContentScripts`
- Dynamic registration
- Persistent across restarts
- Best developer experience

**For Firefox**: Implement `browser.contentScripts.register()`
- Must re-register on extension startup
- Read saved scripts from storage
- Register matching patterns

**For Safari**: Fallback to manifest-based
- Static patterns in manifest.json
- Less dynamic, but most compatible

**Implementation**:
```clojure
(defn ^:async sync-registrations!
  "Browser-specific registration logic"
  []
  (cond
    ;; Chrome: Use registerContentScripts
    (exists? js/chrome.scripting.registerContentScripts)
    (register-chrome-style!)

    ;; Firefox: Use browser.contentScripts.register
    (exists? js/browser.contentScripts.register)
    (register-firefox-style!)

    ;; Safari: Warn that dynamic registration not supported
    :else
    (js/console.warn "Dynamic registration not supported, using manifest patterns")))
```

#### Option 2: Manifest-Only Approach (Simpler)

**All browsers**: Use manifest-based `content_scripts`
- Update manifest.json on script save (requires extension reload)
- Or inject loader for `<all_urls>` and filter at runtime
- Less dynamic, more compatible

**Trade-off**: Less flexible, but guaranteed to work everywhere.

#### Option 3: Loader-Only Approach (Most Compatible)

**All browsers**: Inject loader script for `<all_urls>` at `document-start`
- Loader reads storage, filters matching scripts
- Always loads (performance cost)
- No dynamic registration needed

**Trade-off**: Scittle loads on every page (overhead), but works everywhere.

### Recommended Path for Epupp

Given Epupp's current architecture:

1. **Keep Chrome implementation as-is**
   - `registerContentScripts` works well
   - Dynamic, persistent, clean

2. **Add Firefox support**:
   ```clojure
   ;; In registration.cljs
   (defn ^:async sync-firefox-registrations!
     []
     (when (exists? js/browser.contentScripts.register)
       (let [scripts (get-early-scripts)
             patterns (collect-approved-patterns scripts)]
         ;; Unregister old (if any)
         (when @!firefox-registration-id
           (js-await (.unregister @!firefox-registration-id)))
         ;; Register new
         (when (seq patterns)
           (let [reg (js-await (js/browser.contentScripts.register
                                 #js {:matches (clj->js patterns)
                                      :js #js [{:file "userscript-loader.js"}]
                                      :runAt "document_start"}))]
             (reset! !firefox-registration-id reg))))))
   ```

3. **Add startup re-registration for Firefox**:
   ```clojure
   ;; In background.cljs, on extension startup
   (when (firefox?)
     (js-await (sync-firefox-registrations!)))
   ```

4. **Safari**: Document limitation
   - Manifest-based patterns work
   - Dynamic registration not supported
   - Suggest using manifest `content_scripts` with broad patterns

### Migration Path

**Phase 1** (current): Chrome-only with `registerContentScripts`
**Phase 2**: Add Firefox support with `browser.contentScripts.register`
**Phase 3**: Document Safari limitations, potentially add manifest fallback
**Phase 4**: Consider loader-only approach if dynamic registration proves too complex

## Gotchas and Limitations

### 1. Firefox Registration Persistence

Firefox's `browser.contentScripts.register()` does NOT persist across browser restarts. Must re-register on:
- Extension startup (`runtime.onInstalled`, `runtime.onStartup`)
- Storage changes (script enabled/disabled)
- Pattern approval changes

### 2. Safari CSP Requirements

Safari requires explicit Content Security Policy for WebSocket connections:
```json
{
  "content_security_policy": {
    "extension_pages": "connect-src 'self' ws://localhost:*;"
  }
}
```

Already implemented in Epupp's `tasks.clj`.

### 3. Timing Guarantees

`document-start` is **not deterministic**:
- May run before or after `<html>` element exists
- `document.head` and `document.body` may not exist yet
- Use event listeners and guards (as TamperMonkey does)

### 4. Scittle Loading Overhead

Injecting Scittle (~500KB) at `document-start` blocks page rendering:
- Chrome: Synchronous script injection blocks parser
- Firefox: Same behavior
- Safari: Same behavior

**Mitigation**: Only register for URLs with early-timing scripts (current approach).

### 5. Cross-Browser Testing

Must test on all three browsers:
- Chrome: Works with current implementation
- Firefox: Requires separate code path
- Safari: Requires Xcode conversion, limited testing options

## References

### TamperMonkey Source Code

- [content.js](https://github.com/Tampermonkey/tampermonkey/blob/main/src/content.js) - Main content script with timing logic
- [environment.js](https://github.com/Tampermonkey/tampermonkey/blob/main/src/environment.js) - Script execution timing (`TM_runASAP`, `TM_runBody`, `TM_addLoadListener`)
- [background.js](https://github.com/Tampermonkey/tampermonkey/blob/main/src/background.js) - Background orchestration
- [manifest.json](https://github.com/Tampermonkey/tampermonkey/blob/main/build_sys/manifest.json.google.com) - Chrome manifest structure

### Browser Documentation

- [Chrome: scripting.registerContentScripts](https://developer.chrome.com/docs/extensions/reference/api/scripting#method-registerContentScripts)
- [Firefox: contentScripts.register](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/contentScripts/register)
- [MDN: manifest.json content_scripts](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/content_scripts)
- [MDN: Chrome incompatibilities](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Chrome_incompatibilities)
- [Safari: Converting a Web Extension](https://developer.apple.com/documentation/safariservices/safari_web_extensions/converting_a_web_extension_for_safari)

### Epupp Documentation

- [run-at-injection-timing.md](run-at-injection-timing.md) - Original research document
- [architecture.md](architecture.md) - Current Epupp architecture
- [userscripts-architecture.md](userscripts-architecture.md) - Userscript design decisions

## Next Steps

1. **Verify Firefox API availability**: Test `browser.contentScripts.register` in Firefox 142+
2. **Implement Firefox registration**: Add browser detection and Firefox-specific registration logic
3. **Test Safari compatibility**: Build Safari extension, verify manifest-based approach works
4. **Update documentation**: Document browser-specific behavior in architecture.md
5. **Add browser detection utilities**: Create helper functions for feature detection

## Conclusion

TamperMonkey's approach is **manifest-based content scripts with runtime timing logic**, not dynamic registration APIs. This works across all browsers. Epupp's current Chrome-specific implementation using `registerContentScripts` is more elegant but requires browser-specific code for Firefox and Safari.

**Recommended approach**:
- Keep Chrome's `registerContentScripts` (best UX)
- Add Firefox's `browser.contentScripts.register` (compatible)
- Document Safari limitations (manifest-based patterns or loader-only)

The good news: The core loader architecture (`userscript-loader.js` reading storage and injecting scripts) is already browser-agnostic. Only the *registration* layer needs browser-specific code.
