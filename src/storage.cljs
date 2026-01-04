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
         :storage/granted-origins []}))

;; ============================================================
;; Persistence helpers
;; ============================================================

(defn- persist!
  "Write current !db state to chrome.storage.local"
  []
  (let [{:storage/keys [scripts granted-origins]} @!db]
    (js/chrome.storage.local.set
     #js {:scripts (clj->js (mapv script-utils/script->js scripts))
          :granted-origins (clj->js granted-origins)})))

(defn load!
  "Load scripts from chrome.storage.local into !db atom.
   Returns a promise that resolves when loaded."
  []
  (-> (js/chrome.storage.local.get #js ["scripts" "granted-origins"])
      (.then (fn [result]
               (let [scripts (script-utils/parse-scripts (.-scripts result))
                     granted-origins (if (.-granted-origins result)
                                       (vec (.-granted-origins result))
                                       [])]
                 (reset! !db {:storage/scripts scripts
                              :storage/granted-origins granted-origins})
                 (js/console.log "[Storage] Loaded" (count scripts) "scripts")
                 @!db)))))

(defn init!
  "Initialize storage: load existing data and set up onChanged listener.
   Call this once from background worker on startup."
  []
  (load!)
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
       (js/console.log "[Storage] Updated from external change")))))

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
  "Remove a script by id"
  [script-id]
  (swap! !db update :storage/scripts
         (fn [scripts]
           (filterv #(not= (:script/id %) script-id) scripts)))
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
;; Debug: Expose for console testing
;; ============================================================

(set! js/globalThis.storage
      #js {:db !db
           :get_scripts get-scripts
           :get_script get-script
           :save_script_BANG_ save-script!
           :delete_script_BANG_ delete-script!
           :toggle_script_BANG_ toggle-script!
           :approve_pattern_BANG_ approve-pattern!
           :pattern_approved_QMARK_ pattern-approved?
           :get_granted_origins get-granted-origins
           :add_granted_origin_BANG_ add-granted-origin!
           :remove_granted_origin_BANG_ remove-granted-origin!})
