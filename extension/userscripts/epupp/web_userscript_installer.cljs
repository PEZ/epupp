{:epupp/script-name "epupp/web_userscript_installer.cljs"
 :epupp/auto-run-match "*"
 :epupp/description "Web Userscript Installer. Finds userscripts on web pages, and adds a button to install the script into Epupp"
 :epupp/inject ["scittle://replicant.js"]}

;; Epupp Web Userscript Installer
;;
;; Architecture: Mini-Uniflow (actions decide, effects execute)
;;
;; Layer 1 - Pure Domain: normalization, manifest parsing, state helpers
;; Layer 2 - Pure UI: Replicant hiccup components (pure functions of their args)
;; Layer 3 - Action Handler: pure state transitions + effect declarations
;; Layer 4 - Effect Handler & Dispatch: side effect execution, dispatch loop
;; Layer 5 - Orchestration: Replicant bridge, rendering, scanning, initialization
;;
;; All state lives in a single atom. State transitions flow through dispatch!.
;; Components are pure functions. Side effects are declared as data.

(ns epupp.web-userscript-installer
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [replicant.dom :as r]))

;; ============================================================
;; Pure Domain - Script Normalization & Manifest Parsing
;; ============================================================

(def valid-run-at-values
  #{"document-start" "document-end" "document-idle"})

(def default-run-at "document-idle")

(defn normalize-script-name
  "Normalize a script name to a consistent format."
  [input-name]
  (let [base-name (if (str/ends-with? input-name ".cljs")
                    (subs input-name 0 (- (count input-name) 5))
                    input-name)]
    (-> base-name
        (str/lower-case)
        (str/replace #"[\s.-]+" "_")
        (str/replace #"[^a-z0-9_/]" "")
        (str ".cljs"))))

;; SYNC WARNING: This parser must stay in sync with src/manifest_parser.cljs
;; Key behaviors that must match:
;; - auto-run-match is OPTIONAL (nil means manual-only script)
;; - auto-run-match preserves vector/string as-is

(defn- get-first-form
  "Read the first form from code text. Returns map or nil."
  [code-text]
  (try
    (let [form (edn/read-string code-text)]
      (when (map? form) form))
    (catch :default e
      (js/console.error "[Web Userscript Installer] Parse error:" e)
      nil)))

(defn extract-manifest
  "Extract manifest from the first form (must be a data map)."
  [code-text]
  (when-let [m (get-first-form code-text)]
    (when-let [raw-name (get m :epupp/script-name)]
      (let [normalized-name (normalize-script-name raw-name)
            raw-run-at (get m :epupp/run-at)
            run-at (if (contains? valid-run-at-values raw-run-at)
                     raw-run-at
                     default-run-at)
            auto-run-match (get m :epupp/auto-run-match)]
        {:script-name normalized-name
         :raw-script-name raw-name
         :name-normalized? (not= raw-name normalized-name)
         :auto-run-match auto-run-match
         :description (get m :epupp/description)
         :run-at run-at
         :raw-run-at raw-run-at
         :run-at-invalid? (and raw-run-at
                               (not (contains? valid-run-at-values raw-run-at)))}))))

;; ============================================================
;; DOM Helpers - Code Block Detection
;; ============================================================

;; Format types:
;; - :pre - Generic <pre> elements
;; - :github-table - GitHub gist table-based code
;; - :gitlab-snippet - GitLab snippet with .file-holder container

(defn- get-pre-text
  "Extract code text from pre element"
  [pre-element]
  (.-textContent pre-element))

(defn- get-github-table-text
  "Extract code text from GitHub gist table by joining all js-file-line cells"
  [table-element]
  (let [lines (.querySelectorAll table-element "td.js-file-line")]
    (->> (js/Array.from lines)
         (map #(.-textContent %))
         (str/join "\n"))))

(defn- get-gitlab-snippet-text
  "Extract code text from GitLab snippet pre element within .file-holder"
  [file-holder-element]
  (when-let [pre-element (.querySelector file-holder-element "pre")]
    (.-textContent pre-element)))

(defn- detect-github-tables
  "Find GitHub gist code tables, return array of {:element :format :code-text}"
  []
  (->> (.querySelectorAll js/document "table.js-file-line-container")
       js/Array.from
       (map (fn [el]
              {:element el
               :format :github-table
               :code-text (get-github-table-text el)}))))

(defn- detect-pre-elements
  "Find generic pre elements, return array of {:element :format :code-text}.
   Excludes pre elements inside .file-holder (GitLab snippets) or
   .CodeMirror (code editor line fragments, e.g. GitHub gist edit mode)."
  []
  (->> (.querySelectorAll js/document "pre")
       js/Array.from
       (filter (fn [el]
                 (and (not (.closest el ".file-holder"))
                      (not (.closest el ".CodeMirror")))))
       (map (fn [el]
              {:element el
               :format :pre
               :code-text (get-pre-text el)}))))

(defn- get-textarea-text
  "Extract code text from textarea element using .value property"
  [textarea-element]
  (aget textarea-element "value"))

(defn- detect-textarea-elements
  "Find textarea elements, return array of {:element :format :code-text}.
   Excludes textareas inside code editors (.js-code-editor) such as GitHub gist edit mode."
  []
  (->> (.querySelectorAll js/document "textarea")
       js/Array.from
       (filter (fn [el]
                 (not (.closest el ".js-code-editor"))))
       (map (fn [el]
              {:element el
               :format :textarea
               :code-text (get-textarea-text el)}))))

(defn- detect-gitlab-snippets
  "Find GitLab snippet code blocks, return array of {:element :format :code-text}"
  []
  (->> (.querySelectorAll js/document ".file-holder")
       js/Array.from
       (map (fn [el]
              {:element el
               :format :gitlab-snippet
               :code-text (get-gitlab-snippet-text el)}))
       (filter #(:code-text %))))

(defn- get-github-repo-text
  "Extract code text from GitHub repo file via .react-code-lines container"
  []
  (when-let [code-lines (.querySelector js/document ".react-code-lines")]
    (.-textContent code-lines)))

(defn- detect-github-repo-files
  "Find GitHub repo file via header container (React-based).
   Returns single-element seq with :element being the header container."
  []
  (when-let [header (.querySelector js/document ".react-blob-header-edit-and-raw-actions")]
    (when-let [code-text (get-github-repo-text)]
      [{:element header
        :format :github-repo
        :code-text code-text}])))

(defn- detect-all-code-blocks
  "Detect all code blocks on page. Returns seq of {:element :format :code-text}.
   More specific formats listed first for priority."
  []
  (concat (detect-github-tables)
          (detect-github-repo-files)
          (detect-gitlab-snippets)
          (detect-pre-elements)
          (detect-textarea-elements)))

(defn- get-github-button-container
  "Get the .file-actions container for GitHub gist button placement"
  [table-element]
  (when-let [file-div (.closest table-element ".file")]
    (.querySelector file-div ".file-actions")))

(defn- get-gitlab-button-container
  "Get the .file-actions container for GitLab snippet button placement"
  [file-holder-element]
  (.querySelector file-holder-element ".file-actions"))

(defn- get-github-repo-button-container
  "Get the button container for GitHub repo file button placement.
   Finds ButtonGroup containing Raw button, or first ButtonGroup as fallback."
  [header-element]
  (let [button-groups (js/Array.from (.querySelectorAll header-element "[class*='ButtonGroup']"))]
    (->> (or (->> button-groups
                  (filter #(.querySelector % "[data-testid='raw-button']"))
                  first)
             (first button-groups))
         .-parentElement
         .-parentElement)))

;; ============================================================
;; Pure Domain - State Shape & Helpers
;; ============================================================

(defonce !state
  (atom {:blocks []
         :installed-scripts {}
         :icon-url nil
         :modal {:visible? false
                 :mode nil  ;; :confirm or :error
                 :block-id nil
                 :error-message nil}
         :pending-retry-timeout nil
         :request-id 0
         :button-containers {}
         :ui-container nil
         :ui-setup? false
         :nav-registered? false}))

(def retry-delays [100 1000 3000])

(defn find-block-by-id [state block-id]
  (first (filter #(= (:id %) block-id) (:blocks state))))

(defn update-block-status
  ([state block-id new-status]
   (update-block-status state block-id new-status nil))
  ([state block-id new-status error-message]
   (update state :blocks
           (fn [blocks]
             (mapv (fn [b]
                     (if (= (:id b) block-id)
                       (cond-> (assoc b :status new-status)
                         error-message (assoc :error-message error-message)
                         (not= new-status :error) (dissoc :error-message))
                       b))
                   blocks)))))

(defn- determine-button-state
  "Determine button state based on installed scripts.
   Returns map with :install-state and :existing-script keys.
   When existing-code is nil, assumes :update state for installed scripts."
  [script-name page-code installed-scripts existing-code]
  (if-let [existing-script (get installed-scripts script-name)]
    (if (and existing-code (= page-code existing-code))
      {:install-state :installed
       :existing-script existing-script}
      {:install-state :update
       :existing-script existing-script})
    {:install-state :install
     :existing-script nil}))

;; ============================================================
;; Extension Communication
;; ============================================================

(defn- next-request-id []
  (get (swap! !state update :request-id inc) :request-id))

(defn- send-and-receive
  "Helper: send message to bridge and return promise of response.
   Optional timeout-ms (default 2000) controls how long to wait for a response."
  ([msg-type payload response-type]
   (send-and-receive msg-type payload response-type 2000))
  ([msg-type payload response-type timeout-ms]
   (let [req-id (next-request-id)]
     (js/Promise.
      (fn [resolve reject]
        (let [timeout-id (atom nil)
              handler (fn handler [e]
                        (when (= (.-source e) js/window)
                          (let [msg (.-data e)]
                            (when (and msg
                                       (= "epupp-bridge" (.-source msg))
                                       (= response-type (.-type msg))
                                       (= req-id (.-requestId msg)))
                              (when-let [tid @timeout-id]
                                (js/clearTimeout tid))
                              (.removeEventListener js/window "message" handler)
                              (resolve msg)))))]
          (.addEventListener js/window "message" handler)
          (reset! timeout-id
                  (js/setTimeout
                   (fn []
                     (.removeEventListener js/window "message" handler)
                     (reject (js/Error. (str "Timeout waiting for " response-type))))
                   timeout-ms))
          (.postMessage js/window
                        (clj->js (assoc payload :source "epupp-page" :type msg-type :requestId req-id))
                        "*")))))))

(defn fetch-icon-url!+
  "Fetch the Epupp icon URL from the extension. Returns promise of URL string."
  []
  (-> (send-and-receive "get-icon-url" {} "get-icon-url-response")
      (.then (fn [msg]
               (.-url msg)))))

(defn fetch-installed-scripts!+
  "Fetch installed scripts from extension. Returns promise of map (script-name -> script-data)."
  []
  (-> (send-and-receive "list-scripts" {} "list-scripts-response")
      (.then (fn [msg]
               (if (.-success msg)
                 (let [scripts (js->clj (.-scripts msg) :keywordize-keys true)]
                   (into {} (map (fn [script]
                                   [(:fs/name script) script])
                                 scripts)))
                 {})))))

(defn fetch-script-code!+
  "Fetch code for a specific script by name. Returns promise of code string or nil."
  [script-name]
  (-> (send-and-receive "get-script" {:name script-name} "get-script-response")
      (.then (fn [msg]
               (when (.-success msg)
                 (.-code msg))))))



;; ============================================================
;; Pure UI - Replicant Components
;; ============================================================

(defn epupp-icon
  "Epupp icon - renders extension icon when URL is available."
  [icon-url]
  (when icon-url
    [:img.epupp-icon {:src icon-url
                      :width 20
                      :height 20
                      :alt "Epupp"}]))

(defn button-tooltip [{:keys [status error-message]}]
  (case status
    :install "Install to Epupp"
    :update "Update existing Epupp script"
    :installed "Installed in Epupp (identical)"
    :installing "Installing..."
    :error (or error-message "Installation failed")
    "Install to Epupp"))

(defn run-at-label [run-at]
  (case run-at
    "document-start" "document-start (early)"
    "document-end" "document-end"
    "document-idle (default)"))

(defn render-install-button [{:keys [id status] :as block} icon-url]
  (let [clickable? (#{:install :update} status)
        status-class (str "is-" (name status))]
    [:button.epupp-install-btn
     {:class status-class
      :on {:click [:db/assoc :modal {:visible? true :mode :confirm :block-id id}]}
      :data-e2e-install-state (name status)
      :data-e2e-script-name (get-in block [:manifest :script-name])
      :disabled (not clickable?)
      :title (button-tooltip block)}
     (epupp-icon icon-url)
     (case status
       :install "Install"
       :update "Update"
       :installed "✓"
       :installing "Installing..."
       :error "Install Failed"
       "Install")]))

(defn- modal-header
  "Branded modal header with Epupp icon, title, tagline, and action title."
  [{:keys [action-title error? icon-url]}]
  [:div.epupp-modal__header
   [:div.epupp-modal__brand
    (when icon-url
      [:img.epupp-modal__icon {:src icon-url :alt "Epupp"}])
    [:span.epupp-modal__title
     "Epupp"
     [:span.epupp-modal__tagline "Live Tamper your Web"]]]
   [:h2 {:class (str "epupp-modal__action-title" (when error? " is-error"))}
    action-title]])

(defn render-modal [{:keys [id manifest code status]} icon-url]
  (let [{:keys [script-name raw-script-name name-normalized?
                auto-run-match description run-at run-at-invalid? raw-run-at]} manifest
        page-url js/window.location.href
        is-update? (= status :update)
        modal-title (if is-update? "Update Userscript" "Install Userscript")
        confirm-text (if is-update? "Update" "Install")]
    [:div.epupp-modal-overlay
     {:on {:click [:db/assoc :modal {:visible? false :mode nil :block-id nil :error-message nil}]}}
     [:div.epupp-modal
      {:on {:click [:block/modal-click]}}
      (modal-header {:action-title modal-title :icon-url icon-url})
      ;; Property table
      [:table.epupp-modal__table
       [:tbody
        [:tr
         [:td "Name"]
         [:td
          [:code script-name]
          (when name-normalized?
            [:span
             [:br]
             [:span.epupp-modal__note
              "Normalized from: " raw-script-name]])]]
        [:tr
         [:td "Auto-run pattern"]
         [:td (or auto-run-match [:em "None, script configured for manual run"])]]
        [:tr
         [:td "Description"]
         [:td (or description [:em "Not specified"])]]
        [:tr
         [:td "Run At"]
         [:td
          (run-at-label run-at)
          (when run-at-invalid?
            [:span
             [:br]
             [:span.epupp-modal__note.is-warning
              "Invalid value \"" raw-run-at "\" - using default"]])]]]]
      [:p [:strong "Source:"]]
      [:p
       [:code {:style {:word-break "break-all"}} page-url]]
      ;; Context message
      [:div.epupp-modal__actions
       [:button.epupp-btn.epupp-btn--secondary
        {:id "epupp-cancel"
         :on {:click [:db/assoc :modal {:visible? false :mode nil :block-id nil :error-message nil}]}}
        "Cancel"]
       [:button.epupp-btn.epupp-btn--primary
        {:id "epupp-confirm"
         :on {:click [[:db/assoc :modal {:visible? false :mode nil :block-id nil}]
                      [:block/update-status id :installing]
                      [:block/save-script id code]]}}
        confirm-text]]]]))

(defn render-error-dialog [{:keys [error-message is-update? icon-url]}]
  (let [title (if is-update? "Update Failed" "Installation Failed")]
    [:div.epupp-modal-overlay
     {:on {:click [:db/assoc :modal {:visible? false :mode nil :block-id nil :error-message nil}]}}
     [:div.epupp-modal
      {:on {:click [:block/modal-click]}}
      (modal-header {:action-title title :error? true :icon-url icon-url})
      [:p
       (or error-message "An unknown error occurred.")]
      [:div.epupp-modal__actions
       [:button.epupp-btn.epupp-btn--secondary
        {:id "epupp-close-error"
         :on {:click [:db/assoc :modal {:visible? false :mode nil :block-id nil :error-message nil}]}}
        "Close"]]]]))

(defn render-app [state]
  (let [{:keys [modal icon-url]} state
        {:keys [visible? mode block-id error-message]} modal]
    [:div#epupp-block-installer-ui
     (when visible?
       (case mode
         :error
         (let [current-block (find-block-by-id state block-id)
               is-update? (= (:status current-block) :update)]
           (render-error-dialog {:error-message error-message
                                 :is-update? is-update?
                                 :icon-url icon-url}))

         :confirm
         (when-let [current-block (find-block-by-id state block-id)]
           (render-modal current-block icon-url))))]))

;; ============================================================
;; Action Handler (pure state transitions)
;; ============================================================

(defn handle-action
  "Pure action handler - NO side effects, NO atom reads.
   Takes [state action], returns {:state new-state :effects [...]} or nil."
  [state action]
  (let [[action-type & args] action]
    (case action-type
      :block/update-status
      (let [[block-id new-status error-msg] args]
        {:state (update-block-status state block-id new-status error-msg)})

      :block/save-script
      (let [[block-id code] args]
        {:effects [[:fx/save-script block-id code]]})

      :db/assoc
      (let [[k v] args]
        {:state (assoc state k v)})

      :db/update
      (let [[k f & f-args] args]
        {:state (apply update state k f f-args)})

      :db/update-in
      (let [[path f & f-args] args]
        {:state (apply update-in state path f f-args)})

      nil)))

;; ============================================================
;; Effect Handler & Dispatch Loop
;; ============================================================

(defn perform-effect!
  "Effect executor - ALL side effects happen here.
   Receives dispatch! as first arg for async callbacks."
  [dispatch! effect]
  (let [[effect-type & args] effect]
    (case effect-type
      :fx/save-script
      (let [[block-id code] args
            page-url js/window.location.href
            handle-save-error (fn [error-msg]
                                (js/console.error "[Web Userscript Installer] Save failed:" error-msg)
                                (dispatch! [[:block/update-status block-id :error error-msg]
                                            [:db/assoc :modal {:visible? true
                                                               :mode :error
                                                               :block-id block-id
                                                               :error-message error-msg}]]))]
        (-> (send-and-receive "save-script"
                              {:code code
                               :scriptSource page-url
                               :force true}
                              "save-script-response"
                              5000)
            (.then (fn [msg]
                     (if (.-success msg)
                       (dispatch! [[:block/update-status block-id :installed]])
                       (handle-save-error (or (.-error msg) "Installation failed")))))
            (.catch (fn [error]
                      (handle-save-error (or (.-message error) "Installation failed"))))))

      (js/console.warn "[Web Installer] Unknown effect:" (pr-str effect-type)))))

(defn dispatch!
  "Uniflow dispatch loop: for each action, call handle-action, apply state, execute effects."
  [actions]
  (doseq [action actions]
    (when-let [result (handle-action @!state action)]
      (when-let [new-state (:state result)]
        (reset! !state new-state))
      (when-let [effects (:effects result)]
        (doseq [effect effects]
          (perform-effect! dispatch! effect))))))

;; ============================================================
;; Replicant Bridge
;; ============================================================

(defn handle-event
  "Replicant dispatch bridge.
   Single actions (keyword-first) are routed individually.
   Batch actions (vector-of-vectors) are dispatched together."
  [replicant-data action]
  (if (keyword? (first action))
    ;; Single action
    (case (first action)
      :block/modal-click
      (when-let [e (:replicant/dom-event replicant-data)]
        (.stopPropagation e))
      ;; All other single actions route through dispatch
      (dispatch! [action]))
    ;; Batch of actions
    (dispatch! action)))

;; ============================================================
;; Rendering & Setup
;; ============================================================

(defn render-ui! [state]
  (let [icon-url (:icon-url state)]
    (when-let [container (:ui-container state)]
      (r/render container (render-app state)))
    ;; Also update buttons in their inline containers
    (doseq [[block-id btn-container] (:button-containers state)]
      (when-let [block (find-block-by-id state block-id)]
        (r/render btn-container (render-install-button block icon-url))))))

(defn ensure-installer-css!
  "Inject installer CSS into document.head (idempotent - no-op if already exists)."
  []
  (when-not (js/document.getElementById "epupp-wui-styles")
    (let [style-el (js/document.createElement "style")]
      (set! (.-id style-el) "epupp-wui-styles")
      (set! (.-textContent style-el)
            "
.epupp-install-btn {
  padding: 4px 8px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  transition: all 150ms;
}

.epupp-install-btn.is-install {
  background: #2ea44f;
  color: white;
  border-color: rgba(27,31,36,0.15);
  cursor: pointer;
}

.epupp-install-btn.is-update {
  background: #d97706;
  color: white;
  border-color: rgba(27,31,36,0.15);
  cursor: pointer;
}

.epupp-install-btn.is-installed {
  background: transparent;
  color: black;
  border-color: transparent;
  cursor: default;
}

.epupp-install-btn.is-installing {
  background: #2ea44f;
  color: white;
  border-color: rgba(27,31,36,0.15);
  cursor: default;
}

.epupp-install-btn.is-error {
  background: #dc3545;
  color: white;
  border-color: rgba(27,31,36,0.15);
  cursor: default;
}

.epupp-modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.5);
  z-index: 9998;
  display: flex;
  align-items: center;
  justify-content: center;
}

.epupp-modal {
  background: white;
  padding: 24px;
  border-radius: 8px;
  max-width: 500px;
  box-shadow: 0 8px 24px rgba(0,0,0,0.3);
  z-index: 9999;
}

.epupp-modal h2 {
  margin-top: 0;
}

.epupp-modal h2.is-error {
  color: #dc3545;
}

/* Modal table */
.epupp-modal__table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 16px;
}

.epupp-modal__table td {
  padding: 6px 0;
}

.epupp-modal__table td:first-child {
  color: #666;
  width: 100px;
}

.epupp-modal__note {
  color: #888;
  font-size: 12px;
}

.epupp-modal__note.is-warning {
  color: #d97706;
}

.epupp-modal__actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.epupp-modal__context {
  color: #666;
  font-size: 14px;
  margin-bottom: 16px;
}

.epupp-btn {
  padding: 6px 16px;
  border-radius: 6px;
  cursor: pointer;
}

.epupp-btn--secondary {
  background: #f6f8fa;
  border: 1px solid #d0d7de;
}

.epupp-btn--primary {
  background: #2ea44f;
  color: white;
  border: 1px solid rgba(27,31,36,0.15);
}

.epupp-btn-container {
  margin-left: 8px;
  vertical-align: middle;
}

.epupp-install-btn .epupp-icon {
  flex-shrink: 0;
  margin: -1px 0;
}

.epupp-modal__header { margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid #e1e4e8; }
.epupp-modal__brand { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.epupp-modal__icon { width: 32px; height: 32px; }
.epupp-modal__title { font-size: 16px; font-weight: 600; display: flex; align-items: baseline; gap: 8px; color: #1a1a1a; }
.epupp-modal__tagline { font-size: 16px; font-weight: 400; font-style: italic; color: #1a1a1a; }
.epupp-modal__action-title { margin: 0; font-size: 16px; font-weight: 500; }
.epupp-modal__action-title.is-error { color: #dc3545; }
")
      (.appendChild js/document.head style-el))))

(defn setup-ui! [!db]
  ;; Reuse existing UI container or create new one
  (let [container (or (js/document.getElementById "epupp-block-installer")
                      (let [el (js/document.createElement "div")]
                        (set! (.-id el) "epupp-block-installer")
                        (.appendChild js/document.body el)
                        el))]
    (dispatch! [[:db/assoc :ui-container container]]))

  ;; Set up Replicant dispatcher (idempotent)
  (r/set-dispatch! handle-event)

  ;; One-time setup: ESC handler and state watch (guarded by state flag)
  (when-not (:ui-setup? @!db)
    (dispatch! [[:db/assoc :ui-setup? true]])

    ;; Dismiss modal on ESC key
    (.addEventListener js/document "keydown"
                       (fn [e]
                         (when (and (= (.-key e) "Escape")
                                    (get-in @!db [:modal :visible?]))
                           (dispatch! [[:db/assoc :modal {:visible? false :mode nil :block-id nil :error-message nil}]]))))

    ;; Re-render on state changes
    (add-watch !db ::render (fn [_k _r _o n] (render-ui! n))))

  ;; Initial render
  (render-ui! @!db))

;; ============================================================
;; Scanning & Initialization
;; ============================================================

(defn- script-button-exists?
  "Check if a button for this script name already exists anywhere on the page."
  [script-name]
  (some? (.querySelector js/document (str "[data-epupp-script='" script-name "']"))))

(defn- create-button-container!
  "Create a button container element, set script attribute, register it, return it."
  [tag-name script-name block-id]
  (let [btn-container (js/document.createElement tag-name)]
    (set! (.-className btn-container) "epupp-btn-container")
    (.setAttribute btn-container "data-epupp-script" script-name)
    (dispatch! [[:db/update-in [:button-containers] assoc block-id btn-container]])
    btn-container))

(defn- render-button-into-container!
  "Render install button into container with error handling."
  [container block-data]
  (try
    (r/render container (render-install-button block-data (:icon-url @!state)))
    (catch :default e
      (js/console.error "[Web Userscript Installer] Replicant render error:" e))))

(def ^:private format-specs
  "Specs for attaching buttons to different code block formats."
  {:github-table   {:tag "span" :get-container get-github-button-container :insert :append}
   :gitlab-snippet {:tag "span" :get-container get-gitlab-button-container :insert :append}
   :github-repo    {:tag "span" :get-container get-github-repo-button-container :insert :append}
   :pre            {:tag "div" :insert :before}
   :textarea       {:tag "div" :insert :before}})

(defn- attach-button!
  "Button attachment using format specs."
  [element script-name block-data format]
  (let [{:keys [tag get-container insert]} (get format-specs format)]
    (case insert
      :append
      (when-let [target-container (get-container element)]
        (when-not (.querySelector target-container ".epupp-btn-container")
          (let [btn-container (create-button-container! tag script-name (:id block-data))]
            (.appendChild target-container btn-container)
            (render-button-into-container! btn-container block-data))))

      :before
      (let [existing-btn (.-previousElementSibling element)]
        (when-not (and existing-btn (= (.-className existing-btn) "epupp-btn-container"))
          (let [btn-container (create-button-container! tag script-name (:id block-data))
                parent (.-parentElement element)]
            (.insertBefore parent btn-container element)
            (render-button-into-container! btn-container block-data)))))))

(defn attach-button-to-block!
  "Attach install button to a code block based on format."
  [block-info block-data]
  (let [element (:element block-info)
        format (:format block-info)
        script-name (get-in block-data [:manifest :script-name])]
    (when-not (script-button-exists? script-name)
      (attach-button! element script-name block-data format))))

(defn- create-and-attach-block!
  "Create block-data from processing results and attach button to DOM."
  [block-info block-id manifest code-text existing-code installed-scripts]
  (let [script-name (:script-name manifest)
        state-info (determine-button-state script-name code-text installed-scripts existing-code)
        block-data (merge {:id block-id
                           :manifest manifest
                           :code code-text
                           :format (:format block-info)
                           :status (:install-state state-info)}
                          state-info)]
    (dispatch! [[:db/update :blocks conj block-data]])
    (attach-button-to-block! block-info block-data)))

(defn- process-code-block!+
  "Process a single code block. Returns promise.
   block-info is {:element :format :code-text}
   Fetches script code if needed to determine state."
  [block-info installed-scripts]
  (let [element (:element block-info)
        code-text (:code-text block-info)
        trimmed-text (str/trim code-text)]
    (if (and (> (count trimmed-text) 10)
             (str/starts-with? trimmed-text "{"))
      (if-let [manifest (extract-manifest code-text)]
        (let [script-name (:script-name manifest)
              existing-id (aget element "id")
              block-id (if (and existing-id (pos? (count existing-id)))
                         existing-id
                         (str "block-" (.randomUUID js/crypto)))]
          (.setAttribute element "data-epupp-processed" "true")
          (when-not (and existing-id (pos? (count existing-id)))
            (aset element "id" block-id))
          (-> (if (get installed-scripts script-name)
                (fetch-script-code!+ script-name)
                (js/Promise.resolve nil))
              (.then #(create-and-attach-block! block-info block-id manifest code-text % installed-scripts))
              (.catch (fn [e]
                        (js/console.error "[Web Userscript Installer] Error processing block" script-name e)))))
        (js/Promise.resolve nil))
      (js/Promise.resolve nil))))

(defn- update-existing-blocks-with-installed-scripts!+
  "Update status of existing blocks after installed scripts are fetched.
   Returns promise that resolves when all updates are complete."
  [installed-scripts]
  (let [blocks (:blocks @!state)]
    (-> (js/Promise.all
         (to-array
          (for [block blocks]
            (let [script-name (get-in block [:manifest :script-name])
                  page-code (:code block)]
              (if (get installed-scripts script-name)
                (-> (fetch-script-code!+ script-name)
                    (.then (fn [existing-code]
                             (let [state-info (determine-button-state script-name page-code installed-scripts existing-code)]
                               (dispatch! [[:block/update-status (:id block) (:install-state state-info)]]))))
                    (.catch (fn [e]
                              (js/console.error "[Web Userscript Installer] Error updating block" script-name e))))
                (js/Promise.resolve nil))))))
        (.catch (fn [error]
                  (js/console.error "[Web Userscript Installer] Error updating blocks:" error))))))

(defn scan-code-blocks! []
  (let [all-blocks (detect-all-code-blocks)
        installed-scripts (:installed-scripts @!state)
        unprocessed (filter #(not (.getAttribute (:element %) "data-epupp-processed")) all-blocks)]
    ;; Debug: set marker with scan info
    (when-let [marker (js/document.getElementById "epupp-installer-debug")]
      (set! (.-textContent marker) (str "Scanning: " (count all-blocks) " code blocks, " (count unprocessed) " unprocessed")))
    ;; Process all unprocessed blocks concurrently
    (-> (js/Promise.all
         (to-array (map #(process-code-block!+ % installed-scripts) unprocessed)))
        (.then (fn [_]
                 (when-let [marker (js/document.getElementById "epupp-installer-debug")]
                   (set! (.-textContent marker) (str "Scan complete: " (count (:blocks @!state)) " blocks found")))))
        (.catch (fn [error]
                  (js/console.error "[Web Userscript Installer] Scan error:" error))))))

(defn scan-with-retry!
  "Scan for code blocks, retry with backoff if no blocks found.
   Pending retries are cancelled by rescan! via clearTimeout."
  ([] (scan-with-retry! 0))
  ([retry-index]
   (-> (scan-code-blocks!)
       (.then (fn [_]
                (when (and (zero? (count (:blocks @!state)))
                           (< retry-index (count retry-delays)))
                  (let [delay (nth retry-delays retry-index)
                        timeout-id (js/setTimeout
                                    #(scan-with-retry! (inc retry-index))
                                    delay)]
                    (dispatch! [[:db/assoc :pending-retry-timeout timeout-id]])))))
       (.catch (fn [error]
                 (js/console.error "[Web Userscript Installer] Scan error:" error))))))

(defn rescan!
  "Reset DOM-tied state and re-scan for code blocks.
   Safe to call repeatedly - cancels any pending retry timeout."
  [!state]
  (when-let [timeout-id (:pending-retry-timeout @!state)]
    (js/clearTimeout timeout-id))
  (dispatch! [[:db/assoc :pending-retry-timeout nil]
              [:db/assoc :blocks []]
              [:db/assoc :modal {:visible? false :mode nil :block-id nil :error-message nil}]
              [:db/assoc :button-containers {}]])
  (scan-with-retry!))

(defn init! [!db]
  (let [state @!db]
    (js/console.log "[Web Userscript Installer] Initializing...")

    ;; Debug marker (idempotent)
    (when-not (js/document.getElementById "epupp-installer-debug")
      (let [marker (js/document.createElement "div")]
        (set! (.-id marker) "epupp-installer-debug")
        (set! (.. marker -style -display) "none")
        (when js/document.body
          (.appendChild js/document.body marker))))

    (ensure-installer-css!)
    (setup-ui! !db)

    ;; Initial scan
    (if (= js/document.readyState "loading")
      (.addEventListener js/document "DOMContentLoaded" (partial rescan! !db))
      (rescan! !db))

    ;; Fetch icon URL (once)
    (when-not (:icon-url state)
      (-> (fetch-icon-url!+)
          (.then (fn [url]
                   (dispatch! [[:db/assoc :icon-url url]])))
          (.catch (fn [_]))))

    ;; Fetch installed scripts for button state awareness
    (-> (fetch-installed-scripts!+)
        (.then (fn [installed-scripts]
                 (dispatch! [[:db/assoc :installed-scripts installed-scripts]])
                 (update-existing-blocks-with-installed-scripts!+ installed-scripts)))
        (.catch (fn [error]
                  (js/console.error "[Web Userscript Installer] Failed to fetch installed scripts:" error))))

    ;; SPA navigation listener (once)
    (when-not (:nav-registered? state)
      (dispatch! [[:db/assoc :nav-registered? true]])
      (when js/window.navigation
        (let [!nav-timeout (atom nil)
              !last-url (atom js/window.location.href)]
          (.addEventListener js/window.navigation "navigate"
                             (fn [evt]
                               (let [new-url (.-url (.-destination evt))]
                                 (when (not= new-url @!last-url)
                                   (reset! !last-url new-url)
                                   (when-let [tid @!nav-timeout]
                                     (js/clearTimeout tid))
                                   (reset! !nav-timeout
                                           (js/setTimeout (partial rescan! !db) 300)))))))))

    (js/console.log "[Web Userscript Installer] Ready")))

(init! !state)
