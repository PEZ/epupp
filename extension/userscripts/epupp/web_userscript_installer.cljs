{:epupp/script-name "epupp/web_userscript_installer.cljs"
 :epupp/auto-run-match "*"
 :epupp/description "Web Userscript Installer. Finds userscripts on web pages, and adds a button to install the script into Epupp"
 :epupp/inject ["scittle://replicant.js"]}

;; Epupp Web Userscript Installer
;;
;; This userscript scans code blocks for Epupp userscript manifests,
;; adds Install buttons, which installs the script into the extension.

(ns epupp.web-userscript-installer
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [replicant.dom :as r]))

;; ============================================================
;; Script name normalization (mirrors script-utils logic)
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

;; ============================================================
;; Manifest parsing
;;
;; SYNC WARNING: This parser must stay in sync with src/manifest_parser.cljs
;; Key behaviors that must match:
;; - auto-run-match is OPTIONAL (nil means manual-only script)
;; - auto-run-match preserves vector/string as-is
;; ============================================================

(defn- get-first-form
  "Read the first form from code text. Returns map or nil."
  [code-text]
  (try
    (let [form (edn/read-string code-text)]
      (when (map? form) form))
    (catch :default e
      (js/console.error "[Web Userscript Installer] Parse error:" e)
      nil)))

(defn has-manifest?
  "Check if the first form is a map with :epupp/script-name"
  [code-text]
  (when code-text
    (when-let [m (get-first-form code-text)]
      (get m :epupp/script-name))))

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
;; DOM helpers - Multi-format code block detection
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
   Excludes pre elements that are inside .file-holder (GitLab-specific containers)."
  []
  (->> (.querySelectorAll js/document "pre")
       js/Array.from
       (filter (fn [el]
                 ;; Exclude if inside .file-holder (GitLab snippet)
                 (not (.closest el ".file-holder"))))
       (map (fn [el]
              {:element el
               :format :pre
               :code-text (get-pre-text el)}))))

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

(defn- detect-all-code-blocks
  "Detect all code blocks on page. Returns seq of {:element :format :code-text}"
  []
  ;; More specific formats first (GitHub tables, GitLab snippets), then generic pre elements
  (concat (detect-github-tables)
          (detect-gitlab-snippets)
          (detect-pre-elements)))

(defn- get-github-button-container
  "Get the .file-actions container for GitHub gist button placement"
  [table-element]
  (when-let [file-div (.closest table-element ".file")]
    (.querySelector file-div ".file-actions")))

(defn- get-gitlab-button-container
  "Get the .file-actions container for GitLab snippet button placement"
  [file-holder-element]
  (.querySelector file-holder-element ".file-actions"))

;; ============================================================
;; State management
;; ============================================================

(defonce !state
  (atom {:blocks []
         :installed-scripts {}
         :modal {:visible? false
                 :mode nil  ;; :confirm or :error
                 :block-id nil
                 :error-message nil}}))

(defn find-block-by-id [state block-id]
  (first (filter #(= (:id %) block-id) (:blocks state))))

(defn update-block-status [state block-id new-status]
  (update state :blocks
          (fn [blocks]
            (mapv (fn [b]
                    (if (= (:id b) block-id)
                      (assoc b :status new-status)
                      b))
                  blocks))))

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
;; Extension communication
;; ============================================================

(defonce ^:private !request-id (atom 0))

(defn- next-request-id []
  (swap! !request-id inc))

(defn- send-and-receive
  "Helper: send message to bridge and return promise of response."
  [msg-type payload response-type]
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
                    2000))
          (.postMessage js/window
            (clj->js (assoc payload :source "epupp-page" :type msg-type :requestId req-id))
            "*"))))))

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

(defn send-save-request!
  "Send save-script request to extension via postMessage.
   Sends the code directly (no URL fetch) with the page URL as source."
  [code callback]
  (let [page-url js/window.location.href
        request-id (str "save-" (.now js/Date) "-" (.random js/Math))
        timeout-id (atom nil)
        listener (fn listener [event]
                   (let [data (.-data event)]
                     (when (and (= (aget data "source") "epupp-bridge")
                                (= (aget data "type") "save-script-response")
                                (= (aget data "requestId") request-id))
                       (js/console.log "[Web Userscript Installer] Received save response" (aget data "success"))
                       (when-let [tid @timeout-id]
                         (js/clearTimeout tid))
                       (.removeEventListener js/window "message" listener)
                       (callback #js {:success (aget data "success")
                                      :error (aget data "error")}))))]
    (.addEventListener js/window "message" listener)
    ;; Add timeout for debugging
    (reset! timeout-id
            (js/setTimeout
              (fn []
                (js/console.error "[Web Userscript Installer] Save request timed out after 5s")
                (.removeEventListener js/window "message" listener)
                (callback #js {:success false :error "Timeout waiting for save response"}))
              5000))
    (js/console.log "[Web Userscript Installer] Sending save request" request-id)
    (.postMessage js/window
                  #js {:source "epupp-userscript"
                       :type "save-script"
                       :requestId request-id
                       :code code
                       :scriptSource page-url
                       :force true}
                  "*")))

;; ============================================================
;; UI Components (Replicant hiccup)
;; ============================================================

(defn epupp-icon
  "Epupp icon - lightning bolt design from extension/icons/icon.svg"
  []
  [:svg {:width 16
         :height 16
         :viewBox "0 0 100 100"
         :fill "none"
         :style {:flex-shrink 0}}
   ;; Blue circle background
   [:circle {:cx 50 :cy 50 :r 48 :fill "#4a71c4"}]
   ;; Yellow/gold lightning bolt
   [:path {:fill "#ffdc73"
           :transform "translate(50, 50) scale(0.5) translate(-211, -280)"
           :d "M224.12 259.93h21.11a5.537 5.537 0 0 1 4.6 8.62l-50.26 85.75a5.536 5.536 0 0 1-7.58 1.88 5.537 5.537 0 0 1-2.56-5.85l7.41-52.61-24.99.43a5.538 5.538 0 0 1-5.61-5.43c0-1.06.28-2.04.78-2.89l49.43-85.71a5.518 5.518 0 0 1 7.56-1.95 5.518 5.518 0 0  1 2.65 5.53l-2.54 52.23z"}]])

(defn button-tooltip [status]
  (case status
    :install "Install to Epupp"
    :update "Update existing Epupp script"
    :installed "Already installed in Epupp (identical)"
    :installing "Installing..."
    :error "Installation failed"
    "Install to Epupp"))

(defn run-at-label [run-at]
  (case run-at
    "document-start" "document-start (early)"
    "document-end" "document-end"
    "document-idle (default)"))

(defn render-install-button [{:keys [id status]}]
  (let [clickable? (#{:install :update} status)]
    [:button.epupp-install-btn
     {:on {:click [:block/show-confirm id]}
      :disabled (not clickable?)
      :title (button-tooltip status)
      :style {:margin "8px 0"
              :padding "6px 12px"
              :display "inline-flex"
              :align-items "center"
              :gap "6px"
              :background (case status
                            :install "#2ea44f"
                            :update "#d97706"
                            :installed "#6c757d"
                            :installing "#2ea44f"
                            :error "#dc3545"
                            "#2ea44f")
              :color "white"
              :border "1px solid rgba(27,31,36,0.15)"
              :border-radius "4px"
              :font-size "12px"
              :font-weight "500"
              :font-family "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
              :cursor (if clickable? "pointer" "default")
              :transition "all 150ms"}}
     (epupp-icon)
     (case status
       :install "Install"
       :update "Update"
       :installed "✓ Installed"
       :installing "Installing..."
       :error "Install Failed"
       "Install")]))

(defn render-modal [{:keys [id manifest code status]}]
  (let [{:keys [script-name raw-script-name name-normalized?
                auto-run-match description run-at run-at-invalid? raw-run-at]} manifest
        page-url js/window.location.href
        is-update? (= status :update)
        modal-title (if is-update? "Update Userscript" "Install Userscript")
        confirm-text (if is-update? "Update" "Install")]
    [:div.epupp-modal-overlay
     {:on {:click [:block/overlay-click]}
      :style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
              :background "rgba(0,0,0,0.5)" :z-index 9998
              :display "flex" :align-items "center" :justify-content "center"}}
     [:div.epupp-modal
      {:on {:click [:block/modal-click]}
       :style {:background "white" :padding "24px" :border-radius "8px"
               :max-width "500px" :box-shadow "0 8px 24px rgba(0,0,0,0.3)"
               :z-index 9999}}
      [:h2 {:style {:margin-top 0}} modal-title]
      ;; Property table
      [:table {:style {:width "100%" :border-collapse "collapse" :margin-bottom "16px"}}
       [:tbody
        [:tr
         [:td {:style {:padding "6px 0" :color "#666" :width "100px"}} "Name"]
         [:td {:style {:padding "6px 0"}}
          [:code script-name]
          (when name-normalized?
            [:span
             [:br]
             [:span {:style {:color "#888" :font-size "12px"}}
              "Normalized from: " raw-script-name]])]]
        [:tr
         [:td {:style {:padding "6px 0" :color "#666"}} "URL Pattern"]
         [:td {:style {:padding "6px 0"}} (or auto-run-match [:em "None"])]]
        [:tr
         [:td {:style {:padding "6px 0" :color "#666"}} "Description"]
         [:td {:style {:padding "6px 0"}} (or description [:em "Not specified"])]]
        [:tr
         [:td {:style {:padding "6px 0" :color "#666"}} "Run At"]
         [:td {:style {:padding "6px 0"}}
          (run-at-label run-at)
          (when run-at-invalid?
            [:span
             [:br]
             [:span {:style {:color "#d97706" :font-size "12px"}}
              "Invalid value \"" raw-run-at "\" - using default"]])]]]]
      [:p {:style {:margin "0 0 8px"}} [:strong "Source:"]]
      [:p {:style {:margin "0 0 16px"}}
       [:code {:style {:word-break "break-all"}} page-url]]
      ;; Context message
      [:p {:style {:color "#666" :font-size "14px" :margin-bottom "16px"}}
       (if is-update?
         "This will update the existing script."
         "This will install the script from this page.")]
      [:div {:style {:display "flex" :gap "8px" :justify-content "flex-end"}}
       [:button#epupp-cancel {:on {:click [:block/cancel-install]}
                              :style {:padding "6px 16px" :background "#f6f8fa"
                                      :border "1px solid #d0d7de" :border-radius "6px"
                                      :cursor "pointer"}}
        "Cancel"]
       [:button#epupp-confirm {:on {:click [:block/confirm-install id code]}
                               :style {:padding "6px 16px" :background "#2ea44f"
                                       :color "white" :border "1px solid rgba(27,31,36,0.15)"
                                       :border-radius "6px" :cursor "pointer"}}
        confirm-text]]]]))

(defn render-error-dialog [{:keys [error-message is-update?]}]
  (let [title (if is-update? "Update Failed" "Installation Failed")]
    [:div.epupp-modal-overlay
     {:on {:click [:block/close-error]}
      :style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
              :background "rgba(0,0,0,0.5)" :z-index 9998
              :display "flex" :align-items "center" :justify-content "center"}}
     [:div.epupp-modal
      {:on {:click [:block/modal-click]}
       :style {:background "white" :padding "24px" :border-radius "8px"
               :max-width "500px" :box-shadow "0 8px 24px rgba(0,0,0,0.3)"
               :z-index 9999}}
      [:h2 {:style {:margin-top 0 :color "#dc3545"}} title]
      [:p {:style {:margin "16px 0" :color "#333"}}
       (or error-message "An unknown error occurred.")]
      [:div {:style {:display "flex" :gap "8px" :justify-content "flex-end"}}
       [:button#epupp-close-error {:on {:click [:block/close-error]}
                                   :style {:padding "6px 16px" :background "#f6f8fa"
                                           :border "1px solid #d0d7de" :border-radius "6px"
                                           :cursor "pointer"}}
        "Close"]]]]))

(defn render-app [state]
  (let [{:keys [modal]} state
        {:keys [visible? mode block-id error-message]} modal]
    [:div#epupp-block-installer-ui
     (when visible?
       (case mode
         :error
         (let [current-block (find-block-by-id state block-id)
               is-update? (= (:status current-block) :update)]
           (render-error-dialog {:error-message error-message
                                 :is-update? is-update?}))

         :confirm
         (when-let [current-block (find-block-by-id state block-id)]
           (render-modal current-block))

         ;; Default (backward compatibility)
         (when-let [current-block (find-block-by-id state block-id)]
           (render-modal current-block))))]))

;; ============================================================
;; Event handling
;; ============================================================

(defn handle-event [_replicant-data action]
  (let [[action-type & args] action]
    (case action-type
      :block/show-confirm
      (let [[block-id] args]
        (swap! !state assoc :modal {:visible? true :mode :confirm :block-id block-id}))

      :block/overlay-click
      (swap! !state assoc :modal {:visible? false :mode nil :block-id nil})

      :block/modal-click
      nil  ;; Don't close when clicking inside modal

      :block/cancel-install
      (swap! !state assoc :modal {:visible? false :mode nil :block-id nil})

      :block/close-error
      (swap! !state assoc :modal {:visible? false :mode nil :block-id nil :error-message nil})

      :block/confirm-install
      (let [[block-id code] args]
        (js/console.log "[Web Userscript Installer] Confirming install for block:" block-id)
        (swap! !state assoc :modal {:visible? false :mode nil :block-id nil})
        (swap! !state update-block-status block-id :installing)
        (send-save-request!
         code
         (fn [response]
           (js/console.log "[Web Userscript Installer] Save response:" (.-success response) (.-error response))
           (if (.-success response)
             (do
               (js/console.log "[Web Userscript Installer] Save successful, updating to :installed")
               (swap! !state update-block-status block-id :installed))
             (let [error-msg (or (.-error response) "Installation failed")]
               (js/console.error "[Web Userscript Installer] Save failed:" error-msg)
               (swap! !state update-block-status block-id :error)
               (swap! !state assoc :modal {:visible? true
                                           :mode :error
                                           :block-id block-id
                                           :error-message error-msg}))))))

      ;; Default
      (js/console.warn "[Block Installer] Unknown action:" (pr-str action-type)))))

;; ============================================================
;; Rendering setup
;; ============================================================

(defonce !button-containers (atom {}))
(defonce !ui-container (atom nil))

(defn render-ui! []
  (let [state @!state]
    (when-let [container @!ui-container]
      (r/render container (render-app state))))
  ;; Also update buttons in their inline containers
  (doseq [[block-id btn-container] @!button-containers]
    (when-let [block (find-block-by-id @!state block-id)]
      (r/render btn-container (render-install-button block)))))

(defn setup-ui! []
  ;; Create main UI container for modal
  (let [container (js/document.createElement "div")]
    (set! (.-id container) "epupp-block-installer")
    (.appendChild js/document.body container)
    (reset! !ui-container container))

  ;; Set up Replicant dispatcher (once)
  (r/set-dispatch! handle-event)

  ;; Re-render on state changes
  (add-watch !state ::render (fn [_ _ _ _] (render-ui!)))

  ;; Initial render
  (render-ui!))

;; ============================================================
;; Gist scanning and initialization
;; ============================================================

(defn attach-button-to-block!
  "Attach install button to a code block. Handles different formats:
   - :pre - Insert button container before the pre element
   - :github-table - Append button to .file-actions container
   - :gitlab-snippet - Append button to .file-actions container"
  [block-info block-data]
  (let [element (:element block-info)
        format (:format block-info)]
    (case format
      ;; GitHub gist: append to .file-actions
      :github-table
      (when-let [file-actions (get-github-button-container element)]
        (when-not (.querySelector file-actions ".epupp-btn-container")
          (let [btn-container (js/document.createElement "span")]
            (set! (.-className btn-container) "epupp-btn-container")
            (set! (.. btn-container -style -marginLeft) "8px")
            (.appendChild file-actions btn-container)
            (swap! !button-containers assoc (:id block-data) btn-container)
            (js/console.log "[Web Userscript Installer] GitHub button container created for:" (:script/name block-data))
            (try
              (r/render btn-container (render-install-button block-data))
              (catch :default e
                (js/console.error "[Web Userscript Installer] Replicant render error:" e))))))

      ;; GitLab snippet: append to .file-actions
      :gitlab-snippet
      (when-let [file-actions (get-gitlab-button-container element)]
        (when-not (.querySelector file-actions ".epupp-btn-container")
          (let [btn-container (js/document.createElement "span")]
            (set! (.-className btn-container) "epupp-btn-container btn btn-default btn-sm")
            (set! (.. btn-container -style -marginLeft) "8px")
            (.appendChild file-actions btn-container)
            (swap! !button-containers assoc (:id block-data) btn-container)
            (js/console.log "[Web Userscript Installer] GitLab button container created for:" (:script/name block-data))
            (try
              (r/render btn-container (render-install-button block-data))
              (catch :default e
                (js/console.error "[Web Userscript Installer] Replicant render error:" e))))))

      ;; Pre element (generic): insert before
      :pre
      (let [existing-btn (aget element "previousElementSibling")]
        (when-not (and existing-btn
                       (= (aget existing-btn "className") "epupp-btn-container"))
          (let [btn-container (js/document.createElement "div")
                parent (.-parentElement element)]
            (js/console.log "[Web Userscript Installer] Creating container for:" (:script/name block-data) "parent:" (some? parent))
            (set! (.-className btn-container) "epupp-btn-container")
            (.insertBefore parent btn-container element)
            (swap! !button-containers assoc (:id block-data) btn-container)
            (js/console.log "[Web Userscript Installer] Container inserted, about to render button")
            (try
              (r/render btn-container (render-install-button block-data))
              (js/console.log "[Web Userscript Installer] Replicant render complete")
              (catch :default e
                (js/console.error "[Web Userscript Installer] Replicant render error:" e)
                (let [btn (js/document.createElement "button")]
                  (set! (.-textContent btn) "Install")
                  (set! (.-className btn) "epupp-install-btn epupp-btn-install-state")
                  (.addEventListener btn "click" (fn [] (handle-event nil [:block/show-confirm (:id block-data)])))
                  (.appendChild btn-container btn)
                  (js/console.log "[Web Userscript Installer] Fallback button appended"))))))))))

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
        (let [script-name (:script-name manifest)]
          (js/console.log "[Web Userscript Installer] Found installable script:" script-name)
          ;; Mark as processed
          (.setAttribute element "data-epupp-processed" "true")
          ;; Ensure element has an ID
          (let [existing-id (aget element "id")
                block-id (if (and existing-id (pos? (count existing-id)))
                           existing-id
                           (str "block-" (.randomUUID js/crypto)))]
            (js/console.log "[Web Userscript Installer] Block ID:" block-id "format:" (:format block-info))
            (when-not (and existing-id (pos? (count existing-id)))
              (aset element "id" block-id))
            ;; Fetch code if script is installed
            (-> (if (get installed-scripts script-name)
                  (fetch-script-code!+ script-name)
                  (js/Promise.resolve nil))
                (.then (fn [existing-code]
                         (js/console.log "[Web Userscript Installer] Processing block:" script-name "existing-code:" (some? existing-code))
                         (let [state-info (determine-button-state script-name code-text installed-scripts existing-code)
                               _ (js/console.log "[Web Userscript Installer] State info:" (pr-str state-info))
                               block-data (merge {:id block-id
                                                  :manifest manifest
                                                  :code code-text
                                                  :format (:format block-info)
                                                  :status (:install-state state-info)}
                                                 state-info)]
                           ;; Add to state
                           (swap! !state update :blocks conj block-data)
                           (js/console.log "[Web Userscript Installer] About to attach button for" script-name)
                           ;; Attach button - pass block-info for format-aware placement
                           (attach-button-to-block! block-info block-data))))
                (.catch (fn [e]
                          (js/console.error "[Web Userscript Installer] Error processing block" script-name e))))))
        (js/Promise.resolve nil))
      (js/Promise.resolve nil))))

(defn- update-existing-blocks-with-installed-scripts!+
  "Update status of existing blocks after installed scripts are fetched.
   Returns promise that resolves when all updates are complete."
  [installed-scripts]
  (let [blocks (:blocks @!state)]
    (js/console.log "[Web Userscript Installer] Updating" (count blocks) "blocks with" (count (keys installed-scripts)) "installed scripts")
    (-> (js/Promise.all
         (to-array
          (for [block blocks]
            (let [script-name (get-in block [:manifest :script-name])
                  page-code (:code block)]
              (if (get installed-scripts script-name)
                (-> (fetch-script-code!+ script-name)
                    (.then (fn [existing-code]
                             (let [state-info (determine-button-state script-name page-code installed-scripts existing-code)]
                               (swap! !state update-block-status (:id block) (:install-state state-info))
                               (js/console.log "[Web Userscript Installer] Updated block" script-name "to" (:install-state state-info)))))
                    (.catch (fn [e]
                              (js/console.error "[Web Userscript Installer] Error updating block" script-name e))))
                (js/Promise.resolve nil))))))
        (.then (fn [_]
                 (js/console.log "[Web Userscript Installer] Finished updating block states")))
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
                 (js/console.log "[Web Userscript Installer] Scan complete")
                 (when-let [marker (js/document.getElementById "epupp-installer-debug")]
                   (set! (.-textContent marker) (str "Scan complete: " (count (:blocks @!state)) " blocks found")))))
        (.catch (fn [error]
                  (js/console.error "[Web Userscript Installer] Scan error:" error)
                  (when-let [marker (js/document.getElementById "epupp-installer-debug")]
                    (set! (.-textContent marker) (str "Scan ERROR: " (.-message error)))))))))

(defn init! []
  (js/console.log "[Web Userscript Installer] Initializing with Replicant...")

  ;; Debug marker - invisible element to confirm script ran
  (let [marker (js/document.createElement "div")]
    (set! (.-id marker) "epupp-installer-debug")
    (set! (.-textContent marker) "Installer script executed")
    (set! (.. marker -style -display) "none")
    (when js/document.body
      (.appendChild js/document.body marker)))

  (setup-ui!)

  ;; Scan immediately (with empty installed-scripts)
  (if (= js/document.readyState "loading")
    (.addEventListener js/document "DOMContentLoaded" scan-code-blocks!)
    (scan-code-blocks!))

  ;; Fetch installed scripts in background for state awareness
  ;; This will update button states when info arrives
  (-> (fetch-installed-scripts!+)
      (.then (fn [installed-scripts]
               (js/console.log "[Web Userscript Installer] Fetched" (count (keys installed-scripts)) "installed scripts")
               (swap! !state assoc :installed-scripts installed-scripts)
               ;; Update existing blocks with installed script info
               (update-existing-blocks-with-installed-scripts!+ installed-scripts)))
      (.catch (fn [error]
                (js/console.error "[Web Userscript Installer] Failed to fetch installed scripts:" error))))

  (js/console.log "[Web Userscript Installer] Ready"))

(init!)
