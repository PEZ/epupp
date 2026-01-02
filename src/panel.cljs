(ns panel
  "DevTools panel for live ClojureScript evaluation.
   Communicates with inspected page via chrome.devtools.inspectedWindow."
  (:require [reagami :as r]
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
;; Dispatch
;; ============================================================

(defn dispatch! [[action & args]]
  (case action
    :set-code
    (let [[code] args]
      (swap! !state assoc :panel/code code))

    :set-script-name
    (let [[name] args]
      (swap! !state assoc :panel/script-name name))

    :set-script-match
    (let [[match] args]
      (swap! !state assoc :panel/script-match match))

    :eval
    (let [code (:panel/code @!state)]
      (when (and (seq code) (not (:panel/evaluating? @!state)))
        (swap! !state assoc :panel/evaluating? true)
        (swap! !state update :panel/results conj {:type :input :text code})
        (eval-in-page!
         code
         (fn [result]
           (swap! !state assoc :panel/evaluating? false)
           (if (:error result)
             (swap! !state update :panel/results conj {:type :error :text (:error result)})
             (swap! !state update :panel/results conj {:type :output :text (:result result)}))))))

    :save-script
    (let [{:panel/keys [code script-name script-match]} @!state]
      (if (or (empty? code) (empty? script-name) (empty? script-match))
        (swap! !state assoc :panel/save-status {:type :error :text "Name, match pattern, and code are required"})
        (let [script-id (str "script-" (.now js/Date))
              script {:script/id script-id
                      :script/name script-name
                      :script/match [script-match]
                      :script/code code
                      :script/enabled true}]
          (storage/save-script! script)
          (swap! !state assoc
                 :panel/save-status {:type :success :text (str "Saved \"" script-name "\"")}
                 :panel/script-name ""
                 :panel/script-match "")
          ;; Clear status after 3 seconds
          (js/setTimeout #(swap! !state assoc :panel/save-status nil) 3000))))

    :clear-results
    (swap! !state assoc :panel/results [])

    :clear-code
    (swap! !state assoc :panel/code "")

    :use-current-url
    (js/chrome.devtools.inspectedWindow.eval
     "window.location.href"
     (fn [url _exception]
       (when url
         ;; Convert URL to a match pattern (e.g., https://github.com/* )
         (let [parsed (js/URL. url)
               pattern (str (.-protocol parsed) "//" (.-hostname parsed) "/*")]
           (swap! !state assoc :panel/script-match pattern)))))

    (js/console.warn "Unknown action:" action)))

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
               :on-input (fn [e] (dispatch! [:set-code (.. e -target -value)]))
               :on-keydown (fn [e]
                             (when (and (or (.-ctrlKey e) (.-metaKey e))
                                        (= "Enter" (.-key e)))
                               (.preventDefault e)
                               (dispatch! [:eval])))}]
   [:div.code-actions
    [:button.btn-eval {:on-click #(dispatch! [:eval])
                       :disabled (or evaluating? (empty? code))}
     (if evaluating? "Evaluating..." "Eval")]
    [:button.btn-clear {:on-click #(dispatch! [:clear-results])}
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
              :on-input (fn [e] (dispatch! [:set-script-name (.. e -target -value)]))}]]
    [:div.save-field
     [:label {:for "script-match"} "URL Pattern"]
     [:div.match-input-group
      [:input {:type "text"
               :id "script-match"
               :value script-match
               :placeholder "https://example.com/*"
               :on-input (fn [e] (dispatch! [:set-script-match (.. e -target -value)]))}]
      [:button.btn-use-url {:on-click #(dispatch! [:use-current-url])
                            :title "Use current page URL"}
       "↵"]]]
    [:div.save-actions
     [:button.btn-save {:on-click #(dispatch! [:save-script])
                        :disabled (or (empty? code) (empty? script-name) (empty? script-match))}
      "Save Script"]
     (when save-status
       [:span {:class (str "save-status save-status-" (name (:type save-status)))}
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
    [results-area state]]])

;; ============================================================
;; Init
;; ============================================================

(defn render! []
  (r/render (js/document.getElementById "app")
            [panel-ui @!state]))

(defn init! []
  (js/console.log "[Panel] Initializing...")
  (add-watch !state ::render (fn [_ _ _ _] (render!)))
  (render!))

(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
