(ns panel
  "DevTools panel for live ClojureScript evaluation.
   Communicates with inspected page via chrome.devtools.inspectedWindow."
  (:require [reagami :as r]
            [event-handler :as event-handler]
            [icons :as icons]
            [panel-actions :as panel-actions]
            [script-utils :as script-utils]
            [storage :as storage]))

(defonce !state
  (atom {:panel/results []
         :panel/code ""
         :panel/evaluating? false
         :panel/scittle-status :unknown  ; :unknown, :checking, :loading, :loaded
         :panel/script-name ""
         :panel/original-name nil  ;; Track for rename detection
         :panel/script-match ""
         :panel/script-description ""
         :panel/script-id nil  ; non-nil when editing existing script
         :panel/save-status nil
         :panel/init-version nil
         :panel/needs-refresh? false
         :panel/current-hostname nil
         :panel/detected-manifest nil}))  ; Parsed manifest from current code

;; ============================================================
;; Panel State Persistence (per hostname)
;; ============================================================

(def panel-state-prefix "panelState:")

(defn get-inspected-hostname
  "Get the hostname of the inspected page."
  [callback]
  (js/chrome.devtools.inspectedWindow.eval
   "window.location.hostname"
   (fn [hostname _exception]
     (callback (or hostname "unknown")))))

(defn panel-state-key [hostname]
  (str panel-state-prefix hostname))

(defn save-panel-state!
  "Persist editor state per hostname. Uses cached hostname to avoid race conditions."
  []
  (when-let [hostname (:panel/current-hostname @!state)]
    (let [{:panel/keys [code script-name script-match script-description script-id]} @!state
          key (panel-state-key hostname)
          state-to-save #js {:code code
                             :scriptName script-name
                             :scriptMatch script-match
                             :scriptDescription script-description
                             :scriptId script-id}]
      (js/chrome.storage.local.set (js-obj key state-to-save)))))

(defn restore-panel-state!
  "Restore editor state from storage for current hostname."
  [callback]
  (get-inspected-hostname
   (fn [hostname]
     (let [key (panel-state-key hostname)]
       ;; Update tracked hostname BEFORE restoring state
       (swap! !state assoc :panel/current-hostname hostname)
       (js/chrome.storage.local.get
        #js [key]
        (fn [result]
          (let [saved (aget result key)]
            ;; Reset editor fields - either from saved state or to empty
            (swap! !state merge
                   {:panel/code (if saved (or (.-code saved) "") "")
                    :panel/script-name (if saved (or (.-scriptName saved) "") "")
                    :panel/script-match (if saved (or (.-scriptMatch saved) "") "")
                    :panel/script-description (if saved (or (.-scriptDescription saved) "") "")
                    :panel/script-id (when saved (.-scriptId saved))}))
          (when callback (callback))))))))

;; ============================================================
;; Evaluation via inspectedWindow
;; ============================================================

(defn eval-in-page!
  "Evaluate code in the inspected page context.
   Uses chrome.devtools.inspectedWindow.eval"
  [code callback]
  (let [;; Wrap ClojureScript code for Scittle evaluation
        wrapper (str "(() => {"
                     "  if (!window.scittle || !window.scittle.core) {"
                     "    return {error: 'Scittle not loaded. Connect REPL first via popup.'};"
                     "  }"
                     "  try {"
                     "    const result = scittle.core.eval_string(" (js/JSON.stringify code) ");"
                     "    return {success: true, result: String(result)};"
                     "  } catch(e) {"
                     "    return {error: e.message};"
                     "  }"
                     "})()")]
    (js/chrome.devtools.inspectedWindow.eval
     wrapper
     (fn [result exception-info]
       (if exception-info
         (callback {:error (or (.-value exception-info) "Evaluation failed")})
         ;; Convert JS object to Clojure map
         (if (and result (.-error result))
           (callback {:error (.-error result)})
           (callback {:result (when result (.-result result))})))))))

;; ============================================================
;; Scittle Status & Injection (via background worker)
;; ============================================================

(defn check-scittle-status!
  "Check if Scittle is loaded in the inspected page."
  [callback]
  (js/chrome.devtools.inspectedWindow.eval
   "(function() {
      if (window.scittle && window.scittle.core) return {status: 'loaded'};
      return {status: 'not-loaded'};
    })()"
   (fn [result _exception]
     (callback (if result (.-status result) "not-loaded")))))

(defn ensure-scittle!
  "Request background worker to inject Scittle. Callback receives nil on success, error map on failure."
  [callback]
  (let [tab-id js/chrome.devtools.inspectedWindow.tabId]
    (js/chrome.runtime.sendMessage
     #js {:type "ensure-scittle" :tabId tab-id}
     (fn [response]
       (if (and response (.-success response))
         (callback nil)
         (callback {:error (or (and response (.-error response)) "Failed to inject Scittle")}))))))

(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :editor/fx.eval-in-page
    (let [[code] args]
      (eval-in-page!
       code
       (fn [result]
         (dispatch [[:editor/ax.handle-eval-result result]]))))

    :editor/fx.check-scittle
    (check-scittle-status!
     (fn [status]
       (dispatch [[:editor/ax.update-scittle-status status]])))

    :editor/fx.inject-and-eval
    (let [[code] args]
      (ensure-scittle!
       (fn [err]
         (if err
           ;; Injection failed
           (dispatch [[:editor/ax.update-scittle-status "error"]
                      [:editor/ax.handle-eval-result err]])
           ;; Injection succeeded - Scittle is ready
           (dispatch [[:editor/ax.update-scittle-status "loaded"]
                      [:editor/ax.do-eval code]])))))

    :editor/fx.save-script
    (let [[script] args]
      (storage/save-script! script)
      ;; Notify background to update badge
      (js/chrome.runtime.sendMessage #js {:type "refresh-approvals"}))

    :editor/fx.rename-script
    (let [[script-id new-name] args]
      (storage/rename-script! script-id new-name)
      ;; Notify background to update badge
      (js/chrome.runtime.sendMessage #js {:type "refresh-approvals"}))

    :editor/fx.clear-persisted-state
    (when-let [hostname (:panel/current-hostname @!state)]
      (js/chrome.storage.local.remove (panel-state-key hostname)))

    :editor/fx.use-current-url
    (let [[action] args]
      (js/chrome.devtools.inspectedWindow.eval
       "window.location.href"
       (fn [url _exception]
         (when url
           ;; Convert URL to a match pattern (e.g., https://github.com/* )
           (let [parsed (js/URL. url)
                 pattern (str (.-protocol parsed) "//" (.-hostname parsed) "/*")]
             (dispatch [(conj action pattern)]))))))

    :editor/fx.check-editing-script
    (js/chrome.storage.local.get
     #js ["editingScript"]
     (fn [result]
       (when-let [script (.-editingScript result)]
         ;; Load script into editor
         (dispatch [[:editor/ax.load-script-for-editing
                     (.-id script)
                     (.-name script)
                     (.-match script)
                     (.-code script)
                     (.-description script)]])
         ;; Clear the flag so we don't reload on next panel open
         (js/chrome.storage.local.remove "editingScript"))))

    :uf/unhandled-fx))

(defn dispatch! [actions]
  (event-handler/dispatch! !state panel-actions/handle-action perform-effect! actions))

;; ============================================================
;; UI Components
;; ============================================================

(defn result-item [{:keys [type text]}]
  (case type
    :input
    [:div.result-item.result-input
     [:div.result-label "Input"]
     text]

    :output
    [:div.result-item.result-output
     [:div.result-label "Result"]
     text]

    :error
    [:div.result-item.result-error
     [:div.result-label "Error"]
     text]))

(defn results-area [{:keys [panel/results]}]
  [:div.results-area
   (if (seq results)
     (for [[idx result] (map-indexed vector results)]
       ^{:key idx}
       [result-item result])
     [:div.empty-results
      [:div.empty-results-logos
       [:a {:href "https://github.com/babashka/sci" :target "_blank" :title "SCI - Small Clojure Interpreter"}
        [:img {:src "images/sci.png" :alt "SCI"}]]
       [:a {:href "https://clojurescript.org/" :target "_blank" :title "ClojureScript"}
        [:img {:src "images/cljs.svg" :alt "ClojureScript"}]]
       [:a {:href "https://clojure.org/" :target "_blank" :title "Clojure"}
        [:img {:src "images/clojure.png" :alt "Clojure"}]]]
      [:div.empty-results-text
       "Evaluate ClojureScript code above"
       [:br]
       "Powered by "
       [:a {:href "https://github.com/babashka/scittle" :target "_blank"} "Scittle"]]])])

(defn code-input [{:keys [panel/code panel/evaluating? panel/scittle-status]}]
  (let [loading? (= :loading scittle-status)
        button-text (cond
                      loading? "Loading Scittle..."
                      evaluating? "Evaluating..."
                      (= :loaded scittle-status) "Eval"
                      :else "Eval")]
    [:div.code-input-area
     [:textarea#code-area {:value code
                 :placeholder "(+ 1 2 3)\n\n; Ctrl+Enter to evaluate"
                 :disabled (or evaluating? loading?)
                 :on-input (fn [e] (dispatch! [[:editor/ax.set-code (.. e -target -value)]]))
                 :on-keydown (fn [e]
                               (when (and (or (.-ctrlKey e) (.-metaKey e))
                                          (= "Enter" (.-key e)))
                                 (.preventDefault e)
                                 (dispatch! [[:editor/ax.eval]])))}]
     [:div.code-actions
      [:button.btn-eval {:on-click #(dispatch! [[:editor/ax.eval]])
                         :disabled (or evaluating? loading? (empty? code))}
       button-text]
      [:button.btn-clear {:on-click #(dispatch! [[:editor/ax.clear-results]])}
       "Clear Results"]
      [:span.shortcut-hint "Ctrl+Enter to eval"]]]))

(defn- run-at-badge
  "Returns a badge component for non-default run-at timings (panel version)."
  [run-at]
  (case run-at
    "document-start" [:span.run-at-badge {:title "Runs at document-start (before page loads)"}
                      [icons/bolt]]
    "document-end" [:span.run-at-badge {:title "Runs at document-end (when DOM is ready)"}
                    [icons/flag]]
    ;; document-idle (default) - no badge
    nil))

(defn manifest-info
  "Display detected manifest info from code."
  [{:keys [panel/detected-manifest]}]
  (when detected-manifest
    (let [script-name (get detected-manifest "script-name")
          run-at (get detected-manifest "run-at")]
      [:div.manifest-info
       [:span.manifest-label "Detected manifest: "]
       [:span.manifest-name script-name]
       (run-at-badge run-at)])))

(defn save-script-section [{:keys [panel/script-name panel/script-match panel/script-description
                                   panel/code panel/save-status panel/script-id panel/original-name]
                            :as state}]
  (let [;; Normalize current name for comparison
        normalized-name (when (seq script-name)
                          (script-utils/normalize-script-name script-name))
        ;; Check if we're editing a built-in script
        editing-builtin? (and script-id (script-utils/builtin-script-id? script-id))
        ;; Name changed from original?
        name-changed? (and original-name
                           normalized-name
                           (not= normalized-name original-name))
        ;; Show rename when editing user script and name differs from original
        show-rename? (and script-id
                          (not editing-builtin?)
                          name-changed?)
        ;; Save button disabled rules:
        ;; - Missing required fields
        ;; - Editing built-in with unchanged name (can't overwrite built-in)
        save-disabled? (or (empty? code)
                           (empty? script-name)
                           (empty? script-match)
                           (and editing-builtin? (not name-changed?)))
        ;; Button text: "Create Script" when name changed, otherwise "Save Script"
        save-button-text (if name-changed? "Create Script" "Save Script")
        ;; Rename disabled for built-in scripts
        rename-disabled? editing-builtin?]
    [:div.save-script-section
     [:div.save-script-header (if script-id "Edit Userscript" "Save as Userscript")]
     [manifest-info state]
     [:div.save-script-form
      [:div.save-field
       [:label {:for "script-name"} "Name"]
       [:input {:type "text"
                :id "script-name"
                :value script-name
                :placeholder "My Script"
                :on-input (fn [e] (dispatch! [[:editor/ax.set-script-name (.. e -target -value)]]))}]]
      [:div.save-field
       [:label {:for "script-match"} "URL Pattern"]
       [:div.match-input-group
        [:input {:type "text"
                 :id "script-match"
                 :value script-match
                 :placeholder "https://example.com/*"
                 :on-input (fn [e] (dispatch! [[:editor/ax.set-script-match (.. e -target -value)]]))}]
        [:button.btn-use-url {:on-click #(dispatch! [[:editor/ax.use-current-url]])
                              :title "Use current page URL"}
         "↵"]]]
      [:div.save-field.description-field
       [:label {:for "script-description"} "Description (optional)"]
       [:textarea {:id "script-description"
                   :value script-description
                   :placeholder "What does this script do?"
                   :rows 2
                   :on-input (fn [e] (dispatch! [[:editor/ax.set-script-description (.. e -target -value)]]))}]]
      [:div.save-actions
       [:button.btn-save {:on-click #(dispatch! [[:editor/ax.save-script]])
                          :disabled save-disabled?
                          :title (when (and editing-builtin? (not name-changed?))
                                   "Cannot overwrite built-in script - change the name to create a copy")}
        save-button-text]
       ;; Rename button - appears after Save to keep layout stable
       (when show-rename?
         [:button.btn-rename {:on-click #(dispatch! [[:editor/ax.rename-script]])
                              :disabled rename-disabled?
                              :title (if rename-disabled?
                                       "Cannot rename built-in scripts"
                                       (str "Rename from \"" original-name "\" to \"" normalized-name "\""))}
          "Rename"])
       ;; In Squint, keywords are already strings, so no need for `name`
       (when save-status
         [:span {:class (str "save-status save-status-" (:type save-status))}
          (:text save-status)])]]]))

(defn refresh-banner []
  [:div.refresh-banner
   [:span "Extension updated - please "]
   [:strong "close and reopen DevTools"]
   [:span " to use the new version of this panel"]])

(defn panel-header [{:panel/keys [needs-refresh?]}]
  [:div.panel-header-wrapper
   (when needs-refresh?
     [refresh-banner])
   [:div.panel-header
    [:div.panel-title
     [:img {:src "icons/icon-32.png" :alt ""}]
     "Epupp"]
    [:div.panel-status "Ready"]]])

(defn panel-ui [state]
  [:div.panel-root
   [panel-header state]
   [:div.panel-content
    [save-script-section state]
    [code-input state]
    [results-area state]]])

;; ============================================================
;; Init
;; ============================================================

(defn render! []
  (r/render (js/document.getElementById "app")
            [panel-ui @!state]))

(defn get-extension-version []
  (try
    (.-version (js/chrome.runtime.getManifest))
    (catch :default _e
      ;; Extension context invalidated - extension was updated/reloaded
      nil)))

(defn check-version! []
  (let [current-version (get-extension-version)
        init-version (:panel/init-version @!state)]
    (when (or (nil? current-version)  ; Context invalidated
              (and init-version (not= current-version init-version)))
      (js/console.log "[Panel] Extension updated or context invalidated")
      (swap! !state assoc :panel/needs-refresh? true))))

(defn on-page-navigated [_url]
  (js/console.log "[Panel] Page navigated")
  (check-version!)
  ;; Reset state for the new page
  (swap! !state assoc
         :panel/evaluating? false
         :panel/scittle-status :unknown)
  (dispatch! [[:editor/ax.clear-results]
              [:editor/ax.check-scittle]])
  (restore-panel-state! nil))

(defn init! []
  (js/console.log "[Panel] Initializing...")
  ;; Store version at init time
  (swap! !state assoc :panel/init-version (get-extension-version))
  ;; Restore panel state, then continue initialization
  (restore-panel-state!
   (fn []
     ;; Use async IIFE for the rest of initialization
     ((^:async fn []
        ;; Load existing scripts from storage before rendering
        (js-await (storage/load!))
        (js/console.log "[Panel] Storage loaded, version:" (get-extension-version))
        ;; Watch for render
        (add-watch !state :panel/render (fn [_ _ _ _] (render!)))
        ;; Watch for state changes to persist editor state
        (add-watch !state :panel/persist
                   (fn [_ _ old-state new-state]
                     ;; Only save when editor fields change
                     (when (or (not= (:panel/code old-state) (:panel/code new-state))
                               (not= (:panel/script-name old-state) (:panel/script-name new-state))
                               (not= (:panel/script-match old-state) (:panel/script-match new-state))
                               (not= (:panel/script-description old-state) (:panel/script-description new-state))
                               (not= (:panel/script-id old-state) (:panel/script-id new-state)))
                       (save-panel-state!))))
        (render!)
        ;; Check Scittle status on init
        (dispatch! [[:editor/ax.check-scittle]])
        ;; Check if there's a script to edit (from popup)
        (dispatch! [[:editor/ax.check-editing-script]])
        ;; Listen for page navigation to clear stale results
        (js/chrome.devtools.network.onNavigated.addListener on-page-navigated)
        ;; Check version when panel becomes visible
        (js/document.addEventListener "visibilitychange"
                                      (fn [_] (when (= "visible" js/document.visibilityState)
                                                (check-version!)))))))))

;; Listen for storage changes (when popup sets editingScript)
(js/chrome.storage.onChanged.addListener
 (fn [changes _area]
   (when (.-editingScript changes)
     (dispatch! [[:editor/ax.check-editing-script]]))))

(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
