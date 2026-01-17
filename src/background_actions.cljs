(ns background-actions
  "Pure action handlers for background worker FS operations.
   No Chrome APIs, no atoms, no side effects - just state transitions.

   Actions receive state and return:
   - {:uf/db new-state :uf/fxs effects} on success
   - {:uf/fxs effects} on failure (no state change)
   - :uf/unhandled-ax to delegate to generic handler"
  (:require [script-utils :as script-utils]))

;; ============================================================
;; Helper Functions (Pure)
;; ============================================================

(defn find-script-by-name
  "Find a script by name in the scripts list."
  [scripts name]
  (->> scripts
       (filter #(= (:script/name %) name))
       first))

(defn find-script-by-id
  "Find a script by ID in the scripts list."
  [scripts id]
  (->> scripts
       (filter #(= (:script/id %) id))
       first))

(defn- make-error-response
  "Create a failure response with error message."
  [error-msg]
  {:uf/fxs [[:bg/fx.send-response {:success false :error error-msg}]]})

(defn- make-success-response
  "Create a success response with optional extra data."
  ([state]
   (make-success-response state {}))
  ([state extra]
   {:uf/db state
    :uf/fxs [[:storage/fx.persist!]
             [:bg/fx.broadcast-scripts-changed!]
             [:bg/fx.send-response (merge {:success true} extra)]]}))

(defn- update-script-in-list
  "Update a script in the list by ID, applying update-fn."
  [scripts script-id update-fn]
  (mapv (fn [s]
          (if (= (:script/id s) script-id)
            (update-fn s)
            s))
        scripts))

(defn- remove-script-from-list
  "Remove a script from the list by ID."
  [scripts script-id]
  (filterv #(not= (:script/id %) script-id) scripts))

;; ============================================================
;; Action Handler
;; ============================================================

(defn handle-action
  "Handle background FS actions. Pure function - no side effects.

   Actions:
   - :fs/ax.rename-script [from-name to-name] - Rename a script
   - :fs/ax.delete-script [name] - Delete a script
   - :fs/ax.save-script [script] - Create or update a script"
  [state uf-data [action & args]]
  (let [scripts (:storage/scripts state)
        now-iso (.toISOString (js/Date. (:system/now uf-data)))]
    (case action
      :fs/ax.rename-script
      (let [[from-name to-name] args
            source-script (find-script-by-name scripts from-name)]
        (cond
          ;; Source not found
          (nil? source-script)
          (make-error-response (str "Script not found: " from-name))

          ;; Source is builtin
          (script-utils/builtin-script? source-script)
          (make-error-response "Cannot rename built-in scripts")

          ;; Target name exists
          (find-script-by-name scripts to-name)
          (make-error-response (str "Script already exists: " to-name))

          ;; All checks pass - allow rename
          :else
          (let [updated-scripts (update-script-in-list scripts (:script/id source-script)
                                  #(assoc % :script/name to-name :script/modified now-iso))]
            (make-success-response (assoc state :storage/scripts updated-scripts)
                                   {:from-name from-name :to-name to-name}))))

      :fs/ax.delete-script
      (let [[script-name] args
            script (find-script-by-name scripts script-name)]
        (cond
          ;; Script not found
          (nil? script)
          (make-error-response (str "Script not found: " script-name))

          ;; Script is builtin
          (script-utils/builtin-script? script)
          (make-error-response "Cannot delete built-in scripts")

          ;; All checks pass - allow delete
          :else
          (let [updated-scripts (remove-script-from-list scripts (:script/id script))]
            (make-success-response (assoc state :storage/scripts updated-scripts)
                                   {:name script-name}))))

      :fs/ax.save-script
      (let [[script] args
            script-id (:script/id script)
            script-name (:script/name script)
            force? (:script/force? script)
            existing-by-id (find-script-by-id scripts script-id)
            existing-by-name (find-script-by-name scripts script-name)
            is-update? (some? existing-by-id)]
        (cond
          ;; Trying to update a builtin script (by ID)
          (and is-update? (script-utils/builtin-script-id? script-id))
          (make-error-response "Cannot modify built-in scripts")

          ;; Trying to overwrite a builtin script (by name, even with force)
          (and existing-by-name (script-utils/builtin-script? existing-by-name))
          (make-error-response "Cannot overwrite built-in scripts")

          ;; Name collision on create (different ID, same name, no force)
          (and (not is-update?)
               existing-by-name
               (not force?))
          (make-error-response (str "Script already exists: " script-name))

          ;; All checks pass - create or update
          :else
          (let [;; Remove force? flag before storing
                clean-script (dissoc script :script/force?)
                ;; Add timestamps
                timestamped-script (if is-update?
                                     (assoc clean-script :script/modified now-iso)
                                     (assoc clean-script
                                            :script/created now-iso
                                            :script/modified now-iso))
                updated-scripts (if is-update?
                                  ;; Update existing
                                  (update-script-in-list scripts script-id (constantly timestamped-script))
                                  ;; Create new (remove any with same name if force)
                                  (let [filtered (if (and force? existing-by-name)
                                                   (remove-script-from-list scripts (:script/id existing-by-name))
                                                   scripts)]
                                    (conj filtered timestamped-script)))]
            (make-success-response (assoc state :storage/scripts updated-scripts)
                                   {:name script-name :is-update is-update?}))))

      ;; Unhandled action - delegate to generic handler
      :uf/unhandled-ax)))
