(ns background-actions
  (:require [background-actions.repl-fs-actions :as repl-fs-actions]
            [background-actions.icon-actions :as icon-actions]
            [background-actions.history-actions :as history-actions]
            [background-actions.approval-actions :as approval-actions]
            [background-actions.ws-actions :as ws-actions]
            [scittle-libs :as scittle-libs]
            [script-utils :as script-utils]))

(defn handle-action
  "Pure function - no side effects allowed."
  [state uf-data [action & args]]
  (case action
    :fs/ax.rename-script
    (let [[from-name to-name] args]
      (repl-fs-actions/rename-script
       state
       {:fs/now-iso (.toISOString (js/Date. (:system/now uf-data)))
        :fs/from-name from-name
        :fs/to-name to-name}))

    :fs/ax.delete-script
    (let [[payload] args
          {:keys [script-name bulk-id bulk-index bulk-count]} (if (map? payload)
                                                                payload
                                                                {:script-name payload})]
      (repl-fs-actions/delete-script
       state
       {:fs/script-name script-name
        :fs/bulk-id bulk-id
        :fs/bulk-index bulk-index
        :fs/bulk-count bulk-count}))

    :fs/ax.save-script
    (let [[script] args]
      (repl-fs-actions/save-script
       state
       {:fs/now-iso (.toISOString (js/Date. (:system/now uf-data)))
        :fs/script script}))

    :icon/ax.set-state
    (let [[tab-id new-state] args]
      (icon-actions/set-state
       state
       {:icon/tab-id tab-id
        :icon/new-state new-state}))

    :icon/ax.clear
    (let [[tab-id] args]
      (icon-actions/clear-state
       state
       {:icon/tab-id tab-id}))

    :icon/ax.prune
    (let [[valid-tab-ids] args]
      (icon-actions/prune-states
       state
       {:icon/valid-tab-ids valid-tab-ids}))

    :history/ax.track
    (let [[tab-id port] args]
      (history-actions/track
       state
       {:history/tab-id tab-id
        :history/port port}))

    :history/ax.forget
    (let [[tab-id] args]
      (history-actions/forget
       state
       {:history/tab-id tab-id}))

    :approval/ax.request
    (let [[script pattern tab-id] args]
      (approval-actions/request
       state
       {:approval/script script
        :approval/pattern pattern
        :approval/tab-id tab-id}))

    :approval/ax.clear
    (let [[script-id pattern] args]
      (approval-actions/clear
       state
       {:approval/script-id script-id
        :approval/pattern pattern}))

    :approval/ax.sync
    (let [[scripts-by-id] args]
      (approval-actions/sync
       state
       {:approval/scripts-by-id scripts-by-id}))

    :ws/ax.register
    (let [[tab-id connection-info] args]
      (ws-actions/register
       state
       {:ws/tab-id tab-id
        :ws/connection-info connection-info}))

    :ws/ax.unregister
    (let [[tab-id] args]
      (ws-actions/unregister
       state
       {:ws/tab-id tab-id}))

    :ws/ax.broadcast
    {:uf/fxs [[:ws/fx.broadcast-connections-changed!]]}

    :msg/ax.connect-tab
    (let [[send-response tab-id ws-port] args]
      {:uf/fxs [[:uf/await :repl/fx.connect-tab tab-id ws-port]
                [:msg/fx.send-response send-response :uf/prev-result]]})

    :msg/ax.check-status
    (let [[send-response tab-id] args]
      {:uf/fxs [[:uf/await :page/fx.check-status tab-id]
                [:msg/fx.send-response send-response :uf/prev-result]]})

    :msg/ax.ensure-scittle
    (let [[send-response tab-id] args]
      {:uf/fxs [[:msg/fx.ensure-scittle send-response tab-id]]})

    :msg/ax.ensure-scittle-result
    (let [[send-response {:keys [ok? error]}] args
          response (cond-> {:success (boolean ok?)}
                     error (assoc :error error))]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.evaluate-script
    (let [[send-response tab-id code requires script-id] args
          script (cond-> {:script/id script-id
                          :script/name "popup-eval"
                          :script/code code}
                   requires (assoc :script/require requires))]
      {:uf/fxs [[:uf/await :script/fx.evaluate tab-id script]
                [:msg/fx.send-response send-response :uf/prev-result]]})

    :msg/ax.e2e-get-storage
    (let [[send-response key] args]
      (if key
        {:uf/fxs [[:uf/await :storage/fx.get-local-storage key]
                  [:msg/fx.send-response send-response :uf/prev-result]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "Missing key"}]]}))

    :msg/ax.e2e-set-storage
    (let [[send-response key value] args]
      (if key
        {:uf/fxs [[:uf/await :storage/fx.set-local-storage key value]
                  [:msg/fx.send-response send-response :uf/prev-result]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "Missing key"}]]}))

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
        {:uf/fxs [[:uf/await :tabs/fx.find-by-url-pattern url-pattern]
                  [:msg/fx.send-response send-response :uf/prev-result]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "Missing urlPattern"}]]}))

    :msg/ax.e2e-get-test-events
    (let [[send-response] args]
      {:uf/fxs [[:msg/fx.e2e-get-test-events send-response]]})

    :msg/ax.pattern-approved
    ;; Recipe-style action using result threading:
    ;; 1. Clear pending approval (fire-and-forget)
    ;; 2. Get approved data (await, returns {:script ... :tab-id ...})
    ;; 3. Execute script if valid (await, receives prev result)
    (let [[script-id pattern] args]
      {:uf/fxs [[:msg/fx.clear-pending-approval script-id pattern]
                [:uf/await :msg/fx.get-pattern-approved-data script-id]
                [:uf/await :msg/fx.execute-approved-script :uf/prev-result]]})

    :msg/ax.install-userscript
    (let [[send-response manifest script-url] args
          install-opts {:script-name (:script-name manifest)
                        :site-match (:site-match manifest)
                        :script-url script-url
                        :description (:description manifest)}]
      {:uf/fxs [[:uf/await :userscript/fx.install install-opts]
                [:msg/fx.send-response send-response :uf/prev-result]]})

    :uf/unhandled-ax))
