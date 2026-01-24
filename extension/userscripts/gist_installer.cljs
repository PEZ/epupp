{:epupp/script-name "Gist Installer"
 :epupp/site-match "https://gist.github.com/*"
 :epupp/description "Adds Install buttons to Epupp userscripts on GitHub Gists"
 :epupp/inject ["scittle://replicant.js"]}

;; Epupp Gist Installer - Runs in Scittle on GitHub gist pages
;;
;; This userscript scans gist code blocks for install manifests,
;; adds Install buttons, and sends parsed data to the extension.
;;
;; Uses Replicant for declarative UI rendering.

(ns gist-installer
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
;; ============================================================

(defn- get-first-form
  "Read the first form from code text. Returns map or nil."
  [code-text]
  (try
    (let [form (edn/read-string code-text)]
      (when (map? form) form))
    (catch :default e
      (js/console.error "[Gist Installer] Parse error:" e)
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
            raw-site-match (get m :epupp/site-match)
            site-match (if (vector? raw-site-match)
                         (first raw-site-match)
                         raw-site-match)]
        {:script-name normalized-name
         :raw-script-name raw-name
         :name-normalized? (not= raw-name normalized-name)
         :site-match site-match
         :description (get m :epupp/description)
         :run-at run-at
         :raw-run-at raw-run-at
         :run-at-invalid? (and raw-run-at
                               (not (contains? valid-run-at-values raw-run-at)))}))))

;; ============================================================
;; DOM helpers
;; ============================================================

(defn get-gist-file-text
  "Get text content from a gist file"
  [file-container]
  (let [lines (.querySelectorAll file-container ".js-file-line")
        line-array (js/Array.from lines)]
    (str/join "\n" (map #(.-textContent %) line-array))))

(defn get-gist-raw-url
  "Extract the raw gist URL from the Raw button link"
  [file-container]
  (when-let [file-actions (.querySelector file-container ".file-actions")]
    (when-let [raw-link (.querySelector file-actions "a[href*='/raw/']")]
      (.-href raw-link))))

;; ============================================================
;; State management
;; ============================================================

(defonce !state
  (atom {:gists []
         :modal {:visible? false
                 :gist-id nil}}))

(defn find-gist-by-id [state gist-id]
  (first (filter #(= (:id %) gist-id) (:gists state))))

(defn update-gist-status [state gist-id new-status]
  (update state :gists
          (fn [gists]
            (mapv (fn [g]
                    (if (= (:id g) gist-id)
                      (assoc g :status new-status)
                      g))
                  gists))))

;; ============================================================
;; Extension communication
;; ============================================================

(defn send-install-request!
  "Send install request to extension via postMessage"
  [manifest script-url callback]
  (let [listener (fn listener [event]
                   (let [data (.-data event)]
                     (when (and (= (.-source data) "epupp-bridge")
                                (= (.-type data) "install-response"))
                       (.removeEventListener js/window "message" listener)
                       (callback #js {:success (.-success data)
                                      :error (.-error data)}))))]
    (.addEventListener js/window "message" listener)
    (.postMessage js/window
                  #js {:source "epupp-userscript"
                       :type "install-userscript"
                       :manifest (clj->js manifest)
                       :scriptUrl script-url}
                  "*")))

;; ============================================================
;; UI Components (Replicant hiccup)
;; ============================================================

(defn run-at-label [run-at]
  (case run-at
    "document-start" "document-start (early)"
    "document-end" "document-end"
    "document-idle (default)"))

(defn render-install-button [{:keys [id status]}]
  [:button.epupp-install-btn
   {:on {:click [:gist/show-confirm id]}
    :disabled (not= status :ready)
    :style {:margin "8px 0"
            :padding "6px 12px"
            :background (case status
                          :installed "#6c757d"
                          :error "#dc3545"
                          "#2ea44f")
            :color "white"
            :border "1px solid rgba(27,31,36,0.15)"
            :border-radius "6px"
            :font-size "14px"
            :font-weight "500"
            :cursor (if (= status :ready) "pointer" "default")}}
   (case status
     :ready "Install to Epupp"
     :installing "Installing..."
     :installed "✓ Installed"
     :error "Install Failed"
     "Install")])

(defn render-modal [{:keys [id manifest raw-url]}]
  (let [{:keys [script-name raw-script-name name-normalized?
                site-match description run-at run-at-invalid? raw-run-at]} manifest]
    [:div.epupp-modal-overlay
     {:on {:click [:gist/overlay-click]}
      :style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
              :background "rgba(0,0,0,0.5)" :z-index 9998
              :display "flex" :align-items "center" :justify-content "center"}}
     [:div.epupp-modal
      {:on {:click [:gist/modal-click]}
       :style {:background "white" :padding "24px" :border-radius "8px"
               :max-width "500px" :box-shadow "0 8px 24px rgba(0,0,0,0.3)"
               :z-index 9999}}
      [:h2 {:style {:margin-top 0}} "Install Userscript"]
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
         [:td {:style {:padding "6px 0"}} (or site-match [:em "None"])]]
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
       [:code {:style {:word-break "break-all"}} raw-url]]
      [:p {:style {:color "#666" :font-size "14px" :margin-bottom "16px"}}
       "This will download and install the script from the gist above."]
      [:div {:style {:display "flex" :gap "8px" :justify-content "flex-end"}}
       [:button#epupp-cancel {:on {:click [:gist/cancel-install]}
                              :style {:padding "6px 16px" :background "#f6f8fa"
                                      :border "1px solid #d0d7de" :border-radius "6px"
                                      :cursor "pointer"}}
        "Cancel"]
       [:button#epupp-confirm {:on {:click [:gist/confirm-install id manifest raw-url]}
                               :style {:padding "6px 16px" :background "#2ea44f"
                                       :color "white" :border "1px solid rgba(27,31,36,0.15)"
                                       :border-radius "6px" :cursor "pointer"}}
        "Install"]]]]))

(defn render-app [state]
  (let [{:keys [modal]} state
        {:keys [visible? gist-id]} modal
        current-gist (when visible? (find-gist-by-id state gist-id))]
    [:div#epupp-gist-installer-ui
     (when (and visible? current-gist)
       (render-modal current-gist))]))

;; ============================================================
;; Event handling
;; ============================================================

(defn handle-event [_replicant-data action]
  (let [[action-type & args] action]
    (case action-type
      :gist/show-confirm
      (let [[gist-id] args]
        (swap! !state assoc :modal {:visible? true :gist-id gist-id}))

      :gist/overlay-click
      (swap! !state assoc :modal {:visible? false :gist-id nil})

      :gist/modal-click
      nil  ;; Don't close when clicking inside modal

      :gist/cancel-install
      (swap! !state assoc :modal {:visible? false :gist-id nil})

      :gist/confirm-install
      (let [[gist-id manifest raw-url] args]
        (swap! !state assoc :modal {:visible? false :gist-id nil})
        (swap! !state update-gist-status gist-id :installing)
        (send-install-request!
         manifest
         raw-url
         (fn [response]
           (swap! !state update-gist-status gist-id
                  (if (.-success response) :installed :error)))))

      ;; Default
      (js/console.warn "[Gist Installer] Unknown action:" (pr-str action-type)))))

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
  (doseq [[gist-id btn-container] @!button-containers]
    (when-let [gist (find-gist-by-id @!state gist-id)]
      (r/render btn-container (render-install-button gist)))))

(defn setup-ui! []
  ;; Create main UI container for modal
  (let [container (js/document.createElement "div")]
    (set! (.-id container) "epupp-gist-installer")
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

(defn attach-button-to-gist! [file-container gist-data]
  (let [header (.querySelector file-container ".gist-blob-name")
        btn-container (js/document.createElement "span")]
    (set! (.-className btn-container) "epupp-btn-container")
    (when header
      (let [parent (.-parentElement header)]
        (.insertBefore parent btn-container (.-nextSibling header))))
    ;; Track container for re-renders
    (swap! !button-containers assoc (:id gist-data) btn-container)
    ;; Initial button render
    (r/render btn-container (render-install-button gist-data))))

(defn scan-gist-files! []
  (let [file-containers (.querySelectorAll js/document ".file")
        container-array (js/Array.from file-containers)]
    (doseq [container container-array]
      (let [code-text (get-gist-file-text container)
            container-id (or (.-id container) (str "gist-" (random-uuid)))]
        ;; Ensure container has an ID
        (when-not (.-id container)
          (set! (.-id container) container-id))
        (when-let [manifest (extract-manifest code-text)]
          (js/console.log "[Gist Installer] Found installable script:" (:script-name manifest))
          (let [gist-data {:id container-id
                          :manifest manifest
                          :raw-url (get-gist-raw-url container)
                          :status :ready}]
            ;; Add to state
            (swap! !state update :gists conj gist-data)
            ;; Attach button
            (attach-button-to-gist! container gist-data)))))))

(defn init! []
  (js/console.log "[Gist Installer] Initializing with Replicant...")
  (setup-ui!)
  (if (= js/document.readyState "loading")
    (.addEventListener js/document "DOMContentLoaded" scan-gist-files!)
    (scan-gist-files!))
  (js/console.log "[Gist Installer] Ready"))

(init!)
