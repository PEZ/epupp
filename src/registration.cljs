(ns registration
  "Dynamic content script registration for document-start/document-end timing.

   For scripts with early timing (document-start/document-end), we register
   content scripts to inject them before page scripts run. This bypasses
   the webNavigation flow which only fires after page load.

   Browser-specific APIs:
   - Chrome: chrome.scripting.registerContentScripts (persistent)
   - Firefox: browser.contentScripts.register (non-persistent, re-register on startup)
   - Safari: Not supported (manifest-based only)

   Architecture:
   - One registration covers all early scripts
   - The loader (userscript-loader.js) runs at document-start
   - It reads storage, finds matching scripts, injects Scittle + code
   - Registration matches union of all approved patterns from early scripts"
  (:require [log :as log]
            [storage :as storage]))

;; ============================================================
;; Browser Detection
;; ============================================================

(defn firefox?
  "Check if running in Firefox (has browser.contentScripts.register API).
   Firefox-specific API that Chrome doesn't have."
  []
  (and (exists? js/browser)
       (exists? js/browser.contentScripts)
       (exists? js/browser.contentScripts.register)))

(defn chrome?
  "Check if running in Chrome (has registerContentScripts API).
   Only returns true if NOT Firefox (since Firefox has chrome.* compatibility)."
  []
  (and (not (firefox?))
       (exists? js/chrome)
       (exists? js/chrome.scripting)
       (exists? js/chrome.scripting.registerContentScripts)))

;; ============================================================
;; State - Firefox registration reference
;; ============================================================

;; Firefox registrations don't persist and need to be unregistered via returned object
(def !firefox-registration (atom nil))

;; ============================================================
;; Constants
;; ============================================================

(def registration-id
  "Single registration ID for all early-timing scripts (Chrome only)."
  "epupp-early-injection")

;; ============================================================
;; Helper functions
;; ============================================================

(defn needs-early-injection?
  "Check if a script needs early injection via content script registration.
   Only enabled scripts with patterns and document-start or document-end timing need this."
  [script]
  (and (:script/enabled script)
       (seq (:script/match script))
       (let [run-at (:script/run-at script)]
         (or (= run-at "document-start")
             (= run-at "document-end")))))

(defn get-early-scripts
  "Get all scripts that need early injection."
  []
  (filterv needs-early-injection? (storage/get-scripts)))

(defn normalize-pattern-for-chrome
  "Normalize a match pattern to Chrome's registerContentScripts format.
   Our URL matching allows '*' to match all URLs, but Chrome requires '<all_urls>'."
  [pattern]
  (if (= pattern "*")
    "<all_urls>"
    pattern))

(defn collect-patterns
  "Collect all unique URL patterns from early scripts.
   Normalizes patterns to Chrome's registerContentScripts format."
  [scripts]
  (->> scripts
       (mapcat :script/match)
       distinct
       (mapv normalize-pattern-for-chrome)))

;; ============================================================
;; Chrome API wrappers
;; ============================================================

(defn ^:async get-registered-scripts
  "Get all currently registered content scripts (Chrome only)."
  []
  (js-await (js/chrome.scripting.getRegisteredContentScripts)))

(defn ^:async unregister-scripts!
  "Unregister content scripts by IDs (Chrome only)."
  [ids]
  (when (seq ids)
    (js-await (js/chrome.scripting.unregisterContentScripts
               #js {:ids (clj->js ids)}))))

(defn ^:async register-scripts!
  "Register content scripts (Chrome only)."
  [registrations]
  (when (seq registrations)
    (js-await (js/chrome.scripting.registerContentScripts
               (clj->js registrations)))
    (log/info "Registration:Chrome" "Content scripts registered successfully")))

;; ============================================================
;; Firefox API wrappers
;; ============================================================

(defn ^:async unregister-firefox!
  "Unregister the current Firefox registration if it exists."
  []
  (when-let [reg @!firefox-registration]
    (try
      (js-await (.unregister reg))
      (log/info "Registration:Firefox" "Unregistered")
      (catch :default err
        (log/warn "Registration:Firefox" "Unregister failed:" err)))
    (reset! !firefox-registration nil)))

(defn ^:async register-firefox!
  "Register content scripts using Firefox's browser.contentScripts.register API.
   Returns the registration object (needed for unregister)."
  [patterns]
  (when (seq patterns)
    (let [reg (js-await (js/browser.contentScripts.register
                         #js {:matches (clj->js patterns)
                              :js #js [#js {:file "userscript-loader.js"}]
                              :runAt "document_start"}))]
      (log/info "Registration:Firefox" "Registered for patterns:" patterns)
      (reset! !firefox-registration reg)
      reg)))

;; ============================================================
;; Registration logic
;; ============================================================

(defn build-registration
  "Build Chrome registration config for early scripts.
   Returns nil if no patterns to register."
  [patterns]
  (when (seq patterns)
    {:id registration-id
     :matches patterns
     :js ["userscript-loader.js"]
     :runAt "document_start"
     :persistAcrossSessions true}))

(defn ^:async sync-chrome-registrations!
  "Sync Chrome content script registrations with storage state.
   - Unregisters if no early scripts exist
   - Updates registration if patterns changed
   - Creates registration if none exists"
  []
  (let [early-scripts (get-early-scripts)
        target-patterns (collect-patterns early-scripts)
        registered (js-await (get-registered-scripts))
        existing (->> registered
                      (filter #(= (.-id %) registration-id))
                      first)]
    (cond
      ;; No early scripts - unregister if exists
      (empty? target-patterns)
      (when existing
        (log/info "Registration:Chrome" "No early scripts, unregistering")
        (js-await (unregister-scripts! [registration-id])))

      ;; Registration exists - check if patterns changed
      existing
      (let [current-patterns (vec (.-matches existing))]
        (when (not= (set current-patterns) (set target-patterns))
          (log/info "Registration:Chrome" "Patterns changed, updating")
          (log/info "Registration" "Old:" current-patterns)
          (log/info "Registration" "New:" target-patterns)
          ;; Must unregister before re-registering with same ID
          (js-await (unregister-scripts! [registration-id]))
          (js-await (register-scripts! [(build-registration target-patterns)]))))

      ;; No registration exists - create one
      :else
      (do
        (log/info "Registration:Chrome" "Creating registration for patterns:" target-patterns)
        (js-await (register-scripts! [(build-registration target-patterns)]))))))

(defn ^:async sync-firefox-registrations!
  "Sync Firefox content script registrations with storage state.
   Firefox registrations are non-persistent, so we always re-register."
  []
  (let [early-scripts (get-early-scripts)
        target-patterns (collect-patterns early-scripts)]
    ;; Always unregister first (Firefox doesn't support updating)
    (js-await (unregister-firefox!))
    ;; Register if we have patterns
    (if (seq target-patterns)
      (do
        (log/info "Registration:Firefox" "Registering for patterns:" target-patterns)
        (js-await (register-firefox! target-patterns)))
      (log/info "Registration:Firefox" "No early scripts, skipping registration"))))

(defn ^:async sync-registrations!
  "Sync content script registrations with storage state.
   Detects browser and uses appropriate API.
   Check Firefox FIRST because Firefox also has a chrome.* compatibility layer."
  []
  (try
    (cond
      ;; Check Firefox first - it has both browser.* and chrome.* APIs
      (firefox?)
      (js-await (sync-firefox-registrations!))

      (chrome?)
      (js-await (sync-chrome-registrations!))

      :else
      (log/info "Registration" "No dynamic registration API available (Safari?)"))
    (catch :default err
      (log/error "Registration" "Sync failed:" err))))

;; ============================================================
;; Debug: Expose for console testing
;; ============================================================

(set! js/globalThis.registration
      #js {:sync_registrations_BANG_ sync-registrations!
           :get_registered_scripts get-registered-scripts
           :get_early_scripts get-early-scripts
           :collect_patterns collect-patterns
           :needs_early_injection_QMARK_ needs-early-injection?
           :chrome_QMARK_ chrome?
           :firefox_QMARK_ firefox?})
