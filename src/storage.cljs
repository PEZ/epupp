(ns storage
  "Script storage using chrome.storage.local.
   Provides CRUD operations for userscripts and granted origins.
   Uses in-memory atom mirroring storage for fast access.

   Note: In Squint, keywords become strings and maps are JS objects.
   We use namespaced string keys like \"script/id\" for consistency."
  (:require [builtin-scripts :as builtins]
            [log :as log]
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
   Synced via persist! and the onChanged listener, which applies writes
   made by other extension contexts."
  (atom {:storage/schema-version current-schema-version
         :storage/scripts []
         :storage/granted-origins []
         :storage/ext-dep-cache {}
         :sponsor/status false
         :sponsor/checked-at nil}))

(def ^:private !syncing-builtins?
  "Guard flag to prevent cascading re-syncs from onChanged during sync-builtin-scripts!.
   Each save-script! triggers onChanged which checks for missing builtins - without this guard,
   saving builtin 1 of 3 would trigger a re-sync because builtins 2-3 appear missing."
  (atom false))

(def ^:private write-id-prefix
  "Starts every write id that persist! stores from this context.
   onChanged can deliver the echo of an earlier write after !db has moved on.
   Applying that echo would drop scripts added since, and the next persist!
   would store the shorter list. The listener skips events with this prefix."
  (str (js/crypto.randomUUID) ":"))

(def ^:private !write-count (atom 0))

(defn- next-write-id! []
  (str write-id-prefix (swap! !write-count inc)))

(defn- own-write-echo? [changes]
  (let [write-id (some-> (.-writeId changes) .-newValue)]
    (and (string? write-id)
         (.startsWith write-id write-id-prefix))))

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
                  #js {:writeId (next-write-id!)
                       :schemaVersion schema-version
                       :scripts (clj->js (mapv script-utils/script->js scripts))
                       :grantedOrigins (clj->js granted-origins)
                       :sponsorStatus (:sponsor/status db)
                       :sponsorCheckedAt (:sponsor/checked-at db)}
                  (fn [] (resolve nil))))))))

(defn ^:async persist-ext-dep-cache!
  "Write only the given ext-dep cache to chrome.storage.local."
  [cache]
  (js-await (js/Promise.
             (fn [resolve]
               (js/chrome.storage.local.set
                #js {:extDepCache (clj->js cache)}
                (fn [] (resolve nil)))))))

(defn ^:async load!
  "Load scripts from chrome.storage.local into !db atom.
   Returns a promise that resolves when loaded."
  []
  (let [result (js-await (js/chrome.storage.local.get #js ["schemaVersion" "scripts" "grantedOrigins" "granted-origins" "sponsorStatus" "sponsorCheckedAt" "extDepCache"]))
        {:storage/keys [schema-version scripts granted-origins remove-keys migrated?]}
        (normalize-storage-result result)
        sponsor-status (boolean (.-sponsorStatus result))
        sponsor-checked-at (.-sponsorCheckedAt result)
        ext-dep-cache (or (.-extDepCache result) {})]
    (reset! !db {:storage/schema-version schema-version
                 :storage/scripts scripts
                 :storage/granted-origins granted-origins
                 :storage/ext-dep-cache ext-dep-cache
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

(defn- handle-scripts-change [scripts-change]
  (let [new-scripts (script-utils/parse-scripts (.-newValue scripts-change)
                                                {:extract-manifest mp/extract-manifest})
        bundled-ids (builtins/bundled-builtin-ids)
        bundled-ids-set (set bundled-ids)
        missing-builtin? (some (fn [builtin-id]
                                 (not (some #(= (:script/id %) builtin-id) new-scripts)))
                               bundled-ids)
        stale-builtin? (some (fn [script]
                               (and (script-utils/builtin-script? script)
                                    (not (contains? bundled-ids-set (:script/id script)))))
                             new-scripts)
        needs-resync? (and (not @!syncing-builtins?)
                           (or missing-builtin? stale-builtin?))]
    (swap! !db assoc :storage/scripts new-scripts)
    (when needs-resync?
      (sync-builtin-scripts!))))

(defn- handle-schema-change [changes]
  (when-let [schema-change (.-schemaVersion changes)]
    (when-let [new-version (.-newValue schema-change)]
      (swap! !db assoc :storage/schema-version new-version))))

(defn- handle-origins-change [changes]
  (when-let [origins-change (or (.-grantedOrigins changes)
                                (aget changes "granted-origins"))]
    (let [new-origins (if (.-newValue origins-change)
                        (vec (.-newValue origins-change))
                        [])]
      (swap! !db assoc :storage/granted-origins new-origins))))

(defn- handle-ext-dep-cache-change [changes]
  (when-let [ext-dep-cache-change (.-extDepCache changes)]
    (let [new-cache (or (.-newValue ext-dep-cache-change) {})]
      (swap! !db assoc :storage/ext-dep-cache new-cache))))

(defn- handle-sponsor-changes [changes]
  (when-let [sponsor-change (.-sponsorStatus changes)]
    (swap! !db assoc :sponsor/status (boolean (.-newValue sponsor-change))))
  (when-let [checked-change (.-sponsorCheckedAt changes)]
    (swap! !db assoc :sponsor/checked-at (.-newValue checked-change))))

(defn- handle-storage-change [changes area]
  (when (and (= area "local")
             (not (own-write-echo? changes)))
    (when-let [scripts-change (.-scripts changes)]
      (handle-scripts-change scripts-change))
    (handle-schema-change changes)
    (handle-origins-change changes)
    (handle-ext-dep-cache-change changes)
    (handle-sponsor-changes changes)
    (log/debug "Storage" "Updated from external change")))

(defn ^:async init!
  "Initialize storage: load existing data and set up onChanged listener.
   Returns a promise that resolves when storage is loaded.
   Call this once from background worker on startup."
  []
  (js/chrome.storage.onChanged.addListener handle-storage-change)
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

(defn get-script
  "Get script by id"
  [script-id]
  (->> (get-scripts)
       (filter #(= (:script/id %) script-id))
       first))

(defn- update-script-list [scripts script-id updated-script existing?]
  (if existing?
    (mapv #(if (= (:script/id %) script-id) updated-script %) scripts)
    (conj scripts updated-script)))

(defn- try-extract-manifest [script]
  (when (:script/code script)
    (try (mp/extract-manifest (:script/code script))
         (catch :default _ nil))))

(defn- throw-if-error! [result]
  (when (:error result)
    (throw (js/Error. (:error result)))))

(defn ^:async save-script!
  "Create or update a script. Extracts metadata from manifest in code.
   Delegates to normalize-and-merge-script for shared normalization/merge logic.

   New scripts default to disabled for safety (applies to all scripts, including built-ins).

   Source tracking:
   - Optional :script/source field preserved if provided
   - Caller responsibility: panel passes :source/panel, REPL passes :source/repl, etc."
  [script]
  (let [script-id (:script/id script)
        now (.toISOString (js/Date.))
        existing (get-script script-id)
        manifest (try-extract-manifest script)
        is-builtin? (script-utils/builtin-script? script)
        result (script-utils/normalize-and-merge-script
                script existing manifest
                {:is-builtin? is-builtin?
                 :now-iso now})
        _ (throw-if-error! result)
        updated-script (:script result)]
    (swap! !db update :storage/scripts
           update-script-list script-id updated-script (some? existing))
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

(defn ^:async import-scripts!
  "Replace all user scripts with the scripts in an exported JS array."
  [js-scripts]
  (when-not (js/Array.isArray js-scripts)
    (throw (js/Error. "The import file must contain a list of scripts")))
  (let [imported (script-utils/parse-scripts js-scripts {:extract-manifest mp/extract-manifest})]
    (swap! !db update :storage/scripts builtins/merge-imported-scripts imported)
    (js-await (persist!))))

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
;; External Dependency Cache
;; ============================================================

(defn get-ext-dep-cache
  "Get the full external dependency cache map."
  []
  (:storage/ext-dep-cache @!db))

;; ============================================================
;; Built-in userscripts
;; ============================================================

(defn- ^:async sync-single-builtin! [bundled]
  (let [response (js-await (js/fetch (js/chrome.runtime.getURL (:path bundled))))]
    (when-not (.-ok response)
      (throw (js/Error. (str "Failed to fetch built-in script "
                             (:script/id bundled)
                             " ("
                             (:path bundled)
                             ") status "
                             (.-status response)))))
    (let [code (js-await (.text response))
          desired (builtins/build-bundled-script bundled code)
          existing (get-script (:script/id bundled))]
      (js-await (save-script! desired))
      (when (builtins/builtin-update-needed? existing desired)
        (log/debug "Storage" "Synced built-in" (:script/id bundled))))))

(defn ^:async sync-builtin-scripts!
  "Synchronize built-in scripts with bundled versions.
   Removes stale built-ins and updates bundled ones via save-script!.
   Applies dev sponsor override after sync (if configured).
   Uses !syncing-builtins? guard to prevent cascading re-syncs from onChanged."
  []
  (reset! !syncing-builtins? true)
  (try
    (js-await (load!))
    (let [bundled-ids (set (builtins/bundled-builtin-ids))
          scripts (get-scripts)
          stale-ids (builtins/stale-builtin-ids scripts bundled-ids)]
      (when (seq stale-ids)
        (swap! !db update :storage/scripts #(builtins/remove-stale-builtins % bundled-ids))
        (js-await (persist!))
        (log/debug "Storage" "Removed stale built-ins" stale-ids))
      (doseq [bundled builtins/bundled-builtins]
        (try
          (js-await (sync-single-builtin! bundled))
          (catch :default err
            (log/error "Storage" "Failed to sync built-in" (:script/id bundled) ":" err)))))
    (finally
      (reset! !syncing-builtins? false))))

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



(when (.-dev js/EXTENSION_CONFIG)
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
             :remove_granted_origin_BANG_ remove-granted-origin!}))

(defn get-script-by-name
  "Find script by name"
  [script-name]
  (->> (get-scripts)
       (filter #(= (:script/name %) script-name))
       first))
