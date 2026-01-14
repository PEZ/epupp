# Epupp REPL File Sync Primitives API - Implementation Plan

**Created:** January 14, 2026
**Status:** In Progress

## Implementation Progress

| Primitive | Status | Notes |
|-----------|--------|-------|
| `epupp/cat` | ✅ Done | Retrieves script code by name |
| `epupp/ls` | ✅ Done | List all scripts with metadata |
| `epupp/save!` | ✅ Done | Save code with manifest |
| `epupp/mv!` | ✅ Done | Rename script |
| `epupp/rm!` | ✅ Done | Delete script |

## Problem Statement

Users want to develop userscripts in their editor of choice with version control (Git), but there's no clean path from local `.cljs` files to installed Epupp scripts. The current workflow requires manual copy-paste through the DevTools panel.

## Design Philosophy

Rather than implementing complex filesystem mounting or bidirectional sync, we provide **simple primitives** that users (or their tooling) can compose. Following Unix philosophy: small, composable tools.

The REPL channel already exists (`epupp/manifest!` proves the pattern). We extend it with file-like operations.

## API Design

### Functions

```clojure
;; List all scripts
;; Returns: [{:name "github/tweaks.cljs" :enabled true :match ["https://github.com/*"]} ...]
(epupp/ls)

;; Get a script's code by name
;; Returns: code string, or nil if not found
(epupp/cat "github/tweaks.cljs")

;; Save code to Epupp (parses manifest from code, creates/updates script)
;; Returns: {:success true :name "github/tweaks.cljs"} or {:success false :error "..."}
(epupp/save! "<code-with-manifest>")

;; Rename a script
;; Returns: {:success true} or {:success false :error "..."}
(epupp/mv! "old/name.cljs" "new/name.cljs")

;; Delete a script by name
;; Returns: {:success true} or {:success false :error "..."}
(epupp/rm! "github/tweaks.cljs")
```

### Key Design Points

1. **Name-based interface**: Users interact with script names (like filenames), not internal IDs
2. **IDs are internal**: Timestamp-based IDs remain stable across renames, but users never see them
3. **Manifest in code**: The code string contains its own metadata - no separate config needed
4. **Editor agnostic**: Works from any nREPL client (Calva, CIDER, Cursive, etc.)
5. **No sync magic**: User controls when to push/pull - no background sync

## Workflow Examples

### Push: Local File → Epupp

```clojure
;; User has local file ~/.epupp/github/tweaks.cljs
;; Editor command wraps file content and sends via REPL:

(epupp/save! "{:epupp/script-name \"github/tweaks.cljs\"
 :epupp/site-match \"https://github.com/*\"}

(ns github.tweaks)
(js/console.log \"Hello GitHub!\")")
```

### Pull: Epupp → Local File

```clojure
;; List available scripts
(epupp/ls)
;; => [{:name "github/tweaks.cljs" :enabled true :match ["https://github.com/*"]}
;;     {:name "youtube/controls.cljs" :enabled false :match ["https://youtube.com/*"]}]

;; Get code for a script
(epupp/cat "github/tweaks.cljs")
;; => "{:epupp/script-name \"github/tweaks.cljs\" ...}\n\n(ns github.tweaks)..."

;; User/tooling writes the returned string to local file
```

### Rename

```clojure
;; Move/rename a script (internal ID preserved)
(epupp/mv! "old_name.cljs" "github/tweaks.cljs")
```

## Implementation

### Phase 1: Message Protocol Extension

Add new message types to the bridge protocol:

| Message Type | Payload | Response |
|--------------|---------|----------|
| `list-scripts` | `{}` | `{:success true :scripts [...]}` |
| `get-script` | `{:name "..."}` | `{:success true :code "..."} or {:success false :error "..."}` |
| `save-script` | `{:code "..."}` | `{:success true :name "..."} or {:success false :error "..."}` |
| `rename-script` | `{:from "..." :to "..."}` | `{:success true} or {:success false :error "..."}` |
| `delete-script` | `{:name "..."}` | `{:success true} or {:success false :error "..."}` |

### Phase 2: Background Worker Handlers

In `src/background.cljs`, add handlers for new message types:

```clojure
;; list-scripts handler
;; - Call storage/get-scripts
;; - Map to {:name :enabled :match} (exclude internal fields like :script/id)
;; - Return vector

;; get-script handler
;; - Find script by normalized name
;; - Return :script/code or error

;; save-script handler
;; - Parse manifest from code using manifest-parser
;; - If :epupp/script-name missing, return error
;; - Find existing script by name, or create new
;; - Call storage/save-script!
;; - Return success with name

;; rename-script handler
;; - Find script by old name
;; - Update :script/name (ID stays same)
;; - Persist
;; - Return success

;; delete-script handler
;; - Find script by name
;; - Reject if built-in
;; - Call storage/delete-script!
;; - Return success
```

### Phase 3: Content Bridge Routing

In `src/content_bridge.cljs`, add the new message types to the whitelist for routing.

### Phase 4: Epupp Namespace Extension

Extend `epupp-namespace-code` in `src/background.cljs`:

```clojure
(def epupp-namespace-code
  "(ns epupp)

;; Existing manifest! function...

(defn- send-and-receive
  \"Helper: send message to bridge and return promise of response.\"
  [msg-type payload]
  (js/Promise.
    (fn [resolve reject]
      (letfn [(handler [e]
                (when (= (.-source e) js/window)
                  (let [msg (.-data e)]
                    (when (and msg
                               (= \"epupp-bridge\" (.-source msg))
                               (= (str msg-type \"-response\") (.-type msg)))
                      (.removeEventListener js/window \"message\" handler)
                      (resolve (.-payload msg))))))]
        (.addEventListener js/window \"message\" handler)
        (.postMessage js/window
          #js {:source \"epupp-page\"
               :type msg-type
               :payload (clj->js payload)}
          \"*\")))))

(defn ls
  \"List all scripts. Returns promise of vector with script info.\"
  []
  (-> (send-and-receive \"list-scripts\" {})
      (.then (fn [r] (if (.-success r) (.-scripts r) [])))))

(defn cat
  \"Get script code by name. Returns promise of code string or nil.\"
  [script-name]
  (-> (send-and-receive \"get-script\" {:name script-name})
      (.then (fn [r] (when (.-success r) (.-code r))))))

(defn save!
  \"Save code to Epupp. Parses manifest from code.
   Returns promise of {:success true :name ...} or {:success false :error ...}\"
  [code]
  (send-and-receive \"save-script\" {:code code}))

(defn mv!
  \"Rename a script. Returns promise of result map.\"
  [from-name to-name]
  (send-and-receive \"rename-script\" {:from from-name :to to-name}))

(defn rm!
  \"Delete a script by name. Returns promise of result map.\"
  [script-name]
  (send-and-receive \"delete-script\" {:name script-name}))
")
```

### Phase 5: Storage Helpers

Add lookup-by-name function to `src/storage.cljs`:

```clojure
(defn get-script-by-name
  "Find script by normalized name"
  [script-name]
  (let [normalized (script-utils/normalize-script-name script-name)]
    (->> (get-scripts)
         (filter #(= (:script/name %) normalized))
         first)))
```

## Testing Strategy

### Unit Tests

- `manifest-parser` already tested - no changes needed
- Add `storage/get-script-by-name` tests

### E2E Tests

New test file: `e2e/primitives_test.cljs`

```clojure
(test "epupp/ls returns script list"
  ;; Create script via panel
  ;; Connect REPL
  ;; Evaluate (epupp/ls)
  ;; Assert script appears in list
  )

(test "epupp/save! creates new script"
  ;; Connect REPL
  ;; Evaluate (epupp/save! code-with-manifest)
  ;; Verify script appears in popup
  )

(test "epupp/cat retrieves script code"
  ;; Create script
  ;; Connect REPL
  ;; Evaluate (epupp/cat "name.cljs")
  ;; Assert code matches
  )

(test "epupp/mv! renames script"
  ;; Create script
  ;; Evaluate (epupp/mv! "old.cljs" "new.cljs")
  ;; Verify name changed in popup
  ;; Verify script still works (ID preserved)
  )

(test "epupp/rm! deletes script"
  ;; Create script
  ;; Evaluate (epupp/rm! "name.cljs")
  ;; Verify script gone from popup
  )

(test "epupp/rm! rejects built-in scripts"
  ;; Try to delete gist installer
  ;; Assert error returned
  )
```

## Documentation Updates

### User Guide

Add section: "Managing Scripts from the REPL"

### Architecture Docs

Update `connected-repl.md` with new message types and API.

## Implementation Order

1. **Storage helper**: `get-script-by-name` (foundation)
2. **Message handlers**: Background worker handlers for all 5 operations
3. **Bridge routing**: Whitelist new message types
4. **Epupp namespace**: Add the 5 functions
5. **Unit tests**: Storage lookup
6. **E2E tests**: Full round-trip tests
7. **Documentation**: User guide and architecture

## Open Questions

1. **Error handling granularity**: Should `save!` return detailed parse errors, or just "invalid manifest"?
2. **Listing detail level**: Should `ls` return more fields (description, run-at, require)?
3. **Bulk operations**: Worth adding `epupp/export` / `epupp/import` for JSON format?

## Future Considerations

- **Editor tooling**: Calva/CIDER commands that automate the wrap-and-send pattern
- **Watch mode**: A script that polls `ls` and syncs changes (built in user-space, not Epupp core)
- **Diff support**: `epupp/diff` to compare local vs stored version

## Related Documents

- [connected-repl.md](architecture/connected-repl.md) - Current `epupp/manifest!` implementation
- [userscripts-architecture.md](userscripts-architecture.md) - Script storage design
- [message-protocol.md](architecture/message-protocol.md) - Bridge message types
