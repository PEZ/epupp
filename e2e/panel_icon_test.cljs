(ns e2e.panel-icon-test
  "E2E tests for panel logo connection state reactivity."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           assert-no-errors!]]))

;; =============================================================================
;; Panel Icon: Connection State Reactivity
;; =============================================================================

(defn- ^:async wait-for-panel-connected-state
  "Poll the panel's data-e2e-connected attribute until it matches expected value.
   Returns the attribute value string."
  [panel expected timeout-ms]
  (let [start (.now js/Date)
        timeout-ms (or timeout-ms 2000)]
    (loop []
      (let [attr (js-await (.getAttribute (.locator panel ".panel-root") "data-e2e-connected"))]
        (if (= attr expected)
          attr
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. (str "Timeout waiting for panel connected state. "
                                   "Current: " attr ", Expected: " expected)))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
              (recur))))))))

(defn- ^:async test_panel_icon_reacts_to_connection_state []
  (.setTimeout test 15000)
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))
        ws-port-1 12346]
    (try
      ;; Open a test page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Find the tab ID for this page
        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))]
          (js-await (.close popup))

          ;; Create panel that thinks it's inspecting this tab
          (let [panel (js-await (fixtures/create-panel-page-for-tab context ext-id tab-id))]

            ;; Panel should start disconnected
            (let [state (js-await (wait-for-panel-connected-state panel "false" 2000))]
              (js-await (-> (expect (= state "false"))
                            (.toBeTruthy))))

            ;; Connect REPL to this tab
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (fixtures/connect-tab popup tab-id ws-port-1))
              (js-await (fixtures/wait-for-connection popup 5000))
              (js-await (.close popup)))

            ;; Panel should now show connected
            (let [state (js-await (wait-for-panel-connected-state panel "true" 5000))]
              (js-await (-> (expect (= state "true"))
                            (.toBeTruthy))))

            ;; Disconnect by requesting disconnect from popup
            (let [popup (js-await (create-popup-page context ext-id))]
              (js-await (fixtures/send-runtime-message popup "disconnect-tab" #js {:tabId tab-id}))
              (js-await (.close popup)))

            ;; Panel should return to disconnected
            (let [state (js-await (wait-for-panel-connected-state panel "false" 5000))]
              (js-await (-> (expect (= state "false"))
                            (.toBeTruthy))))

            (js-await (assert-no-errors! panel))
            (js-await (.close panel))))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Panel Icon"
           (fn []
             (test "Panel Icon: panel logo reacts to connection state changes"
                   test_panel_icon_reacts_to_connection_state)))
