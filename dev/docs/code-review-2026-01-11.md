# Code Review: 2026-01-11

Comprehensive code review focusing on bugs, errors, and potential issues.

## Summary

| Severity | Count | Description |
|----------|-------|-------------|
| Critical | 0 | - |
| High | 2 | Missing error handling, memory leak |
| Medium | 3 | Logic edge cases |
| Low | 2 | Code quality |

## Resolved Issues

### 1. Panel State Restoration: Uniflow Violation - FIXED

**Status:** Resolved January 11, 2026

**Original problem:** `restore-panel-state!` used direct `swap!` to set `current-hostname` outside Uniflow, then dispatched other state via `[:editor/ax.initialize-editor]`. This violated the Uniflow pattern.

**Fix:** Modified `:editor/ax.initialize-editor` action to accept `hostname` in its data map. The action handler now sets `:panel/current-hostname` along with other panel state, keeping all state updates within Uniflow.

---

## High Severity Issues

### 2. Missing Error Handling in `save-panel-state!`

**File:** [panel.cljs](../../src/panel.cljs#L41-L50)
**Category:** Storage
**Severity:** High

**Description:** `save-panel-state!` writes to `chrome.storage.local` without checking for errors. If storage quota is exceeded or write fails, it fails silently.

**Evidence:**
```clojure
(defn save-panel-state!
  []
  (when-let [hostname (:panel/current-hostname @!state)]
    ;; No error handling callback
    (js/chrome.storage.local.set (js-obj key state-to-save))))
```

**Recommendation:** Add error handling:
```clojure
(js/chrome.storage.local.set
  (js-obj key state-to-save)
  (fn []
    (when js/chrome.runtime.lastError
      (js/console.error "[Panel] Failed to save state:"
                       (.-message js/chrome.runtime.lastError)))))
```

---

### 4. Icon State Memory Leak

**File:** [background.cljs](../../src/background.cljs#L52-L54)
**Category:** State/Memory
**Severity:** High

**Description:** Icon states are stored with tab IDs as keys. Cleanup happens in `onRemoved` listener, but if the service worker is asleep when a tab closes, the cleanup doesn't fire. Over time, orphaned entries can accumulate.

**Recommendation:** Prune stale entries on service worker wake:
```clojure
(defn ^:async prune-icon-states!
  "Remove icon states for tabs that no longer exist."
  []
  (let [tabs (js-await (js/chrome.tabs.query #js {}))
        valid-ids (set (map #(.-id %) tabs))]
    (swap! !state update :icon/states
           (fn [states]
             (select-keys states valid-ids)))))
```

Call this in `ensure-initialized!`.

**Action:** Implement tab state pruning.

---

## Medium Severity Issues

### 5. Missing Validation in `approve-pattern!`

**File:** [storage.cljs](../../src/storage.cljs#L93-L103)
**Category:** Logic
**Severity:** Medium

**Description:** `approve-pattern!` doesn't validate that the script exists. If called with invalid script-id, it silently does nothing, potentially hiding bugs.

**Evidence:**
```clojure
(defn approve-pattern!
  [script-id pattern]
  (swap! !db update :storage/scripts
         (fn [scripts]
           (mapv (fn [s]
                   (if (= (:script/id s) script-id)
                     (update s :script/approved-patterns ...)
                     s))
                 scripts)))
  (persist!))  ; Persists even if nothing changed
```

**Recommendation:** Add warning and early return:
```clojure
(defn approve-pattern!
  [script-id pattern]
  (if-not (get-script script-id)
    (js/console.warn "[Storage] approve-pattern! called for non-existent script:" script-id)
    (do
      (swap! !db ...)
      (persist!))))
```

---

### 6. `send-to-tab` Swallows Errors

**File:** [background.cljs](../../src/background.cljs#L78-L82)
**Category:** Async/Message
**Severity:** Medium

**Description:** `send-to-tab` logs errors but doesn't propagate them to callers. This can hide failures when the content script isn't injected or tab is closed.

**Evidence:**
```clojure
(defn send-to-tab
  [tab-id message]
  (-> (js/chrome.tabs.sendMessage tab-id (clj->js message))
      (.catch (fn [e]
                ;; Error logged but not propagated
                (js/console.error "[Background] Failed to send to tab:" tab-id "error:" e)))))
```

**Recommendation:** Return the promise and let callers decide on error handling:
```clojure
(defn send-to-tab
  [tab-id message]
  (js/chrome.tabs.sendMessage tab-id (clj->js message)))
```

Callers that need fire-and-forget can add their own `.catch`.

---

### 7. Manifest Parser Doesn't Validate Description Type

**File:** [manifest_parser.cljs](../../src/manifest_parser.cljs)
**Category:** Logic
**Severity:** Medium

**Description:** The manifest parser extracts `description` without validating it's a string. If a user provides a map or vector, it will be stored as-is and cause display issues.

**Recommendation:** Add type validation:
```clojure
description (let [d (aget parsed "epupp/description")]
              (when (string? d) d))
```

---

## Low Severity Issues

### 8. Inconsistent Error Message Prefixes

**File:** Multiple files
**Category:** Consistency
**Severity:** Low

**Description:** Error messages use different prefixes: `[Background]`, `[Storage]`, `[Panel]`, `[Auto-inject]`, `[Userscript]`. This makes grep/searching harder.

**Recommendation:** Standardize on `[ModuleName] ContextInfo: Details` format.

---

### 9. Test Logger Potentially Installed Multiple Times

**File:** [background.cljs](../../src/background.cljs#L12), [content_bridge.cljs](../../src/content_bridge.cljs#L138)
**Category:** Logic
**Severity:** Low

**Description:** Both modules call `install-global-error-handlers!`. Should verify the function guards against double-installation.

---

## Clarification Needed

### Panel State `swap!` vs Dispatch Pattern

The direct `swap!` for `current-hostname` appears intentional - it's needed for `save-panel-state!` to work correctly since that function reads hostname directly from `!state`. However, this creates architectural inconsistency.

**Question:** Is the current pattern intentional? Options:
1. Keep as-is and document why hostname is special
2. Refactor to pass hostname through Uniflow entirely
3. Change `save-panel-state!` to receive hostname as parameter

---

## Verified Non-Issues

These items were flagged during review but confirmed as correct behavior or intentionally removed. Documented here to avoid re-analysis in future reviews.

### `valid-run-at-values` Set Usage

The code correctly uses `contains?` to check set membership, not calling the set as a function (which would fail in Squint). This is the correct pattern per [squint.instructions.md](../../.github/squint.instructions.md#6-sets-are-not-callable).

### Firefox Registration on Startup

`ensure-initialized!` does call `sync-registrations!`, so Firefox registrations are correctly re-created on service worker startup. Not a bug.

### `normalize-match-array` - Removed

**Status:** Removed January 2026

This function attempted to flatten nested arrays in match patterns. Analysis confirmed nested arrays cannot occur in normal operation:

- Panel save: manifest -> `normalize-match-patterns` -> always flat vector
- Background install: string -> `[site-match]` -> flat vector
- Built-in scripts: hardcoded flat vectors

The only path for nested arrays would be malformed import JSON. Since the userscripts feature was pre-release with no external users, this defensive code added complexity without protecting against a real scenario. Removed rather than maintained.

See [userscripts-architecture.md](userscripts-architecture.md#data-integrity-invariants) for the documented invariant.
