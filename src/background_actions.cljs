(ns background-actions
  (:require [background-actions.repl-fs-actions :as repl-fs-actions]
            [background-actions.icon-actions :as icon-actions]
            [background-actions.history-actions :as history-actions]
            [background-actions.approval-actions :as approval-actions]
            [background-actions.ws-actions :as ws-actions]
            [scittle-libs :as scittle-libs]
            [script-utils :as script-utils]))

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

    :msg/ax.connect-tab
    (let [[send-response tab-id ws-port] args]
      {:uf/fxs [[:msg/fx.connect-tab send-response tab-id ws-port]]})

    :msg/ax.check-status
    (let [[send-response tab-id] args]
      {:uf/fxs [[:msg/fx.check-status send-response tab-id]]})

    :msg/ax.ensure-scittle
    (let [[send-response tab-id] args]
      {:uf/fxs [[:msg/fx.ensure-scittle send-response tab-id]]})

    :msg/ax.connect-tab-result
    (let [[send-response {:keys [ok? error]}] args
          response (cond-> {:success (boolean ok?)}
                     error (assoc :error error))]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.check-status-result
    (let [[send-response {:keys [ok? error status]}] args
          response (cond-> {:success (boolean ok?)}
                     error (assoc :error error)
                     ok? (assoc :status status))]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.ensure-scittle-result
    (let [[send-response {:keys [ok? error]}] args
          response (cond-> {:success (boolean ok?)}
                     error (assoc :error error))]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.evaluate-script-result
    (let [[send-response {:keys [ok? error]}] args
          response (cond-> {:success (boolean ok?)}
                     error (assoc :error error))]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.e2e-find-tab-id-result
    (let [[send-response {:keys [found-tab-id error]}] args
          response (cond
                     error {:success false :error error}
                     found-tab-id {:success true :tabId found-tab-id}
                     :else {:success false :error "No matching tab"})]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.e2e-get-storage-result
    (let [[send-response {:keys [key value error]}] args
          response (if error
                     {:success false :error error}
                     {:success true :key key :value value})]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.e2e-set-storage-result
    (let [[send-response {:keys [key value error]}] args
          response (if error
                     {:success false :error error}
                     {:success true :key key :value value})]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.inject-requires
    (let [[send-response tab-id requires] args
          files (when (seq requires)
                  (scittle-libs/collect-require-files [{:script/require requires}]))]
      (if (seq files)
        {:uf/await-fxs (-> [[:msg/fx.inject-bridge tab-id]
                            [:msg/fx.wait-bridge-ready tab-id]]
                           (into (mapv (fn [f] [:msg/fx.inject-require-file tab-id f]) files))
                           (conj [:msg/fx.send-response send-response {:success true}]))}
        {:uf/fxs [[:msg/fx.send-response send-response {:success true}]]}))

    :msg/ax.evaluate-script
    (let [[send-response tab-id code requires script-id] args
          script (cond-> {:script/id script-id
                          :script/name "popup-eval"
                          :script/code code}
                   requires (assoc :script/require requires))]
      {:uf/fxs [[:msg/fx.evaluate-script send-response tab-id script]]})

    :msg/ax.list-scripts-result
    (let [[send-response {:keys [include-hidden? scripts]}] args
          visible-scripts (script-utils/filter-visible-scripts scripts include-hidden?)
          public-scripts (mapv (fn [s]
                                 {:fs/name (:script/name s)
                                  :fs/enabled (:script/enabled s)
                                  :fs/match (:script/match s)
                                  :fs/modified (:script/modified s)})
                               visible-scripts)]
      {:uf/fxs [[:msg/fx.send-response send-response {:success true
                                                      :scripts public-scripts}]]})

    :msg/ax.get-script-result
    (let [[send-response {:keys [script-name script]}] args
          response (if script
                     {:success true :code (:script/code script)}
                     {:success false :error (str "Script not found: " script-name)})]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.list-scripts
    (let [[send-response include-hidden?] args]
      {:uf/fxs [[:msg/fx.list-scripts send-response include-hidden?]]})

    :msg/ax.get-script
    (let [[send-response script-name] args]
      {:uf/fxs [[:msg/fx.get-script send-response script-name]]})

    :msg/ax.load-manifest
    (let [[send-response tab-id manifest] args
          requires (when manifest (vec (aget manifest "require")))
          files (when (seq requires)
                  (scittle-libs/collect-require-files [{:script/require requires}]))]
      (if (seq files)
        {:uf/await-fxs (conj (mapv (fn [f] [:msg/fx.inject-require-file tab-id f]) files)
                             [:msg/fx.send-response send-response {:success true}])}
        {:uf/fxs [[:msg/fx.send-response send-response {:success true}]]}))

    :msg/ax.get-connections
    (let [[send-response] args]
      {:uf/fxs [[:msg/fx.get-connections send-response]]})

    :msg/ax.refresh-approvals
    {:uf/fxs [[:msg/fx.refresh-approvals]]}

    :msg/ax.e2e-find-tab-id
    (let [[send-response url-pattern] args]
      (if url-pattern
        {:uf/fxs [[:msg/fx.e2e-find-tab-id send-response url-pattern]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "Missing urlPattern"}]]}))

    :msg/ax.e2e-get-test-events
    (let [[send-response] args]
      {:uf/fxs [[:msg/fx.e2e-get-test-events send-response]]})

    :msg/ax.e2e-get-storage
    (let [[send-response key] args]
      (if key
        {:uf/fxs [[:msg/fx.e2e-get-storage send-response key]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "Missing key"}]]}))

    :msg/ax.e2e-set-storage
    (let [[send-response key value] args]
      (if key
        {:uf/fxs [[:msg/fx.e2e-set-storage send-response key value]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "Missing key"}]]}))

    :msg/ax.pattern-approved
    (let [[script-id pattern] args]
      {:uf/await-fxs [[:msg/fx.clear-pending-approval script-id pattern]
                      [:msg/fx.pattern-approved-data script-id]]})

    :msg/ax.pattern-approved-result
    (let [[{:keys [script active-tab-id]}] args]
      (if (and script active-tab-id)
        {:uf/fxs [[:msg/fx.execute-script-in-tab active-tab-id script]]}
        {:uf/fxs []}))

    :msg/ax.install-userscript-result
    (let [[send-response {:keys [saved error]}] args
          response (if error
                     {:success false :error error}
                     {:success true
                      :scriptId (:script/id saved)
                      :scriptName (:script/name saved)})]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.install-userscript
    (let [[send-response manifest script-url] args
          install-opts {:script-name (:script-name manifest)
                        :site-match (:site-match manifest)
                        :script-url script-url
                        :description (:description manifest)}]
      {:uf/fxs [[:msg/fx.install-userscript send-response install-opts]]})

    :uf/unhandled-ax))
