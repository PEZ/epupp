(ns background-actions.icon-actions)

(defn set-state
  "Set icon state for a tab and trigger toolbar update."
  [state {:icon/keys [tab-id new-state]}]
  (let [states (or (:icon/states state) {})]
    {:uf/db (assoc state :icon/states (assoc states tab-id new-state))
     :uf/fxs [[:icon/fx.update-toolbar! tab-id]]}))

(defn clear-state
  "Clear icon state for a tab and update the toolbar icon.
   This ensures the global icon state reflects reality when tabs close."
  [state {:icon/keys [tab-id]}]
  (let [states (or (:icon/states state) {})]
    {:uf/db (assoc state :icon/states (dissoc states tab-id))
     :uf/fxs [[:icon/fx.update-toolbar! tab-id]]}))

(defn prune-states
  "Remove icon states for tabs that no longer exist."
  [state {:icon/keys [valid-tab-ids]}]
  (let [states (or (:icon/states state) {})]
    {:uf/db (assoc state :icon/states (select-keys states valid-tab-ids))
     :uf/fxs []}))
