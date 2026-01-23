(ns event-handler
  (:require [clojure.set :as set]))

;; Uniflow event handling
;;
;; Actions return maps with:
;;   :uf/db  - new state
;;   :uf/fxs - effects to execute in order
;;             - Regular: [:fx.name arg1 arg2]
;;             - Await:   [:uf/await :fx.name arg1 arg2]
;;             - Thread:  [:uf/await :fx.name :uf/prev-result arg2]
;;   :uf/dxs - follow-up actions to dispatch after effects

(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :uf/fx.dispatch
    (let [[actions] args]
      (dispatch actions))

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
                                              generic-result))
                  all-fxs (vec (or fxs []))]
              (js/console.debug "Triggered action" (first action) action)
              (cond-> acc
                db (assoc :uf/db db)
                dxs (assoc :uf/dxs dxs)
                (seq all-fxs) (update :uf/fxs into all-fxs))))
          {:uf/db state
           :uf/fxs []}
          (remove nil? actions)))

(defn await-fx?
  "Check if an effect should be awaited (has :uf/await sentinel)."
  [fx]
  (and (vector? fx) (= :uf/await (first fx))))

(defn unwrap-fx
  "Remove :uf/await sentinel from effect if present."
  [fx]
  (if (await-fx? fx)
    (vec (rest fx))
    fx))

(defn replace-prev-result
  "Substitute :uf/prev-result placeholders with actual value."
  [fx prev-result]
  (mapv (fn [arg]
          (if (= :uf/prev-result arg)
            prev-result
            arg))
        fx))

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

(defn get-list-watcher-actions
  "Pure function that detects list membership changes between old and new state.
   Returns a vector of actions to dispatch based on :uf/list-watchers declarations.

   Watcher declaration format:
   {:uf/list-watchers {:path/key {:id-fn :item/id :on-change :action/key}}}

   Returns actions like:
   [[:action/key {:added #{new-ids} :removed #{old-ids}}]]"
  [old-state new-state]
  (let [watchers (:uf/list-watchers old-state)]
    (when (seq watchers)
      (->> watchers
           (map (fn [[list-path {:keys [id-fn on-change]}]]
                  (let [old-list (or (get old-state list-path) [])
                        new-list (or (get new-state list-path) [])
                        old-ids (set (map id-fn old-list))
                        new-ids (set (map id-fn new-list))
                        added (set/difference new-ids old-ids)
                        removed (set/difference old-ids new-ids)]
                    (when (or (seq added) (seq removed))
                      [on-change {:added added :removed removed}]))))
           (remove nil?)
           vec))))

(defn ^:async execute-effects!
  "Execute effects in order. Effects marked with :uf/await are awaited.
   Supports :uf/prev-result substitution for result threading."
  [dispatch ex-handler fxs]
  (loop [remaining fxs
         prev-result nil]
    (when (seq remaining)
      (let [raw-fx (first remaining)
            is-await? (await-fx? raw-fx)
            fx (-> raw-fx unwrap-fx (replace-prev-result prev-result))]
        (if is-await?
          (let [result (js-await (execute-effect! dispatch ex-handler fx))]
            (recur (rest remaining) result))
          (do
            (try
              (execute-effect! dispatch ex-handler fx)
              (catch :default e
                (js/console.error (ex-info "Effect failed"
                                           {:error e :effect fx}
                                           :event-handler/execute-effects))))
            (recur (rest remaining) prev-result)))))))

(defn dispatch!
  "Dispatch actions through the Uniflow system.
   Optionally accepts additional-uf-data to merge into the framework context.

   Actions can return:
   - :uf/fxs      - effects to execute (use [:uf/await ...] sentinel for async)
   - :uf/dxs      - follow-up actions dispatched after effects

   Effects marked with :uf/await are awaited before continuing.
   Use :uf/prev-result in effect args to receive the previous await result.

   List watchers (`:uf/list-watchers`) are evaluated after state change.
   Watcher actions are dispatched before dxs and effects."
  ([!state ax-handler ex-handler actions]
   (dispatch! !state ax-handler ex-handler actions nil))
  ([!state ax-handler ex-handler actions additional-uf-data]
   (let [old-state @!state
         uf-data (merge {:system/now (.now js/Date)} additional-uf-data)
         {:uf/keys [fxs dxs db]}
         (try
           (handle-actions old-state uf-data ax-handler actions)
           (catch :default e
             {:uf/fxs [[:log/fx.log :error (ex-info "handle-action error"
                                                    {:error e}
                                                    :event-handler/handle-actions)]]}))]
     (when db
       (reset! !state db))
     ;; List watchers: detect changes and dispatch actions
     (let [new-state (or db old-state)
           watcher-actions (get-list-watcher-actions old-state new-state)]
       (when (seq watcher-actions)
         (dispatch! !state ax-handler ex-handler watcher-actions additional-uf-data)))
     (when dxs
       (dispatch! !state ax-handler ex-handler dxs additional-uf-data))
     ;; Execute all effects in order (unified model)
     (when (seq fxs)
       (let [dispatch-fn (fn [actions]
                           (dispatch! !state ax-handler ex-handler actions additional-uf-data))]
         (execute-effects! dispatch-fn ex-handler fxs))))))
