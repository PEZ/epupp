(ns popup-icon-test
  "E2E tests for popup toolbar icon state."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           create-panel-page clear-storage wait-for-popup-ready
                                           wait-for-save-status wait-for-panel-ready
                                           assert-no-errors!]]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata."
  [{:keys [name match description run-at code]
    :or {code "(println \"Test script\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/site-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\"")))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

;; =============================================================================
;; Popup User Journey: Toolbar Icon State
;; =============================================================================

(test "Popup: toolbar icon reflects REPL connection state"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Navigate to a test page first
            (let [page (js-await (.newPage context))]
              (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
              (js-await (-> (expect (.locator page "#test-marker"))
                            (.toContainText "ready")))

              ;; Check initial icon state - should be "disconnected" (white bolt)
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js-await (wait-for-popup-ready popup))
                    events-initial (js-await (fixtures/get-test-events popup))
                    icon-events (.filter events-initial (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))]
                (js/console.log "Initial icon events:" (js/JSON.stringify icon-events))
                ;; Initial state should be "disconnected"
                (when (pos? (.-length icon-events))
                  (let [last-event (aget icon-events (dec (.-length icon-events)))]
                    (js-await (-> (expect (.. last-event -data -state))
                                  (.toBe "disconnected")))))
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

              ;; Wait for Scittle to be loaded
              (let [popup (js-await (create-popup-page context ext-id))
                    _ (js-await (fixtures/wait-for-event popup "SCITTLE_LOADED" 10000))]

                ;; Check icon state after Scittle injection - should be "injected" (yellow) or "connected" (green)
                (let [events (js-await (fixtures/get-test-events popup))
                      icon-events (.filter events (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))]
                  (js/console.log "Icon events after Scittle load:" (js/JSON.stringify icon-events))
                  ;; Should have icon state events
                  (js-await (-> (expect (.-length icon-events))
                                (.toBeGreaterThan 0)))
                  ;; Last event should be "injected" or "connected"
                  (let [last-event (aget icon-events (dec (.-length icon-events)))
                        state (.. last-event -data -state)]
                    (js/console.log "Final icon state:" state)
                    (js-await (-> (expect (or (= state "injected") (= state "connected")))
                                  (.toBeTruthy)))))
                (js-await (assert-no-errors! popup))
                (js-await (.close popup)))

              (js-await (.close page)))

            (finally
              (js-await (.close context)))))))

(test "Popup: injected state is tab-local, connected state is global"
      (^:async fn []
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

            ;; Approve the script using test URL override
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
              (js-await (.reload popup))
              (js-await (wait-for-popup-ready popup))
              (let [script-item (.locator popup ".script-item:has-text(\"tab_local_test.cljs\")")]
                (js-await (-> (expect script-item) (.toBeVisible)))
                (let [allow-btn (.locator script-item "button:has-text(\"Allow\")")]
                  (when (pos? (js-await (.count allow-btn)))
                    (js-await (.click allow-btn))
                    (js-await (-> (expect allow-btn) (.not.toBeVisible)))))
                (js-await (.close popup))))

            ;; Navigate Tab A - script is pre-approved, should inject
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
                      events (js-await (fixtures/get-test-events popup))
                      icon-events (.filter events
                                           (fn [e]
                                             (and (= (.-event e) "ICON_STATE_CHANGED")
                                                  (= (aget (.-data e) "tab-id") tab-a-id))))]
                  (js-await (-> (expect (.-length icon-events))
                                (.toBeGreaterThan 0)))
                  (let [last-event (aget icon-events (dec (.-length icon-events)))
                        last-state (.. last-event -data -state)]
                    (js-await (-> (expect (or (= last-state "injected")
                                              (= last-state "connected")))
                                  (.toBeTruthy))))
                  (js-await (.close popup)))

                ;; Open Tab B (spa-test.html - does NOT match our script)
                (let [tab-b (js-await (.newPage context))]
                  (js-await (.goto tab-b "http://localhost:18080/spa-test.html" #js {:timeout 1000}))
                  (js-await (-> (expect (.locator tab-b "#test-marker"))
                                (.toContainText "ready")))

                  ;; Bring Tab B to focus
                  (js-await (.bringToFront tab-b))
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))

                  ;; Tab B should show disconnected, and the icon event should be for Tab B
                  ;; (i.e. not Tab A's Chrome tab-id)
                  (let [popup (js-await (create-popup-page context ext-id))
                        events (js-await (fixtures/get-test-events popup))
                        icon-events (.filter events (fn [e] (= (.-event e) "ICON_STATE_CHANGED")))
                        last-event (aget icon-events (dec (.-length icon-events)))
                        last-tab-id (aget (.-data last-event) "tab-id")
                        last-state (.. last-event -data -state)]
                    (js-await (-> (expect last-tab-id) (.toBeTruthy)))
                    (js-await (-> (expect (not (= last-tab-id tab-a-id)))
                                  (.toBeTruthy)))
                    (js-await (-> (expect (= last-state "disconnected"))
                                  (.toBeTruthy)))
                    (js-await (.close popup)))

                  ;; Bring Tab A back to focus
                  (js-await (.bringToFront tab-a))
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))

                  ;; Tab A should STILL show injected/connected
                  (let [popup (js-await (create-popup-page context ext-id))
                        events (js-await (fixtures/get-test-events popup))
                        icon-events (.filter events
                                             (fn [e]
                                               (and (= (.-event e) "ICON_STATE_CHANGED")
                                                    (= (aget (.-data e) "tab-id") tab-a-id))))]
                    (js-await (-> (expect (.-length icon-events))
                                  (.toBeGreaterThan 0)))
                    (let [last-event (aget icon-events (dec (.-length icon-events)))
                          last-state (.. last-event -data -state)]
                      (js-await (-> (expect (or (= last-state "injected")
                                                (= last-state "connected")))
                                    (.toBeTruthy))))
                    (js-await (assert-no-errors! popup))
                    (js-await (.close popup)))

                  (js-await (.close tab-b))))
              (js-await (.close tab-a)))

            (finally
              (js-await (.close context)))))))
