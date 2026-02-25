(ns background-actions.fs-actions
  "Action implementations for FS sync state management.
   Pure functions - no side effects."
  (:require [bg-ws :as bg-ws]))

(defn toggle-sync
  "Toggle FS sync for a specific tab.
   Enabling for a new tab implicitly disables the previous one.
   Rejects if enabling without an active WS connection."
  [state {:fs/keys [tab-id enabled send-response]}]
  (if (and enabled (not (some? (bg-ws/get-ws (:ws/connections state) tab-id))))
    {:uf/fxs [[:msg/fx.send-response send-response {:success false :error "No REPL connection for this tab"}]]}
    (let [new-sync-tab-id (when enabled tab-id)]
      {:uf/db (assoc state :fs/sync-tab-id new-sync-tab-id)
       :uf/fxs [[:fs/fx.broadcast-sync-status! new-sync-tab-id]
                [:msg/fx.send-response send-response {:success true}]]})))

(defn get-sync-status
  "Return current FS sync tab-id."
  [state {:fs/keys [send-response]}]
  {:uf/fxs [[:msg/fx.send-response send-response
             {:fsSyncTabId (get state :fs/sync-tab-id)}]]})
