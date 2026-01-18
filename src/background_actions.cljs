(ns background-actions
  (:require [background-actions.repl-fs-actions :as repl-fs-actions]))

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

    :uf/unhandled-ax))
