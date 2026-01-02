(ns permissions
  "Runtime permission management for userscript origins.
   Uses chrome.permissions API for requesting host access.
   Syncs with storage to track granted origins."
  (:require [storage :as storage]))

;; ============================================================
;; Permission checking
;; ============================================================

(defn has-all-urls?
  "Check if we have the <all_urls> permission (covers everything).
   Returns a promise."
  []
  (js/chrome.permissions.contains
   #js {:origins #js ["<all_urls>"]}))

(defn has-origin?
  "Check if we have permission for an origin pattern.
   Returns a promise that resolves to boolean."
  [origin]
  (-> (has-all-urls?)
      (.then (fn [has-all]
               (if has-all
                 true
                 (js/chrome.permissions.contains
                  #js {:origins #js [origin]}))))))

(defn has-all-origins?
  "Check if we have permission for all given origin patterns.
   Returns a promise that resolves to boolean."
  [origins]
  (if (empty? origins)
    (js/Promise.resolve true)
    ;; First check if we have <all_urls> which covers everything
    (-> (has-all-urls?)
        (.then (fn [has-all]
                 (if has-all
                   true
                   (js/chrome.permissions.contains
                    #js {:origins (clj->js origins)})))))))

;; ============================================================
;; Permission requests
;; ============================================================

(defn request-origin!
  "Request permission for an origin pattern.
   Returns a promise that resolves to boolean (granted/denied).
   On grant, adds to storage."
  [origin]
  (-> (js/chrome.permissions.request
       #js {:origins #js [origin]})
      (.then (fn [granted]
               (when granted
                 (storage/add-granted-origin! origin))
               granted))))

(defn request-origins!
  "Request permission for multiple origin patterns.
   Returns a promise that resolves to boolean (all granted/any denied).
   On grant, adds all to storage."
  [origins]
  (if (empty? origins)
    (js/Promise.resolve true)
    (-> (js/chrome.permissions.request
         #js {:origins (clj->js origins)})
        (.then (fn [granted]
                 (when granted
                   (doseq [origin origins]
                     (storage/add-granted-origin! origin)))
                 granted)))))

(defn request-missing-origins!
  "Request only the origins we don't already have.
   Returns a promise that resolves to boolean."
  [origins]
  (-> (js/Promise.all
       (clj->js (map (fn [o]
                       (-> (has-origin? o)
                           (.then (fn [has] {:origin o :has has}))))
                     origins)))
      (.then (fn [results]
               (let [missing (->> (js->clj results :keywordize-keys true)
                                  (filter #(not (:has %)))
                                  (map :origin))]
                 (if (empty? missing)
                   (js/Promise.resolve true)
                   (request-origins! missing)))))))

;; ============================================================
;; Permission revocation
;; ============================================================

(defn revoke-origin!
  "Remove permission for an origin pattern.
   Returns a promise that resolves to boolean.
   On revoke, removes from storage."
  [origin]
  (-> (js/chrome.permissions.remove
       #js {:origins #js [origin]})
      (.then (fn [removed]
               (when removed
                 (storage/remove-granted-origin! origin))
               removed))))

;; ============================================================
;; Sync with actual permissions
;; ============================================================

(defn get-all-granted-origins
  "Get all origins we currently have permission for.
   Returns a promise that resolves to vector of origin strings."
  []
  (-> (js/chrome.permissions.getAll)
      (.then (fn [perms]
               (vec (or (.-origins perms) #js []))))))

(defn sync-permissions!
  "Reconcile storage with actual chrome.permissions state.
   Removes origins from storage that we no longer have permission for.
   Returns a promise."
  []
  (-> (get-all-granted-origins)
      (.then (fn [actual-origins]
               (let [actual-set (set actual-origins)
                     stored-origins (storage/get-granted-origins)]
                 ;; Remove stored origins we no longer have
                 (doseq [origin stored-origins]
                   (when-not (actual-set origin)
                     (storage/remove-granted-origin! origin)))
                 ;; Add actual origins not in storage
                 (doseq [origin actual-origins]
                   (storage/add-granted-origin! origin))
                 (js/console.log "[Permissions] Synced" (count actual-origins) "origins"))))))

;; ============================================================
;; Debug: Expose for console testing
;; ============================================================

(set! js/globalThis.permissions
      #js {:has_all_urls_QMARK_ has-all-urls?
           :has_origin_QMARK_ has-origin?
           :has_all_origins_QMARK_ has-all-origins?
           :request_origin_BANG_ request-origin!
           :request_origins_BANG_ request-origins!
           :request_missing_origins_BANG_ request-missing-origins!
           :revoke_origin_BANG_ revoke-origin!
           :get_all_granted_origins get-all-granted-origins
           :sync_permissions_BANG_ sync-permissions!})
