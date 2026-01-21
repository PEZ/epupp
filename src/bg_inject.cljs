(ns bg-inject
  "Scittle injection pipeline for userscripts.
   Handles loading Scittle, content bridge, and script execution."
  (:require [log :as log]
            [test-logger :as test-logger]
            [scittle-libs :as scittle-libs]
            [bg-icon :as bg-icon]))

;; ============================================================
;; Page Context Execution
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
     (log/info "Background" "Inject" "Injecting" file "into tab" tab-id)
     (js/chrome.scripting.executeScript
      #js {:target #js {:tabId tab-id}
           :files #js [file]}
      (fn [results]
        (log/info "Background" "Inject" "executeScript callback, results:" results "lastError:" js/chrome.runtime.lastError)
        (if js/chrome.runtime.lastError
          (do
            (log/error "Background" "Inject" "Error:" (.-message js/chrome.runtime.lastError))
            (reject (js/Error. (.-message js/chrome.runtime.lastError))))
          (do
            (log/info "Background" "Inject" "Success, results:" (js/JSON.stringify results))
            (resolve true))))))))

;; ============================================================
;; Page-Context Functions (pure JS, no Squint runtime)
;; ============================================================

(def inject-script-fn
  (js* "function(url, isModule) {
    var script = document.createElement('script');
    if (isModule) script.type = 'module';
    if (window.trustedTypes && window.trustedTypes.createPolicy) {
      try {
        var policy = window.trustedTypes.defaultPolicy;
        if (!policy) {
          policy = window.trustedTypes.createPolicy('default', {
            createScriptURL: function(s) { return s; }
          });
        }
        script.src = policy.createScriptURL(url);
      } catch(e) {
        console.warn('[Epupp] TrustedTypes policy creation failed, using direct assignment:', e.message);
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

(def trigger-scittle-fn
  "JavaScript function to trigger Scittle evaluation of script tags.
   Called directly via chrome.scripting.executeScript."
  (js* "function() {
    if (window.scittle && window.scittle.core && window.scittle.core.eval_script_tags) {
      window.scittle.core.eval_script_tags();
      return true;
    }
    return false;
  }"))

;; ============================================================
;; Polling Utilities
;; ============================================================

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

;; ============================================================
;; Scittle Loading
;; ============================================================

(defn ^:async ensure-scittle!
  "Ensure Scittle is loaded in the page.
   Parameters:
   - !state: State atom for icon state checking
   - dispatch!: Dispatch function for icon updates
   - tab-id: Target tab ID"
  [!state dispatch! tab-id]
  (let [status (js-await (execute-in-page tab-id check-scittle-fn))]
    (when-not (and status (.-hasScittle status))
      (let [scittle-url (js/chrome.runtime.getURL "vendor/scittle.js")]
        (js-await (execute-in-page tab-id inject-script-fn scittle-url false))
        (js-await (poll-until
                   (fn [] (execute-in-page tab-id check-scittle-fn))
                   (fn [r] (and r (.-hasScittle r)))
                   5000))
        ;; Update icon to show Scittle is injected (yellow bolt)
        ;; Only if not already connected (green)
        (when (not= :connected (bg-icon/get-icon-state !state tab-id))
          (js-await (bg-icon/update-icon-for-tab! dispatch! tab-id :injected)))
        ;; Log test event for E2E tests (after icon update so tests see stable state)
        (js-await (test-logger/log-event! "SCITTLE_LOADED" {:tab-id tab-id}))))
    true))

;; ============================================================
;; Bridge Communication
;; ============================================================

(defn wait-for-bridge-ready
  "Wait for content bridge to be ready by pinging it.
   Returns a promise that resolves when bridge responds."
  [tab-id]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)
           timeout 5000]
       (letfn [(ping []
                 (js/chrome.tabs.sendMessage
                  tab-id
                  #js {:type "bridge-ping"}
                  (fn [response]
                    (cond
                      ;; Success - bridge responded
                      (and response (.-ready response))
                      (do
                        (log/info "Background" "Bridge" "Content bridge ready for tab:" tab-id)
                        (resolve true))

                      ;; Timeout
                      (> (- (js/Date.now) start) timeout)
                      (reject (js/Error. "Timeout waiting for content bridge"))

                      ;; Not ready yet or error - retry
                      :else
                      (js/setTimeout ping 50)))))]
         (ping))))))

(defn send-tab-message
  "Send message to a tab and return a promise."
  [tab-id message]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.tabs.sendMessage
      tab-id
      (clj->js message)
      (fn [response]
        (if js/chrome.runtime.lastError
          (reject (js/Error. (.-message js/chrome.runtime.lastError)))
          (resolve response)))))))

;; ============================================================
;; Script Injection
;; ============================================================

(defn ^:async inject-requires-sequentially!
  "Inject required library files sequentially, awaiting each load.
   Uses loop/recur instead of doseq because doseq doesn't properly
   await js-await calls in Squint."
  [tab-id files]
  (loop [remaining files]
    (when (seq remaining)
      (let [url (js/chrome.runtime.getURL (str "vendor/" (first remaining)))]
        (js-await (send-tab-message tab-id {:type "inject-script" :url url}))
        (recur (rest remaining))))))

(defn ^:async execute-scripts!
  "Execute a list of scripts in the page via Scittle using script tag injection.
   Injects content bridge, waits for readiness signal, injects required Scittle
   libraries, then injects userscripts."
  [tab-id scripts]
  ;; Log test event at start for E2E tests
  (js-await (test-logger/log-event! "EXECUTE_SCRIPTS_START" {:tab-id tab-id :count (count scripts)}))
  (when (seq scripts)
    (try
      (let [;; Collect all required library files from scripts
            require-files (scittle-libs/collect-require-files scripts)]
        ;; First ensure content bridge is loaded
        (js-await (inject-content-script tab-id "content-bridge.js"))
        (js-await (test-logger/log-event! "BRIDGE_INJECTED" {:tab-id tab-id}))
        ;; Wait for bridge to signal readiness via ping response
        (js-await (wait-for-bridge-ready tab-id))
        (js-await (test-logger/log-event! "BRIDGE_READY_CONFIRMED" {:tab-id tab-id}))
        ;; Clear any old userscript tags (prevents re-execution on bfcache navigation)
        (js-await (send-tab-message tab-id {:type "clear-userscripts"}))
        ;; Inject required Scittle libraries (in dependency order)
        (when (seq require-files)
          (js-await (test-logger/log-event! "INJECTING_REQUIRES" {:files require-files}))
          ;; Use sequential await helper - doseq doesn't await properly in Squint
          (js-await (inject-requires-sequentially! tab-id require-files))
          (js-await (test-logger/log-event! "REQUIRES_INJECTED" {:count (count require-files)})))
        ;; Inject all userscript tags
        (js-await
         (js/Promise.all
          (clj->js
           (map (fn [script]
                  (-> (send-tab-message tab-id {:type "inject-userscript"
                                                :id (str "userscript-" (:script/id script))
                                                :code (:script/code script)})
                      (.then (fn [_]
                               ;; Log test event for E2E tests - return it so Promise.all waits
                               (test-logger/log-event! "SCRIPT_INJECTED"
                                                       {:script-id (:script/id script)
                                                        :script-name (:script/name script)
                                                        :timing (or (:script/run-at script) "document-idle")
                                                        :tab-id tab-id})))))
                scripts))))
        ;; Trigger Scittle to evaluate them - use direct execution to avoid
        ;; duplicate script injection check which would skip if already injected
        (js-await (execute-in-page tab-id trigger-scittle-fn)))
      (catch :default err
        (log/error "Background" "Inject" "Userscript injection error:" err)
        (js-await (test-logger/log-event! "EXECUTE_SCRIPTS_ERROR" {:error (.-message err)}))))))
