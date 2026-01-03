(ns panel
  "DevTools panel for live ClojureScript evaluation.
   Communicates with inspected page via chrome.devtools.inspectedWindow."
  (:require [reagami :as r]
            [event-handler :as event-handler]
            [storage :as storage]))

(defonce !state
  (atom {:panel/results []
         :panel/code ""
         :panel/evaluating? false
         :panel/script-name ""
         :panel/script-match ""
         :panel/save-status nil}))

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
         (callback (or result {:error "No result"})))))))

;; ============================================================
;; Uniflow Dispatch
;; ============================================================

(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :editor/fx.eval-in-page
    (let [[code] args]
      (eval-in-page!
       code
       (fn [result]
         (dispatch [[:editor/ax.handle-eval-result result]]))))

    :editor/fx.save-script
    (let [[script] args]
      (storage/save-script! script))

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

    :uf/unhandled-fx))

(defn handle-action [state data [action & args]]
  (case action
    :editor/ax.set-code
    (let [[code] args]
      {:uf/db (assoc state :panel/code code)})

    :editor/ax.set-script-name
    (let [[new-name] args]
      {:uf/db (assoc state :panel/script-name new-name)})

    :editor/ax.set-script-match
    (let [[match] args]
      {:uf/db (assoc state :panel/script-match match)})

    :editor/ax.eval
    (let [code (:panel/code state)]
      (when (and (seq code) (not (:panel/evaluating? state)))
        {:uf/db (-> state
                    (assoc :panel/evaluating? true)
                    (update :panel/results conj {:type :input :text code}))
         :uf/fxs [[:editor/fx.eval-in-page code]]}))

    :editor/ax.handle-eval-result
    (let [[result] args]
      {:uf/db (cond-> state
                :always (assoc :panel/evaluating? false)
                (:error result) (update :panel/results conj {:type :error :text (:error result)})
                (not (:error result)) (update :panel/results conj {:type :output :text (:result result)}))})

    :editor/ax.save-script
    (let [{:panel/keys [code script-name script-match]} state]
      (if (or (empty? code) (empty? script-name) (empty? script-match))
        {:uf/db (assoc state :panel/save-status {:type :error :text "Name, match pattern, and code are required"})}
        (let [script-id (str "script-" (:system/now data))
              script {:script/id script-id
                      :script/name script-name
                      :script/match [script-match]
                      :script/code code
                      :script/enabled true}]
          {:uf/fxs [[:editor/fx.save-script script]
                    [:uf/fx.defer-dispatch [[:db/ax.assoc :panel/save-status nil]] 3000]]
           :uf/db (assoc state
                         :panel/save-status {:type :success :text (str "Saved \"" script-name "\"")}
                         :panel/script-name ""
                         :panel/script-match "")})))

    :editor/ax.clear-results
    {:uf/db (assoc state :panel/results [])}

    :editor/ax.clear-code
    {:uf/db (assoc state :panel/code "")}

    :editor/ax.use-current-url
    {:uf/fxs [[:editor/fx.use-current-url [:db/ax.assoc :panel/script-match]]]}

    :uf/unhandled-ax))

(defn dispatch! [actions]
  (event-handler/dispatch! !state handle-action perform-effect! actions))

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
      [:div.empty-results-icon "λ"]
      [:div.empty-results-text "Evaluate ClojureScript code above"]])])

(defn code-input [{:keys [panel/code panel/evaluating?]}]
  [:div.code-input-area
   [:textarea {:value code
               :placeholder "(+ 1 2 3)\n\n; Ctrl+Enter to evaluate"
               :disabled evaluating?
               :on-input (fn [e] (dispatch! [[:editor/ax.set-code (.. e -target -value)]]))
               :on-keydown (fn [e]
                             (when (and (or (.-ctrlKey e) (.-metaKey e))
                                        (= "Enter" (.-key e)))
                               (.preventDefault e)
                               (dispatch! [[:editor/ax.eval]])))}]
   [:div.code-actions
    [:button.btn-eval {:on-click #(dispatch! [[:editor/ax.eval]])
                       :disabled (or evaluating? (empty? code))}
     (if evaluating? "Evaluating..." "Eval")]
    [:button.btn-clear {:on-click #(dispatch! [[:editor/ax.clear-results]])}
     "Clear"]
    [:span.shortcut-hint "Ctrl+Enter to eval"]]])

(defn save-script-section [{:keys [panel/script-name panel/script-match panel/code panel/save-status]}]
  [:div.save-script-section
   [:div.save-script-header "Save as Userscript"]
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
    [:div.save-actions
     [:button.btn-save {:on-click #(dispatch! [[:editor/ax.save-script]])
                        :disabled (or (empty? code) (empty? script-name) (empty? script-match))}
      "Save Script"]
     ;; In Squint, keywords are already strings, so no need for `name`
     (when save-status
       [:span {:class (str "save-status save-status-" (:type save-status))}
        (:text save-status)])]]])

(defn panel-header []
  [:div.panel-header
   [:div.panel-title
    [:img {:src "icons/icon-32.png" :alt ""}]
    "Browser Jack-in"]
   [:div.panel-status "Ready"]])

(defn panel-ui [state]
  [:div
   [panel-header]
   [:div.panel-content
    [code-input state]
    [save-script-section state]
    [results-area state]]
   [:textarea (pr-str state)]])

;; ============================================================
;; Init
;; ============================================================

(defn render! []
  (r/render (js/document.getElementById "app")
            [panel-ui @!state]))

(defn init! []
  (js/console.log "[Panel] Initializing...")
  ;; Load existing scripts from storage before rendering
  (-> (storage/load!)
      (.then (fn [_]
               (js/console.log "[Panel] Storage loaded")
               (add-watch !state :panel/render (fn [_ _ _ _] (render!)))
               (render!)))))

(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
