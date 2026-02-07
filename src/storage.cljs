(ns storage
  "Script storage using chrome.storage.local.
   Provides CRUD operations for userscripts and granted origins.
   Uses in-memory atom mirroring storage for fast access.

   Note: In Squint, keywords become strings and maps are JS objects.
   We use namespaced string keys like \"script/id\" for consistency."
  (:require [log :as log]
            [manifest-parser :as mp]
            [script-utils :as script-utils]))

;; ============================================================
;; State
;; ============================================================

;; Forward declaration for function defined later
(declare sync-builtin-scripts!)

(def ^:private current-schema-version 1)

(def !db
  "In-memory mirror of chrome.storage.local for fast access.
   Synced via persist! and onChanged listener."
  (atom {:storage/schema-version current-schema-version
         :storage/scripts []
         :storage/granted-origins []
         :sponsor/status false
         :sponsor/checked-at nil}))

;; ============================================================
;; Built-in catalog
;; ============================================================



(def ^:private bundled-builtins
  [{:script/id "epupp-builtin-web-userscript-installer"
    :path "userscripts/epupp/web_userscript_installer.cljs"
    :name "epupp/web_userscript_installer.cljs"}
   {:script/id "epupp-builtin-sponsor-check"
    :path "userscripts/epupp/sponsor.cljs"
    :name "epupp/sponsor.cljs"
    :always-enabled? true}])

(defn bundled-builtin-ids
  "Return the list of bundled built-in script IDs."
  []
  (mapv :script/id bundled-builtins))

;; ============================================================
;; Persistence helpers
;; ============================================================

(defn normalize-storage-result
  "Normalize storage data and apply schema migrations."
  [result]
  (let [schema-version (or (.-schemaVersion result) 0)
        old-granted (aget result "granted-origins")
        new-granted (.-grantedOrigins result)
        granted-origins (cond
                          (some? new-granted) (vec new-granted)
                          (some? old-granted) (vec old-granted)
                          :else [])
        scripts (script-utils/parse-scripts (.-scripts result)
                                            {:extract-manifest mp/extract-manifest})
        migrated? (or (< schema-version current-schema-version)
                      (some? old-granted))
        remove-keys (cond-> []
                      (some? old-granted) (conj "granted-origins"))]
    {:storage/schema-version (if migrated? current-schema-version schema-version)
     :storage/scripts scripts
     :storage/granted-origins granted-origins
     :storage/remove-keys remove-keys
     :storage/migrated? migrated?}))

(defn ^:async persist!
  "Write current !db state to chrome.storage.local"
  []
  (let [db @!db
        {:storage/keys [scripts granted-origins schema-version]} db]
    (js-await (js/Promise.
               (fn [resolve]
                 (js/chrome.storage.local.set
                  #js {:schemaVersion schema-version
                       :scripts (clj->js (mapv script-utils/script->js scripts))
                       :grantedOrigins (clj->js granted-origins)
                       :sponsorStatus (:sponsor/status db)
                       :sponsorCheckedAt (:sponsor/checked-at db)}
                  (fn [] (resolve nil))))))))

(defn ^:async load!
  "Load scripts from chrome.storage.local into !db atom.
   Returns a promise that resolves when loaded."
  []
  (let [result (js-await (js/chrome.storage.local.get #js ["schemaVersion" "scripts" "grantedOrigins" "granted-origins" "sponsorStatus" "sponsorCheckedAt"]))
        {:storage/keys [schema-version scripts granted-origins remove-keys migrated?]}
        (normalize-storage-result result)
        sponsor-status (boolean (.-sponsorStatus result))
        sponsor-checked-at (.-sponsorCheckedAt result)]
    (reset! !db {:storage/schema-version schema-version
                 :storage/scripts scripts
                 :storage/granted-origins granted-origins
                 :sponsor/status sponsor-status
                 :sponsor/checked-at sponsor-checked-at})
    (when migrated?
      (js-await (persist!)))
    (when (seq remove-keys)
      (js-await (js/Promise.
                 (fn [resolve]
                   (js/chrome.storage.local.remove
                    (clj->js remove-keys)
                    (fn [] (resolve nil)))))))
    (log/debug "Storage" "Loaded" (count scripts) "scripts")
    @!db))

(defn ^:async init!
  "Initialize storage: load existing data and set up onChanged listener.
   Returns a promise that resolves when storage is loaded.
   Call this once from background worker on startup."
  []
  ;; Listen for changes from other contexts (popup, devtools panel)
  (js/chrome.storage.onChanged.addListener
   (fn [changes area]
     (when (= area "local")
       (when-let [scripts-change (.-scripts changes)]
         (let [new-scripts (script-utils/parse-scripts (.-newValue scripts-change)
                                                       {:extract-manifest mp/extract-manifest})
               bundled-ids (bundled-builtin-ids)
               bundled-ids-set (set bundled-ids)
               missing-builtin? (some (fn [builtin-id]
                                        (not (some #(= (:script/id %) builtin-id) new-scripts)))
                                      bundled-ids)
               stale-builtin? (some (fn [script]
                                      (and (script-utils/builtin-script? script)
                                           (not (contains? bundled-ids-set (:script/id script)))))
                                    new-scripts)]
           (swap! !db assoc :storage/scripts new-scripts)
           ;; Re-sync built-ins if missing or stale (e.g., by storage.clear())
           (when (or missing-builtin? stale-builtin?)
             (sync-builtin-scripts!))))
       (when-let [schema-change (.-schemaVersion changes)]
         (when-let [new-version (.-newValue schema-change)]
           (swap! !db assoc :storage/schema-version new-version)))
       (when-let [origins-change (or (.-grantedOrigins changes)
                                     (aget changes "granted-origins"))]
         (let [new-origins (if (.-newValue origins-change)
                             (vec (.-newValue origins-change))
                             [])]
           (swap! !db assoc :storage/granted-origins new-origins)))
       (when-let [sponsor-change (.-sponsorStatus changes)]
         (swap! !db assoc :sponsor/status (boolean (.-newValue sponsor-change))))
       (when-let [checked-change (.-sponsorCheckedAt changes)]
         (swap! !db assoc :sponsor/checked-at (.-newValue checked-change)))
       (log/debug "Storage" "Updated from external change"))))
  (js-await (load!))
  (js-await (sync-builtin-scripts!))
  @!db)

;; ============================================================
;; Script CRUD
;; ============================================================

(defn get-scripts
  "Get all scripts"
  []
  (:storage/scripts @!db))

(defn get-enabled-scripts
  "Get all enabled scripts"
  []
  (filterv :script/enabled (get-scripts)))

(defn get-script
  "Get script by id"
  [script-id]
  (->> (get-scripts)
       (filter #(= (:script/id %) script-id))
       first))

(defn- normalize-auto-run-match
  "Normalize auto-run match value to vector or nil when absent."
  [match-raw]
  (cond
    (nil? match-raw) nil
    (string? match-raw) (if (empty? match-raw) [] [match-raw])
    (js/Array.isArray match-raw) (vec match-raw)
    :else nil))

(defn- manifest-has-auto-run-key?
  "Check if manifest explicitly includes :epupp/auto-run-match."
  [manifest]
  (some #(= % "epupp/auto-run-match")
        (get manifest "found-keys")))

(defn ^:async save-script!
  "Create or update a script. Extracts metadata from manifest in code.

   Auto-run behavior (Phase 6 - manifest is source of truth):
   - Manifest with :epupp/auto-run-match → use that match
   - Manifest without :epupp/auto-run-match → revoke auto-run (empty match, disabled)
   - No manifest in code → preserve existing match (allows code-only updates)

   New scripts default to disabled for safety (applies to all scripts, including built-ins).

   Source tracking:
   - Optional :script/source field preserved if provided
   - Caller responsibility: panel passes :source/panel, REPL passes :source/repl, etc."
  [script]
  (let [script-id (:script/id script)
        now (.toISOString (js/Date.))
        existing (get-script script-id)
        ;; Extract manifest from code
        code (:script/code script)
        manifest (when code
                   (try (mp/extract-manifest code)
                        (catch :default _ nil)))
        is-builtin? (script-utils/builtin-script? script)
        ;; Manifest-derived fields apply to all scripts; built-ins bypass name validation.
        has-manifest? (some? manifest)
        ;; Extract fields from manifest
        run-at (if has-manifest?
                 (script-utils/normalize-run-at (get manifest "run-at"))
                 (:script/run-at script))
        manifest-name (or (get manifest "raw-script-name")
                          (get manifest "script-name"))
        manifest-description (get manifest "description")
        manifest-inject (or (get manifest "inject") [])
        raw-name (or (and has-manifest? manifest-name)
                     (:script/name script))
        name-error (when (and raw-name (not is-builtin?))
                     (script-utils/validate-script-name raw-name))
        normalized-name (when raw-name
                          (if is-builtin?
                            raw-name
                            (script-utils/normalize-script-name raw-name)))
        ;; CRITICAL: Auto-run match handling (Phase 6)
        ;; When manifest is present, it's the source of truth for match
        ;; Absence of auto-run-match in manifest = explicit revocation
        manifest-match-raw (when has-manifest? (get manifest "auto-run-match"))
        manifest-match (normalize-auto-run-match manifest-match-raw)
        manifest-has-auto-run-key? (when has-manifest?
                                     (manifest-has-auto-run-key? manifest))
        ;; Determine final match:
        ;; - Manifest has auto-run-match key → use it (even if empty)
        ;; - Manifest present but no match key → revoke (empty)
        ;; - No manifest → use caller-provided value, or preserve existing, or empty
        new-match (cond
                    ;; Manifest explicitly sets match (including empty)
                    manifest-has-auto-run-key?
                    (or manifest-match [])
                    ;; Manifest present but no auto-run-match key → revocation
                    has-manifest?
                    []
                    ;; No manifest → use caller-provided, or existing, or empty
                    :else
                    (or (:script/match script)
                        (when existing (:script/match existing))
                        []))
        has-auto-run? (seq new-match)
        ;; All new scripts (user and built-in) default to disabled
        default-enabled false
        new-enabled (cond
                      ;; Always-enabled scripts cannot be disabled
                      (:script/always-enabled? script) true
                      ;; No auto-run = disabled (manual-only script)
                      (not has-auto-run?) false
                      ;; Existing script → preserve enabled state
                      existing (:script/enabled existing)
                      ;; New script → use default (always disabled)
                      :else default-enabled)
        ;; Build the script with manifest-derived fields
        ;; Only override match when manifest is present (manifest is source of truth)
        script-with-manifest (cond-> script
                               (some? run-at) (assoc :script/run-at run-at)
                               (some? normalized-name) (assoc :script/name normalized-name)
                               has-manifest? (assoc :script/match new-match)
                               (and has-manifest? manifest-description) (assoc :script/description manifest-description)
                               (and has-manifest? (seq manifest-inject)) (assoc :script/inject
                                                                                (if (string? manifest-inject)
                                                                                  [manifest-inject]
                                                                                  (vec manifest-inject))))
        ;; Preserve :script/source if provided (Batch A)
        script-with-source (if-let [source (:script/source script)]
                             (assoc script-with-manifest :script/source source)
                             script-with-manifest)
        ;; Final script state
        updated-script (if existing
                         (-> (merge existing script-with-source)
                             (assoc :script/enabled new-enabled)
                             (assoc :script/modified now))
                         (-> script-with-source
                             (assoc :script/created now)
                             (assoc :script/modified now)
                             (assoc :script/enabled new-enabled)))]
    (when name-error
      (throw (js/Error. name-error)))
    (swap! !db update :storage/scripts
           (fn [scripts]
             (if existing
               (mapv #(if (= (:script/id %) script-id) updated-script %) scripts)
               (conj scripts updated-script))))
    (js-await (persist!))
    updated-script))

(defn delete-script!
  "Remove a script by id. Built-in scripts cannot be deleted."
  [script-id]
  (let [script (get-script script-id)]
    (when (and script (not (script-utils/builtin-script? script)))
      (swap! !db update :storage/scripts
             (fn [scripts]
               (filterv #(not= (:script/id %) script-id) scripts)))
      (persist!))))

(defn clear-user-scripts!
  "Remove all user scripts, preserving built-in scripts."
  []
  (swap! !db update :storage/scripts
         (fn [scripts]
           (filterv script-utils/builtin-script? scripts)))
  (persist!))

(defn toggle-script!
  "Toggle a script's enabled state. Always-enabled scripts cannot be toggled."
  [script-id]
  (let [script (get-script script-id)]
    (when-not (:script/always-enabled? script)
      (swap! !db update :storage/scripts
             (fn [scripts]
               (mapv #(if (= (:script/id %) script-id)
                        (update % :script/enabled not)
                        %)
                     scripts)))
      (persist!))))

(defn rename-script!
  "Rename a script (update display name only, ID remains stable)"
  [script-id new-name]
  (let [name-error (script-utils/validate-script-name new-name)
        normalized-name (when new-name (script-utils/normalize-script-name new-name))
        now (.toISOString (js/Date.))]
    (when name-error
      (throw (js/Error. name-error)))
    (swap! !db update :storage/scripts
           (fn [scripts]
             (mapv #(if (= (:script/id %) script-id)
                      (let [existing-script %
                            existing-code (:script/code existing-script)
                            manifest (when existing-code
                                       (try (mp/extract-manifest existing-code)
                                            (catch :default _ nil)))
                            has-script-name? (and manifest
                                                  (get manifest "script-name"))
                            updated-code (if has-script-name?
                                           (mp/update-manifest-script-name existing-code normalized-name)
                                           existing-code)]
                        (cond-> (assoc existing-script
                                       :script/name normalized-name
                                       :script/modified now)
                          has-script-name? (assoc :script/code updated-code)))
                      %)
                   scripts)))
    (persist!)))

;; ============================================================
;; Granted Origins CRUD
;; ============================================================

(defn get-granted-origins
  "Get all granted origins"
  []
  (:storage/granted-origins @!db))

(defn origin-granted?
  "Check if an origin pattern is in granted list"
  [origin]
  (some #(= % origin) (get-granted-origins)))

(defn add-granted-origin!
  "Add an origin to granted list (if not already present)"
  [origin]
  (when-not (origin-granted? origin)
    (swap! !db update :storage/granted-origins conj origin)
    (persist!)))

(defn remove-granted-origin!
  "Remove an origin from granted list"
  [origin]
  (swap! !db update :storage/granted-origins
         (fn [origins]
           (filterv #(not= % origin) origins)))
  (persist!))

;; ============================================================
;; Built-in userscripts
;; ============================================================

(defn stale-builtin-ids
  "Return built-in script IDs that are not bundled with this extension version."
  [scripts bundled-ids]
  (->> scripts
       (filterv (fn [script]
                  (and (script-utils/builtin-script? script)
                       (not (contains? bundled-ids (:script/id script))))))
       (mapv :script/id)))

(defn remove-stale-builtins
  "Remove built-in scripts not bundled with this extension version."
  [scripts bundled-ids]
  (filterv (fn [script]
             (not (and (script-utils/builtin-script? script)
                       (not (contains? bundled-ids (:script/id script))))))
           scripts))

(defn build-bundled-script
  "Build a built-in script map from bundled code and manifest metadata."
  [bundled code]
  (let [manifest (try (mp/extract-manifest code)
                      (catch :default _ nil))
        manifest-name (when manifest
                        (or (get manifest "script-name")
                            (get manifest "raw-script-name")))
        raw-name (or manifest-name (:name bundled))
        run-at (when manifest
                 (script-utils/normalize-run-at (get manifest "run-at")))
        manifest-description (when manifest (get manifest "description"))
        manifest-inject (when manifest (get manifest "inject"))
        inject (cond
                 (nil? manifest-inject) []
                 (string? manifest-inject) [manifest-inject]
                 (js/Array.isArray manifest-inject) (vec manifest-inject)
                 :else [])
        manifest-match (when manifest (normalize-auto-run-match (get manifest "auto-run-match")))
        has-auto-run-key? (when manifest (manifest-has-auto-run-key? manifest))
        match (when manifest (if has-auto-run-key? (or manifest-match []) []))]
    (cond-> {:script/id (:script/id bundled)
             :script/code code
             :script/builtin? true
             :script/source :source/built-in}
      raw-name (assoc :script/name raw-name)
      (some? match) (assoc :script/match match)
      (some? run-at) (assoc :script/run-at run-at)
      manifest-description (assoc :script/description manifest-description)
      (seq inject) (assoc :script/inject inject)
      (:always-enabled? bundled) (assoc :script/always-enabled? true))))

(defn builtin-update-needed?
  "Check whether a built-in script needs to be updated from bundled code."
  [existing desired]
  (or (nil? existing)
      (not= (:script/code existing) (:script/code desired))
      (not= (:script/name existing) (:script/name desired))
      (not= (:script/match existing) (:script/match desired))
      (not= (:script/description existing) (:script/description desired))
      (not= (:script/run-at existing) (:script/run-at desired))
      (not= (:script/inject existing) (:script/inject desired))))

(defn ^:async sync-builtin-scripts!
  "Synchronize built-in scripts with bundled versions.
   Removes stale built-ins and updates bundled ones via save-script!.
   Applies dev sponsor override after sync (if configured)."
  []
  (js-await (load!))
  (let [bundled-ids (set (bundled-builtin-ids))
        scripts (get-scripts)
        stale-ids (stale-builtin-ids scripts bundled-ids)]
    (when (seq stale-ids)
      (swap! !db update :storage/scripts #(remove-stale-builtins % bundled-ids))
      (js-await (persist!))
      (log/debug "Storage" "Removed stale built-ins" stale-ids))
    (doseq [bundled bundled-builtins]
      (try
        (let [response (js-await (js/fetch (js/chrome.runtime.getURL (:path bundled))))
              code (js-await (.text response))
              desired (build-bundled-script bundled code)
              existing (get-script (:script/id bundled))]
          (js-await (save-script! desired))
          (when (builtin-update-needed? existing desired)
            (log/debug "Storage" "Synced built-in" (:script/id bundled))))
        (catch :default err
          (log/error "Storage" "Failed to sync built-in" (:script/id bundled) ":" err))))))

;; ============================================================
;; Sponsor status
;; ============================================================

(def ^:private sponsor-expiry-ms
  "3 months in milliseconds (approximately 90 days)."
  (* 90 24 60 60 1000))

(defn sponsor-active?
  "Returns true when sponsor status is true and was checked within the last 3 months.
   Arity-2 accepts a custom 'now' timestamp for testing."
  ([db] (sponsor-active? db (.now js/Date)))
  ([db now]
   (let [status (:sponsor/status db)
         checked-at (:sponsor/checked-at db)]
     (boolean
      (and status
           (some? checked-at)
           (< (- now checked-at) sponsor-expiry-ms))))))

;; ============================================================
;; Debug: Expose for console testing
;; ============================================================



(set! js/globalThis.storage
      #js {:db !db
           :get_scripts get-scripts
           :get_script get-script
           :save_script_BANG_ save-script!
           :delete_script_BANG_ delete-script!
           :clear_user_scripts_BANG_ clear-user-scripts!
           :toggle_script_BANG_ toggle-script!
           :get_granted_origins get-granted-origins
           :add_granted_origin_BANG_ add-granted-origin!
           :remove_granted_origin_BANG_ remove-granted-origin!})

(defn get-script-by-name
  "Find script by name"
  [script-name]
  (->> (get-scripts)
       (filter #(= (:script/name %) script-name))
       first))
