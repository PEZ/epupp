# Scittle Dependencies Implementation Plan

**Created**: January 12, 2026
**Status**: Phase 3A complete, Phase 3B in progress
**Related**: [require-feature-design.md](research/require-feature-design.md)

## Current Status

### What Works
- **Auto-injection on page load**: Userscripts with `:epupp/require` that match the current URL get their dependencies injected correctly when the page loads (via `webNavigation.onCompleted` → `execute-scripts!`)
- **Library resolution**: `scittle-libs.cljs` correctly resolves dependencies (e.g., Reagent → React + ReactDOM + Reagent)
- **Manifest parsing**: `:epupp/require` is parsed and stored with scripts
- **Panel UI**: Shows "Requires: N libraries ✓" in property table

### Testing Status
- **E2E tests**: All 56 pass (Docker/headless Chrome)
- **Unit tests**: All 317 pass
- **Manual testing**: Limited - only tested in Brave (Chromium) with default Shields up
- **Not yet tested**: Firefox, Safari, other Chromium browsers

### What Doesn't Work Yet
- **Panel evaluation**: Running a script with requires from the DevTools panel doesn't inject dependencies (goes through `ensure-scittle!` → `eval-in-page!`, bypasses `execute-scripts!`)
- **Popup "Run" button**: Same issue - manual script execution doesn't inject requires
- **REPL evaluation**: Code evaluated via nREPL doesn't have access to libraries unless a matching userscript already loaded them

### Root Cause
The require injection logic is only in `execute-scripts!` (auto-injection flow). Manual evaluation paths don't call this function.

### Next Steps
**Phase 3B**: Add require injection to panel/popup evaluation flows:
1. Panel: Before `eval-in-page!`, check if code has `:epupp/require` and inject dependencies
2. Popup "Run": Ensure dependencies are injected before running script

### Implementation Details for Phase 3B

#### Key Insight: Two Different Code Paths

**Popup "Run" button** - Uses the full script from storage:
- **popup.cljs** `:popup/fx.evaluate-script` sends message to background with full script
- **background.cljs** `"evaluate-script"` handler passes to `execute-scripts!`
- **THE FIX**: The message currently sends `:script/code` but NOT `:script/require`
  - Location: [popup.cljs#L235-L244](../../src/popup.cljs#L235)
  - Change: Include `:script/require` in the message
  - Location: [background.cljs#L919-L932](../../src/background.cljs#L919)
  - Change: Pass `:script/require` to the script map given to `execute-scripts!`

**Panel evaluation** - Uses code typed in editor (may have manifest):
- **panel.cljs** `:editor/ax.eval` dispatches `:editor/fx.inject-and-eval` or `:editor/fx.eval-in-page`
- Panel has `manifest-hints` with parsed `:require` from current code
- **THE FIX**: Before evaluation, if manifest has requires, inject them
  - Location: [panel.cljs#L91-L115](../../src/panel.cljs#L91) `perform-effect!`
  - Two effects need updating: `:editor/fx.inject-and-eval` and `:editor/fx.eval-in-page`
  - Need to call background `"inject-requires"` message (NEW) before eval

#### New Message Type Needed

Add `"inject-requires"` message to background.cljs:
```clojure
"inject-requires"
(let [target-tab-id (.-tabId message)
      requires (js->clj (.-requires message) :keywordize-keys true)]
  ((^:async fn []
     (try
       (let [files (scittle-libs/collect-require-files [{:script/require requires}])]
         (when (seq files)
           (js-await (inject-content-script target-tab-id "content-bridge.js"))
           (js-await (wait-for-bridge-ready target-tab-id))
           (js-await (inject-requires-sequentially! target-tab-id files))))
       (send-response #js {:success true})
       (catch :default err
         (send-response #js {:success false :error (.-message err)})))))
  true)
```

## Overview

This plan covers implementing the `scittle://` URL scheme for the `@require` feature, allowing userscripts to load bundled Scittle ecosystem libraries.

## Bundled Libraries

All libraries downloaded and ready in `extension/vendor/`:

| Library | File | Size | Dependencies |
|---------|------|------|--------------|
| Core (already integrated) | `scittle.js` | 863 KB | None (requires CSP patch) |
| nREPL (already integrated) | `scittle.nrepl.js` | 10 KB | scittle.js |
| Pretty Print | `scittle.pprint.js` | 120 KB | scittle.js |
| Promesa | `scittle.promesa.js` | 92 KB | scittle.js |
| Replicant | `scittle.replicant.js` | 60 KB | scittle.js |
| JS Interop | `scittle.js-interop.js` | 61 KB | scittle.js |
| Reagent | `scittle.reagent.js` | 75 KB | scittle.js + React |
| Re-frame | `scittle.re-frame.js` | 123 KB | scittle.reagent.js |
| CLJS Ajax | `scittle.cljs-ajax.js` | 104 KB | scittle.js |
| React | `react.production.min.js` | 10 KB | None |
| ReactDOM | `react-dom.production.min.js` | 129 KB | react.js |

**Total bundle size**: ~1.65 MB (all libraries)

## Workflow

**ALWAYS act informed.** You start by investigating the testing docs and the existing tests to understand patterns and available fixture.

**ALWAYS use `bb <task>` over direct shell commands.** The bb tasks encode project-specific configurations. Check `bb tasks` for available commands.

**ALWAYS check lint/problem reports after edits.** Use `get_errors` tool to verify no syntax or bracket errors before running tests.

**ALWAYS use the `edit` subagent for file modifications.** The edit subagent specializes in Clojure/Squint structural editing and avoids bracket balance issues. Provide it with complete context: file paths, line numbers, and the exact changes needed.

- `bb test` - Compile and run unit tests
- `bb test:e2e` - Compile and run E2E tests (Docker)

## Implementation Phases

### Phase 1: Library Mapping ✅ COMPLETE

Created `src/scittle_libs.cljs` with library catalog and dependency resolution.

#### 1.1 Create `src/scittle_libs.cljs`

```clojure
(ns scittle-libs)

(def library-catalog
  "Catalog of bundled Scittle libraries and their dependencies"
  {:pprint     {:file "scittle.pprint.js"
                :deps #{:core}}
   :promesa    {:file "scittle.promesa.js"
                :deps #{:core}}
   :replicant  {:file "scittle.replicant.js"
                :deps #{:core}}
   :js-interop {:file "scittle.js-interop.js"
                :deps #{:core}}
   :reagent    {:file "scittle.reagent.js"
                :deps #{:core :react}}
   :re-frame   {:file "scittle.re-frame.js"
                :deps #{:core :reagent}}
   :cljs-ajax  {:file "scittle.cljs-ajax.js"
                :deps #{:core}}
   ;; Internal dependencies (not directly requestable)
   :core       {:file "scittle.js"
                :internal true}
   :react      {:files ["react.production.min.js" "react-dom.production.min.js"]
                :internal true}})

(defn resolve-scittle-url
  "Resolve scittle:// URL to library key.
   Returns nil for invalid URLs."
  [url]
  (when (string? url)
    (when-let [[_ lib-name] (re-matches #"scittle://(.+)\.js" url)]
      (keyword lib-name))))

(defn get-library-files
  "Get the vendor file(s) for a library key.
   Returns vector of filenames in load order."
  [lib-key]
  (when-let [lib (get library-catalog lib-key)]
    (if (:files lib)
      (:files lib)
      [(:file lib)])))

(defn resolve-dependencies
  "Resolve all dependencies for a library key.
   Returns vector of library keys in load order (topological sort)."
  [lib-key]
  (let [visited (atom #{})
        result (atom [])]
    (letfn [(visit [k]
              (when-not (@visited k)
                (swap! visited conj k)
                (when-let [lib (get library-catalog k)]
                  (doseq [dep (:deps lib)]
                    (visit dep))
                  (when-not (:internal lib)
                    (swap! result conj k)))))]
      (visit lib-key)
      @result)))

(defn expand-require
  "Expand a scittle:// require URL to ordered list of vendor files.
   Includes all dependencies."
  [url]
  (when-let [lib-key (resolve-scittle-url url)]
    (when-let [lib (get library-catalog lib-key)]
      (when-not (:internal lib)
        (let [all-deps (resolve-dependencies lib-key)
              ;; Add internal deps first
              internal-files (cond-> []
                               (contains? (:deps lib) :react)
                               (into (get-library-files :react)))]
          {:lib lib-key
           :files (into internal-files
                        (mapcat get-library-files all-deps))})))))
```

#### 1.2 Add Unit Tests (`test/scittle_libs_test.cljs`)

```clojure
(ns scittle-libs-test
  (:require [scittle-libs :as libs]
            [cljs.test :refer [deftest is testing]]))

(deftest resolve-scittle-url-test
  (testing "Valid scittle:// URLs"
    (is (= :pprint (libs/resolve-scittle-url "scittle://pprint.js")))
    (is (= :reagent (libs/resolve-scittle-url "scittle://reagent.js")))
    (is (= :js-interop (libs/resolve-scittle-url "scittle://js-interop.js"))))

  (testing "Invalid URLs"
    (is (nil? (libs/resolve-scittle-url "https://example.com/lib.js")))
    (is (nil? (libs/resolve-scittle-url "scittle://unknown.js")))
    (is (nil? (libs/resolve-scittle-url nil)))))

(deftest expand-require-test
  (testing "Simple library"
    (let [{:keys [lib files]} (libs/expand-require "scittle://pprint.js")]
      (is (= :pprint lib))
      (is (= ["scittle.pprint.js"] files))))

  (testing "Library with React dependency"
    (let [{:keys [lib files]} (libs/expand-require "scittle://reagent.js")]
      (is (= :reagent lib))
      (is (= ["react.production.min.js" "react-dom.production.min.js" "scittle.reagent.js"]
             files))))

  (testing "Library with transitive dependency"
    (let [{:keys [lib files]} (libs/expand-require "scittle://re-frame.js")]
      (is (= :re-frame lib))
      ;; Should include React, Reagent, then Re-frame
      (is (some #{"react.production.min.js"} files))
      (is (some #{"scittle.reagent.js"} files))
      (is (some #{"scittle.re-frame.js"} files)))))
```

### Phase 2: Manifest Parser Extension ✅ COMPLETE

Updated `manifest_parser.cljs` to handle `:epupp/require`.

#### 2.1 Update `parse-manifest`

```clojure
;; In manifest_parser.cljs
(defn normalize-require
  "Normalize :epupp/require to vector of strings"
  [require-value]
  (cond
    (nil? require-value) []
    (string? require-value) [require-value]
    (vector? require-value) (vec require-value)
    :else []))

;; Add to existing parse result:
{:epupp/require (normalize-require (:epupp/require manifest))}
```

#### 2.2 Add Validation

```clojure
(defn validate-require-urls
  "Validate require URLs, return {:valid [...] :invalid [...]}"
  [urls]
  (let [valid-schemes #{"scittle:" "https:" "epupp:"}
        categorize (fn [url]
                     (let [scheme (some-> url (str/split #"//") first)]
                       (if (contains? valid-schemes scheme)
                         :valid
                         :invalid)))]
    (group-by categorize urls)))
```

### Phase 3: Injection Flow (Medium) - PARTIALLY COMPLETE

#### Phase 3A: Auto-injection ✅ COMPLETE

Modified `background.cljs` to inject required libraries in `execute-scripts!` (navigation-triggered flow).

**Key implementation**: Uses `inject-requires-sequentially!` with `loop/recur` because `doseq` + `js-await` doesn't await properly in Squint.

#### Phase 3B: Manual evaluation injection 🔲 TODO

Panel and popup evaluation flows need to inject requires before running code.

**Popup "Run" flow** - EASIEST (already calls `execute-scripts!`):

The popup's "Run" button already goes through `execute-scripts!` which handles requires!
The bug is that the message doesn't include `:script/require`:

1. **Fix `popup.cljs` `:popup/fx.evaluate-script`** (line ~235):
   - Currently sends: `{:type "evaluate-script" :tabId :scriptId :code}`
   - Should send: `{:type "evaluate-script" :tabId :scriptId :code :require}`

2. **Fix `background.cljs` `"evaluate-script"` handler** (line ~919):
   - Currently creates: `{:script/id :script/name :script/code}`
   - Should include: `:script/require` from message

**Panel flow** - MORE COMPLEX (bypasses `execute-scripts!`):

Panel evaluation uses `chrome.devtools.inspectedWindow.eval` directly, which:
- Does NOT go through `execute-scripts!`
- Has NO access to content bridge messaging directly
- Must request background worker to inject requires

1. **Add `"inject-requires"` message handler** to `background.cljs`
2. **Update `panel.cljs` `:editor/fx.inject-and-eval`** to:
   - Check `manifest-hints` for `:require`
   - If requires exist, send `"inject-requires"` message first
   - Then proceed with Scittle injection and eval

3. **Panel state already has the data**: `(:require manifest-hints)` contains parsed requires

**Alternative Panel Approach** (simpler but less reusable):
- Send a new message type `"inject-and-eval-with-requires"` that:
  - Takes `{:tabId :code :requires}`
  - Handles the full flow in background worker
  - Returns result to panel

#### 3.1 Popup "Run" Button Fix (5 min)

**File: `src/popup.cljs` line ~235**

Change from:
```clojure
:popup/fx.evaluate-script
(let [[script] args
      tab (js-await (get-active-tab))]
  (js/chrome.runtime.sendMessage
   #js {:type "evaluate-script"
        :tabId (.-id tab)
        :scriptId (:script/id script)
        :code (:script/code script)}))
```

To:
```clojure
:popup/fx.evaluate-script
(let [[script] args
      tab (js-await (get-active-tab))]
  (js/chrome.runtime.sendMessage
   #js {:type "evaluate-script"
        :tabId (.-id tab)
        :scriptId (:script/id script)
        :code (:script/code script)
        :require (clj->js (:script/require script))}))
```

**File: `src/background.cljs` line ~919**

Change from:
```clojure
"evaluate-script"
(let [target-tab-id (.-tabId message)
      code (.-code message)]
  ((^:async fn []
     (try
       (js-await (ensure-scittle! target-tab-id))
       (js-await (execute-scripts! target-tab-id [{:script/id (.-scriptId message)
                                                   :script/name "popup-eval"
                                                   :script/code code}]))
```

To:
```clojure
"evaluate-script"
(let [target-tab-id (.-tabId message)
      code (.-code message)
      requires (when (.-require message)
                 (vec (.-require message)))]
  ((^:async fn []
     (try
       (js-await (ensure-scittle! target-tab-id))
       (js-await (execute-scripts! target-tab-id [(cond-> {:script/id (.-scriptId message)
                                                           :script/name "popup-eval"
                                                           :script/code code}
                                                    requires (assoc :script/require requires))]))
```

#### 3.2 Panel Evaluation Fix (30 min)

**File: `src/background.cljs`** - Add new message handler after `"ensure-scittle"`:

```clojure
"inject-requires"
(let [target-tab-id (.-tabId message)
      requires (when (.-requires message)
                 (vec (.-requires message)))]
  ((^:async fn []
     (try
       (when (seq requires)
         (let [files (scittle-libs/collect-require-files [{:script/require requires}])]
           (when (seq files)
             (js-await (inject-content-script target-tab-id "content-bridge.js"))
             (js-await (wait-for-bridge-ready target-tab-id))
             (js-await (inject-requires-sequentially! target-tab-id files)))))
       (send-response #js {:success true})
       (catch :default err
         (send-response #js {:success false :error (.-message err)})))))
  true)
```

**File: `src/panel.cljs` line ~91** - Update `:editor/fx.inject-and-eval`:

Change from:
```clojure
:editor/fx.inject-and-eval
(let [[code] args]
  (ensure-scittle!
   (fn [err]
     (if err
       ;; Injection failed
       (dispatch [[:editor/ax.update-scittle-status "error"]
                  [:editor/ax.handle-eval-result err]])
       ;; Injection succeeded - Scittle is ready
       (dispatch [[:editor/ax.update-scittle-status "loaded"]
                  [:editor/ax.do-eval code]])))))
```

To:
```clojure
:editor/fx.inject-and-eval
(let [[code] args
      requires (:require (:panel/manifest-hints @!state))]
  ;; If requires exist, inject them first via background worker
  (if (seq requires)
    (js/chrome.runtime.sendMessage
     #js {:type "inject-requires"
          :tabId js/chrome.devtools.inspectedWindow.tabId
          :requires (clj->js requires)}
     (fn [response]
       (if (and response (.-success response))
         ;; Requires injected - now inject Scittle and eval
         (ensure-scittle!
          (fn [err]
            (if err
              (dispatch [[:editor/ax.update-scittle-status "error"]
                         [:editor/ax.handle-eval-result err]])
              (dispatch [[:editor/ax.update-scittle-status "loaded"]
                         [:editor/ax.do-eval code]]))))
         ;; Require injection failed
         (dispatch [[:editor/ax.update-scittle-status "error"]
                    [:editor/ax.handle-eval-result {:error (or (.-error response) "Failed to inject requires")}]]))))
    ;; No requires - proceed as before
    (ensure-scittle!
     (fn [err]
       (if err
         (dispatch [[:editor/ax.update-scittle-status "error"]
                    [:editor/ax.handle-eval-result err]])
         (dispatch [[:editor/ax.update-scittle-status "loaded"]
                    [:editor/ax.do-eval code]]))))))
```

**Also update `:editor/fx.eval-in-page`** - when Scittle is already loaded but user changes requires in code:

This is a subtle case: if Scittle is already loaded (`:panel/scittle-status :loaded`), the panel calls `:editor/fx.eval-in-page` directly, skipping the injection flow. Need to check for requires there too.

Change from:
```clojure
:editor/fx.eval-in-page
(let [[code] args]
  (eval-in-page!
   code
   (fn [result]
     (dispatch [[:editor/ax.handle-eval-result result]]))))
```

To:
```clojure
:editor/fx.eval-in-page
(let [[code] args
      requires (:require (:panel/manifest-hints @!state))]
  (if (seq requires)
    ;; Inject requires before eval (even if Scittle already loaded - libs might not be)
    (js/chrome.runtime.sendMessage
     #js {:type "inject-requires"
          :tabId js/chrome.devtools.inspectedWindow.tabId
          :requires (clj->js requires)}
     (fn [_response]
       ;; Proceed with eval regardless (best effort)
       (eval-in-page!
        code
        (fn [result]
          (dispatch [[:editor/ax.handle-eval-result result]])))))
    ;; No requires - eval directly
    (eval-in-page!
     code
     (fn [result]
       (dispatch [[:editor/ax.handle-eval-result result]])))))
```

### Phase 4: Panel UI ✅ COMPLETE

Panel shows "Requires: N libraries ✓" in the property table when manifest has `:epupp/require`.

#### 4.1 Property Table Addition

```clojure
;; In panel.cljs render-manifest-info
[:tr
 [:th "Requires"]
 [:td (if (seq requires)
        [:span (str (count requires) " libraries")
         (when (every? valid? requires) " ✓")]
        [:span.dim "None"])]]
```

#### 4.2 Error Display

Show validation errors for invalid require URLs:

```clojure
(when (seq invalid-requires)
  [:div.manifest-warning
   [:strong "⚠️ Invalid requires:"]
   [:ul
    (for [url invalid-requires]
      [:li url])]])
```

### Phase 5: Documentation 🔲 TODO

Update README with usage examples and available libraries table.

#### 5.1 Update README

Add section on using Scittle libraries:

```markdown
## Using Scittle Libraries

Epupp bundles the Scittle ecosystem libraries. Add them to your script:

\`\`\`clojure
{:epupp/script-name "My UI Script"
 :epupp/site-match "https://example.com/*"
 :epupp/require ["scittle://reagent.js"]}

(ns my-script
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

(defn app []
  [:div "Hello from Reagent!"])

(rdom/render [app] (js/document.getElementById "app"))
\`\`\`
```

#### 5.2 Document Available Libraries

```markdown
### Available Libraries

| Require URL | Provides |
|-------------|----------|
| `scittle://pprint.js` | `cljs.pprint` |
| `scittle://promesa.js` | `promesa.core` |
| `scittle://replicant.js` | Replicant UI library |
| `scittle://js-interop.js` | `applied-science.js-interop` |
| `scittle://reagent.js` | Reagent + React |
| `scittle://re-frame.js` | Re-frame (includes Reagent) |
| `scittle://cljs-ajax.js` | `cljs-http.client` |
```

## Implementation Order

**Completed phases:**
1. ✅ **Phase 1**: Library mapping module - pure functions, easy to test
2. ✅ **Phase 2**: Manifest parser - extend existing infrastructure
3. ✅ **Phase 3A**: Auto-injection flow - the core feature work
4. ✅ **Phase 4**: Panel UI - user feedback

**Remaining work (Phase 3B):**

Execute in this order for cleanest incremental progress:

1. **Popup "Run" fix** (easiest, 5-10 min)
   - Edit `popup.cljs` `:popup/fx.evaluate-script` - add `:require` to message
   - Edit `background.cljs` `"evaluate-script"` handler - pass `:script/require` to script map
   - Run `bb test` to verify no regressions

2. **Add `"inject-requires"` message handler** (15 min)
   - Add handler in `background.cljs` after `"ensure-scittle"`
   - Uses existing `inject-requires-sequentially!` function
   - Run `bb test` to verify

3. **Update panel effects** (30 min)
   - Update `:editor/fx.inject-and-eval` to call `"inject-requires"` first when needed
   - Update `:editor/fx.eval-in-page` similarly
   - Manual test in browser to verify

4. **Add E2E tests** (30 min)
   - Test popup Run with requires
   - Test panel eval with requires
   - Run `bb test:e2e` to verify

5. **Phase 5**: Documentation - user guidance (1h)

## Testing Strategy

### Unit Tests
- Library resolution and dependency expansion ✅ DONE (28 tests pass)
- Manifest parsing with `:epupp/require` ✅ DONE

### E2E Tests
- Script with `scittle://pprint.js` - verify pprint available ✅ DONE
- Script with `scittle://reagent.js` - verify React + Reagent load ✅ DONE
- Script with `scittle://re-frame.js` - verify transitive deps ✅ DONE
- **TODO**: Panel eval with `:epupp/require` injects libraries
- **TODO**: Popup "Run" button with script that has requires

### New E2E Tests Needed for Phase 3B

#### Test: Panel eval with requires

```clojure
(test "Panel: evaluating code with :epupp/require injects libraries"
      (^:async fn []
        ;; 1. Open panel
        ;; 2. Type code with {:epupp/require ["scittle://pprint.js"]}
        ;; 3. Press Eval
        ;; 4. Wait for INJECTING_REQUIRES event
        ;; 5. Verify pprint is available (eval "(cljs.pprint/pprint {:a 1})")
        ))
```

#### Test: Popup Run button with requires

```clojure
(test "Popup: Run button on script with requires injects libraries"
      (^:async fn []
        ;; 1. Save script with :epupp/require via panel
        ;; 2. Open popup, find script
        ;; 3. Click Run button (play icon)
        ;; 4. Wait for INJECTING_REQUIRES event
        ;; 5. Verify script executed with library available
        ))
```

### Manual Testing
- Test on CSP-strict sites (GitHub, YouTube)
- Verify load order correctness
- Check React availability for Reagent

## Build System Updates

### tasks.clj Changes

Update `bundle-scittle` task to also copy the new libraries:

```clojure
(defn bundle-scittle
  "Download Scittle and ecosystem libraries to extension/vendor"
  []
  ;; Existing scittle.js + nrepl download...
  ;; Libraries are already in vendor/ (downloaded via Joyride)
  ;; Just verify they exist:
  (let [required-libs ["scittle.pprint.js" "scittle.promesa.js"
                       "scittle.replicant.js" "scittle.js-interop.js"
                       "scittle.reagent.js" "scittle.re-frame.js"
                       "scittle.cljs-ajax.js"
                       "react.production.min.js" "react-dom.production.min.js"]]
    (doseq [lib required-libs]
      (when-not (fs/exists? (str "extension/vendor/" lib))
        (println (str "⚠ Missing: " lib))))))
```

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Library version mismatch | Low | High | Pin to Scittle 0.7.30, test together |
| Load order issues | Medium | Medium | Topological sort, E2E tests |
| React conflicts | Low | Medium | Inject before page scripts if needed |
| CSP on React | Low | Low | React has no eval patterns |

## Effort Estimate

| Phase | Effort | Notes |
|-------|--------|-------|
| Phase 1: Library mapping | ✅ S (1-2h) | Complete |
| Phase 2: Manifest parser | ✅ S (1h) | Complete |
| Phase 3A: Auto-injection | ✅ M (3-4h) | Complete |
| Phase 3B-popup: Popup Run | S (15 min) | Add `:require` to message - 2 small changes |
| Phase 3B-panel: Panel eval | M (1h) | New message handler + effect updates |
| Phase 3B-tests: E2E tests | S (30 min) | 2 new E2E tests |
| Phase 4: Panel UI | ✅ S (1h) | Complete |
| Phase 5: Documentation | S (1h) | README updates |
| **Remaining** | **M (2.5-3h)** | Phase 3B + docs |

## Success Criteria

- [x] `scittle://pprint.js` works in userscripts (auto-injection)
- [x] `scittle://reagent.js` loads React automatically (auto-injection)
- [x] `scittle://re-frame.js` loads Reagent + React (auto-injection)
- [ ] **Panel evaluation injects requires from manifest** (Phase 3B)
- [ ] **Popup "Run" button injects requires before execution** (Phase 3B)
- [x] Panel shows require status
- [x] Works on CSP-strict sites
- [x] All unit tests pass (317)
- [x] All E2E tests pass (56)
- [ ] E2E test for panel eval with requires
- [ ] E2E test for popup Run with requires
- [ ] Gist Installer Script uses Replicant (future)

## Completed Implementation Details

### Bug Fixes Applied

1. **`doseq` + `js-await` doesn't await in Squint**: Fixed by creating `inject-requires-sequentially!` helper using `loop/recur` pattern
2. **`web_accessible_resources` missing libraries**: Added all vendor files to manifest.json
3. **Content bridge didn't wait for script load**: Added `onload` callback with `sendResponse`

### Files Modified

- `src/background.cljs` - Added `inject-requires-sequentially!`, updated `execute-scripts!`
- `src/scittle_libs.cljs` - Created library catalog and resolution functions
- `src/manifest_parser.cljs` - Added `:epupp/require` parsing
- `src/panel_actions.cljs` - Save script includes require field
- `src/content_bridge.cljs` - Script injection waits for load
- `extension/manifest.json` - Added all vendor files to `web_accessible_resources`
- `e2e/require_test.cljs` - 4 E2E tests for require feature

## Future Considerations

### External URL Support
Phase 2 of the `@require` feature will add:
- `https://` URLs with SRI verification
- Origin allowlist management
- Caching in chrome.storage

### Additional Libraries
If users request, we could add:
- DataScript (requires custom Scittle build)
- Other Scittle plugins as they become available
