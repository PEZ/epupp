(ns background-actions.icon-actions
  (:require [background-utils :as bg-utils]))

(defn set-state
  "Set icon state for a tab and trigger toolbar update."
  [state {:icon/keys [tab-id new-state]}]
  (let [new-states (assoc (or (:icon/states state) {}) tab-id new-state)
        display-state (bg-utils/compute-display-icon-state new-states tab-id)]
    {:uf/db (assoc state :icon/states new-states)
     :uf/fxs [[:icon/fx.update-toolbar! tab-id display-state]]}))

(defn clear-state
  "Clear icon state for a tab and update the toolbar icon.
   This ensures the global icon state reflects reality when tabs close."
  [state {:icon/keys [tab-id]}]
  (let [new-states (dissoc (or (:icon/states state) {}) tab-id)
        display-state (bg-utils/compute-display-icon-state new-states tab-id)]
    {:uf/db (assoc state :icon/states new-states)
     :uf/fxs [[:icon/fx.update-toolbar! tab-id display-state]]}))

(defn prune-states
  "Remove icon states for tabs that no longer exist."
  [state {:icon/keys [valid-tab-ids]}]
  (let [states (or (:icon/states state) {})]
    {:uf/db (assoc state :icon/states (select-keys states valid-tab-ids))
     :uf/fxs []}))
