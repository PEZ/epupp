(ns background-actions.history-actions)

(defn track
  "Track a tab's WebSocket port in connection history."
  [state {:history/keys [tab-id port]}]
  (let [entries (or (:connected-tabs/history state) {})]
    {:uf/db (assoc state :connected-tabs/history (assoc entries tab-id {:port port}))
     :uf/fxs []}))

(defn forget
  "Remove a tab from connection history."
  [state {:history/keys [tab-id]}]
  (let [entries (or (:connected-tabs/history state) {})]
    {:uf/db (assoc state :connected-tabs/history (dissoc entries tab-id))
     :uf/fxs []}))
