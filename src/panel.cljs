(ns panel
  "DevTools panel for live ClojureScript evaluation.
   Communicates with inspected page via chrome.devtools.inspectedWindow."
  (:require [clojure.string :as str]
            [reagami :as r]
            [event-handler :as event-handler]
            [icons :as icons]
            [log :as log]
            [manifest-parser :as mp]
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
         :panel/original-name nil  ;; Track for rename detection, non-nil means editing existing
         :panel/script-id nil      ;; Track script ID for updates (preserves ID on save)
         :panel/script-match ""
         :panel/script-description ""
         :panel/init-version nil
         :panel/needs-refresh? false
         :panel/current-hostname nil
         :panel/manifest-hints nil  ; Parsed manifest from current code
         :panel/selection nil       ; Current textarea selection {:start :end :text}
         :panel/system-banners []        ; System banners [{:id :type :message :leaving} ...]
         :panel/system-bulk-names {}     ; bulk-id -> [script-name ...]
         :panel/scripts-list []          ; bulk-id -> [script-name ...]
         :panel/tab-connected? false}))  ; Is the inspected tab connected to REPL?

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
    (let [{:panel/keys [code]} state
          key (panel-state-key hostname)
          state-to-save #js {:code code}]
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
                code (when saved (.-code saved))]
            (dispatch [[:editor/ax.initialize-editor
                        {:code code
                         :hostname hostname}]])
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
    (let [[code libs] args]
      (if (seq libs)
        ;; Inject libs before eval (even if Scittle already loaded - libs might not be)
        (js/chrome.runtime.sendMessage
         #js {:type "inject-libs"
              :tabId js/chrome.devtools.inspectedWindow.tabId
              :libs (clj->js libs)}
         (fn [_response]
           ;; Proceed with eval regardless (best effort)
           (eval-in-page!
            code
            (fn [result]
              (dispatch [[:editor/ax.handle-eval-result result]])))))
        ;; No libs - eval directly
        (eval-in-page!
         code
         (fn [result]
           (dispatch [[:editor/ax.handle-eval-result result]])))))

    :editor/fx.check-scittle
    (check-scittle-status!
     (fn [status]
       (dispatch [[:editor/ax.update-scittle-status status]])))

    :editor/fx.inject-and-eval
    (let [[code libs] args]
      ;; If libs exist, inject them first via background worker
      (if (seq libs)
        (js/chrome.runtime.sendMessage
         #js {:type "inject-libs"
              :tabId js/chrome.devtools.inspectedWindow.tabId
              :libs (clj->js libs)}
         (fn [response]
           (if (and response (.-success response))
             ;; Libs injected - now inject Scittle and eval
             (ensure-scittle!
              (fn [err]
                (if err
                  (dispatch [[:editor/ax.update-scittle-status "error"]
                             [:editor/ax.handle-eval-result err]])
                  (dispatch [[:editor/ax.update-scittle-status "loaded"]
                             [:editor/ax.do-eval code]]))))
             ;; Lib injection failed
             (dispatch [[:editor/ax.update-scittle-status "error"]
                        [:editor/ax.handle-eval-result {:error (or (.-error response) "Failed to inject libs")}]]))))
        ;; No libs - proceed as before
        (ensure-scittle!
         (fn [err]
           (if err
             (dispatch [[:editor/ax.update-scittle-status "error"]
                        [:editor/ax.handle-eval-result err]])
             (dispatch [[:editor/ax.update-scittle-status "loaded"]
                        [:editor/ax.do-eval code]]))))))

    :editor/fx.save-script
    (let [[script normalized-name action-text] args]
      ;; Route through background for centralized FS validation
      ;; Use script->panel-js to convert namespaced keys for panel messages
      (js/chrome.runtime.sendMessage
       #js {:type "panel-save-script"
            :script (script-utils/script->panel-js script)}
       (fn [response]
         (let [error (or (when js/chrome.runtime.lastError
                           (.-message js/chrome.runtime.lastError))
                         (when response (.-error response)))
               unchanged? (and response (.-unchanged response))]
           ;; Dispatch action to handle response and update UI
           (dispatch [[:editor/ax.handle-save-response
                       {:success (and (not js/chrome.runtime.lastError)
                                      response
                                      (.-success response))
                        :error error
                        :name normalized-name
                        :action-text action-text
                        :unchanged unchanged?
                        :is-update (when response (.-isUpdate response))}]])))))

    :editor/fx.rename-script
    (let [[from-name to-name] args]
      ;; Route through background for centralized FS validation
      (js/chrome.runtime.sendMessage
       #js {:type "panel-rename-script"
            :from from-name
            :to to-name}
       (fn [response]
         ;; Dispatch action to handle response and update UI
         (dispatch [[:editor/ax.handle-rename-response
                     {:success (and response (.-success response))
                      :error (when response (.-error response))
                      :from-name from-name
                      :to-name to-name}]]))))

    :editor/fx.clear-persisted-state
    (let [[hostname] args]
      (when hostname
        (js/chrome.storage.local.remove (panel-state-key hostname))))

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

    :editor/fx.reload-script-from-storage
    (let [[script-name] args]
      (js/chrome.storage.local.get
       #js ["scripts"]
       (fn [result]
         (when-let [scripts-raw (.-scripts result)]
           (let [scripts (script-utils/parse-scripts scripts-raw {:extract-manifest mp/extract-manifest})
                 script (some #(when (= (:script/name %) script-name) %) scripts)]
             (when script
               ;; Reload the script content into the editor
               (dispatch [[:editor/ax.load-script-for-editing                           (:script/id script)                           (:script/name script)
                           (str/join "\n" (:script/match script))
                           (:script/code script)
                           (:script/description script)]])))))))

    :editor/fx.load-scripts-list
    (js/chrome.storage.local.get
     #js ["scripts"]
     (fn [result]
       (let [scripts-raw (.-scripts result)
             scripts (if scripts-raw
                       (script-utils/parse-scripts scripts-raw {:extract-manifest mp/extract-manifest})
                       [])]
         (dispatch [[:editor/ax.update-scripts-list scripts]]))))

    :editor/fx.load-connections
    (let [inspected-tab-id js/chrome.devtools.inspectedWindow.tabId]
      (js/chrome.runtime.sendMessage
       #js {:type "get-connections"}
       (fn [response]
         (when (and response (.-success response))
           (let [connections (.-connections response)
                 tab-id-str (str inspected-tab-id)
                 connected? (boolean (some #(= tab-id-str (str (:tab-id %))) connections))]
             (dispatch [[:editor/ax.set-tab-connected connected?]]))))))

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

(defn results-area [{:panel/keys [results]}]
  [:div.results-area
   (if (seq results)
     (for [[idx result] (map-indexed vector results)]
       ^{:key idx}
       [result-item result])
     [view-elements/empty-state {:empty/class "empty-results"}
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

(defn code-input [{:panel/keys [code evaluating? scittle-status]}]
  (let [loading? (= :loading scittle-status)]
    [:div.code-input-area {:data-e2e-scittle-status scittle-status}
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
      [view-elements/action-button
       {:button/variant :primary
        :button/class "btn-eval"
        :button/icon icons/play
        :button/disabled? (or evaluating? loading? (empty? code))
        :button/on-click #(dispatch! [[:editor/ax.eval]])}
       (cond
         loading? " Loading Scittle..."
         evaluating? " Evaluating..."
         :else " Eval script")]
      [view-elements/action-button
       {:button/variant :secondary
        :button/class "btn-clear"
        :button/on-click #(dispatch! [[:editor/ax.clear-results]])}
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

(defn- format-auto-run-match
  "Format auto-run-match for display, handling both string and vector.
   Empty strings and empty collections are treated as nil (no auto-run)."
  [auto-run-match]
  (cond
    (nil? auto-run-match) nil
    (and (string? auto-run-match) (empty? auto-run-match)) nil
    (string? auto-run-match) [auto-run-match]
    (and (sequential? auto-run-match) (empty? auto-run-match)) nil
    (sequential? auto-run-match) (vec auto-run-match)
    :else [auto-run-match]))

(defn- property-row
  "Render a single row in the metadata property table."
  [{:keys [label value values hint badge]}]
  [:tr.property-row {:data-e2e-property (-> label str/lower-case (str/replace " " "-"))}
   [:th.property-label label]
   [:td.property-value
    (cond
      ;; Multiple values (e.g., vector auto-run-match)
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
    "{:epupp/script-name \"My Script\"\n :epupp/auto-run-match \"https://example.com/*\"\n :epupp/description \"What it does\"}\n\n(ns my-script)\n; your code..."]])

(defn- new-script-button
  "Button to clear editor and start a new script. Shows confirmation if code has changed."
  [{:panel/keys [code]}]
  (let [has-changes? (and (seq code)
                          (not= code panel-actions/default-script))]
    [view-elements/action-button
     {:button/variant :secondary
      :button/class "btn-new-script"
      :button/icon icons/add
      :button/title "Start a new script"
      :button/on-click (fn [_e]
                         (if has-changes?
                           (when (js/confirm "Clear current script and start fresh?")
                             (dispatch! [[:editor/ax.new-script]]))
                           (dispatch! [[:editor/ax.new-script]])))}
     "New"]))

(defn save-script-section [{:panel/keys [script-name script-match script-description
                                         code original-name
                                         manifest-hints scripts-list]
                            :as _state}]
  (let [;; Check if we have manifest data (hints present means manifest was parsed)
        has-manifest? (some? manifest-hints)
        ;; Extract hint details
        {:keys [name-normalized? raw-script-name unknown-keys run-at-invalid? raw-run-at inject]} manifest-hints
        ;; Categorize require URLs for validation display
        {:keys [valid invalid]} (categorize-requires inject)
        ;; Site match can be string or already joined (from panel actions)
        auto-run-matches (format-auto-run-match script-match)
        ;; Use raw name when present (for validation), normalize for comparisons
        raw-name (or raw-script-name script-name)
        normalized-name (when (seq raw-name)
                          (script-utils/normalize-script-name raw-name))
        ;; Check if we're editing a built-in script (by name lookup)
        editing-builtin? (and original-name
                              (script-utils/name-matches-builtin? scripts-list original-name))
        name-error (when (and raw-name (not editing-builtin?))
                     (script-utils/validate-script-name raw-name))
        ;; Name changed from original?
        name-changed? (and original-name
                           normalized-name
                           (not= normalized-name original-name))
        ;; Check if the new name conflicts with an existing script (not the one we're editing)
        conflicting-script (script-utils/detect-name-conflict scripts-list raw-name original-name)
        has-name-conflict? (boolean conflicting-script)
        ;; Show rename when editing user script and name differs from original (and no conflict)
        show-rename? (and original-name
                          (not editing-builtin?)
                          name-changed?
                          (not has-name-conflict?))
        ;; Save button disabled rules:
        ;; - Missing required fields
        ;; - Editing built-in with unchanged name (can't overwrite built-in)
        ;; - No manifest present (can't save without manifest)
        ;; - Name conflict (need to use overwrite button instead)
        ;; - Name validation error
        save-disabled? (or (empty? code)
                           (empty? script-name)
                           (not has-manifest?)
                           has-name-conflict?
                           name-error
                           (and editing-builtin? (not name-changed?)))
        ;; Button text: "Create Script" when name changed (no conflict), otherwise "Save Script"
        save-button-text (cond
                           has-name-conflict? "Save Script"
                           name-changed? "Create Script"
                           :else "Save Script")
        ;; Rename disabled for built-in scripts or invalid name
        rename-disabled? (or editing-builtin? name-error)
        ;; Get run-at for display (from parsed manifest, via state)
        run-at (if run-at-invalid?
                 "document-idle"
                 raw-run-at)]
    [:div.save-script-section {:data-e2e-scripts-count (count scripts-list)
                               :data-e2e-editing (boolean original-name)
                               :data-e2e-conflict (boolean has-name-conflict?)}
     [:div.save-script-header
      [:span.header-title (if original-name "Edit Userscript" "Save as Userscript")]
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
            :hint (cond
                    name-error
                    {:type "error" :text name-error}
                    has-name-conflict?
                    {:type "warning" :text (str "\"" normalized-name "\" already exists")}
                    name-normalized?
                    {:type "info" :text (str "Normalized from: " raw-script-name)})}]

          ;; Auto-run row - shows patterns or "No auto-run" when empty
          [property-row
           {:label "Auto-run"
            :values (when (seq auto-run-matches) auto-run-matches)
            :value (when (empty? auto-run-matches) "No auto-run (manual only)")}]

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
         [view-elements/action-button
          {:button/variant :success
           :button/class "btn-save"
           :button/disabled? save-disabled?
           :button/on-click #(dispatch! [[:editor/ax.save-script]])
           :button/title (cond
                           name-error
                           name-error
                           has-name-conflict?
                           (str "Script \"" normalized-name "\" already exists - use Overwrite to replace it")
                           (and editing-builtin? (not name-changed?))
                           "Cannot overwrite built-in script - change the name to create a copy"
                           (empty? script-name)
                           "Add :epupp/script-name to manifest"
                           :else nil)}
          save-button-text]
         ;; Overwrite button - shown when there's a name conflict
         (when has-name-conflict?
           [view-elements/action-button
            {:button/variant :warning
             :button/class "btn-overwrite"
             :button/disabled? (or name-error (script-utils/builtin-script? conflicting-script))
             :button/on-click #(dispatch! [[:editor/ax.save-script-overwrite]])
             :button/title (cond
                             name-error
                             name-error
                             (script-utils/builtin-script? conflicting-script)
                             "Cannot overwrite built-in scripts"
                             :else (str "Replace existing \"" normalized-name "\" with this code"))}
            "Overwrite"])
         ;; Rename button - appears when editing and name differs (no conflict)
         (when show-rename?
           [view-elements/action-button
            {:button/variant :primary
             :button/class "btn-rename"
             :button/disabled? rename-disabled?
             :button/on-click #(dispatch! [[:editor/ax.rename-script]])
             :button/title (cond
                             name-error
                             name-error
                             rename-disabled?
                             "Cannot rename built-in scripts"
                             :else (str "Rename from \"" original-name "\" to \"" normalized-name "\""))}
            "Rename"])]]

       ;; No manifest: show guidance message
       [:div.save-script-form.no-manifest
        [no-manifest-message]
        [:div.save-actions
         [view-elements/action-button
          {:button/variant :success
           :button/class "btn-save"
           :button/disabled? true
           :button/title "Add manifest to code to enable saving"}
          "Save Script"]]])]))

(defn refresh-banner []
  [:div.refresh-banner
   [:span "Extension updated - please "]
   [:strong "close and reopen DevTools"]
   [:span " to use the new version of this panel"]])



(defn panel-header [{:panel/keys [needs-refresh? system-banners tab-connected?]}]
  [view-elements/app-header
   {:elements/wrapper-class "panel-header-wrapper"
    :elements/header-class "panel-header"
    :elements/icon [icons/epupp-logo {:size 28 :connected? tab-connected?}]
    :elements/status "Ready"
    :elements/permanent-banner (when needs-refresh? [refresh-banner])
    :elements/temporary-banner (when (seq system-banners)
                                 [view-elements/system-banners system-banners])}])

(defn panel-footer []
  [view-elements/app-footer {:elements/wrapper-class "panel-footer"}])

(defn panel-ui [state]
  [:div.panel-root {:data-e2e-connected (str (boolean (:panel/tab-connected? state)))}
   [panel-header state]
   [:div.panel-content
    ;; Debug info for tests (hidden but queryable by E2E tests)
    (when (test-logger/test-mode?)
      [:div#debug-info {:style {:position "absolute" :left "-9999px"}}
       "hostname: " (:panel/current-hostname state)
       " | code-len: " (count (:panel/code state))
       " | original-name: " (or (:panel/original-name state) "nil")])
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
      (dispatch! [[:editor/ax.set-needs-refresh]]))))

(defn on-page-navigated [_url]
  (log/info "Panel" nil "Page navigated")
  (check-version!)
  ;; Reset state for the new page
  (dispatch! [[:editor/ax.reset-for-navigation]])
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
  (dispatch! [[:editor/ax.set-init-version (get-extension-version)]])
  ;; Restore panel state, then continue initialization
  (perform-effect! dispatch! [:editor/fx.restore-panel-state
                              (fn []
                                ;; Use async IIFE for the rest of initialization
                                ((^:async fn []
                                   ;; Load existing scripts from storage before rendering
                                   (js-await (storage/load!))
                                   (log/info "Panel" nil "Storage loaded, version:" (get-extension-version))
                                   ;; Watch for render and persist editor state
                                   (add-watch !state :panel/render
                                              (fn [_ _ old-state new-state]
                                                (render!)
                                                ;; Only persist when code changes
                                                (when (not= (:panel/code old-state) (:panel/code new-state))
                                                  (save-panel-state! new-state))))
                                   (render!)
                                   ;; Check Scittle status on init
                                   (dispatch! [[:editor/ax.check-scittle]])
                                   ;; Load connection status for current tab
                                   (perform-effect! dispatch! [:editor/fx.load-connections])
                                   ;; Load scripts list for conflict detection
                                   (perform-effect! dispatch! [:editor/fx.load-scripts-list])
                                   ;; Check if there's a script to edit (from popup)
                                   (dispatch! [[:editor/ax.check-editing-script]])
                                   ;; Listen for page navigation to clear stale results
                                   (js/chrome.devtools.network.onNavigated.addListener on-page-navigated)
                                   ;; Check version when panel becomes visible
                                   (js/document.addEventListener "visibilitychange"
                                                                 (fn [_] (when (= "visible" js/document.visibilityState)
                                                                           (check-version!)))))))]))

;; Listen for storage changes (editingScript for edit requests, scripts for conflict detection)
(js/chrome.storage.onChanged.addListener
 (fn [changes area]
   (when (= area "local")
     ;; Refresh scripts list when scripts change (for conflict detection)
     (when (.-scripts changes)
       (let [new-scripts (.-newValue (.-scripts changes))
             parsed (if new-scripts
                      (script-utils/parse-scripts new-scripts {:extract-manifest mp/extract-manifest})
                      [])]
         (dispatch! [[:editor/ax.update-scripts-list parsed]])))
     ;; Check for script to edit when popup sets editingScript
     (when (.-editingScript changes)
       (dispatch! [[:editor/ax.check-editing-script]])))))

;; Listen for messages from background
(js/chrome.runtime.onMessage.addListener
 (fn [message _sender _send-response]
   (cond
     ;; FS sync events
     (= "system-banner" (.-type message))
     (let [event-type (aget message "event-type")
           operation (aget message "operation")
           script-name (aget message "script-name")
           error-msg (aget message "error")
           unchanged? (aget message "unchanged")
           from-name (aget message "from-name")
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
           current-name (:panel/script-name @!state)
           original-name (:panel/original-name @!state)
           ;; Name-based matching only (like FS Sync API)
           matches-name? (or (= script-name current-name)
                             (= script-name original-name))
           matches-from? (or (= from-name current-name)
                             (= from-name original-name))
           ;; Check if this event affects the currently edited script
           affects-current? (and (or (= event-type "success") (= event-type "info"))
                                 (or matches-name? matches-from?))
           show-banner? (or (= event-type "error")
                            (= event-type "info")
                            (not bulk-op?)
                            bulk-final?)
           banner-msg (cond
                        (= event-type "error")
                        (str "FS sync error: " error-msg)

                        unchanged?
                        (str "Script \"" script-name "\" unchanged")

                        (and bulk-op? bulk-final?)
                        (str bulk-count (if (= bulk-count 1) " file " " files ")
                             (if (= operation "delete") "deleted" "saved"))

                        :else
                        (str "Script \"" script-name "\" " operation "d"))
           ;; Skip banner for saves affecting current script - panel save response handler already showed it
           skip-banner? (and affects-current? (= operation "save"))]
       (when bulk-id
         (dispatch! [[:editor/ax.track-bulk-name bulk-id script-name]]))
       ;; Show banner for all system-banner events (except panel's own saves)
       (when (and show-banner? (not skip-banner?))
         (dispatch! [[:editor/ax.show-system-banner event-type banner-msg]])
         ;; TODO: Move to log module when it supports targeting specific consoles (page vs extension)
         (let [bulk-names (when bulk-id (get-in @!state [:panel/system-bulk-names bulk-id]))]
           (if (and bulk-op? bulk-final? (seq bulk-names))
             (js/console.info "[Epupp:FS]" banner-msg (clj->js {:files bulk-names}))
             (js/console.info "[Epupp:FS]" banner-msg))))
       (when (and bulk-id bulk-final?)
         (dispatch! [[:editor/ax.clear-bulk-names bulk-id]]))
       ;; Reload or clear editor when current script was modified
       (when affects-current?
         (if (= operation "delete")
           (dispatch! [[:editor/ax.new-script]])
           (dispatch! [[:editor/ax.reload-script-from-storage script-name]]))))

     ;; Connection status changes - track if our inspected tab is connected
     (= "connections-changed" (.-type message))
     (let [connections (.-connections message)
           inspected-tab-id js/chrome.devtools.inspectedWindow.tabId
           tab-id-str (str inspected-tab-id)
           tab-connected? (boolean (some #(= (str (:tab-id %)) tab-id-str) connections))]
       ;; Update connection state for icon display
       (dispatch! [[:editor/ax.set-tab-connected tab-connected?]])
       ;; If disconnected, also reset scittle status
       (when-not tab-connected?
         (dispatch! [[:editor/ax.handle-ws-close]]))))
   ;; Return false - we don't send async response
   false))

(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
