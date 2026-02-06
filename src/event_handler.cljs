(ns event-handler
  (:require [clojure.set :as set]
            [log :as log]))

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
    (let [[level subsystem & ms] args]
      (apply (case level
               :debug log/debug
               :info log/info
               :warn log/warn
               :error log/error
               log/info)
             subsystem ms))

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

(defn- apply-id-fn
  "Apply id-fn to an item. Handles both keyword and function id-fns.
   In Squint, keywords are strings, so we use get for keyword access."
  [id-fn item]
  (if (fn? id-fn)
    (id-fn item)
    (get item id-fn)))

(defn get-list-watcher-actions
  "Process list watchers, comparing state before and after changes.
   Two modes:
   1. Classic (no :shadow-path): Compares old-state vs new-state source lists
      Returns: [[:action {:added #{ids} :removed #{ids}}]]

   2. Shadow mode (with :shadow-path): Compares source to shadow in current state
      Shadow items have shape: {:item <original> :ui/entering? bool :ui/leaving? bool}
      Items already marked :ui/leaving? are excluded from removal detection.
      Returns: [[:action {:added-items [...] :removed-ids #{ids}}]]"
  [old-state new-state]
  (let [watchers (:uf/list-watchers old-state)]
    (when (seq watchers)
      (->> watchers
           (map (fn [[list-path watcher-config]]
                  (let [id-fn (:id-fn watcher-config)
                        shadow-path (:shadow-path watcher-config)
                        on-change (:on-change watcher-config)]
                    (if shadow-path
                      ;; Shadow mode: compare source to shadow in new-state
                      ;; Also detects content changes (same ID, different content)
                      (let [source-list (or (get new-state list-path) [])
                            shadow-list (or (get new-state shadow-path) [])
                            source-ids (set (map (fn [item] (apply-id-fn id-fn item)) source-list))
                            ;; Shadow IDs exclude items already leaving
                            active-shadow-items (filterv (fn [s] (not (:ui/leaving? s))) shadow-list)
                            shadow-ids (set (map (fn [s] (apply-id-fn id-fn (:item s))) active-shadow-items))
                            added-ids (set/difference source-ids shadow-ids)
                            removed-ids (set/difference shadow-ids source-ids)
                            ;; Detect content changes: items with same ID but different content
                            shadow-by-id (into {} (map (fn [s] [(apply-id-fn id-fn (:item s)) (:item s)]) active-shadow-items))
                            has-content-changes? (some (fn [item]
                                                         (let [id (apply-id-fn id-fn item)
                                                               shadow-item (get shadow-by-id id)]
                                                           (and shadow-item (not= item shadow-item))))
                                                       source-list)
                            ;; Get full items for additions from source
                            added-items (filterv (fn [item] (contains? added-ids (apply-id-fn id-fn item))) source-list)]
                        ;; Fire if membership OR content changed
                        (when (or (seq added-items) (seq removed-ids) has-content-changes?)
                          [on-change {:added-items added-items :removed-ids removed-ids}]))
                      ;; Classic mode: compare old vs new source
                      (let [old-list (or (get old-state list-path) [])
                            new-list (or (get new-state list-path) [])
                            old-ids (set (map (fn [item] (apply-id-fn id-fn item)) old-list))
                            new-ids (set (map (fn [item] (apply-id-fn id-fn item)) new-list))
                            added (set/difference new-ids old-ids)
                            removed (set/difference old-ids new-ids)]
                        (when (or (seq added) (seq removed))
                          [on-change {:added added :removed removed}]))))))
           (remove nil?)
           vec))))

(defn ^:async execute-effects!
  "Execute effects in order. Effects marked with :uf/await are awaited.
   Supports :uf/prev-result substitution for result threading.
   Returns the final prev-result for use in dxs."
  [dispatch ex-handler fxs]
  (loop [remaining fxs
         prev-result nil]
    (if (seq remaining)
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
            (recur (rest remaining) prev-result))))
      prev-result)))

(defn replace-prev-result-in-actions
  "Substitute :uf/prev-result placeholders in a vector of actions."
  [actions prev-result]
  (mapv (fn [action]
          (replace-prev-result action prev-result))
        actions))

(defn dispatch!
  "Dispatch actions through the Uniflow system.
   Optionally accepts additional-uf-data to merge into the framework context.

   Actions can return:
   - :uf/fxs      - effects to execute (use [:uf/await ...] sentinel for async)
   - :uf/dxs      - follow-up actions dispatched after effects complete

   Effects marked with :uf/await are awaited before continuing.
   Use :uf/prev-result in effect args to receive the previous await result.
   Use :uf/prev-result in dxs action args to receive the final effect result.

   List watchers (`:uf/list-watchers`) are evaluated after state change.
   Watcher actions are dispatched before effects."
  ([!state ax-handler ex-handler actions]
   (dispatch! !state ax-handler ex-handler actions nil))
  ([!state ax-handler ex-handler actions additional-uf-data]
   (let [old-state @!state
         uf-data (merge {:system/now (.now js/Date)} additional-uf-data)
         {:uf/keys [fxs dxs db]}
         (try
           (handle-actions old-state uf-data ax-handler actions)
           (catch :default e
             {:uf/fxs [[:log/fx.log :error "Uniflow" (ex-info "handle-action error"
                                                              {:error e}
                                                              :event-handler/handle-actions)]]}))]
     (when db
       (reset! !state db))
     ;; List watchers: detect changes and dispatch actions
     (let [new-state (or db old-state)
           watcher-actions (get-list-watcher-actions old-state new-state)]
       (when (seq watcher-actions)
         (dispatch! !state ax-handler ex-handler watcher-actions additional-uf-data)))
     ;; Execute effects first (unified model), then dispatch dxs with prev-result
     (let [dispatch-fn (fn [actions]
                         (dispatch! !state ax-handler ex-handler actions additional-uf-data))]
       (if (seq fxs)
         ;; Effects exist: execute them, then dispatch dxs with final result
         (-> (execute-effects! dispatch-fn ex-handler fxs)
             (.then (fn [prev-result]
                      (when dxs
                        (let [substituted-dxs (replace-prev-result-in-actions dxs prev-result)]
                          (dispatch-fn substituted-dxs))))))
         ;; No effects: dispatch dxs immediately (prev-result is nil)
         (when dxs
           (dispatch-fn dxs)))))))
