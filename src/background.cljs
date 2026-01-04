(ns background
  "Background service worker for WebSocket connections.
   Runs in extension context, immune to page CSP.
   Relays WebSocket messages to/from content scripts."
  (:require [storage :as storage]
            [url-matching :as url-matching]))

(js/console.log "[Browser Jack-in Background] Service worker started")

;; Initialize script storage
(storage/init!)

;; Centralized state with namespaced keys
(defonce !state (atom {:ws/connections {}
                       :pending/approvals {}}))

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
                (js/console.error "[Background] WebSocket error for tab:" tab-id error)
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

(defn get-active-tab-id
  "Get the active tab ID. Returns a promise."
  []
  (js/Promise.
   (fn [resolve _reject]
     (js/chrome.tabs.query #js {:active true :currentWindow true}
                           (fn [tabs]
                             (resolve (when (seq tabs)
                                        (.-id (first tabs)))))))))
;; ============================================================
;; Badge Management - Calculate from source of truth
;; ============================================================

(defn set-badge!
  "Set the badge text and color based on count."
  [n]
  (if (pos? n)
    (do
      (js/chrome.action.setBadgeText #js {:text (str n)})
      (js/chrome.action.setBadgeBackgroundColor #js {:color "#f59e0b"}))
    (js/chrome.action.setBadgeText #js {:text ""})))

(defn count-pending-for-url
  "Count scripts needing approval for a given URL.
   A script needs approval if: enabled, matches URL, and pattern not yet approved."
  [url]
  (if (or (nil? url) (= "" url))
    0
    (let [scripts (storage/get-enabled-scripts)]
      (->> scripts
           (filter (fn [script]
                     (when-let [pattern (url-matching/get-matching-pattern url script)]
                       (not (storage/pattern-approved? script pattern)))))
           count))))

(defn update-badge-for-tab!
  "Update badge based on pending count for a specific tab's URL."
  [tab-id]
  (js/chrome.tabs.get tab-id
                      (fn [tab]
                        (when-not js/chrome.runtime.lastError
                          (let [url (.-url tab)
                                n (count-pending-for-url url)]
                            (set-badge! n))))))

(defn update-badge-for-active-tab!
  "Update badge based on pending count for the active tab."
  []
  (-> (get-active-tab-id)
      (.then (fn [tab-id]
               (when tab-id
                 (update-badge-for-tab! tab-id))))))

(defn clear-pending-approval!
  "Remove a specific script/pattern from pending approvals and update badge."
  [script-id pattern]
  (let [approval-id (str script-id "|" pattern)]
    (swap! !state update :pending/approvals dissoc approval-id))
  (update-badge-for-active-tab!))

(defn sync-pending-approvals!
  "Sync pending approvals atom with storage state.
   Removes stale entries for deleted/disabled scripts or approved patterns."
  []
  (doseq [[approval-id context] (:pending/approvals @!state)]
    (let [script-id (:script/id context)
          pattern (:approval/pattern context)
          script (storage/get-script script-id)]
      (when (or (nil? script)
                (not (:script/enabled script))
                (storage/pattern-approved? script pattern))
        (swap! !state update :pending/approvals dissoc approval-id))))
  (update-badge-for-active-tab!))

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

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
;; Unused for now, until we know if we want to keep injecting user scripts
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
  "Execute a list of scripts in the page via Scittle using script tag injection.
   Injects content bridge, then userscript tags, then triggers Scittle to evaluate them."
  [tab-id scripts]
  (when (seq scripts)
    (let [trigger-url (js/chrome.runtime.getURL "trigger-scittle.js")]
      ;; First ensure content bridge is loaded
      (-> (inject-content-script tab-id "content-bridge.js")
          (.then (fn [_]
                   ;; Small delay for content script to initialize
                   (js/Promise. (fn [resolve] (js/setTimeout resolve 100)))))
          ;; Clear any old userscript tags (prevents re-execution on bfcache navigation)
          (.then (fn [_]
                   (js/Promise.
                    (fn [resolve reject]
                      (js/chrome.tabs.sendMessage
                       tab-id
                       #js {:type "clear-userscripts"}
                       (fn [response]
                         (if js/chrome.runtime.lastError
                           (reject (js/Error. (.-message js/chrome.runtime.lastError)))
                           (resolve response))))))))
          ;; Then inject all userscript tags
          (.then (fn [_]
                   (js/Promise.all
                    (clj->js
                     (map (fn [script]
                            (js/Promise.
                             (fn [resolve reject]
                               (js/chrome.tabs.sendMessage
                                tab-id
                                #js {:type "inject-userscript"
                                     :id (str "userscript-" (:script/id script))
                                     :code (:script/code script)}
                                (fn [response]
                                  (if js/chrome.runtime.lastError
                                    (reject (js/Error. (.-message js/chrome.runtime.lastError)))
                                    (do
                                      (js/console.log "[Userscript]" (:script/name script) "tag injected")
                                      (resolve response))))))))
                          scripts)))))
          ;; Then trigger Scittle to evaluate them
          (.then (fn [_]
                   (js/Promise.
                    (fn [resolve reject]
                      (js/chrome.tabs.sendMessage
                       tab-id
                       #js {:type "inject-script"
                            :url trigger-url}
                       (fn [response]
                         (if js/chrome.runtime.lastError
                           (reject (js/Error. (.-message js/chrome.runtime.lastError)))
                           (do
                             (js/console.log "[Userscript] Triggered Scittle evaluation")
                             (resolve response)))))))))
          (.catch (fn [err]
                    (js/console.error "[Userscript] Injection error:" err)))))))

(defn get-pending-approvals
  "Get all pending approvals as a vector for popup"
  []
  (let [approvals (vec (vals (:pending/approvals @!state)))]
    (js/console.log "[Background] get-pending-approvals called, count:" (count approvals))
    approvals))

(defn handle-approval!
  "Handle approval response from popup"
  [approval-id approved?]
  (when-let [context (get-in @!state [:pending/approvals approval-id])]
    (let [script-id (:script/id context)
          script-name (:script/name context)
          script-code (:script/code context)
          pattern (:approval/pattern context)
          tab-id (:approval/tab-id context)]
      (swap! !state update :pending/approvals dissoc approval-id)
      (if approved?
        (do
          (js/console.log "[Approval] User approved" script-name "for" pattern)
          (storage/approve-pattern! script-id pattern)
          (-> (ensure-scittle! tab-id)
              (.then (fn [_]
                       (execute-scripts! tab-id
                                         [{:script/id script-id
                                           :script/name script-name
                                           :script/code script-code}])))
              (.catch (fn [err]
                        (js/console.error "[Approval] Failed to execute after approval:" err)))))
        (do
          (js/console.log "[Approval] User denied" script-name "for" pattern)
          (storage/toggle-script! script-id)))
      ;; Update badge after approval/denial
      (update-badge-for-active-tab!))))

;; Listen for messages from content scripts and popup
(.addListener js/chrome.runtime.onMessage
              (fn [message sender send-response]
                (let [tab-id (when (.-tab sender) (.. sender -tab -id))
                      msg-type (.-type message)]
                  (case msg-type
        ;; Content script messages
                    "ws-connect" (do (handle-ws-connect tab-id (.-port message)) false)
                    "ws-send" (do (handle-ws-send tab-id (.-data message)) false)
                    "ws-close" (do (handle-ws-close tab-id) false)
                    "ping" false
        ;; Popup messages
                    "refresh-approvals"
                    (do
          ;; Reload scripts from storage, then sync pending + badge
                      (-> (storage/load!)
                          (.then (fn [_]
                                   (sync-pending-approvals!))))
                      false)
                    "pattern-approved"
        ;; Clear this script from pending and execute it
                    (let [script-id (.-scriptId message)
                          pattern (.-pattern message)]
                      (clear-pending-approval! script-id pattern)
          ;; Execute the script now
                      (when-let [script (storage/get-script script-id)]
                        (-> (get-active-tab-id)
                            (.then (fn [active-tab-id]
                                     (when active-tab-id
                                       (-> (ensure-scittle! active-tab-id)
                                           (.then (fn [_]
                                                    (execute-scripts! active-tab-id [script])))))))))
                      false)
        ;; Legacy - keep for now
                    "get-pending-approvals"
                    (do (send-response (clj->js (get-pending-approvals)))
                        true)
                    "handle-approval"
                    (do (handle-approval! (.-approvalId message) (.-approved message))
                        (send-response #js {:ok true})
                        true)
        ;; Unknown
                    (do (js/console.log "[Background] Unknown message type:" msg-type)
                        false)))))

(defn request-approval!
  "Add script to pending approvals (for popup display) and update badge.
   Uses script-id + pattern as key to prevent duplicates on page reload."
  [script pattern tab-id _url]
  (let [approval-id (str (:script/id script) "|" pattern)]
    ;; Only add if not already pending
    (when-not (get-in @!state [:pending/approvals approval-id])
      (swap! !state update :pending/approvals assoc approval-id
             {:approval/id approval-id
              :script/id (:script/id script)
              :script/name (:script/name script)
              :script/code (:script/code script)
              :approval/pattern pattern
              :approval/tab-id tab-id})
      (js/console.log "[Approval] Pending approval for" (:script/name script) "on pattern" pattern)))
  ;; Always update badge from source of truth
  (update-badge-for-tab! tab-id))

(defn handle-navigation!
  "Handle page navigation: find matching scripts, check approvals, execute or prompt.
   Scripts with approved patterns run immediately; others trigger approval notifications."
  [tab-id url]
  (let [scripts (url-matching/get-matching-scripts url)]
    (when (seq scripts)
      (js/console.log "[Auto-inject] Found" (count scripts) "scripts for" url)
      (let [script-contexts (map (fn [script]
                                   (let [pattern (url-matching/get-matching-pattern url script)]
                                     {:script script
                                      :pattern pattern
                                      :approved? (storage/pattern-approved? script pattern)}))
                                 scripts)
            approved (filter :approved? script-contexts)
            unapproved (remove :approved? script-contexts)]
        (when (seq approved)
          (js/console.log "[Auto-inject] Executing" (count approved) "approved scripts")
          (-> (ensure-scittle! tab-id)
              (.then (fn [_]
                       (execute-scripts! tab-id (map :script approved))))
              (.catch (fn [err]
                        (js/console.error "[Auto-inject] Failed:" (.-message err))))))
        (doseq [{:keys [script pattern]} unapproved]
          (js/console.log "[Auto-inject] Requesting approval for" (:script/name script))
          (request-approval! script pattern tab-id url))))))

;; Clean up when tab is closed
(.addListener js/chrome.tabs.onRemoved
              (fn [tab-id _remove-info]
                (when (get-ws tab-id)
                  (js/console.log "[Background] Tab closed, cleaning up:" tab-id)
                  (close-ws! tab-id))))

(.addListener js/chrome.webNavigation.onCompleted
              (fn [details]
    ;; Only handle main frame (not iframes)
                (when (zero? (.-frameId details))
                  (handle-navigation! (.-tabId details) (.-url details)))))

(js/console.log "[Browser Jack-in Background] Message listeners registered")
