;; ============================================================
;; Security: Message Forwarding Whitelist
;; ============================================================
;; The content bridge is the ONLY path from page scripts (MAIN world) to the
;; background worker. Page scripts cannot call chrome.runtime.sendMessage directly.
;;
;; IMPORTANT: Only explicitly handled message types below are forwarded.
;; Adding new forwarded messages effectively grants page scripts that capability.
;; Before adding: consider if the background handler could be abused if called
;; by arbitrary page code (e.g., pattern-approved, evaluate-script would be dangerous).
;;
;; Current whitelist:
;; - epupp-page source: ws-connect, ws-send (WebSocket relay for REPL)
;; - epupp-userscript source: install-userscript (with origin validation in background)
;; ============================================================

(ns content-bridge
  "Content script bridge for WebSocket connections.
   Runs in ISOLATED world, relays messages between page (MAIN) and background service worker."
  (:require [log :as log]
            [test-logger :as test-logger]))

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
        (log/info "Bridge" nil "Extension context invalidated, stopping keepalive")
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

(defn- handle-page-message [event]
  (when (same-window? event)
    (let [msg (.-data event)]
      ;; Handle messages from WebSocket bridge (epupp-page)
      (when (and msg (= "epupp-page" (.-source msg)))
        (case (.-type msg)
          "ws-connect"
          (do
            (log/info "Bridge" nil "Requesting connection to port:" (.-port msg))
            (set-connected! true)
            (send-message-safe!
             #js {:type "ws-connect"
                  :port (.-port msg)}))

          "ws-send"
          (when (connected?)
            (send-message-safe!
             #js {:type "ws-send"
                  :data (.-data msg)}))

          nil))

      ;; Handle messages from userscripts (epupp-userscript)
      (when (and msg (= "epupp-userscript" (.-source msg)))
        (case (.-type msg)
          "install-userscript"
          (do
            (log/info "Bridge" nil "Forwarding install request to background")
            ;; This one needs callback, wrap in try/catch
            (try
              (js/chrome.runtime.sendMessage
               #js {:type "install-userscript"
                    :manifest (.-manifest msg)
                    :scriptUrl (.-scriptUrl msg)}
               (fn [response]
                 ;; Send response back to page
                 (.postMessage js/window
                               #js {:source "epupp-bridge"
                                    :type "install-response"
                                    :success (.-success response)
                                    :error (.-error response)}
                               "*")))
              (catch :default e
                (when (re-find #"Extension context invalidated" (.-message e))
                  (log/info "Bridge" nil "Extension context invalidated")
                  (stop-keepalive!)
                  (set-connected! false)))))

          nil)))))

;; Script injection helpers
(defn- inject-script-tag!
  "Inject a script tag with src URL into the page."
  [url]
  (let [script (js/document.createElement "script")]
    (set! (.-src script) url)
    (.appendChild js/document.head script)
    (log/info "Bridge" nil "Injected script:" url)))

(defn- clear-old-userscripts!
  "Remove previously injected userscript tags to prevent re-execution on navigation."
  []
  (let [old-scripts (js/document.querySelectorAll "script[type='application/x-scittle'][id^='userscript-']")]
    (when (pos? (.-length old-scripts))
      (log/info "Bridge" nil "Clearing" (.-length old-scripts) "old userscript tags")
      (.forEach old-scripts (fn [script] (.remove script))))))

(defn- inject-userscript!
  "Inject a Scittle userscript tag (application/x-scittle)."
  [id code]
  (let [script (js/document.createElement "script")]
    (set! (.-type script) "application/x-scittle")
    (set! (.-id script) id)
    (set! (.-textContent script) code)
    (.appendChild js/document.head script)
    (log/info "Bridge" nil "Injected userscript:" id)))

(defn- handle-runtime-message [message _sender send-response]
  (let [msg-type (.-type message)]
    (case msg-type
      ;; Readiness check - background pings to confirm bridge is ready
      "bridge-ping"
      (do
        (log/info "Bridge" nil "Responding to ping")
        (send-response #js {:ready true})
        ;; Return true to keep sendResponse valid for async use
        true)

      ;; Script injection messages - respond synchronously
      "inject-script"
      (do
        (inject-script-tag! (.-url message))
        (send-response #js {:success true})
        false)

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
        (log/info "Bridge" nil "WebSocket connected")
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
        (log/error "Bridge" nil "WebSocket error:" (.-error message))
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
        (log/info "Bridge" nil "WebSocket closed")
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
  (log/info "Bridge" nil "Content script loaded")
  (.addEventListener js/window "message" handle-page-message)
  (.addListener js/chrome.runtime.onMessage handle-runtime-message)
  (.postMessage js/window
                #js {:source "epupp-bridge"
                     :type "bridge-ready"}
                "*")
  ;; Log test event for E2E tests
  (test-logger/log-event! "BRIDGE_READY" {:url js/window.location.href}))
