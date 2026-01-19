(ns event-handler)

;; Uniflow event handling
;;
;; Actions return maps with:
;;   :uf/db       - new state
;;   :uf/fxs      - fire-and-forget effects (executed in parallel)
;;   :uf/await-fxs - sequential async effects (awaited in order, stops on error)
;;   :uf/dxs      - follow-up actions to dispatch after effects

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
                  {:uf/keys [fxs await-fxs dxs db]} (if-not (= :uf/unhandled-ax result)
                                                      result
                                                      (let [generic-result (handle-action state uf-data action)]
                                                        (when (= :uf/unhandled-ax generic-result)
                                                          (js/console.warn "Unhandled action:" action))
                                                        generic-result))]
              (js/console.debug "Triggered action" (first action) action)
              (cond-> acc
                db (assoc :uf/db db)
                dxs (assoc :uf/dxs dxs)
                fxs (update :uf/fxs into fxs)
                await-fxs (update :uf/await-fxs into await-fxs))))
          {:uf/db state
           :uf/fxs []
           :uf/await-fxs []}
          (remove nil? actions)))

(defn- execute-effect!
  "Execute a single effect, returning result or :uf/unhandled-fx.
   Receives dispatch fn for callback patterns."
  [dispatch ex-handler fx]
  (when-not (= :log/fx.log (first fx))
    (js/console.debug "Triggered effect" fx))
  (let [result (ex-handler dispatch fx)]
    (if-not (= :uf/unhandled-fx result)
      result
      (let [generic-result (perform-effect! dispatch fx)]
        (when (= :uf/unhandled-fx generic-result)
          (js/console.warn "Unhandled effect:" fx))
        generic-result))))

(defn ^:async execute-await-fxs!
  "Execute effects sequentially, awaiting each one.
   Wrapped in try/catch - stops and propagates on first error."
  [dispatch ex-handler await-fxs]
  (loop [remaining await-fxs]
    (when (seq remaining)
      (let [fx (first remaining)]
        (js-await (execute-effect! dispatch ex-handler fx))
        (recur (rest remaining))))))

(defn dispatch!
  "Dispatch actions through the Uniflow system.
   Optionally accepts additional-uf-data to merge into the framework context.

   Actions can return:
   - :uf/fxs      - fire-and-forget effects (executed without awaiting)
   - :uf/await-fxs - sequential async effects (awaited in order, stops on error)
   - :uf/dxs      - follow-up actions dispatched after effects"
  ([!state ax-handler ex-handler actions]
   (dispatch! !state ax-handler ex-handler actions nil))
  ([!state ax-handler ex-handler actions additional-uf-data]
   (let [uf-data (merge {:system/now (.now js/Date)} additional-uf-data)
         {:uf/keys [fxs await-fxs dxs db]}
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
     ;; Fire-and-forget effects - no awaiting
     (when (seq fxs)
       (try
         (let [dispatch (fn [actions]
                          (dispatch! !state ax-handler ex-handler actions additional-uf-data))]
           (doseq [fx fxs]
             (when fx
               (try
                 (execute-effect! dispatch ex-handler fx)
                 (catch :default e
                   (js/console.error (ex-info "perform-effect! Effect failed"
                                              {:error e
                                               :effect fx}
                                              :event-handler/perform-effects))
                   (throw e))))))
         (catch :default e
           (js/console.error (ex-info "perform-effects! error"
                                      {:error e}
                                      :event-handler/perform-effects)))))
     ;; Sequential awaited effects - wrapped in try/catch, stops on error
     (when (seq await-fxs)
       (let [dispatch (fn [actions]
                        (dispatch! !state ax-handler ex-handler actions additional-uf-data))]
         ;; Return the promise so callers can await if needed
         (execute-await-fxs! dispatch ex-handler await-fxs))))))
