(ns storage
  "Script storage using chrome.storage.local.
   Provides CRUD operations for userscripts and granted origins.
   Uses in-memory atom mirroring storage for fast access.

   Note: In Squint, keywords become strings and maps are JS objects.
   We use namespaced string keys like \"script/id\" for consistency."
  (:require [script-utils :as script-utils]))

;; ============================================================
;; State
;; ============================================================

(def !db
  "In-memory mirror of chrome.storage.local for fast access.
   Synced via persist! and onChanged listener."
  (atom {:storage/scripts []
         :storage/granted-origins []
         :storage/user-allowed-origins []}))

;; ============================================================
;; Persistence helpers
;; ============================================================

(defn- persist!
  "Write current !db state to chrome.storage.local"
  []
  (let [{:storage/keys [scripts granted-origins user-allowed-origins]} @!db]
    (js/chrome.storage.local.set
     #js {:scripts (clj->js (mapv script-utils/script->js scripts))
          :granted-origins (clj->js granted-origins)
          :userAllowedOrigins (clj->js user-allowed-origins)})))

(defn ^:async load!
  "Load scripts from chrome.storage.local into !db atom.
   Returns a promise that resolves when loaded."
  []
  (let [result (js-await (js/chrome.storage.local.get #js ["scripts" "granted-origins" "userAllowedOrigins"]))
        scripts (script-utils/parse-scripts (.-scripts result))
        granted-origins (if (.-granted-origins result)
                          (vec (.-granted-origins result))
                          [])
        user-allowed-origins (if (.-userAllowedOrigins result)
                               (vec (.-userAllowedOrigins result))
                               [])]
    (reset! !db {:storage/scripts scripts
                 :storage/granted-origins granted-origins
                 :storage/user-allowed-origins user-allowed-origins})
    (js/console.log "[Storage] Loaded" (count scripts) "scripts")
    @!db))

(defn init!
  "Initialize storage: load existing data and set up onChanged listener.
   Returns a promise that resolves when storage is loaded.
   Call this once from background worker on startup."
  []
  ;; Listen for changes from other contexts (popup, devtools panel)
  (js/chrome.storage.onChanged.addListener
   (fn [changes area]
     (when (= area "local")
       (when-let [scripts-change (.-scripts changes)]
         (let [new-scripts (script-utils/parse-scripts (.-newValue scripts-change))]
           (swap! !db assoc :storage/scripts new-scripts)))
       (when-let [origins-change (.-granted-origins changes)]
         (let [new-origins (if (.-newValue origins-change)
                             (vec (.-newValue origins-change))
                             [])]
           (swap! !db assoc :storage/granted-origins new-origins)))
       (when-let [user-origins-change (.-userAllowedOrigins changes)]
         (let [new-user-origins (if (.-newValue user-origins-change)
                                  (vec (.-newValue user-origins-change))
                                  [])]
           (swap! !db assoc :storage/user-allowed-origins new-user-origins)))
       (js/console.log "[Storage] Updated from external change"))))
  ;; Return promise from load! so caller can await initialization
  (load!))

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

(defn save-script!
  "Create or update a script. Merges with existing if id matches."
  [script]
  (let [script-id (:script/id script)
        now (.toISOString (js/Date.))
        existing (get-script script-id)
        updated-script (if existing
                         (-> (merge existing script)
                             (assoc :script/modified now))
                         (-> script
                             (assoc :script/created now)
                             (assoc :script/modified now)
                             (update :script/enabled #(if (some? %) % true))))]
    (swap! !db update :storage/scripts
           (fn [scripts]
             (if existing
               (mapv #(if (= (:script/id %) script-id) updated-script %) scripts)
               (conj scripts updated-script))))
    (persist!)
    updated-script))

(defn delete-script!
  "Remove a script by id. Built-in scripts cannot be deleted."
  [script-id]
  (when-not (script-utils/builtin-script-id? script-id)
    (swap! !db update :storage/scripts
           (fn [scripts]
             (filterv #(not= (:script/id %) script-id) scripts)))
    (persist!)))

(defn clear-user-scripts!
  "Remove all user scripts, preserving built-in scripts."
  []
  (swap! !db update :storage/scripts
         (fn [scripts]
           (filterv script-utils/builtin-script? scripts)))
  (persist!))

(defn toggle-script!
  "Toggle a script's enabled state"
  [script-id]
  (swap! !db update :storage/scripts
         (fn [scripts]
           (mapv #(if (= (:script/id %) script-id)
                    (update % :script/enabled not)
                    %)
                 scripts)))
  (persist!))

(defn rename-script!
  "Rename a script (update display name only, ID remains stable)"
  [script-id new-name]
  (let [now (.toISOString (js/Date.))]
    (swap! !db update :storage/scripts
           (fn [scripts]
             (mapv #(if (= (:script/id %) script-id)
                      (assoc %
                             :script/name new-name
                             :script/modified now)
                      %)
                   scripts)))
    (persist!)))

;; ============================================================
;; Granted Origins CRUD
;; ============================================================

(defn approve-pattern!
  "Add a pattern to a script's approved-patterns list"
  [script-id pattern]
  (swap! !db update :storage/scripts
         (fn [scripts]
           (mapv (fn [s]
                   (if (= (:script/id s) script-id)
                     (update s :script/approved-patterns
                             (fn [patterns]
                               (let [patterns (or patterns [])]
                                 (if (some #(= % pattern) patterns)
                                   patterns
                                   (conj patterns pattern)))))
                     s))
                 scripts)))
  (persist!))

(defn pattern-approved?
  "Check if a pattern is approved for a script"
  [script pattern]
  (some #(= % pattern) (:script/approved-patterns script)))

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
;; User Allowed Origins CRUD
;; ============================================================

(defn get-user-allowed-origins
  "Get user-added allowed script origins"
  []
  (:storage/user-allowed-origins @!db))

(defn user-origin-exists?
  "Check if an origin is already in user allowed origins list"
  [origin]
  (some #(= % origin) (get-user-allowed-origins)))

(defn add-user-allowed-origin!
  "Add an origin to user allowed origins list (if not already present)"
  [origin]
  (when-not (user-origin-exists? origin)
    (swap! !db update :storage/user-allowed-origins conj origin)
    (persist!)))

(defn remove-user-allowed-origin!
  "Remove an origin from user allowed origins list"
  [origin]
  (swap! !db update :storage/user-allowed-origins
         (fn [origins]
           (filterv #(not= % origin) origins)))
  (persist!))

;; ============================================================
;; Built-in userscripts
;; ============================================================

(defn ^:async ensure-gist-installer!
  "Ensure the gist installer built-in userscript exists in storage.
   Loads it from userscripts/gist_installer.cljs and updates if code changed."
  []
  (let [installer-id "scittle-tamper-builtin-gist-installer"]
    (try
      (let [response (js-await (js/fetch (js/chrome.runtime.getURL "userscripts/gist_installer.cljs")))
            code (js-await (.text response))
            existing (get-script installer-id)]
        ;; Install if missing, or update if code changed
        (when (or (not existing)
                  (not= (:script/code existing) code))
          (save-script! {:script/id installer-id
                         :script/name "GitHub Gist Installer (Built-in)"
                         :script/match ["https://gist.github.com/*"]
                         :script/code code
                         :script/enabled true
                         :script/approved-patterns ["https://gist.github.com/*"]})
          (js/console.log "[Storage] Installed/updated built-in gist installer")))
      (catch :default err
        (js/console.error "[Storage] Failed to load gist installer:" err)))))

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
           :approve_pattern_BANG_ approve-pattern!
           :pattern_approved_QMARK_ pattern-approved?
           :get_granted_origins get-granted-origins
           :add_granted_origin_BANG_ add-granted-origin!
           :remove_granted_origin_BANG_ remove-granted-origin!
           :get_user_allowed_origins get-user-allowed-origins
           :add_user_allowed_origin_BANG_ add-user-allowed-origin!
           :remove_user_allowed_origin_BANG_ remove-user-allowed-origin!})
