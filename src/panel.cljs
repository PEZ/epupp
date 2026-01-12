(ns panel
  "DevTools panel for live ClojureScript evaluation.
   Communicates with inspected page via chrome.devtools.inspectedWindow."
  (:require [clojure.string :as str]
            [reagami :as r]
            [event-handler :as event-handler]
            [icons :as icons]
            [log :as log]
            [panel-actions :as panel-actions]
            [scittle-libs :as scittle-libs]
            [script-utils :as script-utils]
            [storage :as storage]
            [view-elements :as view-elements]
            [test-logger :as test-logger]))

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
         :panel/manifest-hints nil  ; Parsed manifest from current code
         :panel/selection nil}))    ; Current textarea selection {:start :end :text}

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
  "Persist editor state per hostname. Receives state snapshot to ensure consistency."
  [state]
  (when-let [hostname (:panel/current-hostname state)]
    (let [{:panel/keys [code script-name script-match script-description script-id]} state
          key (panel-state-key hostname)
          state-to-save #js {:code code
                             :scriptName script-name
                             :scriptMatch script-match
                             :scriptDescription script-description
                             :scriptId script-id}]
      (js/chrome.storage.local.set
       (js-obj key state-to-save)
       (fn []
         (when js/chrome.runtime.lastError
           (log/error "Panel" nil "Failed to save state:"
                      (.-message js/chrome.runtime.lastError))))))))

(defn- restore-panel-state!
  [dispatch callback]
  (get-inspected-hostname
   (fn [hostname]
     (let [key (panel-state-key hostname)]
       (js/chrome.storage.local.get
        #js [key]
        (fn [result]
          (let [saved (aget result key)
                code (when saved (.-code saved))
                script-id (when saved (.-scriptId saved))
                original-name (when saved (.-scriptName saved))]
            ;; Dispatch initialize action with saved data including hostname
            (dispatch [[:editor/ax.initialize-editor
                        {:code code
                         :script-id script-id
                         :original-name original-name
                         :hostname hostname}]])
            ;; Call callback after dispatch completes
            (when callback (callback)))))))))

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
    :editor/fx.restore-panel-state
    (let [[callback] args]
      (test-logger/log-event! "PANEL_RESTORE_START" {})
      (restore-panel-state! dispatch callback))

    :editor/fx.eval-in-page
    (let [[code] args
          requires (:require (:panel/manifest-hints @!state))]
      (if (seq requires)
        ;; Inject requires before eval (even if Scittle already loaded - libs might not be)
        (js/chrome.runtime.sendMessage
         #js {:type "inject-requires"
              :tabId js/chrome.devtools.inspectedWindow.tabId
              :requires (clj->js requires)}
         (fn [_response]
           ;; Proceed with eval regardless (best effort)
           (eval-in-page!
            code
            (fn [result]
              (dispatch [[:editor/ax.handle-eval-result result]])))))
        ;; No requires - eval directly
        (eval-in-page!
         code
         (fn [result]
           (dispatch [[:editor/ax.handle-eval-result result]])))))

    :editor/fx.check-scittle
    (check-scittle-status!
     (fn [status]
       (dispatch [[:editor/ax.update-scittle-status status]])))

    :editor/fx.inject-and-eval
    (let [[code] args
          requires (:require (:panel/manifest-hints @!state))]
      ;; If requires exist, inject them first via background worker
      (if (seq requires)
        (js/chrome.runtime.sendMessage
         #js {:type "inject-requires"
              :tabId js/chrome.devtools.inspectedWindow.tabId
              :requires (clj->js requires)}
         (fn [response]
           (if (and response (.-success response))
             ;; Requires injected - now inject Scittle and eval
             (ensure-scittle!
              (fn [err]
                (if err
                  (dispatch [[:editor/ax.update-scittle-status "error"]
                             [:editor/ax.handle-eval-result err]])
                  (dispatch [[:editor/ax.update-scittle-status "loaded"]
                             [:editor/ax.do-eval code]]))))
             ;; Require injection failed
             (dispatch [[:editor/ax.update-scittle-status "error"]
                        [:editor/ax.handle-eval-result {:error (or (.-error response) "Failed to inject requires")}]]))))
        ;; No requires - proceed as before
        (ensure-scittle!
         (fn [err]
           (if err
             (dispatch [[:editor/ax.update-scittle-status "error"]
                        [:editor/ax.handle-eval-result err]])
             (dispatch [[:editor/ax.update-scittle-status "loaded"]
                        [:editor/ax.do-eval code]]))))))

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
         (when-let [pattern (script-utils/url-to-match-pattern url)]
           (dispatch [(conj action pattern)])))))

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
      [:div.empty-results-text
       "Evaluate ClojureScript code above to see results here"]
      [:div.empty-results-shortcut
       "(" [:kbd "Ctrl"] "+" [:kbd "Enter"] "evaluates only the selection)"]])])

(defn- track-selection!
  "Track textarea selection and dispatch to state."
  [textarea]
  (let [start (.-selectionStart textarea)
        end (.-selectionEnd textarea)
        text (when (and start end (not= start end))
               (.substring (.-value textarea) start end))]
    (dispatch! [[:editor/ax.set-selection
                 (when text {:start start :end end :text text})]])))

(defn code-input [{:keys [panel/code panel/evaluating? panel/scittle-status]}]
  (let [loading? (= :loading scittle-status)]
    [:div.code-input-area
     [:textarea#code-area {:value code
                 :rows 10
                 :placeholder "(+ 1 2 3)\n\n; Ctrl+Enter evaluates selection"
                 :disabled (or evaluating? loading?)
                 :on-input (fn [e] (dispatch! [[:editor/ax.set-code (.. e -target -value)]]))
                 :on-select (fn [e] (track-selection! (.-target e)))
                 :on-click (fn [e] (track-selection! (.-target e)))
                 :on-keyup (fn [e]
                             ;; Track selection on arrow keys etc
                             (track-selection! (.-target e)))
                 :on-keydown (fn [e]
                               (when (and (or (.-ctrlKey e) (.-metaKey e))
                                          (= "Enter" (.-key e)))
                                 (.preventDefault e)
                                 (dispatch! [[:editor/ax.eval-selection]])))}]
     [:div.code-actions
      [:button.btn-eval {:on-click #(dispatch! [[:editor/ax.eval]])
                         :disabled (or evaluating? loading? (empty? code))}
       [icons/play {:size 14}]
       (cond
         loading? " Loading Scittle..."
         evaluating? " Evaluating..."
         :else " Eval script")]
      [:button.btn-clear {:on-click #(dispatch! [[:editor/ax.clear-results]])}
       "Clear Results"]
      [:span.shortcut-hint [:kbd "Ctrl"] "+" [:kbd "Enter"] " evals selection"]]]))

(defn- run-at-badge
  "Returns a badge component for non-default run-at timings (panel version)."
  [run-at]
  (case run-at
    "document-start" [:span.run-at-badge {:title "Runs at document-start (before page loads)"}
                      [icons/rocket]]
    "document-end" [:span.run-at-badge {:title "Runs at document-end (when DOM is ready)"}
                    [icons/flag]]
    ;; document-idle (default) - no badge
    nil))

(defn- hint-message
  "Render a hint/warning message below a field."
  [{:keys [type text]}]
  [:span {:class (str "field-hint " (when type (str "hint-" type)))}
   text])

(defn- format-site-match
  "Format site-match for display, handling both string and vector."
  [site-match]
  (cond
    (nil? site-match) nil
    (string? site-match) [site-match]
    (sequential? site-match) (vec site-match)
    :else [site-match]))

(defn- property-row
  "Render a single row in the metadata property table."
  [{:keys [label value values hint badge]}]
  [:tr.property-row
   [:th.property-label label]
   [:td.property-value
    (cond
      ;; Multiple values (e.g., vector site-match)
      (seq values)
      [:div.multi-values
       (for [[idx v] (map-indexed vector values)]
         ^{:key idx}
         [:div.value-item v])]

      ;; Single value with optional badge
      (seq value)
      [:span.value-text value badge]

      ;; No value - show placeholder
      :else
      [:span.value-placeholder "Not specified"])
    (when hint
      [hint-message hint])]])

(defn- unknown-keys-warning
  "Render warning for unknown manifest keys."
  [unknown-keys]
  (when (seq unknown-keys)
    [:div.manifest-warning
     [:span.warning-icon "⚠️"]
     [:span "Unknown manifest keys: "]
     [:code (str/join ", " unknown-keys)]]))

(defn- valid-require-url?
  "Returns true if the URL is a valid scittle:// URL that resolves to a known library."
  [url]
  (some? (scittle-libs/resolve-scittle-url url)))

(defn- categorize-requires
  "Categorize require URLs into valid and invalid.
   Returns {:valid [...] :invalid [...]}."
  [requires]
  (when (seq requires)
    (let [scittle-urls (filter scittle-libs/scittle-url? requires)
          valid-urls (filter valid-require-url? scittle-urls)
          invalid-urls (remove valid-require-url? scittle-urls)]
      {:valid (vec valid-urls)
       :invalid (vec invalid-urls)})))

(defn- invalid-requires-warning
  "Render warning for invalid require URLs."
  [invalid-requires]
  (when (seq invalid-requires)
    [:div.manifest-warning
     [:span.warning-icon "⚠️"]
     [:span "Invalid requires: "]
     [:ul.invalid-requires-list
      (for [[idx url] (map-indexed vector invalid-requires)]
        ^{:key idx}
        [:li [:code url]])]]))

(defn- no-manifest-message
  "Message shown when code has no manifest annotations."
  []
  [:div.no-manifest-message
   [:p "Add a manifest map to your code to define script metadata:"]
   [:pre.manifest-example
    "{:epupp/script-name \"My Script\"\n :epupp/site-match \"https://example.com/*\"\n :epupp/description \"What it does\"}\n\n(ns my-script)\n; your code..."]])

(defn- new-script-button
  "Button to clear editor and start a new script. Shows confirmation if code has changed."
  [{:keys [panel/code]}]
  (let [has-changes? (and (seq code)
                          (not= code panel-actions/default-script))]
    [:button.btn-new-script
     {:on-click (fn [_e]
                  (if has-changes?
                    (when (js/confirm "Clear current script and start fresh?")
                      (dispatch! [[:editor/ax.new-script]]))
                    (dispatch! [[:editor/ax.new-script]])))
      :title "Start a new script"}
     [icons/plus {:size 14}]
     "New"]))

(defn save-script-section [{:keys [panel/script-name panel/script-match panel/script-description
                                   panel/code panel/save-status panel/script-id panel/original-name
                                   panel/manifest-hints]
                            :as _state}]
  (let [;; Check if we have manifest data (hints present means manifest was parsed)
        has-manifest? (some? manifest-hints)
        ;; Extract hint details
        {:keys [name-normalized? raw-script-name unknown-keys run-at-invalid? raw-run-at require]} manifest-hints
        ;; Categorize require URLs for validation display
        {:keys [valid invalid]} (categorize-requires require)
        ;; Site match can be string or already joined (from panel actions)
        site-matches (format-site-match script-match)
        ;; Normalize current name for comparison
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
        ;; - No manifest present (can't save without manifest)
        save-disabled? (or (empty? code)
                           (empty? script-name)
                           (empty? script-match)
                           (not has-manifest?)
                           (and editing-builtin? (not name-changed?)))
        ;; Button text: "Create Script" when name changed, otherwise "Save Script"
        save-button-text (if name-changed? "Create Script" "Save Script")
        ;; Rename disabled for built-in scripts
        rename-disabled? editing-builtin?
        ;; Get run-at for display (from parsed manifest, via state)
        run-at (if run-at-invalid?
                 "document-idle"
                 raw-run-at)]
    [:div.save-script-section
     [:div.save-script-header
      [:span.header-title (if script-id "Edit Userscript" "Save as Userscript")]
      [new-script-button _state]]

     (if has-manifest?
       ;; Manifest-driven: property table display
       [:div.save-script-form.manifest-driven
        ;; Property table showing all metadata fields
        [:table.metadata-table
         [:tbody
          ;; Name row - always shown
          [property-row
           {:label "Name"
            :value script-name
            :hint (when name-normalized?
                    {:type "info" :text (str "Normalized from: " raw-script-name)})}]

          ;; URL Pattern row - always shown
          [property-row
           {:label "URL Pattern"
            :values site-matches}]

          ;; Description row - always shown
          [property-row
           {:label "Description"
            :value script-description}]

          ;; Run At row - always shown
          [property-row
           {:label "Run At"
            :value (or run-at "document-idle (default)")
            :badge (run-at-badge run-at)
            :hint (when run-at-invalid?
                    {:type "warning"
                     :text (str "Invalid value \"" raw-run-at "\" - using default")})}]

          ;; Requires row - shows library count with checkmark if all valid
          [property-row
           {:label "Requires"
            :value (if (seq require)
                     (str (count require) " "
                          (if (= 1 (count require)) "library" "libraries")
                          (when (and (empty? invalid) (seq valid)) " ✓"))
                     nil)}]]]

        ;; Unknown keys warning
        [unknown-keys-warning unknown-keys]

        ;; Invalid requires warning
        [invalid-requires-warning invalid]

        ;; Save actions
        [:div.save-actions
         [:button.btn-save {:on-click #(dispatch! [[:editor/ax.save-script]])
                            :disabled save-disabled?
                            :title (cond
                                     (and editing-builtin? (not name-changed?))
                                     "Cannot overwrite built-in script - change the name to create a copy"
                                     (empty? script-name)
                                     "Add :epupp/script-name to manifest"
                                     (empty? script-match)
                                     "Add :epupp/site-match to manifest"
                                     :else nil)}
          save-button-text]
         ;; Rename button - appears after Save to keep layout stable
         (when show-rename?
           [:button.btn-rename {:on-click #(dispatch! [[:editor/ax.rename-script]])
                                :disabled rename-disabled?
                                :title (if rename-disabled?
                                         "Cannot rename built-in scripts"
                                         (str "Rename from \"" original-name "\" to \"" normalized-name "\""))}
            "Rename"])
         ;; Status message
         (when save-status
           [:span {:class (str "save-status save-status-" (:type save-status))}
            (:text save-status)])]]

       ;; No manifest: show guidance message
       [:div.save-script-form.no-manifest
        [no-manifest-message]
        [:div.save-actions
         [:button.btn-save {:disabled true
                            :title "Add manifest to code to enable saving"}
          "Save Script"]]])]))

(defn refresh-banner []
  [:div.refresh-banner
   [:span "Extension updated - please "]
   [:strong "close and reopen DevTools"]
   [:span " to use the new version of this panel"]])

(defn panel-header [{:panel/keys [needs-refresh?]}]
  [view-elements/app-header
   {:elements/wrapper-class "panel-header-wrapper"
    :elements/header-class "panel-header"
    :elements/status "Ready"
    :elements/banner (when needs-refresh?
                       [refresh-banner])}])

(defn panel-footer []
  [view-elements/app-footer {:elements/wrapper-class "panel-footer"}])

(defn panel-ui [state]
  [:div.panel-root
   [panel-header state]
   [:div.panel-content
    ;; Debug info for tests (hidden but queryable by E2E tests)
    (when (test-logger/test-mode?)
      [:div#debug-info {:style {:position "absolute" :left "-9999px"}}
       "hostname: " (:panel/current-hostname state)
       " | code-len: " (count (:panel/code state))
       " | script-id: " (or (:panel/script-id state) "nil")])
    [save-script-section state]
    [code-input state]
    [results-area state]
    [panel-footer]]])

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
      (log/info "Panel" nil "Extension updated or context invalidated")
      (swap! !state assoc :panel/needs-refresh? true))))

(defn on-page-navigated [_url]
  (log/info "Panel" nil "Page navigated")
  (check-version!)
  ;; Reset state for the new page
  (swap! !state assoc
         :panel/evaluating? false
         :panel/scittle-status :unknown)
  (dispatch! [[:editor/ax.clear-results]
              [:editor/ax.check-scittle]])
  (perform-effect! dispatch! [:editor/fx.restore-panel-state nil]))

(defn init! []
  (log/info "Panel" nil "Initializing...")
  ;; Install global error handlers for test mode
  (test-logger/install-global-error-handlers! "panel" js/window)
  ;; Expose state for test debugging
  (when (test-logger/test-mode?)
    (set! js/window.__panelState !state))
  ;; Store version at init time
  (swap! !state assoc :panel/init-version (get-extension-version))
  ;; Restore panel state, then continue initialization
  (perform-effect! dispatch! [:editor/fx.restore-panel-state
                              (fn []
                                ;; Use async IIFE for the rest of initialization
                                ((^:async fn []
                                   ;; Load existing scripts from storage before rendering
                                   (js-await (storage/load!))
                                   (log/info "Panel" nil "Storage loaded, version:" (get-extension-version))
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
                                                  (save-panel-state! new-state))))
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
                                                                           (check-version!)))))))]))

;; Listen for storage changes (when popup sets editingScript)
(js/chrome.storage.onChanged.addListener
 (fn [changes _area]
   (when (.-editingScript changes)
     (dispatch! [[:editor/ax.check-editing-script]]))))

(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
