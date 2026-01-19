(ns background-actions
  (:require [background-actions.repl-fs-actions :as repl-fs-actions]
            [background-actions.icon-actions :as icon-actions]
            [background-actions.history-actions :as history-actions]
            [background-actions.approval-actions :as approval-actions]
            [background-actions.ws-actions :as ws-actions]))

(defn- with-db
  "Ensure :uf/db contains full state by merging the updated slice."
  [state result k]
  (if-let [db (:uf/db result)]
    (assoc result :uf/db (assoc state k (get db k)))
    result))

(defn handle-action
  "Pure function - no side effects allowed."
  [state uf-data [action & args]]
  (case action
    :fs/ax.rename-script
    (let [[from-name to-name] args]
      (with-db state
        (repl-fs-actions/rename-script
         {:fs/scripts (:storage/scripts state)
          :fs/now-iso (.toISOString (js/Date. (:system/now uf-data)))
          :fs/from-name from-name
          :fs/to-name to-name})
        :storage/scripts))

    :fs/ax.delete-script
    (let [[payload] args
          {:keys [script-name bulk-id bulk-index bulk-count]} (if (map? payload)
                                                                payload
                                                                {:script-name payload})]
      (with-db state
        (repl-fs-actions/delete-script
         {:fs/scripts (:storage/scripts state)
          :fs/script-name script-name
          :fs/bulk-id bulk-id
          :fs/bulk-index bulk-index
          :fs/bulk-count bulk-count})
        :storage/scripts))

    :fs/ax.save-script
    (let [[script] args]
      (with-db state
        (repl-fs-actions/save-script
         {:fs/scripts (:storage/scripts state)
          :fs/now-iso (.toISOString (js/Date. (:system/now uf-data)))
          :fs/script script})
        :storage/scripts))

    :icon/ax.set-state
    (let [[tab-id new-state] args]
      (with-db state
        (icon-actions/set-state
         {:icon/states (or (:icon/states state) {})
          :icon/tab-id tab-id
          :icon/new-state new-state})
        :icon/states))

    :icon/ax.clear
    (let [[tab-id] args]
      (with-db state
        (icon-actions/clear-state
         {:icon/states (or (:icon/states state) {})
          :icon/tab-id tab-id})
        :icon/states))

    :icon/ax.prune
    (let [[valid-tab-ids] args]
      (with-db state
        (icon-actions/prune-states
         {:icon/states (or (:icon/states state) {})
          :icon/valid-tab-ids valid-tab-ids})
        :icon/states))

    :history/ax.track
    (let [[tab-id port] args]
      (with-db state
        (history-actions/track
         {:history/entries (or (:connected-tabs/history state) {})
          :history/tab-id tab-id
          :history/port port})
        :connected-tabs/history))

    :history/ax.forget
    (let [[tab-id] args]
      (with-db state
        (history-actions/forget
         {:history/entries (or (:connected-tabs/history state) {})
          :history/tab-id tab-id})
        :connected-tabs/history))

    :approval/ax.request
    (let [[script pattern tab-id] args]
      (with-db state
        (approval-actions/request
         {:approval/pending (or (:pending/approvals state) {})
          :approval/script script
          :approval/pattern pattern
          :approval/tab-id tab-id})
        :pending/approvals))

    :approval/ax.clear
    (let [[script-id pattern] args]
      (with-db state
        (approval-actions/clear
         {:approval/pending (or (:pending/approvals state) {})
          :approval/script-id script-id
          :approval/pattern pattern})
        :pending/approvals))

    :approval/ax.sync
    (let [[scripts-by-id] args]
      (with-db state
        (approval-actions/sync
         {:approval/pending (or (:pending/approvals state) {})
          :approval/scripts-by-id scripts-by-id})
        :pending/approvals))

    :ws/ax.register
    (let [[tab-id connection-info] args]
      (with-db state
        (ws-actions/register
         {:ws/connections (or (:ws/connections state) {})
          :ws/tab-id tab-id
          :ws/connection-info connection-info})
        :ws/connections))

    :ws/ax.unregister
    (let [[tab-id] args]
      (with-db state
        (ws-actions/unregister
         {:ws/connections (or (:ws/connections state) {})
          :ws/tab-id tab-id})
        :ws/connections))

    :uf/unhandled-ax))
