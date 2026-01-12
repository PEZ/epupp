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

**Panel flow** (`panel_actions.cljs`):
- `:editor/ax.eval` → check manifest hints for requires → inject before eval

**Popup "Run" flow** (`popup_actions.cljs` or `background.cljs`):
- Run script action → inject requires → execute script

#### 3.1 Library Injection Function

```clojure
(defn inject-scittle-library!
  "Inject a vendor library file into the page"
  [tab-id filename]
  (let [url (js/chrome.runtime.getURL (str "vendor/" filename))]
    (js/chrome.tabs.sendMessage
     tab-id
     #js {:type "inject-script" :url url})))

(defn inject-requires!
  "Inject all required libraries for a script"
  [tab-id script]
  (let [requires (get script :script/require [])
        scittle-requires (filter #(str/starts-with? % "scittle://") requires)]
    (doseq [url scittle-requires]
      (when-let [{:keys [files]} (scittle-libs/expand-require url)]
        (doseq [file files]
          (inject-scittle-library! tab-id file))))))
```

#### 3.2 Update `execute-scripts!`

Modify the existing injection flow:

```clojure
;; In execute-scripts!, after injecting Scittle core:
;; 1. Inject content bridge
;; 2. Wait for bridge ready
;; 3. Inject Scittle core (existing)
;; 4. NEW: Inject required libraries
;; 5. Inject userscript
;; 6. Trigger evaluation
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

1. **Phase 1**: Library mapping module - pure functions, easy to test
2. **Phase 2**: Manifest parser - extend existing infrastructure
3. **Phase 3**: Injection flow - the core feature work
4. **Phase 4**: Panel UI - user feedback
5. **Phase 5**: Documentation - user guidance

## Testing Strategy

### Unit Tests
- Library resolution and dependency expansion
- Manifest parsing with `:epupp/require`
- URL validation

### E2E Tests
- Script with `scittle://pprint.js` - verify pprint available
- Script with `scittle://reagent.js` - verify React + Reagent load
- Script with `scittle://re-frame.js` - verify transitive deps

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
| Phase 1: Library mapping | S (1-2h) | Pure functions, straightforward |
| Phase 2: Manifest parser | S (1h) | Extend existing code |
| Phase 3: Injection flow | M (3-4h) | Core feature, needs careful testing |
| Phase 4: Panel UI | S (1h) | Simple UI addition |
| Phase 5: Documentation | S (1h) | README updates |
| **Total** | **M (6-9h)** | Can be done incrementally |

## Success Criteria

- [x] `scittle://pprint.js` works in userscripts
- [x] `scittle://reagent.js` loads React automatically
- [x] `scittle://re-frame.js` loads Reagent + React
- [x] Panel shows require status
- [x] Works on CSP-strict sites
- [x] All unit tests pass
- [x] E2E test for require feature
- [ ] **NEW**: Panel evaluation injects requires from manifest
- [ ] **NEW**: Popup "Run" button injects requires before execution

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
