(ns bg-icon
  "Toolbar icon and badge state management.
   Extracted from background.cljs to eliminate forward declares.
   Receives !state atom and dispatch! function via dependency injection."
  (:require [background-utils :as bg-utils]
            [test-logger :as test-logger]))

;; ============================================================
;; Icon State Management
;; ============================================================

(defn- get-icon-paths
  "Get icon paths for a given state. Delegates to tested pure function."
  [state]
  (bg-utils/get-icon-paths state))

(defn- compute-display-icon-state
  "Compute icon state to display based on:
   - Connected is GLOBAL: if ANY tab has REPL connected -> green
   - Injected is TAB-LOCAL: only if active-tab has Scittle -> yellow
   - Otherwise: disconnected (white)"
  [!state active-tab-id]
  (bg-utils/compute-display-icon-state (:icon/states @!state) active-tab-id))

(defn ^:async update-icon-now!
  "Update the toolbar icon based on global (connected) and tab-local (injected) state.
   Takes the relevant tab-id to use for tab-local state checking."
  [!state relevant-tab-id]
  (let [display-state (compute-display-icon-state !state relevant-tab-id)]
    (js-await (test-logger/log-event! "ICON_STATE_CHANGED" {:tab-id relevant-tab-id :state display-state}))
    (js/chrome.action.setIcon
     #js {:path (get-icon-paths display-state)})))

(defn ^:async update-icon-for-tab!
  "Update icon state for a specific tab, then update the toolbar icon.
   Uses the given tab-id for tab-local state calculation."
  [dispatch! tab-id state]
  (js-await (dispatch! [[:icon/ax.set-state tab-id state]])))

(defn get-icon-state
  "Get current icon state for a tab."
  [!state tab-id]
  (get-in @!state [:icon/states tab-id] :disconnected))

(defn clear-icon-state!
  "Clear icon state for a tab (when tab closes).
   Does NOT update the toolbar icon - that's handled by onActivated when
   the user switches to another tab."
  [dispatch! tab-id]
  (dispatch! [[:icon/ax.clear tab-id]]))

(defn ^:async prune-icon-states-direct!
  "Remove icon states for tabs that no longer exist by directly manipulating state.
   Used during initialization when dispatch! is not yet available.
   Modifies !state atom directly without going through action handlers."
  [!state]
  (let [tabs (js-await (js/chrome.tabs.query #js {}))
        valid-ids (set (map #(.-id %) tabs))
        current-states (:icon/states @!state)
        pruned-states (select-keys current-states valid-ids)]
    (swap! !state assoc :icon/states pruned-states)))

(defn ^:async prune-icon-states!
  "Remove icon states for tabs that no longer exist.
   Called on service worker wake to prevent memory leaks from orphaned entries
   when tabs close while the worker is asleep."
  [dispatch!]
  (let [tabs (js-await (js/chrome.tabs.query #js {}))
        valid-ids (set (map #(.-id %) tabs))]
    (js-await (dispatch! [[:icon/ax.prune valid-ids]]))))

;; ============================================================
;; FS Event Badge Flash
;; ============================================================

(defn flash-fs-badge!
  "Briefly flash badge to indicate FS operation result.
   Shows checkmark for success, exclamation for error, then clears badge."
  [event-type]
  (let [text (if (= event-type "success") "✓" "!")
        color (if (= event-type "success") "#22c55e" "#ef4444")]
    ;; Set flash badge
    (js/chrome.action.setBadgeText #js {:text text})
    (js/chrome.action.setBadgeBackgroundColor #js {:color color})
    ;; Clear badge after 2 seconds
    (js/setTimeout
     (fn []
       (js/chrome.action.setBadgeText #js {:text ""}))
     2000)))

(defn broadcast-fs-event!
  "Notify popup/panel about FS operation results and flash toolbar badge.
   Called after REPL FS operations (save, rename, delete) complete.
   event should be a map with keys: :event-type (:success/:error),
   :operation (:save/:rename/:delete), :script-name, and optionally :error"
  [event]
  ;; Flash toolbar badge briefly
  (flash-fs-badge! (:event-type event))
  ;; Send event to popup/panel
  (js/chrome.runtime.sendMessage
   (clj->js (merge {:type "fs-event"} event))
   (fn [_response]
     ;; Ignore errors - expected when no popup/panel is open
     (when js/chrome.runtime.lastError nil))))
