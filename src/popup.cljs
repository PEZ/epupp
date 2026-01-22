(ns popup
  "Epupp extension popup - built with Squint + Reagami
   Inspired by Replicant tic-tac-toe state management pattern"
  (:require [reagami :as r]
            [event-handler :as event-handler]
            [icons :as icons]
            [script-utils :as script-utils]
            [popup-utils :as popup-utils]
            [popup-actions :as popup-actions]
            [log :as log]
            [view-elements :as view-elements]
            [test-logger :as test-logger]
            [clojure.string :as str]))

;; EXTENSION_CONFIG is injected by esbuild at bundle time from config/*.edn
;; Shape: {"dev": boolean, "depsString": string}
(def ^:private config js/EXTENSION_CONFIG)

(defonce !state
  (atom {:ports/nrepl "1339"
         :ports/ws "1340"
         :ui/copy-feedback nil
         :ui/reveal-highlight-script-name nil ; Temporary highlight when revealing a script
         :ui/sections-collapsed {:repl-connect false      ; expanded by default
                                 :matching-scripts false  ; expanded by default
                                 :other-scripts false     ; expanded by default
                                 :settings true}          ; collapsed by default
         :browser/brave? false
         :scripts/list []         ; All userscripts
         :scripts/current-url nil ; Current tab URL for matching
         :scripts/current-tab-id nil ; Current tab ID for connection check
         :settings/user-origins []    ; User-added allowed origins
         :settings/new-origin ""      ; Input field for new origin
         :settings/default-origins [] ; Config origins (read-only)
         :settings/auto-connect-repl false ; Auto-connect REPL on page load
         :settings/auto-reconnect-repl true ; Auto-reconnect to previously connected tabs (default on)
         :settings/fs-repl-sync-enabled false ; Allow REPL to write scripts (default off)
         :ui/system-banner nil          ; System banner {:type :success/:error :message "..."}
         :ui/system-bulk-names {}      ; bulk-id -> [script-name ...]
         :repl/connections []}))   ; Pending FS operation confirmations



(defn generate-server-cmd [{:keys [ports/nrepl ports/ws]}]
  (popup-utils/generate-server-cmd {:deps-string (.-depsString config)
                                    :nrepl-port nrepl
                                    :ws-port ws}))

;; ============================================================
;; Tab and storage helpers
;; =============================================================

(defn get-active-tab
  "Gets the active tab. In tests, checks for window.__scittle_tamper_test_url override."
  []
  (js/Promise.
   (fn [resolve]
     ;; Test override: if window.__scittle_tamper_test_url is set, return a mock tab
     (if-let [test-url js/window.__scittle_tamper_test_url]
       (resolve #js {:id -1 :url test-url})
       ;; Normal: query Chrome tabs API
       (js/chrome.tabs.query
        #js {:active true :currentWindow true}
        (fn [tabs] (resolve (first tabs))))))))

(defn get-hostname [tab]
  (try
    (.-hostname (js/URL. (.-url tab)))
    (catch :default _ "default")))

(defn storage-key [tab]
  (str "ports_" (get-hostname tab)))

;; ============================================================
;; Script storage helpers
;; ============================================================

(defn- save-scripts! [scripts]
  (js/chrome.storage.local.set
   #js {:scripts (clj->js (mapv script-utils/script->js scripts))}))



;; ============================================================
;; Effect helpers (side-effecting, but factored for clarity)
;; ============================================================

(defn ^:async save-ports-to-storage!
  "Persist port configuration to chrome.storage.local."
  [{:keys [ports/nrepl ports/ws]}]
  (let [tab (js-await (get-active-tab))
        key (storage-key tab)]
    (js/chrome.storage.local.set
     (clj->js {key {:nreplPort nrepl :wsPort ws}}))))

(defn persist-and-notify-scripts!
  "Save scripts to storage."
  [scripts _notify-type]
  (save-scripts! scripts))

;; ============================================================
;; Uniflow Dispatch
;; ============================================================

(defn ^:async perform-effect! [dispatch [effect & args]]
  (case effect
    :popup/fx.save-ports
    (let [[ports] args]
      (save-ports-to-storage! ports))

    :popup/fx.copy-command
    (let [[cmd] args]
      (js-await (js/navigator.clipboard.writeText cmd))
      (dispatch [[:db/ax.assoc :ui/copy-feedback "Copied!"]])
      (js/setTimeout (fn [] (dispatch [[:db/ax.assoc :ui/copy-feedback nil]])) 1500))

    :popup/fx.connect
    (let [[port] args
          tab (js-await (get-active-tab))]
      (dispatch [[:popup/ax.show-system-banner "info" "Connecting..." {}]])
      (try
        (let [resp (js-await
                    (js/Promise.
                     (fn [resolve reject]
                       (js/chrome.runtime.sendMessage
                        #js {:type "connect-tab"
                             :tabId (.-id tab)
                             :wsPort port}
                        (fn [response]
                          (if js/chrome.runtime.lastError
                            (reject (js/Error. (.-message js/chrome.runtime.lastError)))
                            (resolve response)))))))]
          (if (and resp (.-success resp))
            ;; Success - show banner (auto-clears after 2s)
            (dispatch [[:popup/ax.show-system-banner "success" "Connected!" {}]])
            ;; Failure from background worker
            (dispatch [[:popup/ax.show-system-banner "error" (str "Failed: " (or (and resp (.-error resp)) "Connect failed")) {}]])))
        (catch :default err
          (dispatch [[:popup/ax.show-system-banner "error" (str "Failed: " (.-message err)) {}]]))))

    :popup/fx.check-status
    (let [[_ws-port] args
          tab (js-await (get-active-tab))]
      (try
        (js/Promise.
         (fn [resolve reject]
           (js/chrome.runtime.sendMessage
            #js {:type "check-status"
                 :tabId (.-id tab)}
            (fn [response]
              (if js/chrome.runtime.lastError
                (reject (js/Error. (.-message js/chrome.runtime.lastError)))
                (resolve response))))))
        (catch :default _err
          nil)))

    :popup/fx.load-saved-ports
    ;; chrome.storage.local.get uses callback API, keep as-is
    (let [tab (js-await (get-active-tab))
          key (storage-key tab)]
      (js/chrome.storage.local.get
       #js [key]
       (fn [result]
         (when-let [saved (aget result key)]
           (let [actions (cond-> []
                           (.-nreplPort saved)
                           (conj [:db/ax.assoc :ports/nrepl (str (.-nreplPort saved))])
                           (.-wsPort saved)
                           (conj [:db/ax.assoc :ports/ws (str (.-wsPort saved))]))]
             (when (seq actions)
               (dispatch actions)))))))

    :popup/fx.load-scripts
    ;; chrome.storage.local.get uses callback API, keep as-is
    (js/chrome.storage.local.get
     #js ["scripts"]
     (fn [result]
       (let [scripts (script-utils/parse-scripts (.-scripts result))]
         (dispatch [[:db/ax.assoc :scripts/list scripts]]))))

    :popup/fx.toggle-script
    (let [[scripts script-id _matching-pattern] args
          updated (popup-utils/toggle-script-in-list scripts script-id)]
      (persist-and-notify-scripts! updated :refresh)
      (dispatch [[:db/ax.assoc :scripts/list updated]]))

    :popup/fx.delete-script
    (let [[scripts script-id] args
          updated (popup-utils/remove-script-from-list scripts script-id)]
      (save-scripts! updated)
      (dispatch [[:db/ax.assoc :scripts/list updated]]))

    :popup/fx.inspect-script
    (let [[script] args]
      ;; Store script for panel to pick up
      (js/chrome.storage.local.set
       #js {:editingScript #js {:id (:script/id script)
                                :name (:script/name script)
                                :match (first (:script/match script))
                                :code (:script/code script)
                                :description (:script/description script)}}))

    :uf/fx.defer-dispatch
    (let [[actions timeout] args]
      (js/setTimeout #(dispatch actions) timeout))

    :popup/fx.load-current-url
    (let [tab (js-await (get-active-tab))]
      (dispatch [[:db/ax.assoc
                  :scripts/current-url (.-url tab)
                  :scripts/current-tab-id (.-id tab)]]))

    :popup/fx.evaluate-script
    (let [[script] args
          tab (js-await (get-active-tab))]
      (js/chrome.runtime.sendMessage
       #js {:type "evaluate-script"
            :tabId (.-id tab)
            :scriptId (:script/id script)
            :code (:script/code script)
            :require (clj->js (:script/require script))}))

    :popup/fx.load-user-origins
    (js/chrome.storage.local.get
     #js ["userAllowedOrigins"]
     (fn [result]
       (let [user-origins (if (.-userAllowedOrigins result)
                            (vec (.-userAllowedOrigins result))
                            [])
             default-origins (or (.-allowedScriptOrigins config) [])]
         (dispatch [[:db/ax.assoc
                     :settings/user-origins user-origins
                     :settings/default-origins (vec default-origins)]]))))

    :popup/fx.add-user-origin
    (let [[origin] args]
      (js/chrome.storage.local.get
       #js ["userAllowedOrigins"]
       (fn [result]
         (let [current (if (.-userAllowedOrigins result)
                         (vec (.-userAllowedOrigins result))
                         [])
               updated (conj current origin)]
           (js/chrome.storage.local.set
            #js {:userAllowedOrigins (clj->js updated)})))))

    :popup/fx.remove-user-origin
    (let [[origin] args]
      (js/chrome.storage.local.get
       #js ["userAllowedOrigins"]
       (fn [result]
         (let [current (if (.-userAllowedOrigins result)
                         (vec (.-userAllowedOrigins result))
                         [])
               updated (filterv #(not= % origin) current)]
           (js/chrome.storage.local.set
            #js {:userAllowedOrigins (clj->js updated)})))))

    :popup/fx.load-auto-connect-setting
    (js/chrome.storage.local.get
     #js ["autoConnectRepl"]
     (fn [result]
       (let [enabled (if (some? (.-autoConnectRepl result))
                       (.-autoConnectRepl result)
                       false)]
         (dispatch [[:db/ax.assoc :settings/auto-connect-repl enabled]]))))

    :popup/fx.save-auto-connect-setting
    (let [[enabled] args]
      (js/chrome.storage.local.set #js {:autoConnectRepl enabled}))

    :popup/fx.load-auto-reconnect-setting
    (js/chrome.storage.local.get
     #js ["autoReconnectRepl"]
     (fn [result]
       (let [enabled (if (some? (.-autoReconnectRepl result))
                       (.-autoReconnectRepl result)
                       true)]  ; Default to true
         (dispatch [[:db/ax.assoc :settings/auto-reconnect-repl enabled]]))))

    :popup/fx.save-auto-reconnect-setting
    (let [[enabled] args]
      (js/chrome.storage.local.set #js {:autoReconnectRepl enabled}))

    :popup/fx.load-fs-sync-setting
    (js/chrome.storage.local.get
     #js ["fsReplSyncEnabled"]
     (fn [result]
       (let [enabled (if (some? (.-fsReplSyncEnabled result))
                       (.-fsReplSyncEnabled result)
                       false)]  ; Default to false (safe)
         (dispatch [[:db/ax.assoc :settings/fs-repl-sync-enabled enabled]]))))

    :popup/fx.save-fs-sync-setting
    (let [[enabled] args]
      (js/chrome.storage.local.set #js {:fsReplSyncEnabled enabled}))

    :popup/fx.dump-dev-log
    ;; Fetch test events from storage and console.log with a marker
    ;; Playwright can capture this via page.on('console')
    (js/chrome.storage.local.get
     #js ["test-events"]
     (fn [result]
       ;; Use aget for hyphenated key access (.-test-events becomes .test_events in Squint)
       (let [events (or (aget result "test-events") #js [])]
         ;; Use a unique marker that Playwright can identify
         (js/console.log "__EPUPP_DEV_LOG__" (js/JSON.stringify events)))))

    :popup/fx.export-scripts
    ;; Export user scripts (not built-ins) as JSON file download
    (js/chrome.storage.local.get
     #js ["scripts"]
     (fn [result]
       (let [all-scripts (or (.-scripts result) #js [])
             ;; Filter out built-in scripts using native JS filter
             user-scripts (.filter all-scripts
                                   (fn [s]
                                     (let [id (.-id s)]
                                       (not (and id (.startsWith id "epupp-builtin-"))))))
             json-str (js/JSON.stringify user-scripts nil 2)
             blob (js/Blob. #js [json-str] #js {:type "application/json"})
             url (js/URL.createObjectURL blob)
             link (js/document.createElement "a")
             filename (str "epupp-scripts-" (.toISOString (js/Date.)) ".json")]
         (set! (.-href link) url)
         (set! (.-download link) filename)
         (js/document.body.appendChild link)
         (.click link)
         (js/document.body.removeChild link)
         (js/URL.revokeObjectURL url))))

    :popup/fx.trigger-import
    ;; Create a hidden file input and trigger click
    (let [input (js/document.createElement "input")]
      (set! (.-type input) "file")
      (set! (.-accept input) ".json")
      (set! (.-onchange input)
            (fn [e]
              (when-let [file (aget (.. e -target -files) 0)]
                (let [reader (js/FileReader.)]
                  (set! (.-onload reader)
                        (fn [e]
                          (try
                            (let [json-str (.. e -target -result)
                                  scripts (js/JSON.parse json-str)]
                              (dispatch [[:popup/ax.handle-import scripts]]))
                            (catch :default err
                              (js/alert (str "Failed to parse JSON: " (.-message err)))))))
                  (.readAsText reader file)))))
      (.click input))

    :popup/fx.import-scripts
    ;; Import scripts, preserving built-in scripts from current storage
    (let [[imported-scripts] args]
      (js/chrome.storage.local.get
       #js ["scripts"]
       (fn [result]
         (let [current-scripts (or (.-scripts result) #js [])
               ;; Keep only built-in scripts from current storage
               builtin-scripts (.filter current-scripts
                                        (fn [s]
                                          (let [id (.-id s)]
                                            (and id (.startsWith id "epupp-builtin-")))))
               ;; Filter out any built-ins from imported (safety)
               user-scripts (.filter imported-scripts
                                     (fn [s]
                                       (let [id (.-id s)]
                                         (not (and id (.startsWith id "epupp-builtin-"))))))
               ;; Merge: imported user scripts + current built-ins
               merged-scripts (.concat user-scripts builtin-scripts)]
           (js/chrome.storage.local.set
            #js {:scripts merged-scripts}
            (fn []
              (js/alert "Scripts imported successfully! Reloading...")
              (dispatch [[:popup/ax.load-scripts]])))))))

    :popup/fx.load-connections
    (js/chrome.runtime.sendMessage
     #js {:type "get-connections"}
     (fn [response]
       (when (and response (.-success response))
         ;; In Squint, data is already JS - no js->clj needed
         ;; Keywords are strings, so {:keys [tab-id]} works with "tab-id" keys
         (let [connections (.-connections response)]
           (dispatch [[:db/ax.assoc :repl/connections connections]])))))

    :popup/fx.reveal-script
    (let [[script-name] args
          el (js/document.querySelector (str ".script-item[data-script-name='" script-name "']"))]
      (when el
        (.scrollIntoView el #js {:block "center"}))
      nil)

    :popup/fx.reveal-tab
    (let [[tab-id] args
          ;; Tab IDs from state are strings in Squint, convert to number for Chrome API
          numeric-tab-id (js/parseInt tab-id 10)]
      (js/chrome.tabs.update numeric-tab-id #js {:active true}
                             (fn [_tab]
                               (when-not js/chrome.runtime.lastError
                                 ;; Also focus the window containing the tab
                                 (js/chrome.tabs.get numeric-tab-id
                                                     (fn [tab]
                                                       (when-not js/chrome.runtime.lastError
                                                         (js/chrome.windows.update (.-windowId tab) #js {:focused true}))))))))

    :popup/fx.disconnect-tab
    (let [[tab-id] args
          numeric-tab-id (js/parseInt tab-id 10)]
      (js/chrome.runtime.sendMessage
       #js {:type "disconnect-tab" :tabId numeric-tab-id}))

    :popup/fx.log-system-banner
    ;; TODO: Move to log module when it supports targeting specific consoles (page vs extension)
    (let [[message bulk-op? bulk-final? bulk-names] args]
      (if (and bulk-op? bulk-final? (seq bulk-names))
        (js/console.info "[Epupp:FS]" message (clj->js {:files bulk-names}))
        (js/console.info "[Epupp:FS]" message)))

    :uf/unhandled-fx))

(defn- make-uf-data []
  {:config/deps-string (.-depsString config)
   :config/allowed-origins (or (.-allowedScriptOrigins config) [])})

(defn dispatch! [actions]
  (event-handler/dispatch! !state popup-actions/handle-action perform-effect! actions (make-uf-data)))

(defn port-input [{:keys [id label value on-change]}]
  [:span
   [:label {:for id} label]
   [:input {:type "number"
            :id id
            :value value
            :min "1"
            :max "65535"
            :on-input (fn [e]
                        (on-change (.. e -target -value)))}]])

(defn command-box [{:keys [command copy-feedback]}]
  [:div.command-box
   [:code command]
   [view-elements/action-button
    {:button/variant :secondary
     :button/class "copy-btn"
     :button/icon icons/copy
     :button/size :lg
     :button/title "Copy browser-nrepl server command line. (You need Babashka to run it)"
     :button/on-click #(dispatch! [[:popup/ax.copy-command]])}
    copy-feedback]])

(defn collapsible-section [{:keys [id title expanded? badge-count max-height]} & children]
  [:div.collapsible-section {:class (when-not expanded? "collapsed")}
   [:div.section-header {:on-click #(dispatch! [[:popup/ax.toggle-section id]])}
    [icons/chevron-right {:class (str "chevron " (when expanded? "expanded"))}]
    [:span.section-title title]
    (when (and badge-count (pos? badge-count))
      [:span.section-badge badge-count])]
   (into [:div.section-content {:style (when (and expanded? max-height) {:max-height max-height})}] children)])

(defn- run-at-badge
  "Returns a badge component for non-default run-at timings."
  [run-at]
  (case run-at
    "document-start" [:span.run-at-badge {:title "Runs at document-start (before page loads)"}
                      [icons/rocket {:size 16}]]
    "document-end" [:span.run-at-badge {:title "Runs at document-end (when DOM is ready)"}
                    [icons/flag {:size 16}]]
    ;; document-idle (default) - no badge
    nil))

(defn- safe-pattern-display
  "Safely extract a displayable string from a pattern value.
   Handles malformed data (nested arrays, nil) defensively."
  [pattern]
  (cond
    (nil? pattern) nil
    (string? pattern) pattern
    ;; Vector/array - extract first element recursively
    (or (vector? pattern) (array? pattern))
    (safe-pattern-display (first pattern))
    ;; Fallback for unexpected types
    :else (str pattern)))

(defn script-item [{:keys [script/name script/match script/enabled script/description script/run-at]
                    script-id :script/id
                    :as script}
                   current-url
                   {:keys [reveal-highlight? recently-modified?]}]
  (let [matching-pattern (script-utils/get-matching-pattern current-url script)
        builtin? (script-utils/builtin-script? script)
        ;; All patterns for display - join for single row, newlines for tooltip
        patterns-display (when (seq match)
                           (->> match
                                (mapv safe-pattern-display)
                                (filterv some?)
                                (str/join " ")))
        patterns-tooltip (when (seq match)
                           (->> match
                                (mapv safe-pattern-display)
                                (filterv some?)
                                (str/join "\n")))]
    [:div.script-item {:data-script-name name
                       :class (str (when builtin? "script-item-builtin ")
                                   (when reveal-highlight? "script-item-reveal-highlight ")
                                   (when recently-modified? "script-item-fs-modified"))}
     ;; Column 1: Button column (play button only)
     [:div.script-button-column
      [view-elements/action-button
       {:button/variant :secondary
        :button/class "script-run"
        :button/size :md
        :button/icon icons/play
        :button/title "Run script"
        :button/on-click #(dispatch! [[:popup/ax.evaluate-script script-id]])}
       nil]]
     ;; Column 2: Content column (name/actions, pattern, description)
     [:div.script-content-column
      ;; Row 1: Name and actions
      [:div.script-row-header
       [:span.script-name
        (when builtin?
          [:span.builtin-indicator {:title "Built-in script"}
           [icons/cube]])
        [:span.script-name-text {:title name} name]]
       [:div.script-actions
        [view-elements/action-button
         {:button/variant :secondary
          :button/class "script-inspect"
          :button/size :md
          :button/icon icons/eye
          :button/title "Inspect script"
          :button/on-click #(dispatch! [[:popup/ax.inspect-script script-id]])}
         nil]
        (when-not builtin?
          [view-elements/action-button
           {:button/variant :danger
            :button/class "script-delete"
            :button/size :md
            :button/icon icons/x
            :button/title "Delete script"
            :button/on-click #(when (js/confirm "Delete this script?")
                                (dispatch! [[:popup/ax.delete-script script-id]]))}
           nil])]]
      ;; Row 2: Pattern (single row, CSS truncated)
      [:div.script-row-pattern
       (when (seq match)
         [:input.pattern-checkbox {:type "checkbox"
                                   :checked enabled
                                   :title (if enabled "Auto-run enabled" "Auto-run disabled")
                                   :on-change #(dispatch! [[:popup/ax.toggle-script script-id matching-pattern]])}])
       (when run-at
         (run-at-badge run-at))
       [:span.script-match {:title (or patterns-tooltip "No auto-run (manual only)")}
        (or patterns-display "No auto-run (manual only)")]]
      ;; Row 3: Description (CSS truncated)
      (when (seq description)
        [:div.script-row-description
         [:span.script-description {:title description}
          description]])]]))

(defn- sort-scripts
  "Sort scripts: user scripts alphabetically first, then built-ins alphabetically."
  [scripts]
  (popup-utils/sort-scripts-for-display scripts script-utils/builtin-script?))

(defn matching-scripts-section [{:keys [scripts/list scripts/current-url
                                        ui/reveal-highlight-script-name ui/recently-modified-scripts]}]
  (let [matching-scripts (->> list
                              (filterv #(script-utils/get-matching-pattern current-url %))
                              sort-scripts)
        ;; Filter out built-in scripts to check if user has any custom scripts
        user-scripts (filterv #(not (script-utils/builtin-script? %)) list)
        no-user-scripts? (empty? user-scripts)
        example-pattern (script-utils/url-to-match-pattern current-url {:wildcard-scheme? true})
        modified-set (or recently-modified-scripts #{})]
    [:div.script-list
     (if (seq matching-scripts)
       (for [script matching-scripts]
         ^{:key (:script/id script)}
         [script-item script current-url
          {:reveal-highlight? (= (:script/name script) reveal-highlight-script-name)
           :recently-modified? (contains? modified-set (:script/name script))}])
       [:div.no-scripts
        (if no-user-scripts?
          ;; No user scripts at all - guide to create first one
          "No userscripts yet!"
          ;; Scripts exist but none match
          "No scripts auto-run for this page.")
        [:div.no-scripts-hint
         (if no-user-scripts?
           "Create your first script in DevTools → Epupp panel."
           (if example-pattern
             [:span "Auto-run patterns look like " [:code example-pattern]]
             "Check your script patterns in DevTools → Epupp panel."))]])]))

;; =============================================================================
;; Dev Log Button (only shown in dev/test mode)
;; =============================================================================

(defn dev-log-button
  "Button to dump all test events to console. Only shown in dev/test mode.
   Playwright can capture console output via page.on('console')."
  []
  [:div.dev-log-section
   [view-elements/action-button
    {:button/variant :secondary
     :button/class "dev-log-btn"
     :button/on-click #(dispatch! [[:popup/ax.dump-dev-log]])}
    "Dump Dev Log"]])

;; ============================================================
;; Settings Components
;; ============================================================

(defn other-scripts-section [{:keys [scripts/list scripts/current-url
                                     ui/reveal-highlight-script-name ui/recently-modified-scripts]}]
  (let [other-scripts (->> list
                           (filterv #(not (script-utils/get-matching-pattern current-url %)))
                           sort-scripts)
        modified-set (or recently-modified-scripts #{})]
    [:div.script-list
     (if (seq other-scripts)
       (for [script other-scripts]
         ^{:key (:script/id script)}
         [script-item script current-url
          {:reveal-highlight? (= (:script/name script) reveal-highlight-script-name)
           :recently-modified? (contains? modified-set (:script/name script))}])
       [:div.no-scripts
        "No other scripts."
        [:div.no-scripts-hint
         "Scripts that won't auto-run for this page appear here."]])]))

(defn origin-item [{:keys [origin editable on-delete]}]
  [:div.origin-item {:class (when-not editable "origin-item-default")}
   [:span.origin-url origin]
   (when editable
     [view-elements/action-button
      {:button/variant :danger
       :button/class "origin-delete"
       :button/size :sm
       :button/icon icons/x
       :button/title "Remove origin"
       :button/on-click #(on-delete origin)}
      nil])])

(defn default-origins-list [origins]
  (when (seq origins)
    [:div.origins-section
     [:div.origins-label "Default origins (from extension)"]
     [:div.origin-list
      (for [origin origins]
        ^{:key origin}
        [origin-item {:origin origin :editable false}])]]))

(defn user-origins-list [origins]
  [:div.origins-section
   [:div.origins-label "Your custom origins"]
   (if (seq origins)
     [:div.origin-list
      (for [origin origins]
        ^{:key origin}
        [origin-item {:origin origin
                      :editable true
                      :on-delete #(dispatch! [[:popup/ax.remove-origin %]])}])]
     [:div.no-origins "No custom origins added yet."])])

(defn add-origin-form [{:keys [value]}]
  [:div.add-origin-form
   [:div.add-origin-input-row
    [:input {:type "text"
             :placeholder "https://git.example.com/"
             :value value
             :on-input #(dispatch! [[:popup/ax.set-new-origin (.. % -target -value)]])
             :on-key-down #(when (= "Enter" (.-key %))
                             (dispatch! [[:popup/ax.add-origin]]))}]
    [view-elements/action-button
     {:button/variant :secondary
      :button/class "add-btn"
      :button/title "Add origin"
      :button/on-click #(dispatch! [[:popup/ax.add-origin]])}
     "Add"]]])

(defn settings-content [{:keys [settings/default-origins settings/user-origins settings/new-origin settings/auto-connect-repl settings/auto-reconnect-repl settings/fs-repl-sync-enabled]}]
  [:div.settings-content
   [:div.settings-section
    [:h3.settings-section-title "REPL Connection"]
    [:div.setting
     [:label.checkbox-label
      [:input#auto-reconnect-repl {:type "checkbox"
                                   :checked auto-reconnect-repl
                                   :on-change #(dispatch! [[:popup/ax.toggle-auto-reconnect-repl]])}]
      "Auto-reconnect to previously connected tabs"]
     [:p.description
      "When a connected tab navigates to a new page, automatically reconnect. "
      "REPL state will be lost but connection will be restored."]]
    [:div.setting
     [:label.checkbox-label
      [:input#auto-connect-repl {:type "checkbox"
                                 :checked auto-connect-repl
                                 :on-change #(dispatch! [[:popup/ax.toggle-auto-connect-repl]])}]
      "Auto-connect REPL to all pages"]
     [:p.description.warning
      "Warning: Enabling this will inject the Scittle REPL on every page you visit, "
      "even tabs never connected before. Only enable if you understand the implications."]]
    [:div.setting
     [:label.checkbox-label
      [:input#fs-repl-sync {:type "checkbox"
                            :checked fs-repl-sync-enabled
                            :on-change #(dispatch! [[:popup/ax.toggle-fs-sync]])}]
      "Enable FS REPL Sync"]
     [:p.description
      "Allow connected REPLs to create, modify, and delete userscripts. "
      "Remember to disable when done editing from the REPL."]]]
   [:div.settings-section
    [:h3.settings-section-title "Allowed Userscript-install Base URLs"]
    [:p.section-description
     "Scripts can only be installed from URLs that start with one of these prefixes. "
     "Format: Must start with http:// or https:// and end with / or :"]
    [default-origins-list default-origins]
    [user-origins-list user-origins]
    [add-origin-form {:value new-origin}]]
   [:div.settings-section
    [:h3.settings-section-title "Export / Import Scripts"]
    [:p.section-description
     "Export your scripts to a JSON file for backup, or import scripts from a previously exported file."]
    [:div.export-import-buttons
     [view-elements/action-button
      {:button/variant :secondary
       :button/class "export-btn"
       :button/on-click #(dispatch! [[:popup/ax.export-scripts]])}
      "Export Scripts"]
     [view-elements/action-button
      {:button/variant :secondary
       :button/class "import-btn"
       :button/on-click #(dispatch! [[:popup/ax.import-scripts]])}
      "Import Scripts"]]]])

;; ============================================================
;; Connected Tabs Section
;; ============================================================

(defn connected-tab-item [{:keys [tab-id port title url favicon is-current-tab]}]
  [:div.connected-tab-item {:class (when is-current-tab "current-tab")
                            :title (str title (when url (str "\n" url)))}
   (when favicon
     [:img.connected-tab-favicon {:src favicon :width 16 :height 16}])
   [:span.connected-tab-title (or title "Unknown")]
   [:span.connected-tab-port (str ":" port)]
   (if is-current-tab
     [view-elements/action-button
      {:button/variant :danger
       :button/class "disconnect-tab-btn"
       :button/size :sm
       :button/icon icons/debug-disconnect
       :button/title "Disconnect this tab"
       :button/on-click #(dispatch! [[:popup/ax.disconnect-tab tab-id]])}
      nil]
     [view-elements/action-button
      {:button/variant :secondary
       :button/class "reveal-tab-btn"
       :button/size :sm
       :button/icon icons/link-external
       :button/title "Reveal this tab"
       :button/on-click #(dispatch! [[:popup/ax.reveal-tab tab-id]])}
      nil])])

(defn connected-tabs-section [{:keys [repl/connections scripts/current-tab-id]}]
  [:div.connected-tabs-section
   (if (seq connections)
     (let [current-tab-id-str (str current-tab-id)
           ;; Sort with current tab first
           sorted-connections (sort-by
                               (fn [{:keys [tab-id]}]
                                 (if (= tab-id current-tab-id-str) 0 1))
                               connections)]
       [:div.connected-tabs-list
        (for [{:keys [tab-id] :as conn} sorted-connections]
          ^{:key tab-id}
          [connected-tab-item (assoc conn :is-current-tab (= tab-id current-tab-id-str))])])
     [view-elements/empty-state {:empty/class "no-connections"}
      "No REPL connections active"
      [:div.no-connections-hint
       "Start the server (Step 1), then click Connect (Step 2)."]])])

;; ============================================================;; Main View
;; ============================================================

(defn- current-tab-connected?
  "Check if current tab is in the connections list"
  [{:keys [repl/connections scripts/current-tab-id]}]
  (let [current-tab-id-str (str current-tab-id)]
    (some #(= (:tab-id %) current-tab-id-str) connections)))

(defn repl-connect-content [{:keys [ports/nrepl ports/ws ui/copy-feedback] :as state}]
  (let [is-connected (current-tab-connected? state)]
    [:div
     [:div.step
      [:div.step-header "1. Start the browser-nrepl server"]
      [:div.port-row
       [port-input {:id "nrepl-port"
                    :label "nREPL:"
                    :value nrepl
                    :on-change #(dispatch! [[:popup/ax.set-nrepl-port %]])}]
       [port-input {:id "ws-port"
                    :label "WebSocket:"
                    :value ws
                    :on-change #(dispatch! [[:popup/ax.set-ws-port %]])}]]
      [command-box {:command (generate-server-cmd state)
                    :copy-feedback copy-feedback}]]

     [:div.step
      [:div.step-header "2. Connect browser to server"]
      [:div.connect-row
       [:span.connect-target (str "ws://localhost:" ws)]
       [view-elements/action-button
        (if is-connected
          {:button/variant :danger
           :button/id "disconnect"
           :button/class "disconnect-btn"
           :button/icon icons/debug-disconnect
           :button/title "Disconnect this tab"
           :button/on-click #(dispatch! [[:popup/ax.disconnect-tab (:scripts/current-tab-id state)]])}
          {:button/variant :primary
           :button/id "connect"
           :button/title "Connect this tab to the REPL server"
           :button/on-click #(dispatch! [[:popup/ax.connect]])})
        (if is-connected "Disconnect" "Connect")]]]
     [:div.step
      [:div.step-header "3. Connect editor to browser (via server)"]
      [:div.connect-row
       [:span.connect-target (str "nrepl://localhost:" nrepl)]]]
     [:div.step
      [:div.step-header "Connected Tabs"]
      [connected-tabs-section state]]]))

;; ============================================================
;; FS Confirmation UI
;; ============================================================

(defn system-banner [{:keys [type message leaving]}]
  [:div {:class (str "system-banner "
                     (if (= type "success") "fs-success-banner" "fs-error-banner")
                     (when leaving " leaving"))}
   [:span message]])

(defn popup-ui [{:keys [ui/sections-collapsed scripts/list scripts/current-url repl/connections
                        settings/default-origins settings/user-origins] :as state}]
  (let [matching-scripts (->> list
                              (filterv #(script-utils/get-matching-pattern current-url %)))
        other-scripts (->> list
                           (filterv #(not (script-utils/get-matching-pattern current-url %))))
        ;; Settings section height: base + origins (30px each) + form/buttons
        settings-base-height 850 ; REPL settings + export/import + headers
        origins-height (* 35 (+ (count default-origins) (max 1 (count user-origins))))
        settings-max-height (+ settings-base-height origins-height)]
    [:div
     [view-elements/app-header
      {:elements/wrapper-class "popup-header-wrapper"
       :elements/header-class "popup-header"
       :elements/icon [icons/jack-in {:size 28}]
       :elements/temporary-banner (when-let [system-banner-state (:ui/system-banner state)]
                                    [system-banner system-banner-state])}]

     [collapsible-section {:id :repl-connect
                           :title "REPL Connect"
                           :expanded? (not (:repl-connect sections-collapsed))
                           :max-height (str (+ 500 (* 35 (count connections))) "px")}
      [repl-connect-content state]]
     [collapsible-section {:id :matching-scripts
                           :title "Auto-run for This Page"
                           :expanded? (not (:matching-scripts sections-collapsed))
                           :badge-count (count matching-scripts)
                           :max-height (str (+ 50 (* 65 (max 1 (count matching-scripts)))) "px")}
      [matching-scripts-section state]]
     [collapsible-section {:id :other-scripts
                           :title "Other Scripts"
                           :expanded? (not (:other-scripts sections-collapsed))
                           :badge-count (count other-scripts)
                           :max-height (str (+ 50 (* 65 (max 1 (count other-scripts)))) "px")}
      [other-scripts-section state]]
     [collapsible-section {:id :settings
                           :title "Settings"
                           :expanded? (not (:settings sections-collapsed))
                           :max-height (str settings-max-height "px")}
      [settings-content state]]
     (when (or (.-dev config) (.-test config))
       [dev-log-button])
     [view-elements/app-footer {:elements/wrapper-class "popup-footer"}]]))

(defn render! []
  (r/render (js/document.getElementById "app")
            [popup-ui @!state]))

(defn init! []
  (log/info "Popup" nil "Init!")
  ;; Install global error handlers for test mode
  (test-logger/install-global-error-handlers! "popup" js/window)
  (add-watch !state :popup/render (fn [_ _ _ _] (render!)))

  ;; Detect browser features
  (swap! !state assoc :browser/brave? (some? (.-brave js/navigator)))

  (render!)

  ;; Listen for connection changes from background
  (js/chrome.runtime.onMessage.addListener
   (fn [message _sender _send-response]
     (when (= "connections-changed" (.-type message))
       (let [connections (.-connections message)]
         (dispatch! [[:db/ax.assoc :repl/connections connections]])))
     ;; Return false - we don't send async response
     false))

  ;; Listen for FS sync events from background (show errors to user)
  (js/chrome.runtime.onMessage.addListener
   (fn [message _sender _send-response]
     (when (= "system-banner" (.-type message))
       (let [event-type (aget message "event-type")
             operation (aget message "operation")
             script-name (aget message "script-name")
             error-msg (aget message "error")
             bulk-id (aget message "bulk-id")
             bulk-count (aget message "bulk-count")
             bulk-index (aget message "bulk-index")
             bulk-final? (and (some? bulk-count)
                              (some? bulk-index)
                              (= bulk-index (dec bulk-count)))
             bulk-op? (and (= event-type "success")
                           (some? bulk-count)
                           (or (= operation "save")
                               (= operation "delete")))
             show-banner? (or (= event-type "error")
                              (not bulk-op?)
                              bulk-final?)
             banner-msg (cond
                          (= event-type "error")
                          (str "FS sync error: " error-msg)

                          (and bulk-op? bulk-final?)
                          (str bulk-count (if (= bulk-count 1) " file " " files ")
                               (if (= operation "delete") "deleted" "saved")
                               " via REPL")

                          :else
                          (str "Script \"" script-name "\" " operation "d via REPL"))]
         (when bulk-id
           (dispatch! [[:popup/ax.track-bulk-name bulk-id script-name]]))
         (when (and (= event-type "error")
                    (= operation "save")
                    script-name
                    (not bulk-id))
           (dispatch! [[:popup/ax.mark-scripts-modified [script-name]]]))
         (when show-banner?
           (let [bulk-names (when bulk-id (get-in @!state [:ui/system-bulk-names bulk-id]))]
             (dispatch! [[:popup/ax.show-system-banner event-type banner-msg
                          {:bulk-op? bulk-op?
                           :bulk-final? bulk-final?
                           :bulk-names bulk-names}]])))
         (when (and bulk-id bulk-final?)
           (dispatch! [[:popup/ax.clear-bulk-names bulk-id]]))))
     ;; Return false - we don't send async response
     false))

  ;; Listen for storage changes (scripts modified via REPL, panel, etc.)
  (js/chrome.storage.onChanged.addListener
   (fn [changes area]
     (when (and (= area "local") (.-scripts changes))
       (let [scripts-change (.-scripts changes)
             old-scripts (when (.-oldValue scripts-change)
                           (script-utils/parse-scripts (.-oldValue scripts-change)))
             new-scripts (when (.-newValue scripts-change)
                           (script-utils/parse-scripts (.-newValue scripts-change)))]
         ;; Always reload scripts to update UI
         (dispatch! [[:popup/ax.load-scripts]])
         ;; If we have both old and new, diff to find modified scripts
         (when (and old-scripts new-scripts)
           (let [{:keys [added modified]} (script-utils/diff-scripts old-scripts new-scripts)
                 changed-names (concat added modified)]
             (when (seq changed-names)
               (dispatch! [[:popup/ax.mark-scripts-modified (vec changed-names)]]))))))))

  (dispatch! [[:popup/ax.load-saved-ports]
              [:popup/ax.check-status]
              [:popup/ax.load-scripts]
              [:popup/ax.load-current-url]
              [:popup/ax.load-user-origins]
              [:popup/ax.load-auto-connect-setting]
              [:popup/ax.load-auto-reconnect-setting]
              [:popup/ax.load-fs-sync-setting]
              [:popup/ax.load-connections]]))

;; Start the app when DOM is ready
(log/info "Popup" nil "Script loaded, readyState:" js/document.readyState)
(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
