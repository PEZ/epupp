(ns bg-message-handlers
  "Chrome runtime message handlers for the background service worker.
   All handlers are thin dispatch wrappers that parse message data
   and dispatch Uniflow actions."
  (:require [storage :as storage]
            [script-utils :as script-utils]
            [manifest-parser :as manifest-parser]
            [background-utils :as bg-utils]
            [bg-fs-dispatch :as fs-dispatch]
            [log :as log]))

;; ============================================================
;; WebSocket relay handlers
;; ============================================================

(defn- handle-ws-connect [message tab-id dispatch!]
  (dispatch! [[:ws/ax.handle-connect tab-id (.-port message)]])
  false)

(defn- handle-ws-send [message tab-id dispatch!]
  (dispatch! [[:ws/ax.handle-send tab-id (.-data message)]])
  false)

(defn- handle-ws-close [tab-id dispatch!]
  (dispatch! [[:ws/ax.handle-close tab-id]])
  false)

;; ============================================================
;; FS REPL API handlers
;; ============================================================

(defn- handle-list-scripts [message tab-id dispatch! send-response]
  (let [include-hidden? (.-lsHidden message)]
    (dispatch! [[:fs/ax.guard-list-scripts tab-id send-response include-hidden?]])
    true))

(defn- handle-save-script [message tab-id dispatch! send-response]
  (let [script-source (.-scriptSource message)
        web-install? (and script-source
                          (or (.startsWith script-source "http://")
                              (.startsWith script-source "https://")))
        raw-data {:code (.-code message)
                  :enabled (if (some? (.-enabled message)) (.-enabled message) true)
                  :force? (.-force message)
                  :bulk-id (.-bulkId message)
                  :bulk-index (.-bulkIndex message)
                  :bulk-count (.-bulkCount message)
                  :script-source script-source}]
    (dispatch! [[:fs/ax.guard-save-script tab-id send-response raw-data web-install?]])
    true))

(defn- handle-panel-save-script [message send-response]
  (let [js-script (.-script message)
        script (cond-> {:script/name (.-name js-script)
                        :script/description (.-description js-script)
                        :script/match (vec (.-match js-script))
                        :script/code (.-code js-script)
                        :script/enabled (.-enabled js-script)
                        :script/run-at (script-utils/normalize-run-at (.-runAt js-script))
                        :script/inject (if (.-inject js-script)
                                         (vec (.-inject js-script))
                                         [])}
                 (.-id js-script) (assoc :script/id (.-id js-script))
                 (.-force js-script) (assoc :script/force? true))]
    (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.save-script script]))
  true)

(defn- handle-panel-rename-script [message send-response]
  (let [from-name (.-from message)
        to-name (.-to message)]
    (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.rename-script from-name to-name]))
  true)

(defn- handle-popup-script-change
  "Run a popup script change in storage once the background is initialized.
   A message can wake the service worker before storage has loaded, and
   changing the empty list then would store it."
  [ensure-initialized-fn dispatch! send-response change!]
  ((^:async fn []
     (try
       (js-await (ensure-initialized-fn dispatch!))
       (js-await (change!))
       (send-response #js {:success true})
       (catch :default err
         (send-response #js {:success false :error (.-message err)})))))
  true)

(defn- handle-rename-script [message tab-id dispatch! send-response]
  (let [from-name (.-from message)
        to-name (.-to message)
        force? (when (.-force message) true)
        action (cond-> [:fs/ax.guard-rename-script tab-id send-response from-name to-name]
                 force? (conj true))]
    (dispatch! [action])
    true))

(defn- handle-delete-script [message tab-id dispatch! send-response]
  (let [script-name (.-name message)
        bulk-id (.-bulkId message)
        bulk-index (.-bulkIndex message)
        bulk-count (.-bulkCount message)]
    (dispatch! [[:fs/ax.guard-delete-script tab-id send-response
                 {:script-name script-name
                  :bulk-id bulk-id
                  :bulk-index bulk-index
                  :bulk-count bulk-count}]])
    true))

(defn- handle-get-script [message tab-id dispatch! send-response]
  (let [script-name (.-name message)]
    (dispatch! [[:fs/ax.guard-get-script tab-id send-response script-name]])
    true))

;; ============================================================
;; Panel/popup message handlers
;; ============================================================

(defn- handle-load-manifest [message tab-id dispatch! send-response]
  (let [manifest (.-manifest message)
        all-scripts (storage/get-scripts)]
    (dispatch! [[:msg/ax.load-manifest send-response tab-id manifest all-scripts]])
    true))

(defn- handle-get-connections [dispatch! send-response]
  (dispatch! [[:msg/ax.get-connections send-response]])
  false)

(defn- handle-loader-resolution-errors [message sender dispatch!]
  (let [tab-id (when (.-tab sender) (.. sender -tab -id))
        js-errors (.-errors message)
        errors (when js-errors
                 (mapv (fn [e]
                         {:error/type (.-errorType e)
                          :error/phase :resolve
                          :error/surface :early-loader
                          :error/script-name (.-scriptName e)
                          :error/dep-raw (.-depRaw e)
                          :error/dep-chain (when (.-depChain e) (vec (.-depChain e)))
                          :error/message (.-message e)})
                       (vec js-errors)))]
    (when (and tab-id (seq errors))
      (dispatch! [[:banner/ax.broadcast-resolution-errors errors]
                  [:runtime/ax.set-tab-errors tab-id errors]]))
    false))

(defn- handle-get-runtime-status [message dispatch! send-response]
  (let [tab-id (.-tabId message)]
    (dispatch! [[:runtime/ax.get-tab-errors send-response tab-id]])
    true))

(defn- handle-connect-tab [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)
        ws-port (.-wsPort message)]
    (dispatch! [[:msg/ax.connect-tab send-response target-tab-id ws-port]])
    true))

(defn- handle-check-status [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.check-status send-response target-tab-id]])
    true))

(defn- handle-disconnect-tab [message dispatch!]
  (let [target-tab-id (.-tabId message)]
    (dispatch! [[:ws/ax.explicit-disconnect target-tab-id]]))
  false)

;; ============================================================
;; E2E test handlers
;; ============================================================

(defn- handle-e2e-find-tab-id [message dispatch! send-response]
  (let [url-pattern (.-urlPattern message)]
    (dispatch! [[:msg/ax.e2e-find-tab-id send-response url-pattern]])
    true))

(defn- handle-e2e-get-test-events [dispatch! send-response]
  (dispatch! [[:msg/ax.e2e-get-test-events send-response]])
  true)

(defn- handle-e2e-get-storage [message dispatch! send-response]
  (let [key (.-key message)]
    (dispatch! [[:msg/ax.e2e-get-storage send-response key]])
    true))

(defn- handle-e2e-set-storage [message dispatch! send-response]
  (let [key (.-key message)
        value (.-value message)]
    (dispatch! [[:msg/ax.e2e-set-storage send-response key value]])
    true))

(defn- handle-e2e-activate-tab [message send-response]
  (let [tab-id (.-tabId message)]
    (js/chrome.tabs.update tab-id #js {:active true}
                           (fn [tab]
                             (if js/chrome.runtime.lastError
                               (send-response #js {:success false :error (.-message js/chrome.runtime.lastError)})
                               (js/chrome.windows.update (.-windowId tab) #js {:focused true}
                                                         (fn [_win]
                                                           (if js/chrome.runtime.lastError
                                                             (send-response #js {:success false :error (.-message js/chrome.runtime.lastError)})
                                                             (send-response #js {:success true :tabId tab-id})))))))
    true))

(defn- handle-e2e-update-icon [message dispatch! send-response]
  (let [tab-id (.-tabId message)]
    ((^:async fn []
       (try
         (js-await (dispatch! [[:icon/ax.refresh-toolbar tab-id]]))
         (send-response #js {:success true :tabId tab-id})
         (catch :default err
           (send-response #js {:success false :error (.-message err)})))))
    true))

(defn- handle-e2e-get-icon-display-state [message dispatch! send-response]
  (let [tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.e2e-get-icon-display-state send-response tab-id]])
    true))

(defn- handle-e2e-ensure-builtin [ensure-initialized-fn dispatch! send-response]
  ((^:async fn []
     (try
       (js-await (ensure-initialized-fn dispatch!))
       (js-await (storage/sync-builtin-scripts!))
       (send-response #js {:success true})
       (catch :default err
         (send-response #js {:success false :error (.-message err)})))))
  true)

(defn- handle-e2e-simulate-tab-visible [message dispatch! send-response]
  (let [tab-id (.-tabId message)]
    (dispatch! [[:visibility/ax.handle-tab-visible tab-id]])
    (send-response #js {:success true :tabId tab-id}))
  false)

;; ============================================================
;; Scittle/eval handlers
;; ============================================================

(defn- handle-ensure-scittle [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.ensure-scittle send-response target-tab-id]])
    true))

(defn- handle-inject-libs [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)
        libs (when (.-libs message)
               (vec (.-libs message)))
        all-scripts (storage/get-scripts)]
    (dispatch! [[:msg/ax.inject-libs send-response target-tab-id libs all-scripts]])
    true))

(defn- handle-evaluate-script [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)
        code (.-code message)
        libs (when (.-inject message)
               (vec (.-inject message)))]
    (dispatch! [[:msg/ax.evaluate-script send-response target-tab-id code libs (.-scriptId message)]])
    true))

(defn- handle-sponsor-status [_message sender dispatch! send-response]
  (let [tab-id (when (.-tab sender) (.. sender -tab -id))
        tab-url (when (.-tab sender) (.. sender -tab -url))]
    (dispatch! [[:sponsor/ax.consume-pending tab-id tab-url send-response]]))
  true)

;; ============================================================
;; Sponsor/permission/web-installer handlers
;; ============================================================

(defn- handle-permission-granted [message dispatch!]
  (let [tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.handle-permission-granted tab-id]])
    false))

(defn- handle-get-sponsored-username [_message send-response]
  ((^:async fn []
     (let [storage-result (js-await (js/chrome.storage.local.get #js ["sponsor/sponsored-username"]))
           username (or (aget storage-result "sponsor/sponsored-username") "PEZ")]
       (send-response #js {:success true :username username}))))
  true)

(defn- handle-check-script-exists [message _dispatch! send-response]
  (let [script-name (.-name message)
        code (.-code message)
        script (storage/get-script-by-name script-name)]
    (if script
      (send-response #js {:success true
                          :exists true
                          :identical (= code (:script/code script))})
      (send-response #js {:success true
                          :exists false}))
    false))

(defn- normalize-match-pattern
  "Normalize :epupp/auto-run-match to a vector of patterns."
  [auto-run-match]
  (cond
    (nil? auto-run-match) []
    (vector? auto-run-match) auto-run-match
    :else [auto-run-match]))

(defn- build-web-installer-script
  "Build a script map from web installer save data."
  [code manifest sender]
  (let [{:keys [raw-script-name script-name auto-run-match inject run-at]} manifest
        raw-name (or raw-script-name script-name)
        effective-inject (or inject [])
        effective-run-at (or run-at "document-idle")]
    (when raw-name
      {:script/id (str (.now js/Date))
       :script/name raw-name
       :script/code code
       :script/match (normalize-match-pattern auto-run-match)
       :script/inject effective-inject
       :script/enabled true
       :script/run-at effective-run-at
       :script/force? true
       :script/source (.. sender -tab -url)})))

(defn- handle-web-installer-save-script [message sender _dispatch! send-response]
  (if-not (bg-utils/web-installer-origin-allowed? sender)
    (do (send-response #js {:success false :error "Domain not allowed for web installation"})
        false)
    (try
      (let [code (.-code message)
            manifest (manifest-parser/extract-manifest code)
            script (build-web-installer-script code manifest sender)]
        (if script
          (do (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.save-script script])
              true)
          (do (send-response #js {:success false :error "No script name in manifest"})
              false)))
      (catch :default err
        (send-response #js {:success false :error (str "Parse error: " (.-message err))})
        false))))

(defn- handle-tab-became-visible [tab-id dispatch!]
  (dispatch! [[:visibility/ax.handle-tab-visible tab-id]])
  false)

;; ============================================================
;; User storage handlers
;; ============================================================

(defn- handle-storage-get [message dispatch! send-response]
  (dispatch! [[:user-kv/ax.get send-response (.-key message)]])
  true)

(defn- handle-storage-set [message dispatch! send-response]
  (dispatch! [[:user-kv/ax.set send-response (.-key message) (.-value message)]])
  true)

(defn- handle-storage-remove [message dispatch! send-response]
  (dispatch! [[:user-kv/ax.remove send-response (.-key message)]])
  true)

(defn- handle-storage-keys [_message dispatch! send-response]
  (dispatch! [[:user-kv/ax.keys send-response]])
  true)

(defn- handle-storage-clear [_message dispatch! send-response]
  (dispatch! [[:user-kv/ax.clear send-response]])
  true)

(defn- ^:async capture-visible-tab!
  "Capture screenshot of the visible area of a tab."
  [window-id format quality]
  (let [capture-opts (if (= format "jpeg")
                       #js {:format "jpeg" :quality quality}
                       #js {:format "png"})]
    (js-await (js/chrome.tabs.captureVisibleTab window-id capture-opts))))

;; ============================================================
;; Capture handlers
;; ============================================================

(defn- handle-capture-element
  "Handle capture-element message: take viewport screenshot."
  [message sender send-response]
  (let [tab (.-tab sender)
        tab-id (when tab (.-id tab))
        window-id (when tab (.-windowId tab))
        format (or (.-format message) "jpeg")
        quality (or (.-quality message) 75)]
    (if-not tab-id
      (do (send-response #js {:success false :error "No tab context"})
          false)
      (do ((^:async fn []
             (try
               (let [data-url (js-await (capture-visible-tab! window-id format quality))]
                 (send-response #js {:success true :dataUrl data-url}))
               (catch :default err
                 (send-response #js {:success false
                                     :error (str "Capture failed: " (.-message err))})))))
          true))))

;; ============================================================
;; Fallback handlers
;; ============================================================

(defn- handle-unknown-message [msg-type]
  (log/debug "Background" "Unknown message type:" msg-type)
  false)

(defn- handle-e2e-message
  "Route e2e test messages. Only available in dev mode."
  [{:keys [config ensure-initialized-fn dispatch!]} msg-type message send-response]
  (if (.-dev config)
    (case msg-type
      "e2e/find-tab-id" (handle-e2e-find-tab-id message dispatch! send-response)
      "e2e/get-test-events" (handle-e2e-get-test-events dispatch! send-response)
      "e2e/get-storage" (handle-e2e-get-storage message dispatch! send-response)
      "e2e/set-storage" (handle-e2e-set-storage message dispatch! send-response)
      "e2e/activate-tab" (handle-e2e-activate-tab message send-response)
      "e2e/update-icon" (handle-e2e-update-icon message dispatch! send-response)
      "e2e/get-icon-display-state" (handle-e2e-get-icon-display-state message dispatch! send-response)
      "e2e/ensure-builtin" (handle-e2e-ensure-builtin ensure-initialized-fn dispatch! send-response)
      "e2e/simulate-tab-visible" (handle-e2e-simulate-tab-visible message dispatch! send-response)
      (handle-unknown-message msg-type))
    (do (send-response #js {:success false :error "Not available"})
        false)))

;; ============================================================
;; Message router
;; ============================================================

(defn- route-runtime-message
  "Route a chrome.runtime message to the matching thin handler."
  [{:keys [config ensure-initialized-fn dispatch!]} message sender send-response]
  (let [tab-id (when (.-tab sender) (.. sender -tab -id))
        msg-type (.-type message)]
    (case msg-type
      "ws-connect" (handle-ws-connect message tab-id dispatch!)
      "ws-send" (handle-ws-send message tab-id dispatch!)
      "ws-close" (handle-ws-close tab-id dispatch!)
      "ping" false
      "tab-became-visible" (handle-tab-became-visible tab-id dispatch!)
      "list-scripts" (handle-list-scripts message tab-id dispatch! send-response)
      "save-script" (handle-save-script message tab-id dispatch! send-response)
      "panel-save-script" (handle-panel-save-script message send-response)
      "panel-rename-script" (handle-panel-rename-script message send-response)
      "popup-toggle-script" (handle-popup-script-change
                             ensure-initialized-fn dispatch! send-response
                             #(storage/toggle-script! (.-scriptId message)))
      "popup-delete-script" (handle-popup-script-change
                             ensure-initialized-fn dispatch! send-response
                             #(storage/delete-script! (.-scriptId message)))
      "popup-import-scripts" (handle-popup-script-change
                              ensure-initialized-fn dispatch! send-response
                              #(storage/import-scripts! (.-scripts message)))
      "rename-script" (handle-rename-script message tab-id dispatch! send-response)
      "delete-script" (handle-delete-script message tab-id dispatch! send-response)
      "get-script" (handle-get-script message tab-id dispatch! send-response)
      "check-script-exists" (handle-check-script-exists message dispatch! send-response)
      "web-installer-save-script" (handle-web-installer-save-script message sender dispatch! send-response)
      "load-manifest" (handle-load-manifest message tab-id dispatch! send-response)
      "get-connections" (handle-get-connections dispatch! send-response)
      "get-runtime-status" (handle-get-runtime-status message dispatch! send-response)
      "loader-resolution-errors" (handle-loader-resolution-errors message sender dispatch!)
      "connect-tab" (handle-connect-tab message dispatch! send-response)
      "check-status" (handle-check-status message dispatch! send-response)
      "disconnect-tab" (handle-disconnect-tab message dispatch!)
      "toggle-fs-sync"
      (do (dispatch! [[:fs/ax.toggle-sync (.-tabId message) (.-enabled message) send-response]])
          true)
      "get-fs-sync-status"
      (do (dispatch! [[:fs/ax.get-sync-status send-response]])
          true)
      "capture-element" (handle-capture-element message sender send-response)
      "storage-get" (handle-storage-get message dispatch! send-response)
      "storage-set" (handle-storage-set message dispatch! send-response)
      "storage-remove" (handle-storage-remove message dispatch! send-response)
      "storage-keys" (handle-storage-keys message dispatch! send-response)
      "storage-clear" (handle-storage-clear message dispatch! send-response)
      "ensure-scittle" (handle-ensure-scittle message dispatch! send-response)
      "inject-libs" (handle-inject-libs message dispatch! send-response)
      "evaluate-script" (handle-evaluate-script message dispatch! send-response)
      "sponsor-status" (handle-sponsor-status message sender dispatch! send-response)
      "get-sponsored-username" (handle-get-sponsored-username message send-response)
      "permission-granted" (handle-permission-granted message dispatch!)
      (if (.startsWith msg-type "e2e/")
        (handle-e2e-message {:config config :ensure-initialized-fn ensure-initialized-fn :dispatch! dispatch!} msg-type message send-response)
        (handle-unknown-message msg-type)))))

(defn add-on-message-handler
  "Register the chrome.runtime.onMessage handler.
   config and ensure-initialized-fn are passed to avoid circular deps."
  [config ensure-initialized-fn dispatch!]
  (.addListener js/chrome.runtime.onMessage
                (fn [message sender send-response]
                  (route-runtime-message
                   {:config config
                    :ensure-initialized-fn ensure-initialized-fn
                    :dispatch! dispatch!}
                   message sender send-response))))
