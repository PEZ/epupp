# Epupp REPL File System API - Implementation Plan

**Created:** January 15, 2026
**Status:** In Progress - Blocked on content bridge forwarding
**Related:** [repl-file-sync-plan.md](repl-file-sync-plan.md) (almost completely implemented)

## Overview

A file system API for managing userscripts from the REPL, with confirmation workflows, UI reactivity, and ergonomic async patterns.

## ROOT CAUSE ANALYSIS (January 2026)

**All E2E tests are failing** because of a single missing piece: **the content bridge does not forward queue messages**.

The `epupp.fs` page API sends messages like `queue-save-script`, `queue-delete-script`, `queue-rename-script` to the bridge, but `content_bridge.cljs` only forwards `save-script`, `delete-script`, `rename-script`. The bridge silently drops the queue variants.

Additionally, **the save-script handler in content_bridge does not forward the `enabled` or `force` options** from the page API.

### Message Flow Diagram

```
Page (epupp.fs/save!)  -->  Content Bridge  -->  Background Worker
                           (MISSING ROUTES)
```

**Current bridge routes:**
- `save-script` - forwards only `code`, not `enabled` or `force`
- `rename-script` - forwards `from`, `to` only
- `delete-script` - forwards `name` only

**Missing bridge routes:**
- `queue-save-script`
- `queue-rename-script`
- `queue-delete-script`

**Missing parameter forwarding:**
- `save-script` needs `enabled`, `force`
- `rename-script` needs `force`
- `delete-script` needs `force`

## Implementation Progress

| Task | Status | Notes |
|------|--------|-------|
| Rename files/refs from "primitives" to "fs" | ✅ Done | Test files, docs updated |
| Namespace rename (`epupp.fs/*`, `epupp.repl/*`) | ✅ Done | `epupp-namespace-code` updated |
| Namespaced keywords in returns | ✅ Done | `:fs/name`, `:fs/enabled`, etc. |
| `modified` timestamp in manifest | ✅ Done | Added to `ls` output |
| UI reactivity for fs operations | ✅ Done | Popup listens to storage changes |
| **Content bridge message forwarding** | ❌ Blocked | Missing queue-* routes and options forwarding |
| Confirmation UI placement | ⚠️ Partial | Top-level confirmation section exists; inline/ghost items pending |
| Confirmation for `rm!` | ⚠️ Partial | Queue+confirm exists in background, bridge route missing |
| Confirmation for `mv!` | ⚠️ Partial | Queue+confirm exists in background, bridge route missing |
| Confirmation for `save!` (update) | 🔲 Not started | Requires background queue handler |
| Confirmation for `save!` (create) | 🔲 Not started | Requires background queue handler |
| Options for `save!` | ⚠️ Partial | `:fs/enabled` implemented in page API, not forwarded by bridge |
| Bulk operations | ⚠️ Partial | `cat` and `rm!` bulk implemented; `save!` bulk blocked |
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

### Squint vs Scittle Keyword Handling (CRITICAL)

**Different `clj->js` behavior:**

| Environment | Input | Output |
|-------------|-------|--------|
| Scittle (page API) | `(clj->js {:fs/name "x"})` | `{"name": "x"}` (namespace STRIPPED) |
| Squint (bridge, background) | `(clj->js {:fs/name "x"})` | `{"fs/name": "x"}` (namespace PRESERVED) |

**This difference is intentional and handled correctly:**

1. **Wire protocol uses non-namespaced keys**: The page API deliberately uses `{:code code :enabled enabled :force true}` (not `:fs/code`) when building payloads. After Scittle's `clj->js`, this becomes `{"code": ..., "enabled": ..., "force": ...}`.

2. **Bridge reads non-namespaced keys**: Content bridge uses `(.-code msg)`, `(.-enabled msg)`, `(.-force msg)` - matching the wire format.

3. **Background returns non-namespaced JS**: Responses are `#js {:success true :name "..."}` which becomes `{"success": true, "name": "..."}`.

4. **Page API converts to namespaced on return**: The page API reads `(.-success msg)` and returns `{:fs/success (.-success msg)}` to the REPL user.

**Summary**: Namespaced keywords (`:fs/*`) are ONLY used at the REPL user interface layer. Internal wire protocol uses non-namespaced keys. This avoids Scittle's namespace-stripping behavior causing issues.

## Implementation Order (Original Plan - Most Items Done)

This was the original implementation order. Most foundation items are complete. See **ACTIONABLE IMPLEMENTATION PLAN** below for current work.

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

#### Recent Updates and Gaps
- E2E tests updated to match current `:fs/*` semantics and to force execution when needed.
- Coverage gaps needing new tests:
   - Confirmation UI flow with inline cards and ghost items
   - `queue-save-script` behavior
   - Non-force confirmation path
   - Badge indicator planned UX
   - `mv!` bulk map input

## Open Items

- Determine if backward compatibility aliases are needed or clean break is acceptable

---

## ACTIONABLE IMPLEMENTATION PLAN

**Goal:** Make `epupp.fs/*` operations work end-to-end with `:fs/force? true` option.

**Strategy:** Fix the plumbing first (content bridge forwarding), then layer on confirmation UI. The tests use `:fs/force? true` to bypass confirmations, so fixing the bridge unblocks all current tests.

### Phase 1: Fix Content Bridge Forwarding (CRITICAL - UNBLOCKS ALL TESTS)

**Files to edit:**
- [src/content_bridge.cljs](../../src/content_bridge.cljs)

**Current state:** Bridge forwards `save-script`, `rename-script`, `delete-script` but:
1. Does NOT forward `enabled` or `force` parameters to `save-script`
2. Does NOT forward `force` parameter to `rename-script`/`delete-script`
3. Does NOT have routes for `queue-*` message types

**Task 1.1: Update `save-script` handler in content_bridge.cljs**

```clojure
;; CURRENT (broken):
"save-script"
(do
  (js/chrome.runtime.sendMessage
   #js {:type "save-script"
        :code (.-code msg)}  ;; missing enabled and force!
   ...))

;; FIXED:
"save-script"
(do
  (js/chrome.runtime.sendMessage
   #js {:type "save-script"
        :code (.-code msg)
        :enabled (.-enabled msg)
        :force (.-force msg)}
   ...))
```

**Task 1.2: Update `rename-script` handler in content_bridge.cljs**

Forward the `force` parameter:
```clojure
#js {:type "rename-script"
     :from (.-from msg)
     :to (.-to msg)
     :force (.-force msg)}
```

**Task 1.3: Update `delete-script` handler in content_bridge.cljs**

Forward the `force` parameter:
```clojure
#js {:type "delete-script"
     :name (.-name msg)
     :force (.-force msg)}
```

**Task 1.4: Add queue-* message routes to content_bridge.cljs**

Add new cases:
- `queue-save-script` -> forwards to background
- `queue-rename-script` -> forwards to background
- `queue-delete-script` -> forwards to background

Each needs corresponding response type (e.g., `queue-save-script-response`).

### Phase 2: Add Missing Background Handler

**Files to edit:**
- [src/background.cljs](../../src/background.cljs)

**Task 2.1: Implement `queue-save-script` handler**

New handler similar to `queue-delete-script` and `queue-rename-script`:
- Parse manifest from code to get script name
- Check if script exists (update) or not (create)
- Add to pending confirmations
- Return `{:success true :pending-confirmation true :name normalized-name}`

**Note:** The existing `save-script`, `rename-script`, and `delete-script` handlers already work correctly. The `save-script` handler already reads `enabled` - the problem was only that the bridge didn't forward it. When `:fs/force? true` is used, tests send directly to these handlers (not queue-* variants), so no changes needed to make current tests pass.

### Phase 2.5 (Optional): Force Clears Pending

Nice-to-have for complete semantics: when `force` is true on a direct operation, clear any pending confirmation for that script. This is only relevant when mixing force and non-force calls, which tests don't do.

### Phase 3: Verify Tests Pass

After Phase 1 and 2, run:
```bash
bb test:e2e -- --grep "fs"
```

All fs-related tests should pass since they use `:fs/force? true`.

### Phase 4: Confirmation UI (DEFERRED)

This can be done after the core API works. Tasks:
1. Inline confirmation cards in script list (replace top-level section)
2. Ghost items for new scripts pending creation
3. Badge indicator when popup closed

---

## EXECUTION CHECKLIST

Copy this to the todo list when starting work:

### Immediate (unblocks all E2E tests)

| # | Task | File | Status |
|---|------|------|--------|
| 1 | Forward `enabled`, `force` in `save-script` handler | content_bridge.cljs | 🔲 |
| 2 | Forward `force` in `rename-script` handler | content_bridge.cljs | 🔲 |
| 3 | Forward `force` in `delete-script` handler | content_bridge.cljs | 🔲 |
| 4 | Add `queue-save-script` route to bridge | content_bridge.cljs | 🔲 |
| 5 | Add `queue-rename-script` route to bridge | content_bridge.cljs | 🔲 |
| 6 | Add `queue-delete-script` route to bridge | content_bridge.cljs | 🔲 |
| 7 | Implement `queue-save-script` background handler | background.cljs | 🔲 |
| 8 | Run E2E tests to verify | - | 🔲 |

**Note:** Background handlers for non-queue operations (`save-script`, `rename-script`, `delete-script`) already work correctly. The `enabled` parameter is already read by `save-script` background handler - the problem is only that the bridge doesn't forward it. No background handler changes needed for immediate test fixes.

### Optional (force clears pending - nice-to-have)

| # | Task | File | Status |
|---|------|------|--------|
| O1 | `save-script` handler clears pending on force | background.cljs | 🔲 |
| O2 | `rename-script` handler clears pending on force | background.cljs | 🔲 |
| O3 | `delete-script` handler clears pending on force | background.cljs | 🔲 |

### Deferred (confirmation UI polish)

| # | Task | File | Status |
|---|------|------|--------|
| D1 | Inline confirmation cards in popup | popup.cljs | 🔲 |
| D2 | Ghost items for pending creates | popup.cljs | 🔲 |
| D3 | Badge indicator for pending ops | background.cljs | 🔲 |
| D4 | Update user guide with confirmation flow | docs/user-guide.md | 🔲 |

---

## REFERENCE: Current Message Types

### Page -> Bridge -> Background

| Page API | Bridge Route | Background Handler | Issue |
|----------|--------------|-------------------|-------|
| `epupp.fs/ls` | `list-scripts` ✅ | `list-scripts` ✅ | Working |
| `epupp.fs/show` | `get-script` ✅ | `get-script` ✅ | Working |
| `epupp.fs/save!` (force) | `save-script` ⚠️ | `save-script` ✅ | Bridge doesn't forward `enabled`, `force` |
| `epupp.fs/save!` (confirm) | ❌ no route | ❌ no handler | Both missing |
| `epupp.fs/mv!` (force) | `rename-script` ⚠️ | `rename-script` ✅ | Bridge doesn't forward `force` |
| `epupp.fs/mv!` (confirm) | ❌ no route | `queue-rename-script` ✅ | Bridge route missing |
| `epupp.fs/rm!` (force) | `delete-script` ⚠️ | `delete-script` ✅ | Bridge doesn't forward `force` |
| `epupp.fs/rm!` (confirm) | ❌ no route | `queue-delete-script` ✅ | Bridge route missing |

**Key insight:** Background handlers exist for queue operations (except `queue-save-script`). The bridge simply doesn't route these messages.

### Response Types

| Request | Response Type |
|---------|--------------|
| `list-scripts` | `list-scripts-response` |
| `get-script` | `get-script-response` |
| `save-script` | `save-script-response` |
| `queue-save-script` | `queue-save-script-response` |
| `rename-script` | `rename-script-response` |
| `queue-rename-script` | `queue-rename-script-response` |
| `delete-script` | `delete-script-response` |
| `queue-delete-script` | `queue-delete-script-response` |

