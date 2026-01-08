# Cross-Browser Run-At Implementation

**Created:** January 8, 2026
**Status:** Chrome and Firefox implemented, Safari documented as unsupported

This document captures research and implementation strategy for supporting `document-start` and `document-end` timing in Firefox and Safari.

## Current State

- **Chrome**: Fully implemented using `chrome.scripting.registerContentScripts`
- **Firefox**: Fully implemented using `browser.contentScripts.register`
- **Safari**: Not supported (no dynamic registration API available)

## Browser API Comparison

| Feature | Chrome MV3 | Firefox | Safari |
|---------|------------|---------|--------|
| Dynamic registration | `chrome.scripting.registerContentScripts` | `browser.contentScripts.register()` | Not available |
| Persistence | Yes (`persistAcrossSessions: true`) | No (must re-register on startup) | N/A |
| Manifest content_scripts | Yes | Yes | Yes |
| run_at values | `document_start`, `document_end`, `document_idle` | Same | Same |

## Firefox Implementation

### API: `browser.contentScripts.register()`

Firefox provides a different API for dynamic content script registration:

```javascript
// Firefox-specific registration
const registration = await browser.contentScripts.register({
  matches: ["*://github.com/*"],
  js: [{ file: "userscript-loader.js" }],
  runAt: "document_start"
});

// To unregister later:
registration.unregister();
```

### Key Differences from Chrome

1. **No persistence**: Registrations are lost when extension restarts
2. **Returns registration object**: Must store reference to unregister
3. **Namespace**: Uses `browser.*` not `chrome.*` (though Firefox supports both)

### Implementation Strategy for Firefox

```javascript
// In registration.cljs - Firefox path
(defn ^:async register-firefox! [patterns]
  (when (seq patterns)
    (let [registration (js-await
                        (js/browser.contentScripts.register
                         #js {:matches (clj->js patterns)
                              :js #js [#js {:file "userscript-loader.js"}]
                              :runAt "document_start"
                              :allFrames false}))]
      ;; Store registration for later unregister
      (swap! !state assoc :registration/firefox registration))))

;; Must re-register on extension startup
(defn ^:async ensure-firefox-registration! []
  (when (firefox?)
    (let [scripts (storage/get-early-scripts)]
      (when (seq scripts)
        (register-firefox! (collect-approved-patterns scripts))))))
```

### Firefox Startup Hook

Since registrations don't persist, must re-register in `background.cljs`:

```javascript
// Add to initialization
(.addListener js/browser.runtime.onStartup
  (fn []
    (ensure-firefox-registration!)))

// Also on install/update
(.addListener js/browser.runtime.onInstalled
  (fn []
    (ensure-firefox-registration!)))
```

## Safari Implementation

### Limitations

Safari's Web Extension support is more limited:

1. **No dynamic registration API**: Safari does not expose `contentScripts.register()`
2. **Manifest-only**: Content scripts must be declared in `manifest.json`
3. **CSP already handled**: Epupp already adds `content_security_policy` for Safari

### Options for Safari

**Option A: Broad manifest pattern (not recommended)**
```json
{
  "content_scripts": [{
    "matches": ["<all_urls>"],
    "js": ["userscript-loader.js"],
    "run_at": "document_start"
  }]
}
```
- Pro: Works for early timing
- Con: Loader runs on every page, performance impact

**Option B: Document timing not supported (recommended for now)**
- Safari users get `document-idle` behavior only
- Document this limitation clearly
- Revisit when Safari adds dynamic registration

### Manifest Adjustments for Safari

Already in place via `tasks.clj:adjust-manifest`:
- Uses `background.scripts` instead of `service_worker`
- Adds `content_security_policy` for `ws://localhost:*`

## TamperMonkey's Approach

TamperMonkey uses a **manifest-based loader** strategy:

1. Declares a content script in manifest with `<all_urls>` at `document_start`
2. Loader reads storage to find matching scripts
3. Filters and executes only relevant scripts at runtime

**Why this works for TamperMonkey but may not for Epupp:**
- TamperMonkey's loader is lightweight (~10KB)
- Epupp needs Scittle (~500KB) for ClojureScript evaluation
- Loading Scittle on every page at `document_start` would be too slow

## Recommended Implementation Plan

### Phase 1: Firefox Support (Completed)

Browser detection and Firefox registration implemented in `registration.cljs`:

```clojure
(defn chrome? []
  (and (exists? js/chrome)
       (exists? js/chrome.scripting)
       (exists? js/chrome.scripting.registerContentScripts)))

(defn firefox? []
  (and (exists? js/browser)
       (exists? js/browser.contentScripts)
       (exists? js/browser.contentScripts.register)))

(defn ^:async sync-registrations! []
  (cond
    (chrome?) (js-await (sync-chrome-registrations!))
    (firefox?) (js-await (sync-firefox-registrations!))
    :else (js/console.log "No dynamic registration API (Safari?)")))
```

Firefox registration is re-created on startup via `ensure-initialized!` in background.cljs.

### Phase 2: Safari Documentation (Completed)

Safari limitation documented in README.md. Scripts with early timing fall back to `document-idle` behavior.

### Phase 3: Unified Architecture

Refactor to abstract browser differences:

```clojure
(defprotocol ContentScriptRegistration
  (register! [this patterns])
  (unregister! [this])
  (persistent? [this]))

;; Chrome implementation
(deftype ChromeRegistration []
  ContentScriptRegistration
  (register! [_ patterns] ...)
  (persistent? [_] true))

;; Firefox implementation
(deftype FirefoxRegistration [!registration]
  ContentScriptRegistration
  (register! [_ patterns] ...)
  (persistent? [_] false))
```

## Gotchas and Edge Cases

### 1. Timing Non-Determinism

`document-start` does not guarantee `<head>` exists:

```javascript
// In userscript-loader.js - may need to wait
if (!document.head) {
  // Create observer or use setTimeout fallback
}
```

### 2. Scittle Loading Performance

Loading Scittle (~500KB) at `document-start` adds latency before userscripts execute:

| Browser | Scittle Load Time | Document State After |
|---------|-------------------|---------------------|
| Chrome  | ~40ms             | interactive         |
| Firefox | ~100ms            | interactive         |

*(Measured on Mac M4 Pro Max)*

The loader runs at true `document-start` (`readyState: "loading"`), but by the time Scittle parses and loads, the document typically reaches `"interactive"`. This means:

- Scripts run during page load, before `"complete"`
- Scripts run after HTML parsing but before all resources load
- DOM is available for manipulation
- Page scripts may have already started executing

For use cases requiring execution before ANY page JavaScript (like blocking analytics), a pure JS solution would be needed. For most userscript use cases (DOM manipulation, UI enhancements), `"interactive"` timing is sufficient.

### 3. Firefox Registration Persistence

Registration must complete before navigation to target page. Race condition possible if:
- User approves pattern
- Immediately navigates to matching URL
- Registration not yet complete

**Mitigation:** Show "pending" state until registration confirms.

### 4. Content Script vs Page Script Injection

Current `userscript-loader.js` injects `<script>` tags into page. This works because:
- Loader runs in ISOLATED world (has DOM access)
- Injected scripts run in MAIN world (page context)

This pattern works identically in Firefox and Safari.

## Testing Strategy

### Firefox Testing

1. Install extension in Firefox
2. Create script with `{:epupp/run-at "document-start"}`
3. Approve pattern
4. Navigate to matching URL
5. Verify script runs before page scripts (check console log order)
6. Restart Firefox
7. Verify script still runs (re-registration worked)

### Safari Testing

1. Confirm `document-idle` scripts work
2. Document that early timing is not supported
3. Test that scripts with early timing fall back gracefully

## References

- [Firefox contentScripts.register()](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/contentScripts/register)
- [Chrome registerContentScripts](https://developer.chrome.com/docs/extensions/reference/api/scripting#method-registerContentScripts)
- [Safari Web Extensions](https://developer.apple.com/documentation/safariservices/safari_web_extensions)
- [TamperMonkey source](https://github.com/nickyc975/user-script-manager) (community reference implementation)

## Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-01-08 | Chrome-only for Phase 1 | Ship working feature, iterate |
| 2026-01-08 | Firefox via `browser.contentScripts.register()` | Only viable API |
| 2026-01-08 | Safari: document-idle only | No dynamic registration API |
| 2026-01-08 | Firefox implementation completed | Added browser detection and Firefox-specific registration path |
