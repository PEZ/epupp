(ns background
  "Background service worker for WebSocket connections.
   Runs in extension context, immune to page CSP.
   Relays WebSocket messages to/from content scripts."
  (:require [storage :as storage]
            [url-matching :as url-matching]
            [permissions :as permissions]))

(js/console.log "[Browser Jack-in Background] Service worker started")

;; Initialize script storage
(storage/init!)

;; Centralized state with namespaced keys
(def !state (atom {:ws/connections {}}))

(defn get-ws
  "Get WebSocket for a tab"
  [tab-id]
  (get-in @!state [:ws/connections tab-id]))

(defn close-ws!
  "Close and remove WebSocket for a tab. Does not send ws-close event."
  [tab-id]
  (when-let [ws (get-ws tab-id)]
    ;; Clear onclose to prevent sending ws-close when deliberately closing
    (set! (.-onclose ws) nil)
    (try
      (.close ws)
      (catch :default e
        (js/console.error "[Background] Error closing WebSocket:" e))))
  (swap! !state update :ws/connections dissoc tab-id))

(defn send-to-tab
  "Send message to content script in a tab"
  [tab-id message]
  (-> (js/chrome.tabs.sendMessage tab-id (clj->js message))
      (.catch (fn [e]
                (js/console.error "[Background] Failed to send to tab:" tab-id "error:" e)))))

(defn handle-ws-connect
  "Create WebSocket connection for a tab"
  [tab-id port]
  ;; Close existing connection if any
  (close-ws! tab-id)

  (let [ws-url (str "ws://localhost:" port "/_nrepl")]
    (js/console.log "[Background] Connecting to:" ws-url "for tab:" tab-id)
    (try
      (let [ws (js/WebSocket. ws-url)]
        (swap! !state assoc-in [:ws/connections tab-id] ws)

        (set! (.-onopen ws)
              (fn []
                (js/console.log "[Background] WebSocket connected for tab:" tab-id)
                (send-to-tab tab-id {:type "ws-open"})))

        (set! (.-onmessage ws)
              (fn [event]
                (send-to-tab tab-id {:type "ws-message"
                                     :data (.-data event)})))

        (set! (.-onerror ws)
              (fn [error]
                (js/console.error "[Background] WebSocket error for tab:" tab-id)
                (send-to-tab tab-id {:type "ws-error"
                                     :error (str "WebSocket error connecting to " ws-url)})))

        (set! (.-onclose ws)
              (fn [event]
                (js/console.log "[Background] WebSocket closed for tab:" tab-id)
                (send-to-tab tab-id {:type "ws-close"
                                     :code (.-code event)
                                     :reason (.-reason event)})
                (swap! !state update :ws/connections dissoc tab-id))))
      (catch :default e
        (js/console.error "[Background] Failed to create WebSocket:" e)
        (send-to-tab tab-id {:type "ws-error"
                             :error (str e)})))))

(defn handle-ws-send
  "Send data through WebSocket for a tab"
  [tab-id data]
  (when-let [ws (get-ws tab-id)]
    (when (= 1 (.-readyState ws))
      (.send ws data))))

(defn handle-ws-close
  "Close WebSocket for a tab"
  [tab-id]
  (close-ws! tab-id))

;; Listen for messages from content scripts
(.addListener js/chrome.runtime.onMessage
  (fn [message sender _send-response]
    (let [tab-id (.. sender -tab -id)
          msg-type (.-type message)]
      (case msg-type
        "ws-connect" (handle-ws-connect tab-id (.-port message))
        "ws-send" (handle-ws-send tab-id (.-data message))
        "ws-close" (handle-ws-close tab-id)
        "ping" nil
        (js/console.log "[Background] Unknown message type:" msg-type)))
    false))

;; Clean up when tab is closed
(.addListener js/chrome.tabs.onRemoved
  (fn [tab-id _remove-info]
    (when (get-ws tab-id)
      (js/console.log "[Background] Tab closed, cleaning up:" tab-id)
      (close-ws! tab-id))))

;; ============================================================
;; Auto-Injection: Run userscripts on page load
;; ============================================================

(defn execute-in-page
  "Execute a function in page context (MAIN world).
   Returns a promise."
  [tab-id func & args]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.scripting.executeScript
      #js {:target #js {:tabId tab-id}
           :world "MAIN"
           :func func
           :args (clj->js (vec args))}
      (fn [results]
        (if js/chrome.runtime.lastError
          (reject (js/Error. (.-message js/chrome.runtime.lastError)))
          (resolve (when (seq results) (.-result (first results))))))))))

(defn inject-content-script
  "Inject a script file into ISOLATED world."
  [tab-id file]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.scripting.executeScript
      #js {:target #js {:tabId tab-id}
           :files #js [file]}
      (fn [_results]
        (if js/chrome.runtime.lastError
          (reject (js/Error. (.-message js/chrome.runtime.lastError)))
          (resolve true)))))))

;; Page-context functions (pure JS, no Squint runtime)
(def inject-script-fn
  (js* "function(url, isModule) {
    var script = document.createElement('script');
    if (isModule) script.type = 'module';
    if (window.trustedTypes && window.trustedTypes.createPolicy) {
      try {
        var policy = window.trustedTypes.createPolicy('browser-jack-in', {
          createScriptURL: function(s) { return s; }
        });
        script.src = policy.createScriptURL(url);
      } catch(e) {
        if (!(e.name === 'TypeError' && e.message.includes('already exists'))) throw e;
        script.src = url;
      }
    } else {
      script.src = url;
    }
    document.head.appendChild(script);
    return 'ok';
  }"))

(def check-scittle-fn
  (js* "function() {
    return {
      hasScittle: !!(window.scittle && window.scittle.core),
      hasWsBridge: !!window.__browserJackInWSBridge
    };
  }"))

(def eval-cljs-fn
  "Evaluate ClojureScript code via Scittle"
  (js* "function(code) {
    try {
      var result = scittle.core.eval_string(code);
      console.log('[Userscript] Executed successfully');
      return {success: true, result: String(result)};
    } catch(e) {
      console.error('[Userscript] Error:', e);
      return {success: false, error: e.message};
    }
  }"))

(defn poll-until
  "Poll a check function until success or timeout"
  [check-fn success? timeout]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)]
       (letfn [(poll []
                 (-> (check-fn)
                     (.then (fn [result]
                              (cond
                                (success? result) (resolve result)
                                (> (- (js/Date.now) start) timeout)
                                (reject (js/Error. "Timeout waiting for Scittle"))
                                :else (js/setTimeout poll 100))))
                     (.catch reject)))]
         (poll))))))

(defn ensure-scittle!
  "Ensure Scittle is loaded in the page. Returns promise."
  [tab-id]
  (-> (execute-in-page tab-id check-scittle-fn)
      (.then (fn [status]
               (if (and status (.-hasScittle status))
                 (js/Promise.resolve true)
                 (let [scittle-url (js/chrome.runtime.getURL "vendor/scittle.js")]
                   (-> (execute-in-page tab-id inject-script-fn scittle-url false)
                       (.then (fn [_]
                                (poll-until
                                 (fn [] (execute-in-page tab-id check-scittle-fn))
                                 (fn [r] (and r (.-hasScittle r)))
                                 5000))))))))))

(defn execute-scripts!
  "Execute a list of scripts in the page via Scittle"
  [tab-id scripts]
  (js/Promise.all
   (clj->js
    (map (fn [script]
           (-> (execute-in-page tab-id eval-cljs-fn (:script/code script))
               (.then (fn [result]
                        (js/console.log "[Userscript]" (:script/name script)
                                        (if (.-success result) "✓" "✗"))
                        result))
               (.catch (fn [err]
                         (js/console.error "[Userscript]" (:script/name script) "error:" err)
                         {:success false :error (.-message err)}))))
         scripts))))

(defn handle-navigation!
  "Handle page navigation: find matching scripts and execute them.
   Skips permission check - just tries to inject. Chrome's scripting API
   will fail if we don't have access (respects Site Access settings)."
  [tab-id url]
  (let [scripts (url-matching/get-matching-scripts url)]
    (when (seq scripts)
      (js/console.log "[Auto-inject] Found" (count scripts) "scripts for" url)
      (-> (ensure-scittle! tab-id)
          (.then (fn [_]
                   (execute-scripts! tab-id scripts)))
          (.then (fn [results]
                   (js/console.log "[Auto-inject] Executed" (count results) "scripts")))
          (.catch (fn [err]
                   (js/console.error "[Auto-inject] Failed (may need Site Access permission):" (.-message err))))))))

;; Listen for page navigation completion
(.addListener js/chrome.webNavigation.onCompleted
  (fn [details]
    ;; Only handle main frame (not iframes)
    (when (zero? (.-frameId details))
      (handle-navigation! (.-tabId details) (.-url details)))))

(js/console.log "[Browser Jack-in Background] Message listeners registered")
