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
| Confirmation card pattern | 🔲 Not started | Inline cards below items |
| Confirmation for `rm!` | 🔲 Not started | Default on, `:confirm false` to skip |
| Confirmation for `mv!` | 🔲 Not started | Default on |
| Confirmation for `save!` (update) | 🔲 Not started | When overwriting existing |
| Confirmation for `save!` (create) | 🔲 Not started | Ghost item with card |
| Options for `save!` | 🔲 Not started | `:enabled` option |
| Bulk operations | 🔲 Not started | `cat`, `save!`, `rm!`, `mv!` |
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
(epupp/manifest! {:epupp/require ["scittle://pprint.js"]})
(require '[cljs.pprint :refer [print-table]])

;; Full table (wrap with-out-str to capture in REPL)
(with-out-str (print-table @!scripts))

;; Select columns
(with-out-str (print-table [:name :enabled] @!scripts))
```

This composable approach is preferred over a custom `ls-print!` function.

### Return Value Keywords

All maps use `:fs/` namespaced keywords:

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

Operations requiring confirmation return immediately with `:fs/pending-confirmation true`. The UI shows an inline confirmation card below the affected item (or a ghost item for new files).

- Operating on the same file while pending updates the existing confirmation
- Badge indicates pending confirmations when popup/panel closed
- User confirms/denies in UI
- Promise resolves when confirmed or denied

```clojure
;; Confirmation flow
(p/let [result (epupp.fs/rm! "script.cljs")]
  (if (:fs/pending-confirmation result)
    (println "Awaiting confirmation in Epupp UI...")
    (println "Deleted:" (:fs/name result))))

;; Skip confirmation
(epupp.fs/rm! "script.cljs" {:confirm false})
```

### Bulk Operations

| Function | Single | Bulk input | Bulk output |
|----------|--------|-----------|-------------|
| `cat` | `(cat "name")` → string | `(cat ["a" "b"])` → `{"a" "code" "b" nil}` |
| `save!` | `(save! code)` | `(save! [code1 code2])` → `{"name1" result "name2" result}` |
| `rm!` | `(rm! "name")` | `(rm! ["a" "b"])` → `{"a" result "b" result}` |
| `mv!` | `(mv! "old" "new")` | `(mv! {"old1" "new1" "old2" "new2"})` → per-item results |

### Default Behaviors

- `save!` creates scripts with `:enabled true` (requires approval anyway)
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
* UI shows confirmation card -> User confirms or cancels
* Confirm -> Operation executes, confirmation cleared
* Cancel -> Confirmation cleared, nothing happens

No waiting, no callback resolution needed. The REPL just queues operations for confirmation.

5. **Confirmation card UI pattern**
   - Inline card below list item (replaces modal dialog)
   - Ghost item for new file creation
   - Badge for pending when closed

6. **Implement confirmations**
   - `rm!` confirmation
   - `mv!` confirmation
   - `save!` update confirmation
   - `save!` create confirmation (ghost item)

### Phase 4: Options and Ergonomics

7. **Options for `save!`** - `:enabled`, `:confirm`

8. **Options for other ops** - `:confirm false` for all destructive

9. **Bulk operations** - vectors/maps for batch processing

### Phase 5: Documentation

11. **Update example tamper** - Use promesa patterns

12. **Document features**
    - "Not-approved" behavior for new scripts
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

1. **Missing `:fs/success true` when queuing** - Operations return only `:fs/pending-confirmation true` on successful queue, should include `:fs/success true`
2. **Wrong option name** - Uses `:confirm false` instead of `:fs/force?`
3. **Bulk bypasses confirmation** - Should queue multiple confirmations instead
4. **`save!` lacks confirmation** - Needs confirmation workflow
5. **`save!` bulk uses wrong keys** - Returns `{idx {:success ...}}` instead of `{idx {:fs/success ...}}`
6. **Force mode should clear pending** - Using `:fs/force? true` should clear any pending confirmation for that file

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
| Rename `:confirm` to `:fs/force?` in all functions | 🔲 |
| Return `:fs/success true` when queuing succeeds | 🔲 |
| Add confirmation to `save!` (both create and update) | 🔲 |
| Make bulk `rm!` queue multiple confirmations | 🔲 |
| Make bulk `save!` queue multiple confirmations | 🔲 |
| Fix bulk `save!` to use `:fs/` prefixed keys | 🔲 |
| Force mode clears pending confirmation for file | 🔲 |
| Update E2E tests to reflect correct semantics | 🔲 |
| Update docstrings | 🔲 |
