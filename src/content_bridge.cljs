;; Security: The message-registry below is the authoritative whitelist.
;; Unregistered message types are silently dropped by the bridge.
;; See the :msg/auth values for each message's security model.

(ns content-bridge
  "Content script bridge for WebSocket connections.
   Runs in ISOLATED world, relays messages between page (MAIN) and background service worker."
  (:require [log :as log]
            [test-logger :as test-logger]))

(def message-registry
  ;; WebSocket relay - REPL connectivity
  {"ws-connect"             {:msg/sources #{"epupp-page"}
                             :msg/auth :auth/none
                             :msg/response? false
                             :msg/pre-forward (fn [msg]
                                                (log/debug "Bridge" "Requesting connection to port:" (.-port msg))
                                                (set-connected! true))}
   "ws-send"                {:msg/sources #{"epupp-page"}
                             :msg/auth :auth/connected
                             :msg/response? false
                             :msg/pre-forward (fn [_msg]
                                                (connected?))}

   ;; Script CRUD - read operations
   "list-scripts"           {:msg/sources #{"epupp-page"}
                             :msg/auth :auth/fs-sync
                             :msg/response? true}
   "get-script"             {:msg/sources #{"epupp-page"}
                             :msg/auth :auth/fs-sync
                             :msg/response? true}

   ;; Script CRUD - write operations
   "save-script"            {:msg/sources #{"epupp-page" "epupp-userscript"}
                             :msg/auth :auth/fs-sync-or-token
                             :msg/response? true}
   "rename-script"          {:msg/sources #{"epupp-page"}
                             :msg/auth :auth/fs-sync
                             :msg/response? true}
   "delete-script"          {:msg/sources #{"epupp-page"}
                             :msg/auth :auth/fs-sync
                             :msg/response? true}

   ;; Web installer token flow (not yet implemented - depends on security audit remediation)
   ;; Uncomment when background handler exists:
   ;; "request-save-token"  {:msg/sources #{"epupp-page"} :msg/auth :auth/none :msg/response? true}

   ;; Library injection
   "load-manifest"          {:msg/sources #{"epupp-page"}
                             :msg/auth :auth/none
                             :msg/response? true
                             :msg/response-type "manifest-response"}

   ;; Sponsor system
   "sponsor-status"         {:msg/sources #{"epupp-userscript"}
                             :msg/auth :auth/challenge-response
                             :msg/response? false
                             :msg/pre-forward (fn [_msg]
                                                (log/debug "Bridge" "Forwarding sponsor-status to background"))}
   "get-sponsored-username" {:msg/sources #{"epupp-page"}
                             :msg/auth :auth/none
                             :msg/response? true}

   ;; Userscript self-install
   "install-userscript"     {:msg/sources #{"epupp-userscript"}
                             :msg/auth :auth/origin-validated
                             :msg/response? true
                             :msg/response-type "install-response"}})

(def !state (atom {:bridge/connected? false
                   :bridge/keepalive-interval nil}))

(defn- connected? []
  (:bridge/connected? @!state))

(defn- set-connected! [v]
  (swap! !state assoc :bridge/connected? v))

(defn same-window?
  "Check if event came from same window, safely handling cross-origin frames."
  [event]
  (try
    (identical? (.-source event) js/window)
    (catch :default _
      false)))

(defn- stop-keepalive!
  "Stop the keepalive interval"
  []
  (when-let [interval (:bridge/keepalive-interval @!state)]
    (js/clearInterval interval)
    (swap! !state assoc :bridge/keepalive-interval nil)))

(defn- send-message-safe!
  "Send message to background, catching context invalidated errors.
   Returns true if message was sent, false if context was invalidated."
  [message]
  (try
    (js/chrome.runtime.sendMessage message)
    true
    (catch :default e
      (when (re-find #"Extension context invalidated" (.-message e))
        (log/debug "Bridge" "Extension context invalidated, stopping keepalive")
        (stop-keepalive!)
        (set-connected! false))
      false)))

(defn- start-keepalive!
  "Start sending keepalive pings to background to prevent service worker termination"
  []
  (stop-keepalive!)
  (swap! !state assoc :bridge/keepalive-interval
         (js/setInterval
          (fn []
            (when (connected?)
              (send-message-safe! #js {:type "ping"})))
          5000)))

(defn- handle-context-invalidated! []
  (log/debug "Bridge" "Extension context invalidated")
  (stop-keepalive!)
  (set-connected! false))

(defn- forward-with-response
  "Generic forwarder for messages that expect a response from background.
   Sends the raw message to background, posts the response back to the page."
  [msg]
  (let [msg-type (.-type msg)
        entry (get message-registry msg-type)]
    (try
      (js/chrome.runtime.sendMessage
       msg
       (fn [response]
         (let [response-type (or (:msg/response-type entry)
                                 (str msg-type "-response"))
               base #js {:source "epupp-bridge"
                         :type response-type}]
           (when-let [rid (.-requestId msg)]
             (set! (.-requestId base) rid))
           (when response
             (js/Object.assign base response))
           (.postMessage js/window base "*"))))
      (catch :default e
        (when (re-find #"Extension context invalidated" (.-message e))
          (handle-context-invalidated!))))))

(defn- forward-fire-and-forget
  "Generic forwarder for messages with no response. Uses send-message-safe!
   which handles context invalidation."
  [msg]
  (send-message-safe! msg))

(defn- handle-page-message [event]
  (when (same-window? event)
    (let [msg (.-data event)]
      (when msg
        (let [msg-type (.-type msg)
              msg-source (.-source msg)
              entry (get message-registry msg-type)]
          ;; Registry-driven dispatch: check source is allowed
          (when (and entry (contains? (:msg/sources entry) msg-source))
            ;; Run pre-forward hook if present
            (let [pre-forward (:msg/pre-forward entry)
                  should-forward? (if pre-forward
                                    (not (false? (pre-forward msg)))
                                    true)]
              (when should-forward?
                (if (:msg/response? entry)
                  (forward-with-response msg)
                  (forward-fire-and-forget msg)))))
          ;; Locally-handled messages (never reach background)
          (when (= "epupp-page" msg-source)
            (case msg-type
              "log"
              (let [level (.-level msg)
                    subsystem (.-subsystem msg)
                    messages (.-messages msg)]
                (apply log/log level subsystem messages))

              "get-icon-url"
              (.postMessage js/window
                            #js {:source "epupp-bridge"
                                 :type "get-icon-url-response"
                                 :requestId (.-requestId msg)
                                 :url (js/chrome.runtime.getURL "icons/icon.svg")}
                            "*")
              nil)))))))

;; Script injection helpers
(defn- inject-script-tag!
  "Inject a script tag with src URL into the page.
   Tracks injected URLs to prevent duplicates.
   Returns a promise that resolves when the script has loaded."
  [url send-response]
  ;; Initialize tracker if needed (survives across content script re-injections)
  (when-not js/window.__epuppInjectedScripts
    (set! js/window.__epuppInjectedScripts (js/Set.)))
  ;; Check for duplicate
  (if (.has js/window.__epuppInjectedScripts url)
    (do
      (log/debug "Bridge" "Script already injected, skipping:" url)
      (send-response #js {:success true :skipped true}))
    (do
      ;; Track this URL
      (.add js/window.__epuppInjectedScripts url)
      (let [script (js/document.createElement "script")]
        (set! (.-onload script)
              (fn []
                (log/debug "Bridge" "Script loaded:" url)
                (send-response #js {:success true})))
        (set! (.-onerror script)
              (fn [e]
                (log/error "Bridge" "Script load error:" url e)
                ;; Remove from tracker on error so retry is possible
                (.delete js/window.__epuppInjectedScripts url)
                (send-response #js {:success false :error (str "Failed to load " url)})))
        (set! (.-src script) url)
        (.appendChild js/document.head script)
        (log/debug "Bridge" "Injecting script:" url)))))

(defn- clear-old-userscripts!
  "Remove previously injected userscript tags to prevent re-execution on navigation."
  []
  (let [old-scripts (js/document.querySelectorAll "script[type='application/x-scittle'][id^='userscript-']")]
    (when (pos? (.-length old-scripts))
      (log/debug "Bridge" "Clearing" (.-length old-scripts) "old userscript tags")
      (.forEach old-scripts (fn [script] (.remove script))))))

(defn- inject-userscript!
  "Inject a Scittle userscript tag (application/x-scittle)."
  [id code]
  (let [script (js/document.createElement "script")]
    (set! (.-type script) "application/x-scittle")
    (set! (.-id script) id)
    (set! (.-textContent script) code)
    (.appendChild js/document.head script)
    (log/debug "Bridge" "Injected userscript:" id)))

(defn- handle-runtime-message [message _sender send-response]
  (let [msg-type (.-type message)]
    (case msg-type
      ;; Readiness check - background pings to confirm bridge is ready
      "bridge-ping"
      (do
        (log/debug "Bridge" "Responding to ping")
        (send-response #js {:ready true})
        ;; Return true to keep sendResponse valid for async use
        true)

      ;; Script injection messages
      "inject-script"
      (do
        ;; inject-script-tag! calls send-response when script loads
        (inject-script-tag! (.-url message) send-response)
        ;; Return true to indicate async response
        true)

      "clear-userscripts"
      (do
        (clear-old-userscripts!)
        (send-response #js {:success true})
        false)

      "inject-userscript"
      (do
        (inject-userscript! (.-id message) (.-code message))
        (send-response #js {:success true})
        false)

      ;; WebSocket relay messages - no response needed
      "ws-open"
      (do
        (log/debug "Bridge" "WebSocket connected")
        (start-keepalive!)
        (.postMessage js/window
                      #js {:source "epupp-bridge"
                           :type "ws-open"}
                      "*")
        false)

      "ws-message"
      (do
        (.postMessage js/window
                      #js {:source "epupp-bridge"
                           :type "ws-message"
                           :data (.-data message)}
                      "*")
        false)

      "ws-error"
      (do
        (log/error "Bridge" "WebSocket error:" (.-error message))
        (stop-keepalive!)
        (set-connected! false)
        (.postMessage js/window
                      #js {:source "epupp-bridge"
                           :type "ws-error"
                           :error (.-error message)}
                      "*")
        false)

      "ws-close"
      (do
        (log/debug "Bridge" "WebSocket closed")
        (stop-keepalive!)
        (set-connected! false)
        (.postMessage js/window
                      #js {:source "epupp-bridge"
                           :type "ws-close"}
                      "*")
        false)

      ;; Unknown message type
      false)))

;; Initialize - guard against multiple injections
(when-not js/window.__browserJackInBridge
  (set! js/window.__browserJackInBridge true)
  ;; Install error handlers for test mode (now that we have EXTENSION_CONFIG)
  (test-logger/install-global-error-handlers! "content-bridge" js/window)
  ;; Load debug logging setting
  (js/chrome.storage.local.get
   #js ["settings/debug-logging"]
   (fn [result]
     (log/set-debug-enabled! (boolean (aget result "settings/debug-logging")))))
  ;; Listen for debug logging setting changes
  (.addListener js/chrome.storage.onChanged
                (fn [changes area]
                  (when (and (= area "local") (aget changes "settings/debug-logging"))
                    (let [change (aget changes "settings/debug-logging")
                          enabled (boolean (.-newValue change))]
                      (log/set-debug-enabled! enabled)))))
  (log/debug "Bridge" "Content script loaded")
  (.addEventListener js/window "message" handle-page-message)
  (.addListener js/chrome.runtime.onMessage handle-runtime-message)
  (.postMessage js/window
                #js {:source "epupp-bridge"
                     :type "bridge-ready"}
                "*")
  ;; Log test event for E2E tests
  (test-logger/log-event! "BRIDGE_READY" {:url js/window.location.href}))
