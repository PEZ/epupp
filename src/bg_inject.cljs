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

(defn execute-in-isolated
  "Execute a function in ISOLATED world (content script context).
   Returns a promise. Safe from page CSP restrictions."
  [tab-id func & args]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.scripting.executeScript
      #js {:target #js {:tabId tab-id}
           :world "ISOLATED"
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
     (log/debug "Background:Inject" "Injecting" file "into tab" tab-id)
     (js/chrome.scripting.executeScript
      #js {:target #js {:tabId tab-id}
           :files #js [file]}
      (fn [results]
        (log/debug "Background:Inject" "executeScript callback, results:" results "lastError:" js/chrome.runtime.lastError)
        (if js/chrome.runtime.lastError
          (do
            (log/error "Background:Inject" "Error:" (.-message js/chrome.runtime.lastError))
            (reject (js/Error. (.-message js/chrome.runtime.lastError))))
          (do
            (log/debug "Background:Inject" "Success, results:" (js/JSON.stringify results))
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
            createHTML: function(s) { return s; },
            createScript: function(s) { return s; },
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

(def check-namespaces-fn
  "JavaScript function to check if specific namespaces are registered in Scittle.
   Takes an array of namespace name strings.
   Returns {available: true} when all are found, or {available: false, missing: [...]}."
  (js* "function(namespaces) {
    if (!window.scittle || !window.scittle.core || !window.scittle.core.eval_string) {
      return {available: false, missing: namespaces};
    }
    var missing = [];
    for (var i = 0; i < namespaces.length; i++) {
      var nsName = namespaces[i];
      if (!/^[a-zA-Z][a-zA-Z0-9._-]*$/.test(nsName)) {
        missing.push(nsName);
        continue;
      }
      try {
        var result = window.scittle.core.eval_string('(boolean (find-ns \\'' + nsName + '))');
        if (!result) missing.push(nsName);
      } catch(e) {
        missing.push(nsName);
      }
    }
    return {available: missing.length === 0, missing: missing};
  }"))

(def trigger-scittle-fn
  "JavaScript function to trigger Scittle evaluation of script tags.
   Ensures a broad TrustedTypes default policy before evaluation.
   Called directly via chrome.scripting.executeScript."
  (js* "function() {
    if (window.trustedTypes && window.trustedTypes.createPolicy && !window.trustedTypes.defaultPolicy) {
      try {
        window.trustedTypes.createPolicy('default', {
          createHTML: function(s) { return s; },
          createScript: function(s) { return s; },
          createScriptURL: function(s) { return s; }
        });
      } catch(e) {
        console.warn('[Epupp] TrustedTypes default policy creation failed:', e.message);
      }
    }
    if (window.scittle && window.scittle.core && window.scittle.core.eval_script_tags) {
      window.scittle.core.eval_script_tags();
      return true;
    }
    return false;
  }"))

;; ============================================================
;; Polling Utilities
;; ============================================================

(def scan-for-userscripts-fn
  "Scan page DOM for code blocks containing Epupp userscript manifests.
   Checks specific formats first (GitHub, GitLab), then generic <pre> and
   <textarea>. Returns early on first match: {found: true/false}."
  (js* "function() {
    function hasManifest(text) {
      if (!text || text.length < 10) return false;
      var trimmed = text.trimStart();
      if (trimmed.charAt(0) !== '{') return false;
      return /:epupp\\/script-name/.test(trimmed.slice(0, 500));
    }
    // 1. GitHub gist tables
    var tables = document.querySelectorAll('table.js-file-line-container');
    for (var i = 0; i < tables.length; i++) {
      var lines = tables[i].querySelectorAll('td.js-file-line');
      var text = '';
      for (var j = 0; j < lines.length; j++) text += lines[j].textContent + '\\n';
      if (hasManifest(text)) return {found: true};
    }
    // 2. GitHub repo file view
    var repoCode = document.querySelector('.react-code-lines');
    if (repoCode && hasManifest(repoCode.textContent)) return {found: true};
    // 3. GitLab snippets
    var holders = document.querySelectorAll('.file-holder');
    for (var i = 0; i < holders.length; i++) {
      var pre = holders[i].querySelector('pre');
      if (pre && hasManifest(pre.textContent)) return {found: true};
    }
    // 4. Generic <pre>
    var pres = document.querySelectorAll('pre');
    for (var i = 0; i < pres.length; i++) {
      if (hasManifest(pres[i].textContent)) return {found: true};
    }
    // 5. Textareas
    var textareas = document.querySelectorAll('textarea');
    for (var i = 0; i < textareas.length; i++) {
      if (hasManifest(textareas[i].value)) return {found: true};
    }
    return {found: false};
  }"))

(defn poll-until
  "Poll a check function until success or timeout.
   timeout-message: Optional custom message for timeout errors."
  [check-fn success? timeout timeout-message]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)]
       (letfn [(poll []
                 (-> (check-fn)
                     (.then (fn [result]
                              (cond
                                (success? result) (resolve result)
                                (> (- (js/Date.now) start) timeout)
                                (reject (js/Error. (or timeout-message "Timeout")))
                                :else (js/setTimeout poll 100))))
                     (.catch reject)))]
         (poll))))))

;; ============================================================
;; Scittle Loading
;; ============================================================

(defn ^:async ensure-scittle!
  "Ensure Scittle is loaded in the page.
   icon-state: Current icon state for the tab (keyword, e.g. :connected, :disconnected)"
  [dispatch! tab-id icon-state]
  (let [status (js-await (execute-in-page tab-id check-scittle-fn))]
    (when-not (and status (.-hasScittle status))
      (let [scittle-url (js/chrome.runtime.getURL "vendor/scittle.js")]
        (js-await (execute-in-page tab-id inject-script-fn scittle-url false))
        (js-await (poll-until
                   (fn [] (execute-in-page tab-id check-scittle-fn))
                   (fn [r] (and r (.-hasScittle r)))
                   5000
                   "Timeout waiting for Scittle"))
        ;; Update icon to show Scittle is injected (stays disconnected/white)
        ;; Only if not already connected (gold)
        (when (not= :connected icon-state)
          (js-await (bg-icon/update-icon-for-tab! dispatch! tab-id :disconnected)))
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
                        (log/debug "Background:Bridge" "Content bridge ready for tab:" tab-id)
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

(defn ^:async inject-libs-sequentially!
  "Inject library files sequentially, awaiting each load.
   Checks each response for errors before continuing.
   Uses loop/recur instead of doseq because doseq doesn't properly
   await js-await calls in Squint."
  [tab-id files]
  (loop [remaining files]
    (when (seq remaining)
      (let [file (first remaining)
            url (js/chrome.runtime.getURL (str "vendor/" file))
            response (js-await (send-tab-message tab-id {:type "inject-script" :url url}))]
        (when (and response (false? (.-success response)))
          (throw (js/Error. (str "Failed to inject library " file ": "
                                 (or (.-error response) "unknown error")))))
        (recur (rest remaining))))))

(defn ^:async execute-scripts!
  "Execute a list of scripts in the page via Scittle using script tag injection.
   Injects content bridge, waits for readiness signal, injects required Scittle
   libraries, verifies namespace availability, then injects userscripts."
  [tab-id scripts]
  ;; Log test event at start for E2E tests
  (js-await (test-logger/log-event! "EXECUTE_SCRIPTS_START" {:tab-id tab-id :count (count scripts)}))
  (when (seq scripts)
    (try
      (let [;; Collect all required library files from scripts
            lib-files (scittle-libs/collect-lib-files scripts)]
        ;; First ensure content bridge is loaded
        (js-await (inject-content-script tab-id "content-bridge.js"))
        (js-await (test-logger/log-event! "BRIDGE_INJECTED" {:tab-id tab-id}))
        ;; Wait for bridge to signal readiness via ping response
        (js-await (wait-for-bridge-ready tab-id))
        (js-await (test-logger/log-event! "BRIDGE_READY_CONFIRMED" {:tab-id tab-id}))
        ;; Clear any old userscript tags (prevents re-execution on bfcache navigation)
        (js-await (send-tab-message tab-id {:type "clear-userscripts"}))
        ;; Inject required Scittle libraries (in dependency order)
        (when (seq lib-files)
          (js-await (test-logger/log-event! "INJECTING_LIBS" {:files lib-files}))
          ;; Use sequential await helper - doseq doesn't await properly in Squint
          (js-await (inject-libs-sequentially! tab-id lib-files))
          (js-await (test-logger/log-event! "LIBS_INJECTED" {:count (count lib-files)}))
          ;; Verify namespace availability before evaluation
          (let [expected-ns (scittle-libs/collect-lib-namespaces scripts)]
            (when (seq expected-ns)
              (js-await (poll-until
                         (fn [] (execute-in-page tab-id check-namespaces-fn expected-ns))
                         (fn [r] (and r (.-available r)))
                         5000
                         (str "Timeout waiting for library namespaces: "
                              (.join expected-ns ", "))))
              (js-await (test-logger/log-event! "NAMESPACES_VERIFIED" {:namespaces expected-ns})))))
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
        (log/error "Background:Inject" "Userscript injection error:" err)
        (js-await (test-logger/log-event! "EXECUTE_SCRIPTS_ERROR" {:error (.-message err)}))))))

(defn ^:async inject-installer!
  "Inject the web userscript installer on a tab.
   Ensures Scittle is loaded, injects dependency libraries,
   injects the installer code, and triggers evaluation."
  [dispatch! tab-id installer-script]
  (js-await (ensure-scittle! dispatch! tab-id :disconnected))
  (js-await (execute-scripts! tab-id [installer-script])))
