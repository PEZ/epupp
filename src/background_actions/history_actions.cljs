(ns background-actions.history-actions)

(defn track
  "Track a tab's WebSocket port in connection history."
  [{:history/keys [entries tab-id port]}]
  {:uf/db {:connected-tabs/history (assoc entries tab-id {:port port})}
   :uf/fxs []})

(defn forget
  "Remove a tab from connection history."
  [{:history/keys [entries tab-id]}]
  {:uf/db {:connected-tabs/history (dissoc entries tab-id)}
   :uf/fxs []})
