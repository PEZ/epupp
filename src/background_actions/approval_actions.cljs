(ns background-actions.approval-actions)

(defn request
  "Add a script/pattern to pending approvals and trigger badge update."
  [state {:approval/keys [script pattern tab-id]}]
  (let [pending (or (:pending/approvals state) {})
        approval-id (str (:script/id script) "|" pattern)
        already-pending? (get pending approval-id)
        pending (if already-pending?
                  pending
                  (assoc pending approval-id
                         {:approval/id approval-id
                          :script/id (:script/id script)
                          :script/name (:script/name script)
                          :script/code (:script/code script)
                          :approval/pattern pattern
                          :approval/tab-id tab-id}))
        fxs (cond-> [[:approval/fx.update-badge-for-tab! tab-id]]
              (not already-pending?)
              (conj [:approval/fx.log-pending!
                     {:script-name (:script/name script)
                      :pattern pattern}]))]
    {:uf/db (assoc state :pending/approvals pending)
     :uf/fxs fxs}))

(defn clear
  "Remove a script/pattern from pending approvals and update badge."
  [state {:approval/keys [script-id pattern]}]
  (let [pending (or (:pending/approvals state) {})
        approval-id (str script-id "|" pattern)]
    {:uf/db (assoc state :pending/approvals (dissoc pending approval-id))
     :uf/fxs [[:approval/fx.update-badge-active!]]}))

(defn sync
  "Prune pending approvals using current script state.
   Removes entries for missing scripts, disabled scripts, or approved patterns."
  [state {:approval/keys [scripts-by-id]}]
  (let [pending (or (:pending/approvals state) {})
        pruned (reduce-kv
                (fn [acc approval-id context]
                  (let [script-id (:script/id context)
                        pattern (:approval/pattern context)
                        script (get scripts-by-id script-id)
                        approved-patterns (or (:script/approved-patterns script) [])]
                    (if (or (nil? script)
                            (not (:script/enabled script))
                            (some #(= % pattern) approved-patterns))
                      acc
                      (assoc acc approval-id context))))
                {}
                pending)]
    {:uf/db (assoc state :pending/approvals pruned)
     :uf/fxs [[:approval/fx.update-badge-active!]]}))
