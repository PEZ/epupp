(ns background-actions.repl-fs-actions
  "Action implementations for background REPL FS operations.
   No Chrome APIs, no atoms, no side effects - just state transitions."
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
  "Create a failure response with error message and broadcast error event."
  ([error-msg]
   (make-error-response error-msg {}))
  ([error-msg {:keys [operation script-name event-data]}]
   {:uf/fxs [[:bg/fx.broadcast-system-banner! (merge {:event-type "error"
                                                 :operation operation
                                                 :script-name script-name
                                                 :error error-msg}
                                                event-data)]
             [:bg/fx.send-response {:success false :error error-msg}]]}))

(defn- make-success-response
  "Create a success response with optional extra data and broadcast success event."
  ([updated-scripts operation script-name]
   (make-success-response updated-scripts operation script-name {}))
  ([updated-scripts operation script-name {:keys [event-data response-data] :as extra}]
   (let [response-data (or response-data (dissoc extra :event-data))]
     {:uf/db {:storage/scripts updated-scripts}
      :uf/fxs [[:storage/fx.persist!]
               [:bg/fx.broadcast-system-banner! (merge {:event-type "success"
                                                   :operation operation
                                                   :script-name script-name}
                                                  event-data)]
               [:bg/fx.send-response (merge {:success true} response-data)]]})))

(defn- make-info-response
  "Create an info response (no storage change) with broadcast info event."
  [operation script-name {:keys [event-data response-data]}]
  {:uf/fxs [[:bg/fx.broadcast-system-banner! (merge {:event-type "info"
                                                     :operation operation
                                                     :script-name script-name}
                                                    event-data)]
            [:bg/fx.send-response (merge {:success true} response-data)]]})

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
;; Action Implementations
;; ============================================================

(defn rename-script
  "Rename a script by name."
  [state {:fs/keys [now-iso from-name to-name]}]
  (let [scripts (:storage/scripts state)
        source-script (find-script-by-name scripts from-name)]
    (cond
      ;; Source not found
      (nil? source-script)
      (make-error-response (str "Script not found: " from-name) {:operation "rename" :script-name from-name})

      ;; Source is builtin
      (script-utils/builtin-script? source-script)
      (make-error-response "Cannot rename built-in scripts" {:operation "rename" :script-name from-name})

      ;; Target name exists
      (find-script-by-name scripts to-name)
      (make-error-response (str "Script already exists: " to-name) {:operation "rename" :script-name from-name})

      ;; All checks pass - allow rename
      :else
      (let [updated-scripts (update-script-in-list scripts (:script/id source-script)
                                                   #(assoc % :script/name to-name :script/modified now-iso))]
        (make-success-response updated-scripts "rename" to-name
                               {:event-data {:script-id (:script/id source-script)
                                             :from-name from-name
                                             :to-name to-name}
                                :response-data {:from-name from-name
                                                :to-name to-name}})))))

(defn delete-script
  "Delete a script by name."
  [state {:fs/keys [script-name bulk-id bulk-index bulk-count]}]
  (let [scripts (:storage/scripts state)
        script (find-script-by-name scripts script-name)
        bulk-event-data (cond-> {}
                          (some? bulk-id) (assoc :bulk-id bulk-id)
                          (some? bulk-index) (assoc :bulk-index bulk-index)
                          (some? bulk-count) (assoc :bulk-count bulk-count))]
    (cond
      ;; Script not found
      (nil? script)
      (make-error-response (str "Not deleting non-existent file: " script-name)
                           {:operation "delete"
                            :script-name script-name
                            :event-data bulk-event-data})

      ;; Script is builtin
      (script-utils/builtin-script? script)
      (make-error-response "Cannot delete built-in scripts"
                           {:operation "delete"
                            :script-name script-name
                            :event-data bulk-event-data})

      ;; All checks pass - allow delete
      :else
      (let [updated-scripts (remove-script-from-list scripts (:script/id script))]
        (make-success-response updated-scripts "delete" script-name
                               {:event-data (merge {:script-id (:script/id script)}
                                                   bulk-event-data)
                                :response-data {:name script-name}})))))

(defn save-script
  "Create or update a script. When force-overwriting by name, preserves the
   existing script's ID to ensure stable identity."
  [state {:fs/keys [now-iso script]}]
  (let [scripts (:storage/scripts state)
        incoming-id (:script/id script)
        script-name (:script/name script)
        force? (:script/force? script)
        bulk-id (:script/bulk-id script)
        bulk-index (:script/bulk-index script)
        bulk-count (:script/bulk-count script)
        existing-by-id (when incoming-id (find-script-by-id scripts incoming-id))
        existing-by-name (find-script-by-name scripts script-name)
        ;; Update if:
        ;; - Found by ID, OR
        ;; - Force-overwriting by name, OR
        ;; - No incoming ID but name exists (name-based tracking)
        is-update? (or (some? existing-by-id)
                       (and force? (some? existing-by-name))
                       (and (nil? incoming-id) (some? existing-by-name)))
        ;; Determine script-id:
        ;; - Force-overwrite by name: use existing script's ID for stable identity
        ;; - Name-based update (no incoming ID, name exists): use existing script's ID
        ;; - Has incoming ID: keep it
        ;; - New script: generate new ID
        script-id (cond
                    ;; Force-overwrite by name
                    (and force? existing-by-name (not existing-by-id))
                    (:script/id existing-by-name)

                    ;; Name-based update (no incoming ID, name exists)
                    (and (nil? incoming-id) existing-by-name)
                    (:script/id existing-by-name)

                    ;; Has incoming ID
                    incoming-id
                    incoming-id

                    ;; New script
                    :else
                    (script-utils/generate-script-id))
        script (assoc script :script/id script-id)
        ;; For update checks below, use the matched script
        existing-by-id (or existing-by-id
                           (when (and force? existing-by-name) existing-by-name)
                           (when (and (nil? incoming-id) existing-by-name) existing-by-name))]
    (cond
      ;; Trying to update a builtin script (by ID)
      (and is-update? (script-utils/builtin-script-id? script-id))
      (make-error-response "Cannot modify built-in scripts" {:operation "save" :script-name script-name})

      ;; Trying to overwrite a builtin script (by name, even with force)
      (and existing-by-name (script-utils/builtin-script? existing-by-name))
      (make-error-response "Cannot overwrite built-in scripts" {:operation "save" :script-name script-name})

      ;; Trying to save with a name that would shadow a builtin (via normalization)
      (script-utils/name-matches-builtin? scripts script-name)
      (make-error-response "Cannot overwrite built-in scripts" {:operation "save" :script-name script-name})

      ;; Name collision on create (different ID, same name, no force)
      (and (not is-update?)
           existing-by-name
           (not force?))
      (make-error-response (str "Script already exists: " script-name) {:operation "save" :script-name script-name})

      ;; Content unchanged on update - report but don't save
      (and is-update? (= (:script/code existing-by-id) (:script/code script)))
      (make-info-response "save" script-name
                          {:event-data (cond-> {:script-id script-id :unchanged true}
                                         (some? bulk-id) (assoc :bulk-id bulk-id)
                                         (some? bulk-index) (assoc :bulk-index bulk-index)
                                         (some? bulk-count) (assoc :bulk-count bulk-count))
                           :response-data {:name script-name
                                           :id script-id
                                           :is-update true
                                           :unchanged true}})

      ;; All checks pass - create or update
      :else
      (let [;; Remove transient flags before storing
            clean-script (dissoc script :script/force? :script/bulk-id :script/bulk-index :script/bulk-count)
            ;; For updates: merge with existing, but preserve enabled state from existing
            ;; For creates: default enabled to false for user scripts, true for built-ins
            is-builtin? (script-utils/builtin-script-id? script-id)
            merged-script (if is-update?
                            (-> existing-by-id
                                (merge (dissoc clean-script :script/enabled)))
                            (update clean-script :script/enabled #(if (some? %) % (if is-builtin? true false))))
            ;; Add timestamps
            timestamped-script (if is-update?
                                 (assoc merged-script :script/modified now-iso)
                                 (assoc merged-script
                                        :script/created now-iso
                                        :script/modified now-iso))
            updated-scripts (if is-update?
                              ;; Update existing - replace with merged script
                              (update-script-in-list scripts script-id (constantly timestamped-script))
                              ;; Create new (remove any with same name if force)
                              (let [filtered (if (and force? existing-by-name)
                                               (remove-script-from-list scripts (:script/id existing-by-name))
                                               scripts)]
                                (conj filtered timestamped-script)))]
        (make-success-response updated-scripts "save" script-name
                               {:event-data (cond-> {:script-id script-id}
                                              (some? bulk-id) (assoc :bulk-id bulk-id)
                                              (some? bulk-index) (assoc :bulk-index bulk-index)
                                              (some? bulk-count) (assoc :bulk-count bulk-count))
                                :response-data {:name script-name
                                                :id script-id
                                                :is-update is-update?}})))))
