# Document-Start Implementation Plan

**Status**: Ready for Implementation
**Created**: January 8, 2026
**Related**: [run-at-injection-timing.md](run-at-injection-timing.md), [architecture.md](architecture.md)

## Executive Summary

This plan describes how to implement true `@run-at document-start` support in Epupp using Chrome's `chrome.scripting.registerContentScripts` API. This enables userscripts to execute **before** page scripts run, which is essential for intercepting page initialization, blocking scripts, or modifying globals.

### Design Philosophy: Script Manifest as Source of Truth

Instead of storing metadata separately in the UI/storage, **the script code itself is the source of truth**. Metadata is embedded in the first form's metadata map (typically the `ns` form), similar to TamperMonkey's comment-based headers but using idiomatic Clojure metadata.

This approach:
- Makes scripts **self-documenting and portable**
- Aligns with the existing gist installer manifest format
- Enables sharing scripts as standalone `.cljs` files
- Uses **edamame** (npm package) for reliable Clojure parsing

### Script Manifest Format

```clojure
^{:epupp/script-name "my-cool-script.cljs"
  :epupp/site-match "https://example.com/*"
  :epupp/description "Enhance example.com UX"
  :epupp/run-at "document-start"}  ; NEW: document-start | document-end | document-idle
(ns my-cool-script)

(println "Script runs before page scripts!")
```

### Key Insight

The current approach uses `webNavigation.onCompleted` + `executeScript`, which fires after page load (equivalent to `document-idle`). To achieve `document-start`, we must use `registerContentScripts` which integrates with Chrome's internal script injection system.

### Solution Architecture

```
User saves script with manifest metadata
    │
    ▼
Parse manifest from code using edamame → Extract run-at, match, etc.
    │
    ▼
Storage updated → Background syncs registrations
    │
    ▼
chrome.scripting.registerContentScripts({
  id: "userscript-{id}",
  matches: patterns,
  runAt: "document_start",
  world: "MAIN",
  persistAcrossSessions: true
})
    │
    ▼
On page navigation: Chrome injects automatically
(no webNavigation listener needed for registered scripts)
```

---

## Implementation Phases

### Phase 0: Manifest Parser with Edamame (S - 1-2 days)

**Goal**: Create a manifest parser using edamame to extract metadata from script code.

#### 0.1 Add edamame dependency

```bash
npm install edamame
```

#### 0.2 Create manifest-parser.cljs

Create `src/manifest_parser.cljs`:

```clojure
(ns manifest-parser
  "Parse Epupp manifest metadata from script code using edamame.
   Extracts :epupp/* keys from the first form's metadata."
  (:require ["edamame" :as edamame]))

(def manifest-keys
  "Supported manifest metadata keys"
  #{:epupp/script-name
    :epupp/site-match
    :epupp/description
    :epupp/run-at})

(def valid-run-at-values
  #{"document-start" "document-end" "document-idle"})

(def default-run-at "document-idle")

(defn parse-first-form
  "Parse the first form from code text using edamame.
   Returns the parsed form with metadata, or nil on parse error."
  [code-text]
  (try
    (let [;; Configure edamame to preserve metadata
          opts #js {:all true}  ; Parse all forms, take first
          forms (edamame/parseString code-text opts)]
      (first forms))
    (catch :default e
      (js/console.warn "[ManifestParser] Parse error:" (.-message e))
      nil)))

(defn extract-manifest
  "Extract Epupp manifest from script code.
   Returns map with :script-name, :site-match, :description, :run-at
   or nil if no valid manifest found."
  [code-text]
  (when-let [form (parse-first-form code-text)]
    (when-let [m (meta form)]
      (let [script-name (get m :epupp/script-name)
            site-match (get m :epupp/site-match)
            run-at (get m :epupp/run-at default-run-at)]
        (when script-name  ; script-name is required
          {:script-name script-name
           :site-match site-match
           :description (get m :epupp/description)
           :run-at (if (valid-run-at-values run-at)
                     run-at
                     default-run-at)})))))

(defn has-manifest?
  "Check if code has a valid Epupp manifest (has :epupp/script-name)."
  [code-text]
  (boolean (:script-name (extract-manifest code-text))))

(defn get-run-at
  "Extract just the run-at value from code, with default fallback."
  [code-text]
  (or (:run-at (extract-manifest code-text)) default-run-at))
```

#### 0.3 Tests

```clojure
;; test/manifest_parser_test.cljs
(describe "Manifest Parser"
  (it "extracts manifest from ns form metadata"
    (let [code "^{:epupp/script-name \"test.cljs\"
                  :epupp/site-match \"https://example.com/*\"
                  :epupp/run-at \"document-start\"}
                (ns test)"
          manifest (mp/extract-manifest code)]
      (expect (:script-name manifest)) (toBe "test.cljs")
      (expect (:run-at manifest)) (toBe "document-start")))

  (it "returns default run-at when not specified"
    (let [code "^{:epupp/script-name \"test.cljs\"}
                (ns test)"
          manifest (mp/extract-manifest code)]
      (expect (:run-at manifest)) (toBe "document-idle")))

  (it "validates run-at values"
    (let [code "^{:epupp/script-name \"test.cljs\"
                  :epupp/run-at \"invalid\"}
                (ns test)"
          manifest (mp/extract-manifest code)]
      (expect (:run-at manifest)) (toBe "document-idle")))

  (it "returns nil for code without manifest"
    (let [code "(defn foo [] 42)"]
      (expect (mp/extract-manifest code)) (toBeNil))))
```

---

### Phase 1: Schema and Data Model (S - 1-2 days)

**Goal**: Add `:script/run-at` field to script data model, derived from manifest.

#### 1.1 Update script-utils.cljs

Add run-at parsing and serialization:

```clojure
;; Valid run-at values (as strings, matching manifest format)
(def valid-run-at-values
  #{"document-start" "document-end" "document-idle"})

(def default-run-at "document-idle")

(defn normalize-run-at
  "Normalize run-at value to valid string, with fallback to default"
  [value]
  (let [s (if (keyword? value) (name value) (str value))]
    (if (valid-run-at-values s) s default-run-at)))
```

Update `parse-scripts` to include run-at:

```clojure
;; In parse-scripts
{:script/run-at (or (.-runAt s) default-run-at)}
```

Update `script->js` to serialize run-at:

```clojure
;; In script->js
:runAt (or (:script/run-at script) default-run-at)
```

#### 1.2 Update storage.cljs

When saving a script, extract run-at from manifest if present:

```clojure
(:require [manifest-parser :as mp])

;; In save-script!, derive run-at from code manifest
(let [manifest (mp/extract-manifest (:script/code script))
      run-at (or (:run-at manifest) default-run-at)]
  (-> script
      (assoc :script/run-at run-at)))
```

This makes the **code manifest authoritative** - run-at in storage is derived from parsing the code.

#### 1.3 Tests

- Unit test: `normalize-run-at` with valid/invalid values
- Unit test: `script->js` round-trip with run-at
- Unit test: backward compatibility (scripts without run-at get default)
- Unit test: run-at extracted from code manifest on save

---

### Phase 2: UI Changes (S - 1-2 days)

**Goal**: Display run-at in popup; panel shows manifest-derived values (read-only display).

#### 2.1 DevTools Panel (panel.cljs)

Since run-at is embedded in the code manifest, the panel doesn't need a separate input field. Instead, **display the detected run-at** from the current code:

```clojure
;; In panel !state, track detected manifest
:panel/detected-manifest nil  ; {:script-name "..." :run-at "..." ...}

;; Component to show detected manifest info
(defn manifest-info [{:keys [panel/detected-manifest panel/code]}]
  (let [manifest (or detected-manifest
                     (when (seq code)
                       (mp/extract-manifest code)))]
    (when manifest
      [:div.manifest-info
       [:span.manifest-label "Manifest detected:"]
       [:span.manifest-name (:script-name manifest)]
       (when-let [run-at (:run-at manifest)]
         (when (not= run-at "document-idle")
           [:span.run-at-badge {:title (str "Runs at " run-at)}
            (case run-at
              "document-start" " ⚡ document-start"
              "document-end" " 📄 document-end"
              "")]))])))
```

**Key insight**: The user edits the manifest IN the code itself:

```clojure
;; User types this in the code textarea:
^{:epupp/script-name "my-script.cljs"
  :epupp/site-match "https://example.com/*"
  :epupp/run-at "document-start"}
(ns my-script)
```

The panel can pre-fill form fields from the detected manifest, but **saving always re-parses** to ensure consistency.

#### 2.2 Auto-populate form from manifest

When code changes, parse and suggest values:

```clojure
;; In :editor/ax.set-code action handler
(let [manifest (mp/extract-manifest code)]
  {:uf/db (cond-> (assoc state :panel/code code)
            manifest
            (assoc :panel/detected-manifest manifest
                   ;; Auto-fill fields from manifest (user can override)
                   :panel/script-name (or (:script-name manifest)
                                          (:panel/script-name state))
                   :panel/script-match (or (:site-match manifest)
                                           (:panel/script-match state))))})
```

#### 2.3 Popup Script List (popup.cljs)

Add subtle indicator for non-default run-at:

```clojure
;; In script-item component, after script name
(when (and (:script/run-at script)
           (not= "document-idle" (:script/run-at script)))
  [:span.run-at-badge {:title (str "Runs at " (:script/run-at script))}
   (case (:script/run-at script)
     "document-start" "⚡"
     "document-end" "📄"
     "")])
```

Add CSS for `.run-at-badge`:

```css
.run-at-badge {
  font-size: 0.8em;
  margin-left: 4px;
  opacity: 0.8;
}
```

#### 2.4 Tests

- E2E: Create script with document-start in manifest via panel
- E2E: Verify run-at detected and displayed from code
- E2E: Verify run-at badge displays in popup
- Unit test: manifest extraction triggers form auto-fill

---

### Phase 3: Content Script Registration System (M - 3-5 days)

**Goal**: Implement dynamic content script registration for document-start scripts.

#### 3.1 New Module: registration.cljs

Create `src/registration.cljs` for content script registration management:

```clojure
(ns registration
  "Dynamic content script registration for document-start/document-end timing.
   Uses chrome.scripting.registerContentScripts for early injection."
  (:require [script-utils :as script-utils]))

;; Registration ID format: "epupp-{script-id}"
(defn registration-id [script-id]
  (str "epupp-" script-id))

(defn ^:async get-registered-scripts
  "Get all currently registered content scripts."
  []
  (js-await (js/chrome.scripting.getRegisteredContentScripts)))

(defn ^:async register-script!
  "Register a script for early injection.
   Only used for document-start and document-end scripts."
  [{:script/keys [id match run-at approved-patterns]}]
  (when (and (seq approved-patterns)
             (not= run-at :document-idle))
    (let [reg-id (registration-id id)
          ;; Only register approved patterns
          patterns (filterv #(some #{%} approved-patterns) match)]
      (when (seq patterns)
        (js-await
         (js/chrome.scripting.registerContentScripts
          (clj->js
           [{:id reg-id
             :matches patterns
             :js ["userscript-loader.js"]  ; Generic loader
             :runAt (case run-at
                      :document-start "document_start"
                      :document-end "document_end"
                      "document_idle")
             :world "MAIN"
             :persistAcrossSessions true}])))))))

(defn ^:async unregister-script!
  "Unregister a script."
  [script-id]
  (let [reg-id (registration-id script-id)]
    (try
      (js-await
       (js/chrome.scripting.unregisterContentScripts
        #js {:ids #js [reg-id]}))
      (catch :default _e
        ;; Ignore errors if script wasn't registered
        nil))))

(defn ^:async sync-registrations!
  "Sync registered scripts with storage state.
   Call after storage changes affecting enabled scripts."
  [scripts]
  (let [registered (js-await (get-registered-scripts))
        registered-ids (set (map #(.-id %) registered))

        ;; Scripts that should be registered (early timing + approved)
        should-register (->> scripts
                             (filter :script/enabled)
                             (filter #(not= :document-idle (:script/run-at %)))
                             (filter #(seq (:script/approved-patterns %))))
        should-ids (set (map #(registration-id (:script/id %)) should-register))

        ;; Unregister orphaned
        to-unregister (remove should-ids registered-ids)
        ;; Register missing
        to-register (remove #(registered-ids (registration-id (:script/id %))) should-register)]

    ;; Unregister
    (when (seq to-unregister)
      (js-await
       (js/chrome.scripting.unregisterContentScripts
        #js {:ids (clj->js (vec to-unregister))})))

    ;; Register new
    (doseq [script to-register]
      (js-await (register-script! script)))))
```

#### 3.2 Userscript Loader (userscript-loader.js)

Create `extension/userscript-loader.js` - a minimal JS file that gets registered:

```javascript
// userscript-loader.js
// Injected by registerContentScripts at document_start/document_end
// Signals to background worker that early injection point was reached

(function() {
  // Get script ID from registration (Chrome injects with metadata)
  const scriptMeta = document.currentScript?.dataset || {};

  // Post message to trigger userscript loading from background
  window.postMessage({
    source: 'epupp-loader',
    type: 'early-inject-ready',
    url: window.location.href,
    timing: document.readyState
  }, '*');
})();
```

**Alternative approach**: Store script code directly in storage and have the loader fetch it. This avoids needing to generate JS files.

#### 3.3 Background Worker Integration

Update `background.cljs` to:

1. Call `sync-registrations!` after storage init
2. Call `sync-registrations!` on storage changes
3. Handle `early-inject-ready` messages from loader (if needed)

```clojure
;; In ensure-initialized!
(js-await (registration/sync-registrations! (storage/get-enabled-scripts)))

;; Add storage change listener
(js/chrome.storage.onChanged.addListener
 (fn [changes area]
   (when (and (= area "local") (.-scripts changes))
     (registration/sync-registrations! (storage/get-enabled-scripts)))))
```

#### 3.4 Handle Pattern Approval Changes

When a pattern is approved for a document-start script, register it:

```clojure
;; In pattern-approved handler
(when-let [script (storage/get-script script-id)]
  (when (not= :document-idle (:script/run-at script))
    (registration/register-script! script)))
```

When a script is disabled, unregister it:

```clojure
;; After toggling script off
(registration/unregister-script! script-id)
```

#### 3.5 Scittle Pre-loading Challenge

**Problem**: Document-start scripts need Scittle available immediately, but Scittle is ~500KB.

**Options**:

1. **Register Scittle as content script** for URLs with document-start scripts
   - Pros: True document-start, Scittle loads before page scripts
   - Cons: Scittle loads on ALL matching pages even without REPL

2. **Transpile to JS at save time** (requires build tooling)
   - Pros: No runtime Scittle dependency
   - Cons: Complex, loses live REPL benefits

3. **Accept that document-start means "before DOMContentLoaded"**
   - Inject Scittle at document_start, then userscript
   - May not be fast enough for some use cases

**Recommended**: Option 1 for MVP. Register Scittle bundle as content script alongside userscript loader for document-start scripts only.

```clojure
;; In register-script!, include Scittle
:js ["vendor/scittle.js" "userscript-loader.js"]
```

#### 3.6 Tests

- Unit test: `registration-id` format
- Unit test: `sync-registrations!` with various script states
- E2E: Document-start script executes before page script
- E2E: Disabling script removes registration
- E2E: Pattern approval triggers registration

---

### Phase 4: Dual Injection Path (M - 2-3 days)

**Goal**: Maintain existing document-idle flow while adding early injection.

#### 4.1 Update webNavigation.onCompleted Handler

Only process document-idle scripts via the existing flow:

```clojure
;; In process-navigation!
(let [scripts (url-matching/get-matching-scripts url)
      ;; Filter to only document-idle scripts
      idle-scripts (filter #(= :document-idle (:script/run-at %)) scripts)]
  ;; Existing logic only for idle-scripts
  )
```

Document-start/end scripts are handled by Chrome's content script system.

#### 4.2 Early Script Execution Flow

When the registered loader runs at document-start:

1. Loader posts message indicating URL
2. Content bridge (also registered early) forwards to background
3. Background looks up script code from storage
4. Background sends code to page via content bridge
5. Scittle evaluates the code

**Simpler alternative**: Store script code as data attribute in registered script tag, have loader read and eval it. Avoids round-trip to background.

#### 4.3 Tests

- E2E: Document-idle scripts still work via webNavigation flow
- E2E: Document-start scripts inject before page scripts
- E2E: Mixed timing scripts on same URL both execute

---

### Phase 5: Testing Infrastructure (S - 1-2 days)

#### 5.1 New E2E Test File: timing_test.cljs

```clojure
(ns timing-test
  (:require ["playwright" :as pw]
            [fixtures :as fixtures]))

(describe "Script Injection Timing"
  (it "document-start script runs before page script"
    ;; 1. Create test page that sets window.pageScriptRan = true
    ;; 2. Create userscript that checks window.pageScriptRan
    ;; 3. Verify userscript sees pageScriptRan = undefined
    )

  (it "document-end script runs after DOM but before load"
    ;; Similar approach with DOMContentLoaded marker
    )

  (it "document-idle script runs after load"
    ;; Verify existing behavior
    ))
```

#### 5.2 Test HTML Page

Create `e2e/test-timing-page.html`:

```html
<!DOCTYPE html>
<html>
<head>
  <script>
    window.pageScriptRan = true;
    window.pageScriptTime = Date.now();
  </script>
</head>
<body>
  <div id="content">Test page for timing</div>
</body>
</html>
```

#### 5.3 Unit Tests for Registration

```clojure
;; test/registration_test.cljs
(describe "Registration"
  (it "generates correct registration ID"
    (expect (registration/registration-id "my-script"))
            (toBe "epupp-my-script"))

  (it "filters to approved patterns only"
    ;; Test that unapproved patterns don't get registered
    ))
```

---

### Phase 6: Documentation Updates (S - 1 day)

#### 6.1 Update architecture.md

Add new section "Content Script Registration":

```markdown
### Content Script Registration (document-start/document-end)

For scripts with `run-at: document-start` or `document-end`, Epupp uses
Chrome's `registerContentScripts` API for true early injection...
```

#### 6.2 Update userscripts-architecture.md

Add section explaining the dual injection system.

#### 6.3 Update run-at-injection-timing.md

Mark Phase 1-3 as implemented, add implementation notes.

#### 6.4 Update README.md

Add note about `@run-at` support in userscripts section.

#### 6.5 Update Gist Installer Documentation

Document the `:epupp/run-at` metadata option.

---

### Phase 7: Userscript Installer Updates (S - 1 day)

#### 7.1 Update gist_installer.cljs to use manifest-parser

The gist installer already parses manifests, but now uses the shared parser:

```clojure
(ns gist-installer
  (:require [manifest-parser :as mp]
            [clojure.string :as str]))

;; Use shared parser - remove duplicate functions
(def has-manifest? mp/has-manifest?)
(def extract-manifest mp/extract-manifest)
```

#### 7.2 Update background.cljs install-userscript!

Handle run-at from parsed manifest:

```clojure
(:require [manifest-parser :as mp])

;; In install-userscript!
(let [code (js-await (fetch-text! script-url))
      ;; Re-parse manifest from actual code (more reliable than passed manifest)
      parsed-manifest (mp/extract-manifest code)
      run-at (or (:run-at parsed-manifest)
                 (:run-at manifest)  ; Fallback to passed manifest
                 "document-idle")]
  (let [script {:script/id normalized-name
                :script/name normalized-name
                :script/match [site-match]
                :script/code code
                :script/run-at run-at  ; NEW
                :script/enabled true
                :script/approved-patterns []}]
    (storage/save-script! script)))
```

#### 7.3 Tests

- E2E: Install gist with `:epupp/run-at "document-start"` in manifest
- E2E: Verify installed script has correct run-at value
- Unit test: manifest-parser extracts run-at correctly

---

## Implementation Order

Recommended order to minimize risk and enable incremental testing:

1. **Phase 0: Manifest Parser** - Foundation for all other phases
2. **Phase 1: Schema** - Data model changes, uses parser
3. **Phase 2: UI** - User-visible changes, displays manifest info
4. **Phase 3: Registration** - Core feature, complex
5. **Phase 4: Dual Path** - Integration, requires Phase 3
6. **Phase 5: Tests** - In parallel with Phase 3-4
7. **Phase 6: Docs** - After implementation complete
8. **Phase 7: Installer** - Migrate to shared parser

**Minimum Viable Feature**: Phases 0-4 deliver working document-start support.

**Key Dependency**: Phase 0 (manifest parser) must be completed first as all other phases depend on it.

---

## Risk Assessment

### Technical Risks

| Risk | Mitigation |
|------|------------|
| Scittle load time at document-start | Pre-register Scittle as content script |
| Chrome API version requirements | Document Chrome 96+ requirement |
| Service worker lifecycle | Use `persistAcrossSessions: true` |
| Pattern validation by Chrome | Validate patterns before registration |

### User Experience Risks

| Risk | Mitigation |
|------|------------|
| Scittle on all matching pages | Clear documentation, user opt-in via run-at selection |
| Breaking existing scripts | Default to document-idle, no change for existing |
| Complexity increase | Keep UI simple, advanced users benefit |

---

## Success Criteria

- [ ] Manifest parser (edamame) extracts `:epupp/run-at` from script code
- [ ] Scripts with `run-at: document-start` in manifest execute before page scripts
- [ ] Scripts with `run-at: document-end` in manifest execute at DOMContentLoaded
- [ ] Scripts without `run-at` (or `document-idle`) maintain current behavior
- [ ] Panel detects and displays manifest metadata from code
- [ ] Popup displays run-at badge for non-idle scripts
- [ ] Gist installer uses shared manifest parser
- [ ] All existing tests pass
- [ ] New timing tests pass
- [ ] Documentation updated

---

## Open Questions Resolved

1. **Should Scittle always be injected for matching URLs?**
   - **Decision**: Yes, but only for document-start scripts. This is the tradeoff for true early injection.

2. **How to handle approval for document-start scripts?**
   - **Decision**: Approval gates registration. Unapproved patterns are not registered, even for document-start scripts.

3. **What about REPL connection timing?**
   - **Decision**: REPL connects separately after page load. Document-start scripts run without REPL; REPL connects later for iteration.

4. **Performance impact?**
   - **Decision**: Accept Scittle overhead for document-start scripts. Users opt-in by selecting that timing.

---

## References

- [Chrome scripting.registerContentScripts](https://developer.chrome.com/docs/extensions/reference/api/scripting#method-registerContentScripts)
- [Chrome RunAt enum](https://developer.chrome.com/docs/extensions/reference/api/extensionTypes#type-RunAt)
- [TamperMonkey @run-at documentation](https://www.tampermonkey.net/documentation.php#meta:run_at)
- [Current Epupp Architecture](architecture.md)
- [edamame npm package](https://www.npmjs.com/package/edamame) - Clojure parser for JavaScript

---

## Appendix: Why Edamame?

### Current Approach (gist_installer.cljs)

The gist installer currently uses Scittle's `clojure.edn/read-string` to parse the first form:

```clojure
(defn- get-first-form-meta [code-text]
  (try
    (let [form (edn/read-string code-text)]
      (meta form))
    (catch js/Error e nil)))
```

**Limitations:**
- Only works in Scittle context (page scripts)
- Cannot be used in extension context (popup, panel, background)
- EDN parser may not handle all Clojure syntax

### Edamame Solution

[Edamame](https://github.com/borkdude/edamame) is a Clojure/EDN parser by Michiel Borkent (author of Babashka, Scittle, SCI). Available on npm.

**Advantages:**
- Works in any JavaScript context (extension pages, background worker, page scripts)
- Full Clojure syntax support (not just EDN)
- Preserves metadata (critical for our manifest approach)
- Lightweight (~50KB)
- Well-maintained, same author as Scittle

**Usage:**

```javascript
import { parseString } from 'edamame';

const code = `^{:epupp/run-at "document-start"} (ns foo)`;
const forms = parseString(code, { all: true });
const firstForm = forms[0];
const meta = firstForm.meta;  // { 'epupp/run-at': 'document-start' }
```

**Note on Squint interop**: Edamame returns plain JavaScript objects. Keywords become strings (e.g., `:epupp/run-at` → `"epupp/run-at"`). This aligns with Squint's keyword handling.

### Alternative Considered: Keep EDN in Page Context

We could keep the current EDN approach for the gist installer (runs in Scittle) and add edamame only for extension contexts. However, having a **single manifest parser** used everywhere is cleaner and ensures consistent behavior.
