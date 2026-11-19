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
;; Built-in catalog
;; ============================================================



(def ^:private base-builtins
  [{:script/id "epupp-builtin-internal-helpers"
    :path "userscripts/epupp/internal/helpers.cljs"
    :name "epupp/internal/helpers.cljs"
    :always-enabled? true}
   {:script/id "epupp-builtin-ui"
    :path "userscripts/epupp/ui.cljs"
    :name "epupp/ui.cljs"
    :always-enabled? true}
   {:script/id "epupp-builtin-storage"
    :path "userscripts/epupp/storage.cljs"
    :name "epupp/storage.cljs"
    :always-enabled? true}
   {:script/id "epupp-builtin-tools"
    :path "userscripts/epupp/tools.cljs"
    :name "epupp/tools.cljs"
    :always-enabled? true}
   {:script/id "epupp-builtin-web-userscript-installer"
    :path "userscripts/epupp/web_userscript_installer.cljs"
    :name "epupp/web_userscript_installer.cljs"
    :special? true
    :web-installer-scan true}
   {:script/id "epupp-builtin-sponsor-check"
    :path "userscripts/epupp/sponsor.cljs"
    :name "epupp/sponsor.cljs"
    :always-enabled? true}])

(def ^:private security-probe-builtin
  {:script/id "epupp-builtin-security-probe"
   :path "userscripts/epupp/security_probe.cljs"
   :name "epupp/security_probe.cljs"})

(def ^:private bundled-builtins
  (if (.-dev js/EXTENSION_CONFIG)
    (conj base-builtins security-probe-builtin)
    base-builtins))

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
        bundled-ids (bundled-builtin-ids)
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

(defn merge-imported-scripts
  "Return the imported scripts followed by the built-ins from scripts.
   Imported entries with built-in ids are skipped, so an export file
   cannot replace bundled code."
  [scripts imported]
  (into (filterv #(not (some-> (:script/id %) (.startsWith "epupp-builtin-")))
                 imported)
        (filter script-utils/builtin-script? scripts)))

(defn ^:async import-scripts!
  "Replace all user scripts with the scripts in an exported JS array."
  [js-scripts]
  (when-not (js/Array.isArray js-scripts)
    (throw (js/Error. "The import file must contain a list of scripts")))
  (let [imported (script-utils/parse-scripts js-scripts {:extract-manifest mp/extract-manifest})]
    (swap! !db update :storage/scripts merge-imported-scripts imported)
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

(defn- normalize-inject [inject-raw]
  (cond
    (nil? inject-raw) []
    (string? inject-raw) [inject-raw]
    (js/Array.isArray inject-raw) (vec inject-raw)
    :else []))

(defn- extract-bundled-manifest-data [manifest]
  (when manifest
    (let [has-auto-run-key? (manifest-has-auto-run-key? manifest)
          raw-match (normalize-auto-run-match (get manifest "auto-run-match"))]
      {:manifest/name (or (get manifest "script-name")
                          (get manifest "raw-script-name"))
       :manifest/run-at (script-utils/normalize-run-at (get manifest "run-at"))
       :manifest/description (get manifest "description")
       :manifest/inject (normalize-inject (get manifest "inject"))
       :manifest/match (if has-auto-run-key? (or raw-match []) [])})))

(defn build-bundled-script
  "Build a built-in script map from bundled code and manifest metadata."
  [bundled code]
  (let [manifest (try (mp/extract-manifest code)
                      (catch :default _ nil))
        mdata (extract-bundled-manifest-data manifest)
        raw-name (or (:manifest/name mdata) (:name bundled))
        run-at (:manifest/run-at mdata)
        description (:manifest/description mdata)
        inject (or (:manifest/inject mdata) [])
        match (:manifest/match mdata)]
    (cond-> {:script/id (:script/id bundled)
             :script/code code
             :script/builtin? true
             :script/source :source/built-in}
      raw-name (assoc :script/name raw-name)
      (some? match) (assoc :script/match match)
      (some? run-at) (assoc :script/run-at run-at)
      description (assoc :script/description description)
      (seq inject) (assoc :script/inject inject)
      (:always-enabled? bundled) (assoc :script/always-enabled? true)
      (:special? bundled) (assoc :script/special? true)
      (:web-installer-scan bundled) (assoc :script/web-installer-scan true))))

(defn builtin-update-needed?
  "Check whether a built-in script needs to be updated from bundled code."
  [existing desired]
  (or (nil? existing)
      (not= (:script/code existing) (:script/code desired))
      (not= (:script/name existing) (:script/name desired))
      (not= (:script/match existing) (:script/match desired))
      (not= (:script/description existing) (:script/description desired))
      (not= (:script/run-at existing) (:script/run-at desired))
      (not= (:script/inject existing) (:script/inject desired))
      (not= (:script/special? existing) (:script/special? desired))
      (not= (:script/web-installer-scan existing) (:script/web-installer-scan desired))))

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
          desired (build-bundled-script bundled code)
          existing (get-script (:script/id bundled))]
      (js-await (save-script! desired))
      (when (builtin-update-needed? existing desired)
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
    (let [bundled-ids (set (bundled-builtin-ids))
          scripts (get-scripts)
          stale-ids (stale-builtin-ids scripts bundled-ids)]
      (when (seq stale-ids)
        (swap! !db update :storage/scripts #(remove-stale-builtins % bundled-ids))
        (js-await (persist!))
        (log/debug "Storage" "Removed stale built-ins" stale-ids))
      (doseq [bundled bundled-builtins]
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
