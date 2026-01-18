(ns background-actions.ws-actions)

(defn register
  "Register a WebSocket connection for a tab."
  [{:ws/keys [connections tab-id connection-info]}]
  {:uf/db {:ws/connections (assoc connections tab-id connection-info)}
   :uf/fxs []})

(defn unregister
  "Remove a WebSocket connection for a tab and broadcast the change."
  [{:ws/keys [connections tab-id]}]
  {:uf/db {:ws/connections (dissoc connections tab-id)}
   :uf/fxs [[:ws/fx.broadcast-connections-changed!]]})
