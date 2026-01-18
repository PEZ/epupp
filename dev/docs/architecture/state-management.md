# Epupp State Management

Each component maintains its own state atom with namespaced keys.

## Background Worker (`background.cljs`)

```clojure
;; Uses `def` (not defonce) so state resets on service worker wake.
;; WebSocket connections don't survive script termination anyway.

!init-promise  ; atom - initialization promise (reset per wake)
!state         ; atom
  {:ws/connections {}        ; tab-id -> WebSocket instance
   :pending/approvals {}}    ; approval-id -> approval context map

;; Approval context shape:
{:approval/id "script-id|pattern"
 :script/id "..."
 :script/name "..."
 :script/code "..."
 :approval/pattern "..."
 :approval/tab-id 123}
```

## Content Bridge (`content_bridge.cljs`)

```clojure
!state  ; atom
  {:bridge/connected? false
   :bridge/keepalive-interval nil}
```

## WebSocket Bridge (`ws_bridge.cljs`)

```clojure
!state  ; atom
  {:bridge/ready? false
   :ws/message-handler nil}  ; current event listener reference
```

## Popup (`popup.cljs`)

```clojure
!state  ; atom, rendered via add-watch
  {:ports/nrepl "1339"
   :ports/ws "1340"
   :ui/status nil
   :ui/copy-feedback nil
   :ui/has-connected false
   :ui/editing-hint-script-id nil
   :ui/sections-collapsed {:repl-connect false      ; expanded by default
                           :matching-scripts false  ; expanded by default
                           :other-scripts false     ; expanded by default
                           :settings true}          ; collapsed by default
   :browser/brave? false
   :scripts/list []
   :scripts/current-url nil
   :settings/user-origins []    ; User-added allowed script origins
   :settings/new-origin ""      ; Input field for new origin
   :settings/default-origins []} ; Config origins (read-only)
```

## Panel (`panel.cljs`)

```clojure
!state  ; atom, rendered via add-watch, persisted per hostname
  {:panel/results []
   :panel/code ""
   :panel/evaluating? false
   :panel/scittle-status :unknown  ; :checking, :loading, :loaded
   :panel/script-name ""
   :panel/script-match ""
   :panel/script-id nil            ; non-nil when editing
   :panel/save-status nil
   :panel/init-version nil
   :panel/needs-refresh? false
   :panel/current-hostname nil
   :panel/selection nil}           ; {:start int :end int :text string} or nil
```

## Storage (`storage.cljs`)

```clojure
!db  ; atom, synced with chrome.storage.local
  {:storage/scripts []
   :storage/granted-origins []        ; reserved for future use
   :storage/user-allowed-origins []}  ; user-added allowed script origins
```

## Uniflow Event System

The popup and panel use a Re-frame-inspired unidirectional data flow pattern
called Uniflow. The background worker also uses a scoped Uniflow pipeline for
REPL FS write operations. See [uniflow.md](uniflow.md) for the event system and
[background-uniflow-implementation.md](background-uniflow-implementation.md)
for the FS-specific background plan.

### Popup Actions (`:popup/ax.*`)

| Action | Args | Purpose |
|--------|------|---------|
| `:popup/ax.set-nrepl-port` | `[port]` | Update nREPL port, persist to storage |
| `:popup/ax.set-ws-port` | `[port]` | Update WebSocket port, persist to storage |
| `:popup/ax.copy-command` | - | Copy bb server command to clipboard |
| `:popup/ax.connect` | - | Initiate REPL connection to current tab |
| `:popup/ax.check-status` | - | Check if page has Scittle/bridge loaded |
| `:popup/ax.load-saved-ports` | - | Load ports from chrome.storage |
| `:popup/ax.load-scripts` | - | Load userscripts from storage |
| `:popup/ax.load-current-url` | - | Get current tab URL for matching |
| `:popup/ax.toggle-script` | `[script-id pattern]` | Toggle script enabled, revoke pattern on disable |
| `:popup/ax.delete-script` | `[script-id]` | Remove script from storage |
| `:popup/ax.approve-script` | `[script-id pattern]` | Add pattern to approved list, execute script |
| `:popup/ax.deny-script` | `[script-id]` | Disable script (deny approval) |
| `:popup/ax.inspect-script` | `[script-id]` | Send script to DevTools panel for viewing/editing |
| `:popup/ax.show-settings` | - | Switch to settings view |
| `:popup/ax.show-main` | - | Switch to main view |
| `:popup/ax.load-user-origins` | - | Load user origins from storage |
| `:popup/ax.set-new-origin` | `[value]` | Update new origin input field |
| `:popup/ax.add-origin` | - | Validate and add origin to user list |
| `:popup/ax.remove-origin` | `[origin]` | Remove origin from user list |

### Panel Actions (`:editor/ax.*`)

| Action | Args | Purpose |
|--------|------|---------|
| `:editor/ax.set-code` | `[code]` | Update code textarea |
| `:editor/ax.set-script-name` | `[name]` | Update script name field |
| `:editor/ax.set-script-match` | `[pattern]` | Update URL pattern field |
| `:editor/ax.set-selection` | `[{:start :end :text}]` | Track current textarea selection |
| `:editor/ax.eval` | - | Evaluate full code (inject Scittle if needed) |
| `:editor/ax.eval-selection` | - | Evaluate selection if present, else full code |
| `:editor/ax.do-eval` | `[code]` | Execute evaluation (internal) |
| `:editor/ax.handle-eval-result` | `[result]` | Process eval result/error |
| `:editor/ax.save-script` | - | Save current code as userscript |
| `:editor/ax.load-script-for-editing` | `[id name match code]` | Load script from popup |
| `:editor/ax.clear-results` | - | Clear results area |
| `:editor/ax.clear-code` | - | Clear code textarea |
| `:editor/ax.use-current-url` | - | Fill pattern from current page URL |
| `:editor/ax.check-scittle` | - | Check Scittle status in page |
| `:editor/ax.update-scittle-status` | `[status]` | Update status (`:unknown`, `:checking`, `:loading`, `:loaded`) |
| `:editor/ax.check-editing-script` | - | Check for script sent from popup |

### Generic Actions (`:db/ax.*`)

| Action | Args | Purpose |
|--------|------|---------|
| `:db/ax.assoc` | `[k v ...]` | Directly update state keys |

### Generic Effects (`:uf/fx.*`, `:log/fx.*`)

| Effect | Args | Purpose |
|--------|------|---------|
| `:uf/fx.defer-dispatch` | `[actions timeout-ms]` | Dispatch actions after delay |
| `:log/fx.log` | `[level & messages]` | Log to console (`:debug`, `:log`, `:info`, `:warn`, `:error`) |
