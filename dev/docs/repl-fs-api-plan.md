# Epupp REPL File System API - Implementation Plan

**Created:** January 15, 2026
**Status:** In Progress
**Related:** [repl-file-sync-plan.md](repl-file-sync-plan.md) (almost completely implemented)

## Overview

A file system API for managing userscripts from the REPL, with confirmation workflows, UI reactivity, and ergonomic async patterns.

## Implementation Progress

| Task | Status | Notes |
|------|--------|-------|
| Rename files/refs from "primitives" to "fs" | ✅ Done | Test files, docs updated |
| Namespace rename (`epupp.fs/*`, `epupp.repl/*`) | ✅ Done | `epupp-namespace-code` updated |
| Namespaced keywords in returns | ✅ Done | `:fs/name`, `:fs/enabled`, etc. |
| `modified` timestamp in manifest | ✅ Done | Added to `ls` output |
| UI reactivity for fs operations | ✅ Done | Popup listens to storage changes |
| Confirmation UI placement | ⚠️ Partial | Top-level confirmation section exists; inline/ghost items pending |
| Confirmation for `rm!` | ⚠️ Partial | Queue+confirm exists, but UI placement is still top-level; inline/ghost pending |
| Confirmation for `mv!` | ⚠️ Partial | Queue+confirm exists, but UI placement is still top-level; inline/ghost pending |
| Confirmation for `save!` (update) | 🔲 Not started | Requires background queue handler |
| Confirmation for `save!` (create) | 🔲 Not started | Requires background queue handler |
| Options for `save!` | ⚠️ Partial | `:fs/enabled` implemented, docs/tests still mention `:enabled` |
| Bulk operations | ⚠️ Partial | `cat` and `rm!` bulk implemented; `save!` bulk blocked by missing queue handler; `mv!` bulk not implemented |
| Promesa in example tamper | ✅ Done | Updated repl_fs.cljs with epupp.fs/* |
| Document "not-approved" behavior | ✅ Done | Added to user-guide.md REPL FS API section |

## Design Decisions

### Namespace Structure

```
epupp.fs/     - File system operations
  ls          - List scripts
  cat         - Get script code
  save!       - Create/update script
  mv!         - Rename script
  rm!         - Delete script

epupp.repl/   - REPL session utilities
  manifest!   - Inject libraries (existing, to be moved)
```

### Output Formatting

Use `cljs.pprint/print-table` with `with-out-str` for formatted script listings:

```clojure
(epupp.repl/manifest! {:epupp/require ["scittle://pprint.js"]})
(require '[cljs.pprint :refer [print-table]])

;; Full table (wrap with-out-str to capture in REPL)
(with-out-str (print-table @!scripts))

;; Select columns
(with-out-str (print-table [:fs/name :fs/enabled] @!scripts))
```

This composable approach is preferred over a custom `ls-print!` function.

### Return Value Keywords

All maps use `:fs/` namespaced keywords (tests/docs still contain `:success` in several places):

```clojure
;; ls returns
[{:fs/name "script.cljs"
  :fs/enabled true
  :fs/match ["https://example.com/*"]
  :fs/modified 1705276800000}]

;; save!/mv!/rm! returns
{:fs/success true
 :fs/name "script.cljs"
 :fs/pending-confirmation true  ; when confirmation needed
 :fs/error nil}
```

### Confirmation Pattern

Operations requiring confirmation return immediately with `:fs/pending-confirmation true`. The UI shows inline confirmation cards in the script list, with ghost items representing new files.

- Badge indicates pending confirmations when popup/panel closed (planned UX item, not implemented yet)
- User confirms/denies in UI
- Promise resolves immediately when queued; later confirm/cancel only affects state, no second resolution

```clojure
;; Confirmation flow
(p/let [result (epupp.fs/rm! "script.cljs")]
  (if (:fs/pending-confirmation result)
    (println "Awaiting confirmation in Epupp UI...")
    (println "Deleted:" (:fs/name result))))

;; Skip confirmation
(epupp.fs/rm! "script.cljs" {:fs/force? true})
```

### Bulk Operations

| Function | Single | Bulk input | Bulk output |
|----------|--------|-----------|-------------|
| `cat` | `(cat "name")` → string | `(cat ["a" "b"])` → `{"a" "code" "b" nil}` |
| `save!` | `(save! code)` | `(save! [code1 code2])` → `{0 result 1 result}` |
| `rm!` | `(rm! "name")` | `(rm! ["a" "b"])` → `{"a" result "b" result}` |
| `mv!` | `(mv! "old" "new")` | `(mv! {"old1" "new1" "old2" "new2"})` → per-item results (map form not implemented yet - planned) |

### Default Behaviors

- `save!` creates scripts with `:fs/enabled true` (requires approval anyway)
- Destructive ops require confirmation by default
- Pre-approval deferred (security consideration)

## Implementation Order

Order optimized for enabling further work:

### Phase 1: Foundation

1. **Namespace rename** - `epupp.fs/*` and `epupp.repl/*`
   - Update `epupp-namespace-code` in background.cljs
   - Maintain backward compatibility aliases temporarily?

2. **Namespaced keywords** - `:fs/*` in all returns
   - Update handlers in background.cljs
   - Update tests

3. **`modified` timestamp** - Add to script manifest
   - Update storage format
   - Include in `ls` output

### Phase 2: UI Reactivity

4. **Notify popup/panel on fs changes**
   - Check existing message patterns for storage updates
   - Popup: refresh script list on change
   - Panel: only react if edited file is affected

### Phase 3: Confirmation Workflow

The design is:

* REPL calls `(rm! "script.cljs")` -> Promise resolves immediately with `{:fs/pending-confirmation true :fs/name "script.cljs"}`
* Background stores pending confirmation -> keyed by script name (so subsequent ops replace)
* UI shows inline confirmation card -> User confirms or cancels
* Confirm -> Operation executes, confirmation cleared
* Cancel -> Confirmation cleared, nothing happens

No waiting, no callback resolution needed. The REPL just queues operations for confirmation.

5. **Confirmation UI pattern**
   - Inline confirmation cards in list
   - Ghost items for new files
   - Badge for pending when closed (planned)

6. **Implement confirmations**
   - `rm!` confirmation
   - `mv!` confirmation
   - `save!` update confirmation
   - `save!` create confirmation (ghost item)

### Phase 4: Options and Ergonomics

7. **Options for `save!`** - `:fs/enabled`, `:fs/force?`

8. **Options for other ops** - `:fs/force? true` for all destructive

9. **Bulk operations** - vectors/maps for batch processing

### Phase 5: Documentation

10. **Update example tamper** - Use promesa patterns (done)

11. **Document features**
   - "Not-approved" behavior for new scripts
   - Inline confirmation cards and ghost items
   - Full API reference
   - User guide section

## Testing Strategy

### Unit Tests
- Namespace changes (symbol resolution)
- Keyword transformations
- Timestamp handling

### E2E Tests
- Confirmation workflow (pending state, UI card, resolution)
- UI reactivity (create/rename/delete reflects in popup)
- Bulk operations
- Backward compatibility (if aliases maintained)

## Open Items

- Determine if backward compatibility aliases are needed or clean break is acceptable

---

## Mini-Plan: Fix Confirmation Semantics (January 2026)

**Problem:** Current implementation has incorrect semantics discovered via E2E test failures.

### Issues Found

1. **`save!` confirmation not implemented in background** - No `queue-save-script` handler, so `save!` never queues confirmation
2. **Wrong option names in tests/docs** - Tests still send `:confirm false` and `:enabled`; current API uses `:fs/force?` and `:fs/enabled`
3. **Return keys mismatch in tests/docs** - Tests still assert `:success`, but code returns `:fs/success`
4. **UI confirmation placement mismatch** - Implementation uses a top-level confirmation section and must move to inline cards with ghost items
5. **Bulk confirmation behavior undefined for `save!`** - Needs explicit queue semantics and return format
6. **Force mode should clear pending** - Using `:fs/force? true` should clear any pending confirmation for that file

### Current Failing Tests

- [e2e/fs_write_test.cljs](e2e/fs_write_test.cljs) - expects `:success`, `:confirm`, `:enabled` semantics
   - Assumes immediate execution; update to use `:fs/force?` or trigger the UI confirmation flow.
- [e2e/fs_ui_reactivity_test.cljs](e2e/fs_ui_reactivity_test.cljs) - expects `:success` results from `save!`/`rm!`/`mv!`
   - Assumes immediate execution; update to use `:fs/force?` or trigger the UI confirmation flow.
- [e2e/fs_read_test.cljs](e2e/fs_read_test.cljs) - expects `:name`/`:enabled` in `ls` output instead of `:fs/name`/`:fs/enabled`

### Correct Semantics

`:fs/success` = whether the command was accepted (queue or execute)
`:fs/pending-confirmation` = orthogonal flag indicating confirmation needed

```clojure
;; Successfully queued for confirmation
{:fs/success true
 :fs/pending-confirmation true
 :fs/name "script.cljs"}

;; Successfully executed (forced)
{:fs/success true
 :fs/name "script.cljs"}

;; Error
{:fs/success false
 :fs/error "Script not found"}
```

### Tasks

| Task | Status |
|------|--------|
| Implement background `queue-save-script` handler and wiring | 🔲 | Needed for `save!` confirmation (create/update) |
| Decide `epupp.fs` option aliasing/back-compat (`:confirm`/`:enabled`) | 🔲 | Either alias or update all call sites/tests/docs |
| Normalize return keys to `:fs/*` everywhere | 🔲 | Ensure bulk and single operations match |
| Confirm `:fs/force?` clears pending confirmation for file | 🔲 | Behavior must be consistent across ops |
| Define bulk confirmation behavior for `save!` | 🔲 | Queue per item, return per-item `:fs/*` results |
| Update E2E expectations to current API | 🔲 | [e2e/fs_write_test.cljs](e2e/fs_write_test.cljs), [e2e/fs_ui_reactivity_test.cljs](e2e/fs_ui_reactivity_test.cljs), [e2e/fs_read_test.cljs](e2e/fs_read_test.cljs) |
| Align docs/examples with inline confirmation UI | 🔲 | Ensure ghost item behavior is documented |
| Update docstrings and user guide | 🔲 | Keep API docs consistent |
