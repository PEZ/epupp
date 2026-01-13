# REPL Manifest Implementation Plan

**Created**: January 13, 2026
**Status**: Design complete, ready for implementation
**Required reading**: [scittle-dependencies-implementation.md](scittle-dependencies-implementation.md)

## Problem Statement

Code evaluated via connected REPL (nREPL -> Babashka relay -> WebSocket -> Scittle) bypasses Epupp's injection orchestration entirely. Users cannot access Scittle ecosystem libraries (Reagent, Replicant, etc.) from the REPL unless a userscript already loaded them.

## Solution

Inject an `epupp` namespace when the REPL connects, providing `epupp/manifest!` function that triggers library injection on demand.

**User workflow:**
```clojure
;; From connected REPL - load libraries first
(epupp/manifest! {:epupp/require ["scittle://reagent.js"]})

;; Then use them
(require '[reagent.core :as r])
(r/atom {:count 0})
```

## Design Decisions

### API: `epupp/manifest!` vs `js/epupp.manifest`

| Option | Pros | Cons |
|--------|------|------|
| `epupp/manifest!` | Idiomatic Clojure, matches userscript pattern | Requires Scittle snippet injection |
| `js/epupp.manifest` | Simple JS global | Clunky from Clojure |

**Decision**: `epupp/manifest!` - better UX, consistent with userscript manifest format.

### Injection Point

Inject the `epupp` namespace at REPL connect time, after `ensure-scittle-nrepl!` succeeds in `connect-tab!`.

### Manifest Format

Same as userscripts - future-proof for additional manifest keys:
```clojure
{:epupp/require ["scittle://reagent.js" "scittle://pprint.js"]}
```

## Architecture

```
REPL eval: (epupp/manifest! {...})
    |
    v
Scittle executes epupp/manifest! function
    |
    v
postMessage to content bridge (source: "epupp-page", type: "load-manifest")
    |
    v
Content bridge forwards to background worker
    |
    v
Background: inject-requires-sequentially! (existing)
    |
    v
postMessage response back through bridge
    |
    v
Promise resolves in epupp/manifest!
```

## Implementation Phases

### Phase 1: Epupp Namespace Snippet

Create the ClojureScript code to inject via Scittle eval.

**File**: Inline string in `background.cljs` (or separate `.cljs` resource)

```clojure
(ns epupp)

(defn manifest!
  "Load Epupp manifest. Injects required Scittle libraries.
   Returns a promise that resolves when libraries are loaded.

   Example: (epupp/manifest! {:epupp/require [\"scittle://reagent.js\"]})"
  [m]
  (js/Promise.
    (fn [resolve reject]
      (let [handler (fn [e]
                      (when (identical? (.-source e) js/window)
                        (let [msg (.-data e)]
                          (when (and msg
                                     (= "epupp-bridge" (.-source msg))
                                     (= "manifest-response" (.-type msg)))
                            (.removeEventListener js/window "message" handler)
                            (if (.-success msg)
                              (resolve true)
                              (reject (js/Error. (.-error msg))))))))]
        (.addEventListener js/window "message" handler)
        (.postMessage js/window
          #js {:source "epupp-page"
               :type "load-manifest"
               :manifest (clj->js m)}
          "*")))))
```

### Phase 2: Content Bridge Handler

Add handler for `load-manifest` message in `content_bridge.cljs`.

**Location**: `handle-page-message` function, after existing `epupp-page` handlers

```clojure
"load-manifest"
(do
  (log/info "Bridge" nil "Forwarding manifest request to background")
  (try
    (js/chrome.runtime.sendMessage
     #js {:type "load-manifest"
          :manifest (.-manifest msg)}
     (fn [response]
       (.postMessage js/window
                     #js {:source "epupp-bridge"
                          :type "manifest-response"
                          :success (.-success response)
                          :error (.-error response)}
                     "*")))
    (catch :default e
      ;; Handle extension context invalidated
      ...)))
```

### Phase 3: Background Message Handler

Add `"load-manifest"` handler in `background.cljs` message listener.

**Reuses**: `inject-requires-sequentially!` and `scittle-libs/collect-require-files`

```clojure
"load-manifest"
(let [manifest (.-manifest message)
      requires (when manifest
                 (vec (aget manifest "epupp/require")))]
  ((^:async fn []
     (try
       (when (seq requires)
         (let [tab-id (js-await (get-sender-tab-id sender))
               files (scittle-libs/collect-require-files [{:script/require requires}])]
           (when (seq files)
             (js-await (inject-requires-sequentially! tab-id files)))))
       (send-response #js {:success true})
       (catch :default err
         (send-response #js {:success false :error (.-message err)})))))
  true)
```

**Note**: Need to get tab ID from sender context since message comes from content script.

### Phase 4: Inject at REPL Connect

Modify `connect-tab!` in `background.cljs` to inject the epupp namespace.

**Location**: After `ensure-scittle-nrepl!` succeeds

```clojure
(defn ^:async connect-tab!
  [tab-id ws-port]
  ;; ... existing code ...
  (let [status2 (js-await (execute-in-page tab-id check-status-fn))]
    (js-await (ensure-scittle-nrepl! tab-id ws-port status2)))
  ;; NEW: Inject epupp namespace
  (js-await (inject-epupp-namespace! tab-id))
  true)
```

The `inject-epupp-namespace!` function evaluates the snippet via Scittle:

```clojure
(def epupp-namespace-code
  "(ns epupp) (defn manifest! [m] ...)")

(defn ^:async inject-epupp-namespace!
  [tab-id]
  (js-await (execute-in-page tab-id eval-scittle-fn epupp-namespace-code)))
```

### Phase 5: E2E Test

Add test to `e2e/repl_ui_spec.cljs` that validates the full pipeline.

**Test: REPL manifest loads Replicant and renders UI**

```clojure
(test "epupp/manifest! loads Replicant for REPL evaluation"
  (^:async fn []
    ;; Step 1: Load Replicant via manifest
    (let [manifest-result (js-await (eval-in-browser
                            "(epupp/manifest! {:epupp/require [\"scittle://replicant.js\"]})"))]
      (-> (expect (.-success manifest-result)) (.toBe true)))

    ;; Step 2: Wait for injection to complete
    (js-await (sleep 1000))

    ;; Step 3: Use Replicant to render Epupp-themed UI
    (let [render-result (js-await (eval-in-browser
                          "(require '[replicant.dom :as r])
                           (let [container (js/document.createElement \"div\")]
                             (set! (.-id container) \"epupp-repl-test\")
                             (.appendChild js/document.body container)
                             (r/render container
                               [:div.epupp-banner
                                [:h2 \"Epupp REPL\"]
                                [:p \"Live tampering your web!\"]]))
                           :rendered"))]
      (-> (expect (.-success render-result)) (.toBe true))
      (-> (expect (.-values render-result)) (.toContain ":rendered")))

    ;; Step 4: Verify DOM was actually modified
    (let [dom-check (js-await (eval-in-browser
                       "(boolean (js/document.getElementById \"epupp-repl-test\"))"))]
      (-> (expect (.-success dom-check)) (.toBe true))
      (-> (expect (.-values dom-check)) (.toContain "true")))))
```

**Why Replicant?**
- Validates full require pipeline (library resolution, injection, availability)
- Proves async promise-based flow works
- Observable result (DOM element created)
- More interesting than pprint - actually does something visible

## Testing Strategy

### E2E Tests (Primary)
The `repl_ui_spec.cljs` test validates the complete pipeline:
1. REPL connected to page
2. `epupp/manifest!` called with Replicant require
3. Promise resolves successfully
4. Replicant namespace available
5. DOM manipulation works

See [testing-e2e.md](testing-e2e.md) for infrastructure details.

### Manual Testing (Secondary)
1. Connect REPL to a page
2. Evaluate `(epupp/manifest! {:epupp/require ["scittle://pprint.js"]})`
3. Verify promise resolves
4. Evaluate `(require '[cljs.pprint :refer [pprint]])`
5. Evaluate `(pprint {:a 1 :b 2})` - should pretty print

### Unit Tests
- None needed - this is integration/wiring code

## Files to Modify

| File | Change |
|------|--------|
| `src/background.cljs` | Add `"load-manifest"` handler, `inject-epupp-namespace!`, modify `connect-tab!` |
| `src/content_bridge.cljs` | Add `"load-manifest"` forwarding in `handle-page-message` |
| `e2e/repl_ui_spec.cljs` | Add `epupp/manifest!` test with Replicant |

## Security Considerations

The `load-manifest` message is forwarded from page context. This is acceptable because:
1. It only triggers library injection (same libraries available to userscripts)
2. Libraries are bundled with extension (no external URLs)
3. Requires content bridge to be injected (only happens on REPL connect)

Add to content bridge security whitelist comment:
```
;; - epupp-page source: load-manifest (library injection for REPL)
```

## Effort Estimate

| Phase | Effort |
|-------|--------|
| Phase 1: Namespace snippet | S (30 min) |
| Phase 2: Content bridge | S (15 min) |
| Phase 3: Background handler | S (30 min) |
| Phase 4: Connect injection | S (15 min) |
| Phase 5: E2E test | S (30 min) |
| Testing & iteration | M (1h) |
| **Total** | **~3h** |

## Success Criteria

- [ ] `(epupp/manifest! {:epupp/require ["scittle://replicant.js"]})` returns promise
- [ ] Promise resolves after libraries injected
- [ ] `(require '[replicant.dom])` works after manifest call
- [ ] Works on CSP-strict sites (GitHub, YouTube)
- [ ] Idempotent - calling twice doesn't break anything
- [ ] E2E test passes: Replicant renders DOM element from REPL

## Future Considerations

### Auto-Detection (Idea 1 from design discussion)

Could add manifest detection in browser-nrepl relay to auto-inject before eval. Deferred - explicit `epupp/manifest!` is clearer and sufficient for now.

### Additional Manifest Keys

The manifest format supports future extensions:
- `:epupp/inject-css` - inject stylesheets
- `:epupp/permissions` - request additional capabilities
