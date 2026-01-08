(ns popup
  "Epupp extension popup - built with Squint + Reagami
   Inspired by Replicant tic-tac-toe state management pattern"
  (:require [reagami :as r]
            [event-handler :as event-handler]
            [icons :as icons]
            [script-utils :as script-utils]
            [popup-utils :as popup-utils]
            [popup-actions :as popup-actions]))

;; EXTENSION_CONFIG is injected by esbuild at bundle time from config/*.edn
;; Shape: {"dev": boolean, "depsString": string}
(def ^:private config js/EXTENSION_CONFIG)

(defonce !state
  (atom {:ports/nrepl "1339"
         :ports/ws "1340"
         :ui/status nil
         :ui/copy-feedback nil
         :ui/has-connected false  ; Track if we've connected at least once
         :ui/editing-hint-script-id nil ; Show "open DevTools" hint under this script
         :ui/sections-collapsed {:repl-connect false      ; expanded by default
                                 :matching-scripts false  ; expanded by default
                                 :other-scripts false     ; expanded by default
                                 :settings true}          ; collapsed by default
         :browser/brave? false
         :scripts/list []         ; All userscripts
         :scripts/current-url nil ; Current tab URL for matching
         :settings/user-origins []    ; User-added allowed origins
         :settings/new-origin ""      ; Input field for new origin
         :settings/default-origins [] ; Config origins (read-only)
         :settings/error nil}))          ; Validation error message



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
  "Save scripts to storage and notify background worker."
  [scripts notify-type & {:keys [script-id pattern]}]
  (save-scripts! scripts)
  (case notify-type
    :refresh (js/chrome.runtime.sendMessage #js {:type "refresh-approvals"})
    :approved (js/chrome.runtime.sendMessage
               #js {:type "pattern-approved"
                    :scriptId script-id
                    :pattern pattern})
    nil))

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
      (try
        (dispatch [[:db/ax.assoc :ui/status "Connecting..."]])
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
            (dispatch [[:db/ax.assoc
                        :ui/status (str "Connected to ws://localhost:" port)
                        :ui/has-connected true]])
            (dispatch [[:db/ax.assoc
                        :ui/status (str "Failed: " (or (and resp (.-error resp)) "Connect failed"))]])))
        (catch :default err
          (dispatch [[:db/ax.assoc :ui/status (str "Failed: " (.-message err))]]))))

    :popup/fx.check-status
    (let [[ws-port] args
          tab (js-await (get-active-tab))]
      (try
        (let [resp (js-await
                    (js/Promise.
                     (fn [resolve reject]
                       (js/chrome.runtime.sendMessage
                        #js {:type "check-status"
                             :tabId (.-id tab)}
                        (fn [response]
                          (if js/chrome.runtime.lastError
                            (reject (js/Error. (.-message js/chrome.runtime.lastError)))
                            (resolve response)))))))
              status (when (and resp (.-success resp))
                       (.-status resp))]
          (when status
            (let [has-scittle (.-hasScittle status)
                  has-bridge (.-hasWsBridge status)]
              (js/console.log "[Check Status] hasScittle:" has-scittle "hasWsBridge:" has-bridge)
              (when (and has-scittle has-bridge)
                (dispatch [[:db/ax.assoc
                            :ui/has-connected true
                            :ui/status (str "Connected to ws://localhost:" ws-port)]])))))
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

    :popup/fx.approve-script
    (let [[scripts script-id pattern] args
          updated (popup-utils/approve-pattern-in-list scripts script-id pattern)]
      (persist-and-notify-scripts! updated :approved :script-id script-id :pattern pattern)
      (dispatch [[:db/ax.assoc :scripts/list updated]]))

    :popup/fx.deny-script
    (let [[scripts script-id] args
          updated (popup-utils/disable-script-in-list scripts script-id)]
      (persist-and-notify-scripts! updated :refresh)
      (dispatch [[:db/ax.assoc :scripts/list updated]]))

    :popup/fx.delete-script
    (let [[scripts script-id] args
          updated (popup-utils/remove-script-from-list scripts script-id)]
      (save-scripts! updated)
      ;; Notify background to update badge
      (js/chrome.runtime.sendMessage #js {:type "refresh-approvals"})
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
      (dispatch [[:db/ax.assoc :scripts/current-url (.-url tab)]]))

    :popup/fx.evaluate-script
    (let [[script] args
          tab (js-await (get-active-tab))]
      (js/chrome.runtime.sendMessage
       #js {:type "evaluate-script"
            :tabId (.-id tab)
            :scriptId (:script/id script)
            :code (:script/code script)}))

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
   [:button.copy-btn {:on-click #(dispatch! [[:popup/ax.copy-command]])}
    (or copy-feedback "Copy")]])

(defn collapsible-section [{:keys [id title expanded? badge-count]} & children]
  [:div.collapsible-section {:class (when-not expanded? "collapsed")}
   [:div.section-header {:on-click #(dispatch! [[:popup/ax.toggle-section id]])}
    [icons/chevron-right {:class (str "chevron " (when expanded? "expanded"))}]
    [:span.section-title title]
    (when (and badge-count (pos? badge-count))
      [:span.section-badge badge-count])]
   (when expanded?
     (into [:div.section-content] children))])

(defn- run-at-badge
  "Returns a badge component for non-default run-at timings."
  [run-at]
  (case run-at
    "document-start" [:span.run-at-badge {:title "Runs at document-start (before page loads)"}
                      [icons/rocket]]
    "document-end" [:span.run-at-badge {:title "Runs at document-end (when DOM is ready)"}
                    [icons/flag]]
    ;; document-idle (default) - no badge
    nil))

(defn script-item [{:keys [script/name script/match script/enabled script/description script/run-at]
                    script-id :script/id
                    :as script}
                   current-url
                   editing-hint-script-id]
  (let [matching-pattern (script-utils/get-matching-pattern current-url script)
        matches-current (some? matching-pattern)
        needs-approval (and matches-current
                            enabled
                            (not (script-utils/pattern-approved? script matching-pattern)))
        pattern-display (or matching-pattern (first match))
        show-edit-hint (= script-id editing-hint-script-id)
        truncated-desc (when (seq description)
                         (if (> (count description) 60)
                           (str (subs description 0 57) "...")
                           description))
        builtin? (script-utils/builtin-script? script)]
    [:div
     [:div.script-item {:class (str (when builtin? "script-item-builtin ")
                                    (when needs-approval "script-item-approval"))}
      [:input {:type "checkbox"
               :checked enabled
               :title (if enabled "Enabled" "Disabled")
               :on-change #(dispatch! [[:popup/ax.toggle-script script-id matching-pattern]])}]
      [:div.script-info
       [:span.script-name
        (when builtin?
          [:span.builtin-indicator {:title "Built-in script"}
           [icons/cube]])
        name]
       (when truncated-desc
         [:span.script-description truncated-desc])
       [:span.script-match (run-at-badge run-at) pattern-display]]
      [:div.script-actions
       ;; Show approval buttons when script matches current URL but pattern not approved
       (when needs-approval
         [:button.approval-allow {:on-click #(dispatch! [[:popup/ax.approve-script script-id matching-pattern]])}
          "Allow"])
       (when needs-approval
         [:button.approval-deny {:on-click #(dispatch! [[:popup/ax.deny-script script-id]])}
          "Deny"])
       [:button.script-inspect {:on-click #(dispatch! [[:popup/ax.inspect-script script-id]])
                                :title "Inspect script"}
        [icons/eye]]
       [:button.script-run {:on-click #(dispatch! [[:popup/ax.evaluate-script script-id]])
                            :title "Run script"}
        [icons/play]]
       (when-not builtin?
         [:button.script-delete {:on-click #(when (js/confirm "Delete this script?")
                                              (dispatch! [[:popup/ax.delete-script script-id]]))
                                 :title "Delete script"}
          [icons/x]])]]
     (when show-edit-hint
       [:div.script-edit-hint
        "Open the Epupp panel in Developer Tools"])]))

(defn- sort-scripts
  "Sort scripts: user scripts alphabetically first, then built-ins alphabetically."
  [scripts]
  (popup-utils/sort-scripts-for-display scripts script-utils/builtin-script?))

(defn matching-scripts-section [{:keys [scripts/list scripts/current-url ui/editing-hint-script-id]}]
  (let [matching-scripts (->> list
                              (filterv #(script-utils/get-matching-pattern current-url %))
                              sort-scripts)]
    [:div.script-list
     (if (seq matching-scripts)
       (for [script matching-scripts]
         ^{:key (:script/id script)}
         [script-item script current-url editing-hint-script-id])
       [:div.no-scripts "No scripts match this page."])]))

;; ============================================================
;; Settings Components
;; ============================================================

(defn other-scripts-section [{:keys [scripts/list scripts/current-url ui/editing-hint-script-id]}]
  (let [other-scripts (->> list
                           (filterv #(not (script-utils/get-matching-pattern current-url %)))
                           sort-scripts)]
    [:div.script-list
     (if (seq other-scripts)
       (for [script other-scripts]
         ^{:key (:script/id script)}
         [script-item script current-url editing-hint-script-id])
       [:div.no-scripts "No other scripts."])]))

(defn origin-item [{:keys [origin editable on-delete]}]
  [:div.origin-item {:class (when-not editable "origin-item-default")}
   [:span.origin-url origin]
   (when editable
     [:button.origin-delete {:on-click #(on-delete origin)
                             :title "Remove origin"}
      [icons/x]])])

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

(defn add-origin-form [{:keys [value error]}]
  [:div.add-origin-form
   [:div.add-origin-input-row
    [:input {:type "text"
             :placeholder "https://git.example.com/"
             :value value
             :on-input #(dispatch! [[:popup/ax.set-new-origin (.. % -target -value)]])
             :on-key-down #(when (= "Enter" (.-key %))
                             (dispatch! [[:popup/ax.add-origin]]))}]
    [:button.add-btn {:on-click #(dispatch! [[:popup/ax.add-origin]])
                      :title "Add origin"}
     "Add"]]
   (when error
     [:div.add-origin-error error])])

(defn settings-content [{:keys [settings/default-origins settings/user-origins settings/new-origin settings/error]}]
  [:div.settings-content
   [:div.settings-section
    [:h3.settings-section-title "Allowed Userscript-install Base URLs"]
    [:p.section-description
     "Scripts can only be installed from URLs that start with one of these prefixes. "
     "Format: Must start with http:// or https:// and end with / or :"]
    [default-origins-list default-origins]
    [user-origins-list user-origins]
    [add-origin-form {:value new-origin :error error}]]])

;; ============================================================
;; Main View
;; ============================================================

(defn repl-connect-content [{:keys [ports/nrepl ports/ws ui/status ui/copy-feedback ui/has-connected] :as state}]
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
     [:button#connect {:on-click #(dispatch! [[:popup/ax.connect]])}
      (if has-connected "Reconnect" "Connect")]]
    (when status
      [:div#status {:class (popup-utils/status-class status)} status])]
   [:div.step
    [:div.step-header "3. Connect editor to browser (via server)"]
    [:div.connect-row
     [:span.connect-target (str "nrepl://localhost:" nrepl)]]]])

(defn popup-ui [{:keys [ui/sections-collapsed scripts/list scripts/current-url] :as state}]
  (let [matching-scripts (->> list
                              (filterv #(script-utils/get-matching-pattern current-url %)))
        other-scripts (->> list
                           (filterv #(not (script-utils/get-matching-pattern current-url %))))]
    [:div
     ;; Header with logos
     [:div.header
      [:div.header-left
       [icons/jack-in]
       [:h1 "Epupp"]]
      [:div.header-right
       [:a.header-tagline {:href "https://github.com/babashka/scittle/tree/main/doc/nrepl"
                           :target "_blank"}
        "Scittle nREPL"]
       [:div.header-logos
        [:img {:src "images/sci.png" :alt "SCI"}]
        [:img {:src "images/clojure.png" :alt "Clojure"}]]]]

     ;; REPL Connect section
     [collapsible-section {:id :repl-connect
                           :title "REPL Connect"
                           :expanded? (not (:repl-connect sections-collapsed))}
      [repl-connect-content state]]

     ;; Matching Scripts section
     [collapsible-section {:id :matching-scripts
                           :title "Matching Scripts"
                           :expanded? (not (:matching-scripts sections-collapsed))
                           :badge-count (count matching-scripts)}
      [matching-scripts-section state]]

     ;; Other Scripts section
     [collapsible-section {:id :other-scripts
                           :title "Other Scripts"
                           :expanded? (not (:other-scripts sections-collapsed))
                           :badge-count (count other-scripts)}
      [other-scripts-section state]]

     ;; Settings section (collapsed by default)
     [collapsible-section {:id :settings
                           :title "Settings"
                           :expanded? (not (:settings sections-collapsed))}
      [settings-content state]]]))

(defn render! []
  (r/render (js/document.getElementById "app")
            [popup-ui @!state]))

(defn init! []
  (js/console.log "Epupp popup init!")
  (add-watch !state :popup/render (fn [_ _ _ _] (render!)))

  ;; Detect browser features
  (swap! !state assoc :browser/brave? (some? (.-brave js/navigator)))

  (render!)
  ;; Refresh badge on popup open
  (js/chrome.runtime.sendMessage #js {:type "refresh-approvals"})
  (dispatch! [[:popup/ax.load-saved-ports]
              [:popup/ax.check-status]
              [:popup/ax.load-scripts]
              [:popup/ax.load-current-url]
              [:popup/ax.load-user-origins]]))

;; Start the app when DOM is ready
(js/console.log "Popup script loaded, readyState:" js/document.readyState)
(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))

;; =============================================================================
;; Test Helpers (exported for E2E tests)
;; =============================================================================

(defn get-state
  "Returns current popup state as JS object. For E2E test inspection."
  []
  (clj->js @!state))
