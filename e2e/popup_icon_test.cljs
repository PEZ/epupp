(ns e2e.popup-icon-test
  "E2E tests for popup toolbar icon state."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           create-panel-page wait-for-popup-ready
                                           wait-for-save-status activate-tab update-icon
                                           assert-no-errors!]]))

(defn- code-with-manifest
  "Generate test code with epupp manifest metadata."
  [{:keys [name match description run-at code]
    :or {code "(println \"Test script\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\"")))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

;; =============================================================================
;; Popup User Journey: Toolbar Icon State
;; =============================================================================

(defn- ^:async wait-for-icon-state
  "Poll get-icon-display-state until the state is one of the allowed states.
   Returns the state string."
  [popup tab-id allowed-states timeout-ms]
  (let [start (.now js/Date)
        timeout-ms (or timeout-ms 2000)]
    (loop []
      (let [state (try
                    (js-await (fixtures/get-icon-display-state popup tab-id))
                    (catch :default _e nil))]
        (if (and state (.includes allowed-states state))
          state
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. (str "Timeout waiting for icon state for tab " tab-id
                                   ". Current state: " state
                                   ". Expected one of: " (js/JSON.stringify allowed-states))))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
              (recur))))))))

(defn- ^:async test_toolbar_icon_reflects_connection_state []
  (.setTimeout test 10000)
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Ensure background initialized and clear events so we only see fresh icon changes
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (try
          (js-await (fixtures/wait-for-event popup "EXTENSION_STARTED" 3000))
          (catch :default _ nil))
        (js-await (fixtures/clear-test-events! popup))
        (js-await (.close popup)))

      ;; Navigate to a test page first
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Check initial icon state - should be "disconnected" (white bolt)
        (let [popup (js-await (create-popup-page context ext-id))
              _ (js-await (wait-for-popup-ready popup))
              tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))
              _ (js-await (activate-tab popup tab-id))
              _ (js-await (update-icon popup tab-id))
              state (js-await (wait-for-icon-state popup tab-id #js ["disconnected"] 5000))]
          (js/console.log "Initial icon state:" state)
          (js-await (-> (expect (= state "disconnected"))
                        (.toBeTruthy)))
          (js-await (.close popup)))

        ;; Enable auto-connect via popup
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))
          (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")]
            (js-await (.click settings-header)))
          (let [auto-connect-checkbox (.locator popup "#auto-connect-repl")]
            (js-await (.click auto-connect-checkbox))
            (js-await (-> (expect auto-connect-checkbox) (.toBeChecked))))
          (js-await (.close popup)))

        ;; Navigate to trigger auto-connect (Scittle injection)
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Wait for Scittle to be loaded and icon state updated
        (let [popup (js-await (create-popup-page context ext-id))
              _ (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))
              tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))
              _ (js-await (activate-tab popup tab-id))
              _ (js-await (update-icon popup tab-id))
              state (js-await (wait-for-icon-state popup tab-id #js ["injected" "connected"] 5000))]
          (js/console.log "Final icon state:" state)
          (js-await (-> (expect (or (= state "injected") (= state "connected")))
                        (.toBeTruthy)))
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_injected_state_is_tab_local []
  (.setTimeout test 10000)
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create a userscript that ONLY targets basic.html
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "tab-local-test"
                                      :match "*://localhost:*/basic.html"
                                      :code "(js/console.log \"Tab A script loaded\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button:text(\"Save Script\")")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable script via popup checkbox
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))
        (try
          (js-await (fixtures/wait-for-event popup "EXTENSION_STARTED" 3000))
          (catch :default _ nil))
        (js-await (fixtures/clear-test-events! popup))
        ;; Script starts disabled - enable it
        (let [script-item (.locator popup ".script-item:has-text(\"tab_local_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (.click checkbox)))
        (js-await (.close popup)))

      ;; Navigate Tab A - script should inject when enabled and matching
      (let [tab-a (js-await (.newPage context))]
        (js-await (.goto tab-a "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator tab-a "#test-marker"))
                      (.toContainText "ready")))

        ;; Wait for Scittle injection, capture Tab A's Chrome tab-id
        (let [popup (js-await (create-popup-page context ext-id))
              scittle-loaded-event (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))
              tab-a-id (aget (.-data scittle-loaded-event) "tab-id")]
          (js-await (.close popup))

          ;; Assert Tab A shows injected/connected based on its tab-id
          (let [popup (js-await (create-popup-page context ext-id))
                state (js-await (wait-for-icon-state popup tab-a-id #js ["injected" "connected"] 5000))]
            (js-await (-> (expect (or (= state "injected")
                                      (= state "connected")))
                          (.toBeTruthy)))
            (js-await (.close popup)))

          ;; Open Tab B (spa-test.html - does NOT match our script)
          (let [tab-b (js-await (.newPage context))]
            (js-await (.goto tab-b "http://localhost:18080/spa-test.html" #js {:timeout 1000}))
            (js-await (-> (expect (.locator tab-b "#test-marker"))
                          (.toContainText "ready")))

            ;; Tab B should show disconnected
            (let [popup (js-await (create-popup-page context ext-id))
                  tab-b-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/spa-test.html"))
                  _ (js-await (activate-tab popup tab-b-id))
                  _ (js-await (update-icon popup tab-b-id))
                  state (js-await (wait-for-icon-state popup tab-b-id #js ["disconnected"] 5000))]
              (js-await (-> (expect (= state "disconnected"))
                            (.toBeTruthy)))
              (js-await (.close popup)))

            ;; Tab A should STILL show injected/connected
            (let [popup (js-await (create-popup-page context ext-id))
                  _ (js-await (update-icon popup tab-a-id))
                  _ (js-await (activate-tab popup tab-a-id))
                  state (js-await (wait-for-icon-state popup tab-a-id #js ["injected" "connected"] 5000))]
              (js-await (-> (expect (or (= state "injected")
                                        (= state "connected")))
                            (.toBeTruthy)))
              (js-await (assert-no-errors! popup))
              (js-await (.close popup)))

            (js-await (.close tab-b))))
        (js-await (.close tab-a)))

      (finally
        (js-await (.close context))))))

(.describe test "Popup Icon"
           (fn []
             (test "Popup Icon: toolbar icon reflects REPL connection state"
                   test_toolbar_icon_reflects_connection_state)

             (test "Popup Icon: injected state is tab-local, connected state is global"
                   test_injected_state_is_tab_local)))
