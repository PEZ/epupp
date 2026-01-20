# Implementation Plan: WebSocket Connection Tracking

**Created:** January 11, 2026
**Status:** Planning
**Related TODO:** "Keep track on when we use the websocket for a new tab we are no longer connected on a previous connected tab using the same port number"

## Problem Statement

The browser-nrepl relay server only supports ONE active WebSocket client per port. When Tab B connects to the same port as Tab A, the server's internal `nrepl-channel` atom is overwritten - Tab A's WebSocket stays "open" but becomes orphaned (responses go to Tab B).

**Current behavior:**
- Tab A connects to port 1340 - works
- Tab B connects to port 1340 - Tab B works, Tab A silently broken
- Tab A on 1340, Tab B on 1341 - both work (different ports)

**The extension doesn't track this.** The popup has no visibility into which tabs are connected or on what ports.

## Goals

1. Track which port each tab is connected to
2. When connecting a new tab to a port, explicitly disconnect any existing tab on that port
3. Display connected tabs in the popup UI with their ports
4. Provide "reveal tab" buttons for quick navigation

## Architecture Overview

```
Background Worker State (current):
  {:ws/connections {tab-id -> WebSocket}
   :pending/approvals {...}
   :icon/states {...}}

Background Worker State (proposed):
  {:ws/connections {tab-id -> {:ws/socket WebSocket
                               :ws/port number
                               :ws/tab-title string}}
   :pending/approvals {...}
   :icon/states {...}}
```

New message types:
- `get-connections` - Popup requests connection info from background
- Response: `{:connections [{:tab-id 123 :port 1340 :title "GitHub"}]}`

---

## ⚠️ Workflow Reminder

**ALWAYS use `bb <task>` over direct shell commands.** The bb tasks encode project-specific configurations. Check `bb tasks` for available commands.

**ALWAYS check lint/problem reports after edits.** Use `get_errors` tool to verify no syntax or bracket errors before running tests.

**ALWAYS use the `edit` subagent for file modifications.** The Clojure-editor subagent specializes in Clojure/Squint structural editing and avoids bracket balance issues. Provide it with complete context: file paths, line numbers, and the exact changes needed.

- `bb test` - Compile and run unit tests
- `bb test:e2e` - Compile and run E2E tests (Docker)

Before reporting a task as done:

1. Run unit ad e2e tests
2. Build the extension for manual testing, `bb build:dev`

---

## Implementation Chunks

### Chunk 1: Update State Structure in Background Worker

**File:** `src/background.cljs`
**Estimated time:** 30 minutes
**Tests:** Unit tests in `test/background_utils_test.cljs`

#### 1.1 Update `handle-ws-connect` to store port and title

**Current code (around line 70-100):**
```clojure
(defn handle-ws-connect
  "Create WebSocket connection for a tab"
  [tab-id port]
  ;; Close existing connection if any
  (close-ws! tab-id)
  ;; ... creates WebSocket and stores in :ws/connections
  (swap! !state assoc-in [:ws/connections tab-id] ws)
```

**Changes needed:**

1. Before creating the new WebSocket, find and close any OTHER tab using the same port
2. Store `{:ws/socket ws :ws/port port :ws/tab-title title}` instead of just `ws`
3. Fetch tab title via `chrome.tabs.get`

**Pseudocode:**
```clojure
(defn handle-ws-connect [tab-id port]
  ;; Step 1: Close existing connection for THIS tab (unchanged)
  (close-ws! tab-id)

  ;; Step 2: NEW - Find and close any OTHER tab on the same port
  (doseq [[other-tab-id conn-info] (:ws/connections @!state)]
    (when (and (not= other-tab-id tab-id)
               (= (:ws/port conn-info) port))
      (close-ws! other-tab-id)))

  ;; Step 3: Get tab title for display
  (let [tab-title (js-await (get-tab-title tab-id))]
    ;; Step 4: Create WebSocket and store with metadata
    (let [ws (js/WebSocket. ws-url)]
      (swap! !state assoc-in [:ws/connections tab-id]
             {:ws/socket ws
              :ws/port port
              :ws/tab-title (or tab-title "Unknown")}))))
```

#### 1.2 Update `get-ws` helper

**Current:**
```clojure
(defn get-ws [tab-id]
  (get-in @!state [:ws/connections tab-id]))
```

**New:**
```clojure
(defn get-ws [tab-id]
  (get-in @!state [:ws/connections tab-id :ws/socket]))
```

#### 1.3 Update `close-ws!` helper

**Current:** Closes WebSocket and removes from map
**New:** Same logic but access `:ws/socket` from the map

#### 1.4 Update all WebSocket event handlers

The `onopen`, `onmessage`, `onerror`, `onclose` handlers reference `ws` directly. These don't need changes since `ws` is still the local variable.

#### 1.5 Add helper to get tab title

```clojure
(defn ^:async get-tab-title [tab-id]
  (js/Promise.
   (fn [resolve]
     (js/chrome.tabs.get tab-id
       (fn [tab]
         (if js/chrome.runtime.lastError
           (resolve nil)
           (resolve (.-title tab))))))))
```

#### 1.6 Add pure utility functions (for testing)

**File:** `src/background_utils.cljs`

```clojure
(defn find-tab-on-port
  "Find the first tab-id connected to a given port, excluding exclude-tab-id.
   Returns tab-id or nil."
  [connections port exclude-tab-id]
  (->> connections
       (some (fn [[tab-id conn-info]]
               (when (and (not= tab-id exclude-tab-id)
                          (= (:ws/port conn-info) port))
                 tab-id)))))

(defn connections->display-list
  "Transform connections map to list for popup display.
   Returns [{:tab-id n :port n :title s}]"
  [connections]
  (->> connections
       (mapv (fn [[tab-id {:ws/port port :ws/tab-title title}]]
               {:tab-id tab-id
                :port port
                :title (or title "Unknown")}))
       (sort-by :port)))
```

**Tests:** Add to `test/background_utils_test.cljs`

---

### Chunk 2: Add Message Handler for Connection Info

**File:** `src/background.cljs`
**Estimated time:** 20 minutes

#### 2.1 Add `get-connections` message handler

In the `chrome.runtime.onMessage` listener, add a new case:

```clojure
"get-connections"
(let [connections (:ws/connections @!state)
      display-list (bg-utils/connections->display-list connections)]
  (send-response (clj->js {:success true
                           :connections display-list})))
```

This is synchronous, so return `false` (no async response needed).

---

### Chunk 3: Add Popup State and Actions

**Files:** `src/popup.cljs`, `src/popup_actions.cljs`
**Estimated time:** 45 minutes

#### 3.1 Add state keys

**File:** `src/popup.cljs`

Add to the initial `!state` atom:
```clojure
:repl/connections []  ; [{:tab-id n :port n :title s}]
```

#### 3.2 Add action handlers

**File:** `src/popup_actions.cljs`

```clojure
:popup/ax.load-connections
{:uf/fxs [[:popup/fx.load-connections]]}

:popup/ax.reveal-tab
(let [[tab-id] args]
  {:uf/fxs [[:popup/fx.reveal-tab tab-id]]})
```

#### 3.3 Add effect handlers

**File:** `src/popup.cljs`

```clojure
:popup/fx.load-connections
(js/chrome.runtime.sendMessage
 #js {:type "get-connections"}
 (fn [response]
   (when (and response (.-success response))
     (let [connections (js->clj (.-connections response) :keywordize-keys true)]
       (dispatch [[:db/ax.assoc :repl/connections connections]])))))

:popup/fx.reveal-tab
(let [[tab-id] args]
  (js/chrome.tabs.update tab-id #js {:active true}
    (fn [_tab]
      (when-not js/chrome.runtime.lastError
        ;; Also focus the window containing the tab
        (js/chrome.tabs.get tab-id
          (fn [tab]
            (when-not js/chrome.runtime.lastError
              (js/chrome.windows.update (.-windowId tab) #js {:focused true}))))))))
```

#### 3.4 Load connections on popup init

In `init!` function, add to the dispatch vector:
```clojure
[:popup/ax.load-connections]
```

---

### Chunk 4: Add Popup UI Component

**File:** `src/popup.cljs`
**Estimated time:** 45 minutes

#### 4.1 Create connected-tabs component

```clojure
(defn connected-tab-item [{:keys [tab-id port title]}]
  [:div.connected-tab-item
   [:span.connected-tab-port (str ":" port)]
   [:span.connected-tab-title (or title "Unknown")]
   [:button.reveal-tab-btn
    {:on-click #(dispatch! [[:popup/ax.reveal-tab tab-id]])
     :title "Reveal this tab"}
    [icons/external-link]]])

(defn connected-tabs-section [{:keys [repl/connections]}]
  [:div.connected-tabs-section
   (if (seq connections)
     [:div.connected-tabs-list
      (for [{:keys [tab-id] :as conn} connections]
        ^{:key tab-id}
        [connected-tab-item conn])]
     [:div.no-connections "No REPL connections active"])])
```

#### 4.2 Add to REPL Connect section

In `repl-connect-content`, after the existing steps, add:

```clojure
[:div.step
 [:div.step-header "Connected Tabs"]
 [connected-tabs-section state]]
```

Or make it a separate collapsible section if preferred.

#### 4.3 Add icon for reveal button

**File:** `src/icons.cljs`

Add `external-link` icon (from VS Code Codicons):
```clojure
(defn external-link
  ([] (external-link {}))
  ([{:keys [size class] :or {size 16}}]
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :width size :height size
          :viewBox "0 0 16 16"
          :fill "currentColor"
          :class class}
    [:path {:d "M1.5 1H6v1H2v12h12v-4h1v4.5l-.5.5h-13l-.5-.5v-13l.5-.5z"}]
    [:path {:d "M15 1.5V8h-1V2.707L7.243 9.465l-.707-.708L13.293 2H8V1h6.5l.5.5z"}]]))
```

---

### Chunk 5: Add CSS Styling

**File:** `extension/popup.css`
**Estimated time:** 20 minutes

```css
/* Connected Tabs Section */
.connected-tabs-section {
  margin-top: 8px;
}

.connected-tabs-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.connected-tab-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  background: var(--bg-secondary);
  border-radius: 4px;
}

.connected-tab-port {
  font-family: monospace;
  font-weight: bold;
  color: var(--accent-color);
  min-width: 50px;
}

.connected-tab-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reveal-tab-btn {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 2px;
  color: var(--text-secondary);
}

.reveal-tab-btn:hover {
  color: var(--accent-color);
}

.no-connections {
  color: var(--text-secondary);
  font-style: italic;
  padding: 4px 0;
}
```

---

### Chunk 6: Unit Tests

**File:** `test/background_utils_test.cljs`
**Estimated time:** 30 minutes

```clojure
(describe "find-tab-on-port"
  (it "returns nil when no connections"
    (expect (bg-utils/find-tab-on-port {} 1340 nil))
      .toBeNil))

  (it "finds tab on matching port"
    (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}
                       2 {:ws/port 1341 :ws/tab-title "Tab 2"}}]
      (expect (bg-utils/find-tab-on-port connections 1340 nil))
        .toBe 1)))

  (it "excludes specified tab-id"
    (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}}]
      (expect (bg-utils/find-tab-on-port connections 1340 1))
        .toBeNil)))

  (it "returns nil when port not found"
    (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}}]
      (expect (bg-utils/find-tab-on-port connections 9999 nil))
        .toBeNil))))

(describe "connections->display-list"
  (it "transforms connections map to sorted list"
    (let [connections {2 {:ws/port 1341 :ws/tab-title "Second"}
                       1 {:ws/port 1340 :ws/tab-title "First"}}
          result (bg-utils/connections->display-list connections)]
      (expect (count result)).toBe 2)
      (expect (:port (first result))).toBe 1340)
      (expect (:title (first result))).toBe "First"))))
```

---

### Chunk 7: E2E Test (Optional)

**File:** `e2e/popup_test.cljs`
**Estimated time:** 45 minutes (if implementing)

This is optional but recommended. Test scenario:
1. Connect Tab A to port 1340
2. Verify popup shows 1 connection
3. Connect Tab B to port 1340
4. Verify popup shows 1 connection (Tab B replaced Tab A)
5. Verify Tab A's icon state changed to disconnected/injected

---

## Implementation Order

1. **Chunk 1** - State structure (background) - Foundation
2. **Chunk 6** - Unit tests for pure functions - TDD style
3. **Chunk 2** - Message handler - Enables popup communication
4. **Chunk 3** - Popup state/actions - State management
5. **Chunk 4** - UI component - User-facing
6. **Chunk 5** - CSS styling - Polish
7. **Chunk 7** - E2E test (optional) - Confidence

## Verification Checklist

After implementation, manually verify:

- [ ] Connect Tab A to port 1340 - popup shows connection
- [ ] Connect Tab B to port 1340 - popup shows only Tab B, Tab A's icon changes
- [ ] Connect Tab C to port 1341 - popup shows Tab B (1340) and Tab C (1341)
- [ ] Click "reveal" on Tab B - browser switches to Tab B
- [ ] Close Tab B - popup updates to show only Tab C
- [ ] Unit tests pass: `bb test`
- [ ] E2E tests pass: `bb test:e2e`

## Notes for Implementer

- The browser-nrepl server limitation (one client per port) is external and won't change
- Our job is to make the extension aware of this and provide good UX
- Tab titles can change after connection - consider refreshing on popup open
- The `close-ws!` function already handles cleanup; we're just adding port-aware logic
- Use the existing Uniflow pattern for actions/effects - see other actions as examples
