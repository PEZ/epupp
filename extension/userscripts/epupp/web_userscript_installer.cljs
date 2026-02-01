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
;; DOM helpers
;; ============================================================

(defn get-code-block-text
  "Get text content from a code block (pre element)"
  [pre-element]
  (.-textContent pre-element))



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
  "Simple Epupp icon (16x16 SVG - 'E' mark)"
  []
  [:svg {:width 16
         :height 16
         :viewBox "0 0 16 16"
         :fill "currentColor"
         :style {:flex-shrink 0}}
   [:path {:d "M3 2h8v2H5v3h5v2H5v3h6v2H3V2z"}]])

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

(defn attach-button-to-block! [pre-element block-data]
  ;; Check if button container already exists (idempotency)
  ;; Use aget for property access (Scittle doesn't support .-previousElementSibling)
  (let [existing-btn (aget pre-element "previousElementSibling")]
    (when-not (and existing-btn
                   (= (aget existing-btn "className") "epupp-btn-container"))
      (let [btn-container (js/document.createElement "div")
            parent (.-parentElement pre-element)]
        (js/console.log "[Web Userscript Installer] Creating container for:" (:script/name block-data) "parent:" (some? parent))
        (set! (.-className btn-container) "epupp-btn-container")
        (.insertBefore parent btn-container pre-element)
        ;; Track container for re-renders
        (swap! !button-containers assoc (:id block-data) btn-container)
        (js/console.log "[Web Userscript Installer] Container inserted, about to render button")
        ;; Initial button render with error handling
        (try
          (r/render btn-container (render-install-button block-data))
          (js/console.log "[Web Userscript Installer] Replicant render complete")
          (catch :default e
            (js/console.error "[Web Userscript Installer] Replicant render error:" e)
            ;; Fallback: create simple button without Replicant
            (let [btn (js/document.createElement "button")]
              (set! (.-textContent btn) "Install")
              (set! (.-className btn) "epupp-install-btn epupp-btn-install-state")
              (.addEventListener btn "click" (fn [] (handle-event nil [:block/show-confirm (:id block-data)])))
              (.appendChild btn-container btn)
              (js/console.log "[Web Userscript Installer] Fallback button appended"))))))))

(defn- process-code-block!+
  "Process a single code block element. Returns promise.
   Fetches script code if needed to determine state."
  [pre-element installed-scripts]
  (let [code-text (.-textContent pre-element)
        trimmed-text (str/trim code-text)]
    (if (and (> (count trimmed-text) 10)
             (str/starts-with? trimmed-text "{"))
      (if-let [manifest (extract-manifest code-text)]
        (let [script-name (:script-name manifest)]
          (js/console.log "[Web Userscript Installer] Found installable script:" script-name)
          ;; Mark as processed
          (.setAttribute pre-element "data-epupp-processed" "true")
          ;; Ensure element has an ID - use aget since Scittle doesn't fully support .-id
          (let [existing-id (aget pre-element "id")
                block-id (if (and existing-id (pos? (count existing-id)))
                           existing-id
                           (str "block-" (.randomUUID js/crypto)))]
            (js/console.log "[Web Userscript Installer] Block ID:" block-id "existing:" existing-id)
            (when-not (and existing-id (pos? (count existing-id)))
              (aset pre-element "id" block-id))
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
                                                  :status (:install-state state-info)}
                                                 state-info)]
                           ;; Add to state
                           (swap! !state update :blocks conj block-data)
                           (js/console.log "[Web Userscript Installer] About to attach button for" script-name)
                           ;; Attach button
                           (attach-button-to-block! pre-element block-data))))
                (.catch (fn [e]
                          (js/console.error "[Web Userscript Installer] Error processing block" script-name e)))))
        (js/Promise.resolve nil))
      (js/Promise.resolve nil)))))

(defn scan-code-blocks! []
  (let [pre-elements (.querySelectorAll js/document "pre")
        pre-array (js/Array.from pre-elements)
        installed-scripts (:installed-scripts @!state)
        unprocessed (filter #(not (.getAttribute % "data-epupp-processed")) pre-array)]
    ;; Debug: set marker with scan info
    (when-let [marker (js/document.getElementById "epupp-installer-debug")]
      (set! (.-textContent marker) (str "Scanning: " (count pre-array) " pre elements, " (count unprocessed) " unprocessed")))
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

  ;; Debug marker - visible element to confirm script ran
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
               ;; Re-scan to update button states with installed info
               (scan-code-blocks!)))
      (.catch (fn [error]
                (js/console.error "[Web Userscript Installer] Failed to fetch installed scripts:" error))))

  (js/console.log "[Web Userscript Installer] Ready"))

(init!)
