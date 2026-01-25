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
(declare ensure-gist-installer!)

(def !db
  "In-memory mirror of chrome.storage.local for fast access.
   Synced via persist! and onChanged listener."
  (atom {:storage/scripts []
         :storage/granted-origins []
         :storage/user-allowed-origins []}))

;; ============================================================
;; Persistence helpers
;; ============================================================

(defn persist!
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
    (log/info "Storage" nil "Loaded" (count scripts) "scripts")
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
           (swap! !db assoc :storage/scripts new-scripts)
           ;; Re-ensure built-in scripts if they were removed (e.g., by storage.clear())
           (when-not (some #(= (:script/id %) "epupp-builtin-gist-installer") new-scripts)
             (ensure-gist-installer!))))
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
       (log/info "Storage" nil "Updated from external change"))))
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
  "Create or update a script. Extracts metadata from manifest in code.

   Auto-run behavior (Phase 6 - manifest is source of truth):
   - Manifest with :epupp/auto-run-match → use that match
   - Manifest without :epupp/auto-run-match → revoke auto-run (empty match, disabled)
   - No manifest in code → preserve existing match (allows code-only updates)

   New scripts default to disabled for safety (built-in scripts always enabled)."
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
           ;; Built-in scripts keep their explicit metadata; manifest does not override it.
           has-manifest? (and (some? manifest) (not is-builtin?))
           ;; Extract fields from manifest
           run-at (if has-manifest?
              (script-utils/normalize-run-at (get manifest "run-at"))
              (:script/run-at script))
           manifest-name (get manifest "script-name")
           manifest-description (get manifest "description")
           manifest-inject (or (get manifest "inject") [])
        ;; CRITICAL: Auto-run match handling (Phase 6)
        ;; When manifest is present, it's the source of truth for match
        ;; Absence of auto-run-match in manifest = explicit revocation
        manifest-match-raw (when has-manifest? (get manifest "auto-run-match"))
        ;; Normalize match to vector
        manifest-match (cond
                         (nil? manifest-match-raw) nil  ; key not present
                         (string? manifest-match-raw) (if (empty? manifest-match-raw) [] [manifest-match-raw])
                         (js/Array.isArray manifest-match-raw) (vec manifest-match-raw)
                         :else nil)
        ;; Check if manifest explicitly declared auto-run-match
        ;; (found-keys tracks all epupp/* keys in the manifest)
        manifest-has-auto-run-key? (when has-manifest?
                                     (some #(= % "epupp/auto-run-match")
                                           (get manifest "found-keys")))
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
        ;; Determine enabled state
        default-enabled (if is-builtin? true false)
        new-enabled (cond
                      ;; No auto-run = disabled (manual-only script)
                      (not has-auto-run?) false
                      ;; Existing script → preserve enabled state
                      existing (:script/enabled existing)
                      ;; New script → use default
                      :else default-enabled)
        ;; Build the script with manifest-derived fields
        ;; Only override match when manifest is present (manifest is source of truth)
        script-with-manifest (cond-> script
                               (some? run-at) (assoc :script/run-at run-at)
                               has-manifest? (assoc :script/match new-match)
                               (and has-manifest? manifest-name) (assoc :script/name manifest-name)
                               (and has-manifest? manifest-description) (assoc :script/description manifest-description)
                               (and has-manifest? (seq manifest-inject)) (assoc :script/inject
                                                                                (if (string? manifest-inject)
                                                                                  [manifest-inject]
                                                                                  (vec manifest-inject))))
        ;; Final script state
        updated-script (if existing
                         (-> (merge existing script-with-manifest)
                             (assoc :script/enabled new-enabled)
                             (assoc :script/modified now))
                         (-> script-with-manifest
                             (assoc :script/created now)
                             (assoc :script/modified now)
                             (assoc :script/enabled new-enabled)))]
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
   Loads it from userscripts/gist_installer.cljs and updates if code changed.
   Parses manifest to extract :epupp/inject for library dependencies."
  []
  (let [installer-id "epupp-builtin-gist-installer"]
    (try
      (let [response (js-await (js/fetch (js/chrome.runtime.getURL "userscripts/gist_installer.cljs")))
            code (js-await (.text response))
            manifest (mp/extract-manifest code)
            ;; manifest-parser returns string keys like "inject", not :epupp/inject
            injects (or (get manifest "inject") [])
            existing (get-script installer-id)]
        ;; Install if missing, or update if code changed
        (when (or (not existing)
                  (not= (:script/code existing) code))
          (save-script! {:script/id installer-id
                         :script/name "GitHub Gist Installer (Built-in)"
                         :script/match ["https://gist.github.com/*"
                                        "http://localhost:18080/mock-gist.html"]
                         :script/code code
                         :script/enabled true
                         :script/inject injects
                         :script/builtin? true})
          (log/info "Storage" nil "Installed/updated built-in gist installer")))
      (catch :default err
        (log/error "Storage" nil "Failed to load gist installer:" err)))))

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
           :remove_granted_origin_BANG_ remove-granted-origin!
           :get_user_allowed_origins get-user-allowed-origins
           :add_user_allowed_origin_BANG_ add-user-allowed-origin!
           :remove_user_allowed_origin_BANG_ remove-user-allowed-origin!})

(defn get-script-by-name
  "Find script by name"
  [script-name]
  (->> (get-scripts)
       (filter #(= (:script/name %) script-name))
       first))
