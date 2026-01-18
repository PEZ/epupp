(ns background-actions
  (:require [background-actions.repl-fs-actions :as repl-fs-actions]
            [background-actions.icon-actions :as icon-actions]
            [background-actions.history-actions :as history-actions]
            [background-actions.approval-actions :as approval-actions]
            [background-actions.ws-actions :as ws-actions]))

(defn handle-action
  "Pure function - no side effects allowed."
  [state uf-data [action & args]]
  (case action
    :fs/ax.rename-script
    (let [[from-name to-name] args]
      (repl-fs-actions/rename-script
       {:fs/scripts (:storage/scripts state)
        :fs/now-iso (.toISOString (js/Date. (:system/now uf-data)))
        :fs/from-name from-name
        :fs/to-name to-name}))

    :fs/ax.delete-script
    (let [[payload] args
          {:keys [script-name bulk-id bulk-index bulk-count]} (if (map? payload)
                                                                payload
                                                                {:script-name payload})]
      (repl-fs-actions/delete-script
       {:fs/scripts (:storage/scripts state)
        :fs/script-name script-name
        :fs/bulk-id bulk-id
        :fs/bulk-index bulk-index
        :fs/bulk-count bulk-count}))

    :fs/ax.save-script
    (let [[script] args]
      (repl-fs-actions/save-script
       {:fs/scripts (:storage/scripts state)
        :fs/now-iso (.toISOString (js/Date. (:system/now uf-data)))
        :fs/script script}))

    :icon/ax.set-state
    (let [[tab-id new-state] args]
      (icon-actions/set-state
       {:icon/states (or (:icon/states state) {})
        :icon/tab-id tab-id
        :icon/new-state new-state}))

    :icon/ax.clear
    (let [[tab-id] args]
      (icon-actions/clear-state
       {:icon/states (or (:icon/states state) {})
        :icon/tab-id tab-id}))

    :icon/ax.prune
    (let [[valid-tab-ids] args]
      (icon-actions/prune-states
       {:icon/states (or (:icon/states state) {})
        :icon/valid-tab-ids valid-tab-ids}))

    :history/ax.track
    (let [[tab-id port] args]
      (history-actions/track
       {:history/entries (or (:connected-tabs/history state) {})
        :history/tab-id tab-id
        :history/port port}))

    :history/ax.forget
    (let [[tab-id] args]
      (history-actions/forget
       {:history/entries (or (:connected-tabs/history state) {})
        :history/tab-id tab-id}))

    :approval/ax.request
    (let [[script pattern tab-id] args]
      (approval-actions/request
       {:approval/pending (or (:pending/approvals state) {})
        :approval/script script
        :approval/pattern pattern
        :approval/tab-id tab-id}))

    :approval/ax.clear
    (let [[script-id pattern] args]
      (approval-actions/clear
       {:approval/pending (or (:pending/approvals state) {})
        :approval/script-id script-id
        :approval/pattern pattern}))

    :approval/ax.sync
    (let [[scripts-by-id] args]
      (approval-actions/sync
       {:approval/pending (or (:pending/approvals state) {})
        :approval/scripts-by-id scripts-by-id}))

    :ws/ax.register
    (let [[tab-id connection-info] args]
      (ws-actions/register
       {:ws/connections (or (:ws/connections state) {})
        :ws/tab-id tab-id
        :ws/connection-info connection-info}))

    :ws/ax.unregister
    (let [[tab-id] args]
      (ws-actions/unregister
       {:ws/connections (or (:ws/connections state) {})
        :ws/tab-id tab-id}))

    :uf/unhandled-ax))
