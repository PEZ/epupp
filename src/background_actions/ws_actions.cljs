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

(defn handle-connect
  "Extract connections from state and delegate to effect for WS connect."
  [state {:ws/keys [tab-id port]}]
  (let [connections (or (:ws/connections state) {})]
    {:uf/fxs [[:ws/fx.handle-connect connections tab-id port]]}))

(defn handle-send
  "Extract connections from state and delegate to effect for WS send."
  [state {:ws/keys [tab-id data]}]
  (let [connections (or (:ws/connections state) {})]
    {:uf/fxs [[:ws/fx.handle-send connections tab-id data]]}))

(defn handle-close
  "Extract connections from state and delegate to effect for WS close."
  [state {:ws/keys [tab-id]}]
  (let [connections (or (:ws/connections state) {})]
    {:uf/fxs [[:ws/fx.handle-close connections tab-id]]}))
