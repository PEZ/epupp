(ns background-actions.ws-actions)

(defn register
  "Register a WebSocket connection for a tab."
  [state {:ws/keys [tab-id connection-info]}]
  {:uf/db (assoc state :ws/connections (assoc (or (:ws/connections state) {}) tab-id connection-info))
   :uf/fxs []})

(defn unregister
  "Remove a WebSocket connection for a tab and broadcast the change.
   Also clears FS sync if the disconnecting tab was the FS sync tab."
  [state {:ws/keys [tab-id]}]
  (let [new-connections (dissoc (or (:ws/connections state) {}) tab-id)
        clear-fs? (= tab-id (:fs/sync-tab-id state))]
    {:uf/db (cond-> (assoc state :ws/connections new-connections)
              clear-fs? (assoc :fs/sync-tab-id nil))
     :uf/fxs (cond-> [[:ws/fx.broadcast-connections-changed! new-connections]]
               clear-fs? (conj [:fs/fx.broadcast-sync-status! nil]))}))
