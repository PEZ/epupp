(ns permissions
  "Host permission checking for Firefox compatibility.
   Firefox treats host_permissions as optional/revocable, so we must check
   before calling chrome.scripting.executeScript."
  (:require [log :as log]))

(defn ^:async has-host-permission?
  "Check if extension has host permission for the given URL.
   Returns a promise resolving to boolean."
  [url]
  (js/Promise.
   (fn [resolve]
     (try
       (js/chrome.permissions.contains
        #js {:origins #js [url]}
        (fn [result]
          (resolve (boolean result))))
       (catch :default _
         ;; If permissions API is unavailable, assume granted (Chrome behavior)
         (resolve true))))))

(defn ^:async has-all-urls-permission?
  "Check if extension has the <all_urls> permission.
   Returns a promise resolving to boolean."
  []
  (js/Promise.
   (fn [resolve]
     (try
       (js/chrome.permissions.contains
        #js {:origins #js ["<all_urls>"]}
        (fn [result]
          (resolve (boolean result))))
       (catch :default _
         (resolve true))))))

(defn ^:async request-host-permission!
  "Request the <all_urls> host permission from the user.
   Must be called from a user gesture context (e.g. popup button click).
   Returns a promise resolving to boolean (granted or not)."
  []
  (js/Promise.
   (fn [resolve]
     (try
       (js/chrome.permissions.request
        #js {:origins #js ["<all_urls>"]}
        (fn [granted]
          (resolve (boolean granted))))
       (catch :default _
         (resolve false))))))

(defn ^:async check-tab-permission
  "Check host permission for a tab's URL. Returns the URL origin pattern
   needed for permission checking. If permission is missing, logs a debug
   message and returns false. If granted, returns true."
  [tab-id]
  (js/Promise.
   (fn [resolve]
     (try
       (js/chrome.tabs.get
        tab-id
        (fn [tab]
          (if (or js/chrome.runtime.lastError (not tab) (not (.-url tab)))
            (resolve true) ;; Can't determine URL, assume permitted
            (let [url (.-url tab)]
              (if (or (.startsWith url "chrome-extension://")
                      (.startsWith url "chrome://")
                      (.startsWith url "about:")
                      (.startsWith url "moz-extension://"))
                (resolve true) ;; Extension/internal pages - not applicable
                (-> (has-host-permission? url)
                    (.then (fn [has-perm?]
                             (when-not has-perm?
                               (log/debug "Background:Inject"
                                          "Skipping injection - host permission not granted for"
                                          url))
                             (resolve has-perm?)))))))))
       (catch :default _
         (resolve true))))))
