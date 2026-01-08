# @run-at Injection Timing

**Status**: Research / Planning
**Related**: [TamperMonkey Gap Analysis](tampermonkey-gap-analysis.md)

This document explores the `@run-at` injection timing feature from TamperMonkey and how it might be implemented in Epupp.

## TamperMonkey Reference

See [TamperMonkey @run-at documentation](https://www.tampermonkey.net/documentation.php#meta:run_at).

### Timing Modes

| Value | When it fires | Use case |
|-------|---------------|----------|
| `document-start` | As early as possible, before any page scripts execute | Intercept/modify globals, block scripts, patch APIs |
| `document-body` | When `<body>` element exists | Early DOM manipulation that needs body |
| `document-end` | At or after `DOMContentLoaded` | DOM is ready, before images/iframes load |
| `document-idle` | After `DOMContentLoaded` (default) | Safe default, full DOM available |
| `context-menu` | When user clicks browser context menu | On-demand scripts triggered by user action |

### Page Load Timeline

```
Navigation starts
    │
    ▼
[document-start] ◄── Earliest injection point
    │                 - No DOM yet (except <html>)
    │                 - Page scripts haven't run
    │                 - Can patch window.*, intercept fetch, etc.
    │
    ▼
<head> parsing begins
    │
    ▼
Page scripts start executing
    │
    ▼
<body> element created
    │
    ▼
[document-body] ◄── Body exists but not fully parsed
    │
    ▼
DOM parsing continues...
    │
    ▼
DOMContentLoaded event
    │
    ▼
[document-end] ◄── DOM complete, external resources loading
    │
    ▼
Images, iframes, etc. load
    │
    ▼
load event (window.onload)
    │
    ▼
[document-idle] ◄── Everything loaded (current Epupp behavior)
```

## Current Epupp State

Epupp currently injects scripts only at `webNavigation.onCompleted`, which fires after the page is fully loaded. This is roughly equivalent to TamperMonkey's `document-idle`.

**Current injection flow** (from [architecture.md](architecture.md)):
1. `webNavigation.onCompleted` fires (main frame only)
2. Match enabled scripts against URL
3. Check pattern approval status
4. If approved: inject content bridge → inject Scittle → inject userscript tags → trigger evaluation

## Why This Matters for REPL-Driven Development

Early injection timing is important for interactive development scenarios:

### 1. Intercepting Page Initialization

Many pages set up their state during initial script execution. To modify or observe this setup, you need to run before their scripts:

```clojure
;; Intercept fetch to log all API calls
(let [original-fetch js/window.fetch]
  (set! js/window.fetch
    (fn [& args]
      (println "Fetch:" (first args))
      (apply original-fetch args))))
```

This only works at `document-start`. By `document-idle`, the page has already made its initial requests.

### 2. Blocking Unwanted Scripts

Some pages load analytics, ads, or other scripts you want to prevent during development:

```clojure
;; Block specific scripts from loading
(let [original-createElement js/document.createElement]
  (set! js/document.createElement
    (fn [tag]
      (let [el (original-createElement tag)]
        (when (= tag "script")
          ;; Could filter by src attribute when set
          )
        el))))
```

### 3. Modifying Global Configuration

Many frameworks read configuration from global variables at startup:

```clojure
;; Set React development mode before React loads
(set! js/window.__REACT_DEVTOOLS_GLOBAL_HOOK__ #js {:isDisabled true})

;; Or inject configuration for a SPA
(set! js/window.APP_CONFIG #js {:debug true :apiUrl "http://localhost:3000"})
```

### 4. Observing Framework Initialization

To understand how a page works, you often want to observe its initialization:

```clojure
;; Watch for specific global variables being set
(js/Object.defineProperty js/window "myApp"
  #js {:set (fn [v] (println "myApp set to:" v) v)
       :get (fn [] @!app-ref)
       :configurable true})
```

## Implementation Considerations

### Chrome Extension APIs

Chrome provides several mechanisms for content script timing:

#### 1. `chrome.scripting.registerContentScripts` (for `document-start`)

```javascript
chrome.scripting.registerContentScripts([{
  id: "my-script",
  matches: ["https://example.com/*"],
  js: ["content-script.js"],
  runAt: "document_start",  // or "document_end", "document_idle"
  world: "MAIN"  // or "ISOLATED"
}]);
```

**Pros**: True `document-start` timing, persistent across navigations
**Cons**: Must be registered before navigation, requires dynamic management

#### 2. `chrome.scripting.executeScript` (for `document-end` and later)

```javascript
chrome.scripting.executeScript({
  target: {tabId: tabId},
  files: ["script.js"],
  injectImmediately: true,  // false waits for DOMContentLoaded
  world: "MAIN"
});
```

**Pros**: On-demand injection, simpler API
**Cons**: Cannot achieve true `document-start`

### Architectural Challenges

#### Challenge 1: Scittle Loading Time

Scittle itself needs to load before userscripts can run. The current vendor bundle is ~500KB. At `document-start`, we'd need to:
- Inject Scittle synchronously (blocking)
- Or pre-register Scittle as a content script

**Potential approach**: Register Scittle as a content script for URLs that have `document-start` userscripts. This adds Scittle to all matching pages, even without REPL connection.

#### Challenge 2: Per-Pattern Approval

Current approval flow happens at runtime via popup interaction. For `document-start`, we need approval before navigation:
- Pre-approved patterns could use `registerContentScripts`
- Unapproved patterns fall back to later injection with approval prompt

#### Challenge 3: Dynamic Script Registration

When users save/enable scripts, we need to update registered content scripts:
- Add/remove from `chrome.scripting.registeredContentScripts`
- Handle script updates (unregister + re-register)
- Persist registrations across extension restarts

#### Challenge 4: Multiple Scripts Same URL

If multiple scripts target the same URL with different `run-at` values:
- Group by timing for efficient registration
- Ensure execution order within same timing is predictable

### Proposed Schema Changes

```clojure
{:script/id "..."
 :script/name "..."
 :script/match ["https://.../*"]
 :script/code "..."
 :script/enabled true
 :script/run-at :document-idle  ; NEW: :document-start, :document-body, :document-end, :document-idle
 :script/created "ISO-timestamp"
 :script/modified "ISO-timestamp"
 :script/approved-patterns ["..."]
 :script/description "..."}
```

### Proposed Implementation Phases

#### Phase 1: Schema and UI (S - days)
- Add `:script/run-at` field with default `:document-idle`
- Add dropdown/selector in DevTools panel
- Display run-at value in popup script list

#### Phase 2: `document-end` Support (S - days)
- Use `executeScript` with `injectImmediately: false`
- Triggered by `webNavigation.onDOMContentLoaded` instead of `onCompleted`

#### Phase 3: `document-start` Support (M - weeks)
- Implement content script registration management
- Handle Scittle pre-loading for early injection
- Update approval flow for pre-registered scripts

#### Phase 4: `context-menu` Support (S - optional)
- Register context menu item
- Execute script on menu click
- Lower priority, niche use case

## Open Questions

1. **Should Scittle always be injected for matching URLs?**
   - Pro: Enables true `document-start`
   - Con: Adds overhead to pages even without active REPL

2. **How to handle approval for `document-start` scripts?**
   - Option A: Require approval before enabling (approval gates registration)
   - Option B: Register optimistically, check approval at runtime (may miss early window)

3. **What about the REPL connection timing?**
   - REPL connection currently happens after page load
   - `document-start` scripts would run before REPL connects
   - Is this acceptable? (scripts run, REPL connects later for iteration)

4. **Performance impact of pre-registered content scripts?**
   - Need to measure overhead of Scittle injection on matching pages
   - Consider lazy-loading strategies

## References

- [TamperMonkey @run-at documentation](https://www.tampermonkey.net/documentation.php#meta:run_at)
- [Chrome scripting.registerContentScripts](https://developer.chrome.com/docs/extensions/reference/api/scripting#method-registerContentScripts)
- [Chrome scripting.executeScript](https://developer.chrome.com/docs/extensions/reference/api/scripting#method-executeScript)
- [MDN: DOMContentLoaded](https://developer.mozilla.org/en-US/docs/Web/API/Document/DOMContentLoaded_event)
- [Epupp Architecture](architecture.md)
