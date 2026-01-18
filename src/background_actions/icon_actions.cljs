(ns background-actions.icon-actions)

(defn set-state
  "Set icon state for a tab and trigger toolbar update."
  [{:icon/keys [states tab-id new-state]}]
  {:uf/db {:icon/states (assoc states tab-id new-state)}
   :uf/fxs [[:icon/fx.update-toolbar! tab-id]]})

(defn clear-state
  "Clear icon state for a tab without updating the toolbar icon."
  [{:icon/keys [states tab-id]}]
  {:uf/db {:icon/states (dissoc states tab-id)}
   :uf/fxs []})

(defn prune-states
  "Remove icon states for tabs that no longer exist."
  [{:icon/keys [states valid-tab-ids]}]
  {:uf/db {:icon/states (select-keys states valid-tab-ids)}
   :uf/fxs []})
