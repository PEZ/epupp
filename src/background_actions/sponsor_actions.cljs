(ns background-actions.sponsor-actions)

(defn set-pending
  "Track a pending sponsor check for a tab with a timestamp."
  [state {:sponsor/keys [tab-id now]}]
  {:uf/db (assoc-in state [:sponsor/pending-checks tab-id] now)})

(defn consume-pending
  "Consume a pending sponsor check and pass result to effect for response handling.
   Removes the pending entry from state regardless of outcome."
  [state {:sponsor/keys [tab-id now tab-url send-response]}]
  (let [pending (get-in state [:sponsor/pending-checks tab-id])
        pending? (boolean (and pending (< (- now pending) 30000)))]
    {:uf/db (update state :sponsor/pending-checks dissoc tab-id)
     :uf/fxs [[:sponsor/fx.handle-status-result
               {:pending? pending?
                :tab-url tab-url
                :send-response send-response}]]}))
