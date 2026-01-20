(ns background-actions.ws-actions)

(defn register
  "Register a WebSocket connection for a tab."
  [state {:ws/keys [tab-id connection-info]}]
  {:uf/db (assoc state :ws/connections (assoc (or (:ws/connections state) {}) tab-id connection-info))
   :uf/fxs []})

(defn unregister
  "Remove a WebSocket connection for a tab and broadcast the change."
  [state {:ws/keys [tab-id]}]
  {:uf/db (assoc state :ws/connections (dissoc (or (:ws/connections state) {}) tab-id))
   :uf/fxs [[:ws/fx.broadcast-connections-changed!]]})
