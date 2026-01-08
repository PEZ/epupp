# SCI Integration for Background Worker - Planning Document

**Created:** January 8, 2026
**Status:** Research/Future Planning

## Vision

Enable Clojure evaluation and ecosystem reach in the background worker, providing access to `clojure.edn`, `clojure.string`, `clojure.set`, and potentially custom namespaces for extension logic.

## Research Findings

### SCI Overview

**SCI (Small Clojure Interpreter)** is the engine behind Babashka, Scittle, Joyride, and many other tools. It provides:

- Safe Clojure evaluation (no host `eval`)
- Configurable namespace exposure
- Works in Clojure JVM, GraalVM, ClojureScript, and JavaScript
- Battle-tested in production

### Distribution Challenge

**SCI is NOT available as an npm package.** It's a ClojureScript library that must be:

1. Compiled with shadow-cljs or ClojureScript compiler
2. Bundled as a JavaScript file
3. Exposed via global or module exports

### Current Scittle Usage

We already vendor Scittle (which includes SCI):
- `vendor/scittle.js` - 863KB (minified, includes SCI + core libs)
- `vendor/scittle.nrepl.js` - 9.8KB (nREPL client)

**Problem:** Scittle is designed for page/DOM context, not service workers:
- Assumes `window` global
- Uses script tag processing
- Large bundle for service worker context

## Technical Approaches

### Approach A: Build Custom SCI Bundle for Service Worker

Create a minimal SCI build specifically for service worker context.

**Process:**

1. Create new shadow-cljs build target:
   ```clojure
   ;; shadow-cljs.edn
   {:builds
    {:sci-worker
     {:target :esm
      :output-dir "extension/vendor"
      :modules {:sci {:entries [sci.core]
                      :exports {eval-string sci.core/eval-string
                                init sci.core/init
                                fork sci.core/fork}}}
      :compiler-options {:optimizations :advanced}}}}
   ```

2. Configure minimal namespaces:
   ```clojure
   (sci/init {:namespaces {'clojure.edn {'read-string edn/read-string}
                           'clojure.string {'join str/join
                                            'split str/split
                                            'trim str/trim
                                            ;; etc
                                            }
                           'clojure.set {'union set/union
                                         'intersection set/intersection}}})
   ```

3. Expose as ES module for service worker import

**Estimated size:** 200-400KB (depending on included namespaces)

**Pros:**
- Full Clojure semantics
- Access to clojure.* namespaces
- Can add custom namespaces for extension logic
- Reuse evaluation patterns from Scittle

**Cons:**
- Significant bundle size increase
- Requires shadow-cljs build setup
- Maintenance of separate SCI build

### Approach B: Offscreen Document with Scittle

Use Chrome's [Offscreen API](https://developer.chrome.com/docs/extensions/reference/offscreen/) to run Scittle in a hidden page context.

**Process:**

1. Create `offscreen.html` that loads Scittle
2. Background worker creates offscreen document on demand
3. Send code via messaging, receive results

```javascript
// background.js
await chrome.offscreen.createDocument({
  url: 'offscreen.html',
  reasons: ['DOM_PARSER'],  // or WORKERS
  justification: 'Clojure evaluation via Scittle'
});

// Send evaluation request
const result = await chrome.runtime.sendMessage({
  type: 'eval-clojure',
  code: '(+ 1 2)'
});
```

**Pros:**
- Reuses existing Scittle bundle
- Full Scittle capabilities
- No new build tooling

**Cons:**
- Async overhead for every evaluation
- Offscreen document lifecycle management
- Chrome-specific (Firefox/Safari need different approaches)
- Offscreen doc can be closed by browser to save resources

### Approach C: Compile Background Worker with ClojureScript

Instead of Squint, compile background.cljs with shadow-cljs including SCI.

**Process:**

1. Add shadow-cljs.edn for background worker build
2. Include SCI in dependencies
3. Write background logic in full ClojureScript

```clojure
;; deps.edn
{:deps {org.babashka/sci {:mvn/version "0.11.50"}}}

;; shadow-cljs.edn
{:builds
 {:background
  {:target :chrome-extension
   :extension-dir "extension"
   :outputs {:background "background.js"}}}}
```

**Pros:**
- Full ClojureScript power
- Native SCI integration
- Better development experience

**Cons:**
- Major architecture change
- Lose Squint simplicity
- Larger bundle sizes
- Build complexity increase

### Approach D: Hybrid - SCI for Specific Operations

Add SCI only for specific operations that need it (e.g., EDN parsing, string operations), keep rest in Squint.

**Process:**

1. Build minimal SCI bundle with only needed namespaces
2. Lazy-load when needed
3. Use for specific operations, not general evaluation

```clojure
;; In Squint background.cljs
(defn ^:async parse-edn [s]
  (let [sci (js-await (js/import "./vendor/sci-mini.js"))]
    (.eval_string sci (str "(clojure.edn/read-string \"" (escape s) "\")"))))
```

**Estimated size:** 100-200KB for minimal build

**Pros:**
- Smaller impact than full SCI
- Lazy loading reduces initial load
- Targeted functionality

**Cons:**
- Still significant bundle addition
- Requires custom SCI build

## Recommended Path Forward

### Phase 1: Minimal EDN Parser (Immediate)

See [edn-parsing-plan.md](edn-parsing-plan.md) - implement custom minimal EDN parser for immediate needs.

### Phase 2: Evaluate Need (1-2 months)

As we build more features, track when we need:
- `clojure.string` functions beyond what Squint provides
- `clojure.set` operations
- Full EDN with tagged literals
- Clojure macro evaluation

### Phase 3: SCI Integration (If Needed)

If Phase 2 reveals significant need:

1. **Start with Approach D** - minimal SCI bundle for specific operations
2. **Measure bundle size impact** - must stay reasonable for extension
3. **Consider Approach B** (offscreen) if bundle size is prohibitive
4. **Only move to Approach C** (full shadow-cljs) if we need pervasive Clojure

## Technical Considerations

### Bundle Size Budget

Current extension size (rough):
- `background.js` - ~50KB
- `scittle.js` - 863KB (page-injected, not counted in extension load)
- Other scripts - ~30KB

**Target:** Keep background worker bundle < 300KB additional for SCI

### Service Worker Constraints

- No DOM access
- No `window` global
- CSP applies (no `eval()` - but SCI doesn't use host eval)
- Should load quickly (affects extension startup)

### Cross-Browser Compatibility

| Feature | Chrome | Firefox | Safari |
|---------|--------|---------|--------|
| Service Worker | Yes | Yes | Yes |
| Offscreen API | Yes | No | No |
| ES Modules in worker | Yes | Yes | Yes |

Approach B (offscreen) is Chrome-only. Approaches A, C, D work cross-browser.

## Resources

- **SCI GitHub:** https://github.com/babashka/sci
- **SCI API:** https://github.com/babashka/sci/blob/master/API.md
- **shadow-cljs Chrome Extension:** https://shadow-cljs.github.io/docs/UsersGuide.html#target-chrome-extension
- **Offscreen API:** https://developer.chrome.com/docs/extensions/reference/offscreen/

## Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-01-08 | Start with custom EDN parser | Minimal impact, covers immediate need |
| TBD | Evaluate SCI need | Based on feature requirements |

## Open Questions

1. What specific `clojure.*` functions do we need beyond EDN?
2. Is 200-400KB bundle increase acceptable for full SCI?
3. Should userscript metadata use EDN or stick with JSON?
4. Do we need macro evaluation in background context?
