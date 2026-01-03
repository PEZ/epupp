(ns event-handler)

(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :ex/fx.defer-dispatch
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

    (js/console.warn "Unkown effect:" effect args)))

(defn handle-action [state [action & args]]
  (case action
    :db/ax.assoc
    {:ex/db (apply (partial assoc state) args)}

    (js/console.warn "Unknown action:" action args)))

(defn handle-actions [state handler actions]
  (reduce (fn [{state :ex/db :as acc} action]
            (let [{:ex/keys [fxs dxs db]} (let [result (handler state action)]
                                            (if-not (= :ex/unhandled-ax result)
                                              result
                                              (handle-action state action)))]
              (js/console.debug "Triggered action" (first action) action)
              (cond-> acc
                db (assoc :ex/db db)
                dxs (assoc :ex/dxs dxs)
                fxs (update :ex/fxs into fxs))))
          {:ex/db state
           :ex/fxs []}
          (remove nil? actions)))

(defn dispatch! [!state ax-handler ex-handler actions]
  (let [{:ex/keys [fxs dxs db]}
        (try
          (handle-actions @!state ax-handler actions)
          (catch :default e
            {:ex/fxs [[:log/fx.log :error (ex-info "handle-action error"
                                                   {:error e}
                                                   :event-handler/handle-actions)]]}))]
    (when db
      (reset! !state db))
    (when dxs
      (dispatch! !state ax-handler ex-handler dxs))
    (when fxs
      (try
        (doseq [fx fxs]
          (when fx
            (when-not (= :log/fx.log (first fx))
              (js/console.debug "Triggered effect" fx))
            (try
              (let [dispatch (partial dispatch! !state ax-handler ex-handler)
                    result (ex-handler dispatch fx)]
                (if-not (= :ex/unhandled-fx result)
                  result
                  (perform-effect! dispatch fx)))
              (catch :default e
                (js/console.error (ex-info "perform-effect! Effect failed"
                                           {:error e
                                            :effect fx}
                                           :event-handler/perform-effects))
                (throw e)))))
        (catch :default e
          (js/console.error (ex-info "perform-effects! error"
                                     {:error e}
                                     :event-handler/perform-effects)))))))