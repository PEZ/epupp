(ns panel-actions
  "Pure action handlers for the DevTools panel.
   No browser dependencies - testable without Chrome APIs."
  (:require [script-utils :as script-utils]))

(defn handle-action
  "Pure action handler for panel state transitions.
   Returns map with :uf/db, :uf/fxs, :uf/dxs keys."
  [state uf-data [action & args]]
  (case action
    :editor/ax.set-code
    (let [[code] args]
      {:uf/db (assoc state :panel/code code)})

    :editor/ax.set-script-name
    (let [[new-name] args]
      {:uf/db (assoc state :panel/script-name new-name)})

    :editor/ax.set-script-match
    (let [[match] args]
      {:uf/db (assoc state :panel/script-match match)})

    :editor/ax.set-script-description
    (let [[desc] args]
      {:uf/db (assoc state :panel/script-description desc)})

    :editor/ax.update-scittle-status
    (let [[status] args]
      {:uf/db (assoc state :panel/scittle-status (or status :unknown))})

    :editor/ax.check-scittle
    {:uf/db (assoc state :panel/scittle-status :checking)
     :uf/fxs [[:editor/fx.check-scittle]]}

    :editor/ax.eval
    (let [code (:panel/code state)
          scittle-status (:panel/scittle-status state)]
      (cond
        ;; No code or already evaluating - no change
        (or (empty? code) (:panel/evaluating? state))
        nil

        ;; Scittle ready - eval directly
        (= :loaded scittle-status)
        {:uf/db (-> state
                    (assoc :panel/evaluating? true)
                    (update :panel/results conj {:type :input :text code}))
         :uf/fxs [[:editor/fx.eval-in-page code]]}

        ;; Scittle not ready - inject first
        :else
        {:uf/db (-> state
                    (assoc :panel/evaluating? true)
                    (assoc :panel/scittle-status :loading)
                    (update :panel/results conj {:type :input :text code}))
         :uf/fxs [[:editor/fx.inject-and-eval code]]}))

    :editor/ax.do-eval
    (let [[code] args]
      {:uf/fxs [[:editor/fx.eval-in-page code]]})

    :editor/ax.handle-eval-result
    (let [[result] args]
      {:uf/db (cond-> state
                :always (assoc :panel/evaluating? false)
                (:error result) (update :panel/results conj {:type :error :text (:error result)})
                (not (:error result)) (update :panel/results conj {:type :output :text (:result result)}))})

    :editor/ax.save-script
    (let [{:panel/keys [code script-name script-match script-description script-id]} state]
      (if (or (empty? code) (empty? script-name) (empty? script-match))
        {:uf/db (assoc state :panel/save-status {:type :error :text "Name, match pattern, and code are required"})}
        (let [;; Use existing id if editing, otherwise normalize name for new script
              id (or script-id (script-utils/normalize-script-id script-name))
              script (cond-> {:script/id id
                              :script/name script-name
                              :script/match [script-match]
                              :script/code code
                              :script/enabled true}
                       (seq script-description) (assoc :script/description script-description))]
          {:uf/fxs [[:editor/fx.save-script script]
                    [:uf/fx.defer-dispatch [[:db/ax.assoc :panel/save-status nil]] 3000]
                    [:editor/fx.clear-persisted-state]]
           :uf/db (assoc state
                         :panel/save-status {:type :success :text (str "Saved \"" script-name "\"")}
                         :panel/script-name ""
                         :panel/script-match ""
                         :panel/script-description ""
                         :panel/script-id nil)})))

    :editor/ax.load-script-for-editing
    (let [[id name match code description] args]
      {:uf/db (assoc state
                     :panel/script-id id
                     :panel/script-name name
                     :panel/script-match match
                     :panel/code code
                     :panel/script-description (or description ""))})

    :editor/ax.clear-results
    {:uf/db (assoc state :panel/results [])}

    :editor/ax.clear-code
    {:uf/db (assoc state :panel/code "")}

    :editor/ax.use-current-url
    {:uf/fxs [[:editor/fx.use-current-url [:db/ax.assoc :panel/script-match]]]}

    :editor/ax.check-editing-script
    {:uf/fxs [[:editor/fx.check-editing-script]]}

    :uf/unhandled-ax))
