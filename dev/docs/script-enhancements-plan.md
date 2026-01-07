# Script Enhancements Implementation Plan

**Created:** January 7, 2026
**Status:** Planning

This document outlines the implementation plan for script management enhancements in Epupp.

## Feature Summary

1. **Script Descriptions** - Add optional description field to scripts
2. **Script ID/Filename Normalization** - Enforce valid ClojureScript filenames for script IDs
3. **Built-in Script Protection** - Prevent deletion/clearing of built-in scripts
4. **Collapsible Popup Sections** - Reorganize popup UI with collapsible sections

## Current State Analysis

### Script Data Model ([script_utils.cljs](../../src/script_utils.cljs))

```clojure
{:script/id "my-script"
 :script/name "My Script"
 :script/match ["*://example.com/*"]
 :script/code "(println \"hello\")"
 :script/enabled true
 :script/created "2026-01-01T..."
 :script/modified "2026-01-01T..."
 :script/approved-patterns ["*://example.com/*"]}
```

### Built-in Script Identification

Currently identified by ID prefix convention: `"scittle-tamper-builtin-*"` (see [storage.cljs](../../src/storage.cljs#L227)).

### Popup UI Structure ([popup.cljs](../../src/popup.cljs))

Current sections (not collapsible):
1. Header with logos and settings button
2. Step 1: Start browser-nrepl server
3. Step 2: Connect browser to server
4. Step 3: Connect editor to browser
5. Userscripts section

---

## Implementation Tasks

### Task 1: Script Descriptions

**Goal:** Add optional `:script/description` field to script data model.

#### 1.1 Update Data Model

**File:** [script_utils.cljs](../../src/script_utils.cljs)

- Add `:script/description` to `parse-scripts` function
- Add `description` field to `script->js` function

```clojure
;; parse-scripts addition
:script/description (.-description s)

;; script->js addition
:description (:script/description script)
```

#### 1.2 Update Panel Save

**File:** [panel_actions.cljs](../../src/panel_actions.cljs)

- Add `:panel/script-description` to state
- Include description in `:editor/ax.save-script` action

**File:** [panel.cljs](../../src/panel.cljs)

- Add description textarea to save-script-section
- Add `:editor/ax.set-script-description` action

#### 1.3 Update Popup Display

**File:** [popup.cljs](../../src/popup.cljs)

- Display description (truncated) in `script-item` component below name

#### 1.4 Tests

**File:** [test/script_utils_test.cljs](../../test/script_utils_test.cljs)

- Test `parse-scripts` handles description field
- Test `script->js` includes description

**File:** [test/panel_test.cljs](../../test/panel_test.cljs)

- Test `:editor/ax.set-script-description` action
- Test `:editor/ax.save-script` includes description

---

### Task 2: Script ID/Filename Normalization

**Goal:** Script IDs should be valid ClojureScript filenames, supporting namespace-like organization with `/`.

#### 2.1 Create Normalization Function

**File:** [script_utils.cljs](../../src/script_utils.cljs)

```clojure
(defn normalize-script-id
  "Convert script name to valid ClojureScript filename.
   - Lowercase
   - Replace spaces and dashes with underscores
   - Preserve `/` for namespace-like paths (e.g., my_project/utils.cljs)
   - Append .cljs if not present
   - Remove invalid characters"
  [name]
  (-> name
      (.toLowerCase)
      (.replace (js/RegExp. "[\\s-]+" "g") "_")
      (.replace (js/RegExp. "[^a-z0-9_./]" "g") "")
      (as-> s (if (.endsWith s ".cljs") s (str s ".cljs")))))
```

#### 2.2 Apply on Save

**File:** [panel_actions.cljs](../../src/panel_actions.cljs)

- Use `normalize-script-id` when generating script ID in `:editor/ax.save-script`
- When editing existing script, preserve original ID (don't re-normalize)

#### 2.3 Tests

**File:** [test/script_utils_test.cljs](../../test/script_utils_test.cljs)

```clojure
(describe "normalize-script-id"
  (test "lowercases name" ...)
  (test "replaces spaces with underscores" ...)
  (test "replaces dashes with underscores" ...)
  (test "preserves slashes for namespace paths" ...)
  (test "appends .cljs if missing" ...)
  (test "preserves .cljs if present" ...)
  (test "removes invalid characters" ...)
  (test "handles empty string" ...))
```

---

### Task 3: Built-in Script Protection

**Goal:** Prevent deletion of built-in scripts, exclude from "clear all" operations. Built-in scripts can still be edited (fork behavior), enabled, and disabled.

#### 3.1 Add Built-in Detection

**File:** [script_utils.cljs](../../src/script_utils.cljs)

```clojure
(def builtin-id-prefix "scittle-tamper-builtin-")

(defn builtin-script?
  "Check if a script is a built-in script by ID prefix."
  [script]
  (and (:script/id script)
       (.startsWith (:script/id script) builtin-id-prefix)))
```

#### 3.2 Update Storage Operations

**File:** [storage.cljs](../../src/storage.cljs)

- `delete-script!` - Add guard to prevent deleting built-in scripts
- Add `clear-user-scripts!` function that preserves built-in scripts

```clojure
(defn delete-script!
  "Remove a script by id. Built-in scripts cannot be deleted."
  [script-id]
  (when-not (.startsWith script-id builtin-id-prefix)
    (swap! !db update :storage/scripts
           (fn [scripts]
             (filterv #(not= (:script/id %) script-id) scripts)))
    (persist!)))

(defn clear-user-scripts!
  "Remove all user scripts, preserving built-in scripts."
  []
  (swap! !db update :storage/scripts
         (fn [scripts]
           (filterv builtin-script? scripts)))
  (persist!))
```

#### 3.3 Update Popup UI

**File:** [popup.cljs](../../src/popup.cljs)

- Hide delete button for built-in scripts in `script-item`
- Keep edit, enable/disable controls visible for built-in scripts
- Add visual indicator (e.g., "(Built-in)" badge)

#### 3.4 Tests

**File:** [test/script_utils_test.cljs](../../test/script_utils_test.cljs)

```clojure
(describe "builtin-script?"
  (test "returns true for builtin prefix" ...)
  (test "returns false for user scripts" ...)
  (test "returns false for nil id" ...))
```

**File:** [test/popup_utils_test.cljs](../../test/popup_utils_test.cljs)

- Test `remove-script-from-list` behavior (may need update or new function)

---

### Task 4: Collapsible Popup Sections

**Goal:** Reorganize popup into collapsible sections with sensible defaults.

#### 4.1 Update State Model

**File:** [popup.cljs](../../src/popup.cljs)

Add collapsed state tracking:

```clojure
{:ui/sections-collapsed {:repl-connect false    ; expanded by default
                         :scripts false          ; expanded by default
                         :builtin-scripts false  ; expanded by default
                         :settings true}}        ; collapsed by default
```

#### 4.2 Create Collapsible Section Component

**File:** [popup.cljs](../../src/popup.cljs)

```clojure
(defn collapsible-section [{:keys [id title expanded? on-toggle]} & children]
  [:div.collapsible-section {:class (when-not expanded? "collapsed")}
   [:div.section-header {:on-click on-toggle}
    [:span.section-title title]
    [icons/chevron {:direction (if expanded? :down :right)}]]
   (when expanded?
     [:div.section-content children])])
```

#### 4.3 Reorganize Main View and Remove Separate Settings View

**File:** [popup.cljs](../../src/popup.cljs)

Sections in order:
1. **REPL Connect** (expanded) - Steps 1-3 combined
2. **Scripts** (expanded) - User scripts only
3. **Built-in Scripts** (expanded) - Built-in scripts only
4. **Settings** (collapsed) - Moved inline from separate view

**Remove:**
- `:ui/view` state (no longer needed - single view with collapsible sections)
- `settings-view` component
- `:popup/ax.show-settings` and `:popup/ax.show-main` actions
- Settings cog button in header (settings now inline)

#### 4.4 Add Toggle Action

**File:** [popup_actions.cljs](../../src/popup_actions.cljs)

```clojure
:popup/ax.toggle-section
(let [[section-id] args]
  {:uf/db (update-in state [:ui/sections-collapsed section-id] not)})
```

#### 4.5 Update CSS

**File:** [extension/popup.css](../../extension/popup.css)

```css
.collapsible-section { ... }
.section-header { cursor: pointer; display: flex; align-items: center; }
.section-header:hover { background: var(--btn-hover-bg); }
.section-title { flex: 1; }
.section-content { ... }
.collapsed .section-content { display: none; }
```

#### 4.6 Add Chevron Icon

**File:** [icons.cljs](../../src/icons.cljs)

Add chevron-right and chevron-down icons for expand/collapse indicator.

#### 4.7 Tests

**File:** [test/popup_actions_test.cljs](../../test/popup_actions_test.cljs)

```clojure
(describe "popup section toggle actions"
  (test ":popup/ax.toggle-section toggles collapsed state" ...)
  (test ":popup/ax.toggle-section works for each section" ...))
```

**File:** [e2e/popup_test.cljs](../../e2e/popup_test.cljs)

- Update existing tests to account for collapsible sections
- Add test for section expand/collapse behavior

---

## Implementation Order

Recommended order to minimize conflicts and enable incremental testing:

1. **Task 1: Script Descriptions** (low risk, additive change)
2. **Task 3: Built-in Script Protection** (isolated, enables Task 4)
3. **Task 2: Script ID Normalization** (isolated pure function)
4. **Task 4: Collapsible Sections** (most UI changes, depends on Task 3)

---

## Definition of Done Checklist

### Task 1: Script Descriptions
- [ ] `parse-scripts` handles `:script/description` field
- [ ] `script->js` includes `description` field
- [ ] Panel has description textarea in save section
- [ ] Panel state includes `:panel/script-description`
- [ ] `:editor/ax.set-script-description` action works
- [ ] `:editor/ax.save-script` includes description in saved script
- [ ] `:editor/ax.load-script-for-editing` loads description
- [ ] Popup displays truncated description in script items
- [ ] Unit tests pass for new functionality
- [ ] E2E tests updated if needed

### Task 2: Script ID Normalization
- [ ] `normalize-script-id` function implemented
- [ ] Function handles: lowercase, spaces, dashes, `/` paths, .cljs extension
- [ ] Panel uses normalized ID on new script save
- [ ] Panel preserves original ID when editing existing script
- [ ] Unit tests cover all normalization cases (including `/` preservation)
- [ ] E2E save workflow still works

### Task 3: Built-in Script Protection
- [ ] `builtin-script?` predicate function implemented
- [ ] `delete-script!` guards against deleting built-in scripts
- [ ] Popup hides delete button for built-in scripts
- [ ] Popup keeps edit and enable/disable controls for built-in scripts
- [ ] Built-in scripts show visual indicator ("Built-in" badge)
- [ ] Unit tests for `builtin-script?` predicate
- [ ] E2E tests verify built-in script cannot be deleted
- [ ] E2E tests verify built-in script can be edited and toggled

### Task 4: Collapsible Popup Sections
- [ ] State tracks collapsed status per section
- [ ] `:popup/ax.toggle-section` action implemented
- [ ] Collapsible section component created
- [ ] Chevron icons added to icons.cljs
- [ ] CSS for collapsible sections added
- [ ] REPL Connect section (steps 1-3) created
- [ ] Scripts section shows only user scripts
- [ ] Built-in Scripts section shows only built-in scripts
- [ ] Settings section moved inline (collapsible)
- [ ] Separate settings view removed (`:ui/view`, `settings-view`, related actions)
- [ ] Settings cog button removed from header
- [ ] Default collapse states: REPL/Scripts/Built-in expanded, Settings collapsed
- [ ] Unit tests for toggle action
- [ ] Unit tests for removed settings view actions cleaned up
- [ ] E2E tests updated for new structure
- [ ] E2E test for expand/collapse interaction

### Overall
- [ ] All unit tests pass (115+ tests)
- [ ] All E2E tests pass
- [ ] No new linting warnings
- [ ] Manual testing on Chrome
- [ ] Manual testing on Firefox (if applicable)

---

## Files to Modify

| File | Tasks |
|------|-------|
| `src/script_utils.cljs` | 1, 2, 3 |
| `src/storage.cljs` | 3 |
| `src/popup.cljs` | 1, 3, 4 |
| `src/popup_actions.cljs` | 4 |
| `src/panel.cljs` | 1 |
| `src/panel_actions.cljs` | 1, 2 |
| `src/icons.cljs` | 4 |
| `extension/popup.css` | 1, 3, 4 |
| `test/script_utils_test.cljs` | 1, 2, 3 |
| `test/popup_actions_test.cljs` | 4 |
| `test/panel_test.cljs` | 1 |
| `e2e/popup_test.cljs` | 3, 4 |

---

## Resolved Questions

1. **Description length limit?** No enforcement needed - let users manage their own descriptions.
2. **Namespace in filename?** Yes - support `/` for namespace-like organization (e.g., `my_project/utils.cljs`).
3. **Settings view deprecation?** Yes - remove the separate settings view; settings become an inline collapsible section.
4. **Built-in script editing?** Yes - built-in scripts can be edited (fork behavior), enabled, and disabled. Only deletion is prevented.

---

## Related Documents

- [architecture.md](architecture.md) - State schemas and message protocols
- [userscripts-architecture.md](userscripts-architecture.md) - Design decisions
- [testing.md](testing.md) - Test strategy
