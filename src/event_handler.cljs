(ns event-handler)

;; Uniflow event handling

(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :uf/fx.defer-dispatch
    (let [[actions timeout] args]
      (js/setTimeout #(dispatch actions) timeout))

    :log/fx.log
    (let [[level & ms] args]
      (apply (case level
               :debug js/console.debug
               :log js/console.log
               :info js/console.info
               :warn js/console.warn
               :error js/console.error
               js/console.log)
             ms))

    :uf/unhandled-fx))

(defn handle-action [state _uf-data [action & args]]
  (case action
    :db/ax.assoc
    {:uf/db (apply (partial assoc state) args)}

    :uf/unhandled-ax))

(defn handle-actions [state uf-data handler actions]
  (reduce (fn [{state :uf/db :as acc} action]
            (let [result (handler state uf-data action)
                  {:uf/keys [fxs dxs db]} (if-not (= :uf/unhandled-ax result)
                                            result
                                            (let [generic-result (handle-action state uf-data action)]
                                              (when (= :uf/unhandled-ax generic-result)
                                                (js/console.warn "Unhandled action:" action))
                                              generic-result))]
              (js/console.debug "Triggered action" (first action) action)
              (cond-> acc
                db (assoc :uf/db db)
                dxs (assoc :uf/dxs dxs)
                fxs (update :uf/fxs into fxs))))
          {:uf/db state
           :uf/fxs []}
          (remove nil? actions)))

(defn dispatch!
  "Dispatch actions through the Uniflow system.
   Optionally accepts additional-uf-data to merge into the framework context."
  ([!state ax-handler ex-handler actions]
   (dispatch! !state ax-handler ex-handler actions nil))
  ([!state ax-handler ex-handler actions additional-uf-data]
   (let [uf-data (merge {:system/now (.now js/Date)} additional-uf-data)
         {:uf/keys [fxs dxs db]}
         (try
           (handle-actions @!state uf-data ax-handler actions)
           (catch :default e
             {:uf/fxs [[:log/fx.log :error (ex-info "handle-action error"
                                                    {:error e}
                                                    :event-handler/handle-actions)]]}))]
     (when db
       (reset! !state db))
     (when dxs
       (dispatch! !state ax-handler ex-handler dxs additional-uf-data))
     (when fxs
       (try
         (doseq [fx fxs]
           (when fx
             (when-not (= :log/fx.log (first fx))
               (js/console.debug "Triggered effect" fx))
             (try
               (let [dispatch (fn [actions]
                                (dispatch! !state ax-handler ex-handler actions additional-uf-data))
                     result (ex-handler dispatch fx)]
                 (if-not (= :uf/unhandled-fx result)
                   result
                   (let [generic-result (perform-effect! dispatch fx)]
                     (when (= :uf/unhandled-fx generic-result)
                       (js/console.warn "Unhandled effect:" fx))
                     generic-result)))
               (catch :default e
                 (js/console.error (ex-info "perform-effect! Effect failed"
                                            {:error e
                                             :effect fx}
                                            :event-handler/perform-effects))
                 (throw e)))))
         (catch :default e
           (js/console.error (ex-info "perform-effects! error"
                                      {:error e}
                                      :event-handler/perform-effects))))))))