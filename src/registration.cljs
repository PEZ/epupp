(ns registration
  "Dynamic content script registration for document-start/document-end timing.

   For scripts with early timing (document-start/document-end), we use Chrome's
   registerContentScripts API to inject them before page scripts run. This bypasses
   the webNavigation flow which only fires after page load.

   Architecture:
   - One registration 'epupp-early-injection' covers all early scripts
   - The loader (userscript-loader.js) runs at document-start
   - It reads storage, finds matching scripts, injects Scittle + code
   - Registration matches union of all approved patterns from early scripts"
  (:require [storage :as storage]))

;; ============================================================
;; Constants
;; ============================================================

(def registration-id
  "Single registration ID for all early-timing scripts."
  "epupp-early-injection")

;; ============================================================
;; Helper functions
;; ============================================================

(defn needs-early-injection?
  "Check if a script needs early injection via content script registration.
   Only enabled scripts with document-start or document-end timing need this."
  [script]
  (and (:script/enabled script)
       (seq (:script/approved-patterns script))
       (let [run-at (:script/run-at script)]
         (or (= run-at "document-start")
             (= run-at "document-end")))))

(defn get-early-scripts
  "Get all scripts that need early injection."
  []
  (filterv needs-early-injection? (storage/get-scripts)))

(defn collect-approved-patterns
  "Collect all unique approved patterns from early scripts."
  [scripts]
  (->> scripts
       (mapcat :script/approved-patterns)
       distinct
       vec))

;; ============================================================
;; Chrome API wrappers
;; ============================================================

(defn ^:async get-registered-scripts
  "Get all currently registered content scripts."
  []
  (js-await (js/chrome.scripting.getRegisteredContentScripts)))

(defn ^:async unregister-scripts!
  "Unregister content scripts by IDs."
  [ids]
  (when (seq ids)
    (js-await (js/chrome.scripting.unregisterContentScripts
               #js {:ids (clj->js ids)}))))

(defn ^:async register-scripts!
  "Register content scripts."
  [registrations]
  (when (seq registrations)
    (js-await (js/chrome.scripting.registerContentScripts
               (clj->js registrations)))))

;; ============================================================
;; Registration logic
;; ============================================================

(defn build-registration
  "Build registration config for early scripts.
   Returns nil if no patterns to register."
  [patterns]
  (when (seq patterns)
    {:id registration-id
     :matches patterns
     :js ["userscript-loader.js"]
     :runAt "document_start"
     :persistAcrossSessions true}))

(defn ^:async sync-registrations!
  "Sync Chrome content script registrations with storage state.
   - Unregisters if no early scripts exist
   - Updates registration if patterns changed
   - Creates registration if none exists"
  []
  (try
    (let [early-scripts (get-early-scripts)
          target-patterns (collect-approved-patterns early-scripts)
          registered (js-await (get-registered-scripts))
          existing (->> registered
                        (filter #(= (.-id %) registration-id))
                        first)]
      (cond
        ;; No early scripts - unregister if exists
        (empty? target-patterns)
        (when existing
          (js/console.log "[Registration] No early scripts, unregistering")
          (js-await (unregister-scripts! [registration-id])))

        ;; Registration exists - check if patterns changed
        existing
        (let [current-patterns (vec (.-matches existing))]
          (when (not= (set current-patterns) (set target-patterns))
            (js/console.log "[Registration] Patterns changed, updating registration")
            (js/console.log "[Registration] Old:" current-patterns)
            (js/console.log "[Registration] New:" target-patterns)
            ;; Must unregister before re-registering with same ID
            (js-await (unregister-scripts! [registration-id]))
            (js-await (register-scripts! [(build-registration target-patterns)]))))

        ;; No registration exists - create one
        :else
        (do
          (js/console.log "[Registration] Creating registration for patterns:" target-patterns)
          (js-await (register-scripts! [(build-registration target-patterns)])))))
    (catch :default err
      (js/console.error "[Registration] Sync failed:" err))))

;; ============================================================
;; Debug: Expose for console testing
;; ============================================================

(set! js/globalThis.registration
      #js {:sync_registrations_BANG_ sync-registrations!
           :get_registered_scripts get-registered-scripts
           :get_early_scripts get-early-scripts
           :collect_approved_patterns collect-approved-patterns
           :needs_early_injection_QMARK_ needs-early-injection?})
