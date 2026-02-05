(ns e2e.popup-icon-test
  "E2E tests for popup toolbar icon state."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           wait-for-popup-ready activate-tab update-icon
                                           assert-no-errors!]]))



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
        ext-id (js-await (get-extension-id context))
        ws-port-1 12346]
    (try
      ;; Navigate to a test page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Check initial icon state - should be "disconnected"
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

        ;; Connect to the tab
        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))]
          (js-await (fixtures/connect-tab popup tab-id ws-port-1))
          (js-await (fixtures/wait-for-connection popup 5000))
          (js-await (.close popup)))

        ;; Check icon state after connection - should be "connected"
        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))
              _ (js-await (activate-tab popup tab-id))
              _ (js-await (update-icon popup tab-id))
              state (js-await (wait-for-icon-state popup tab-id #js ["connected"] 5000))]
          (js/console.log "Final icon state:" state)
          (js-await (-> (expect (= state "connected"))
                        (.toBeTruthy)))
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_icon_reflects_active_tab_connection_state []
  (.setTimeout test 15000)
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))
        ws-port-1 12346]
    (try
      ;; Open Tab A and connect
      (let [tab-a (js-await (.newPage context))]
        (js-await (.goto tab-a "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator tab-a "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              tab-a-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))]
          (js-await (fixtures/connect-tab popup tab-a-id ws-port-1))
          (js-await (fixtures/wait-for-connection popup 5000))
          (js-await (.close popup)))

        ;; Verify Tab A shows connected
        (let [popup (js-await (create-popup-page context ext-id))
              tab-a-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))
              _ (js-await (activate-tab popup tab-a-id))
              _ (js-await (update-icon popup tab-a-id))
              state (js-await (wait-for-icon-state popup tab-a-id #js ["connected"] 5000))]
          (js-await (-> (expect (= state "connected"))
                        (.toBeTruthy)))
          (js-await (.close popup)))

        ;; Open Tab B (not connected)
        (let [tab-b (js-await (.newPage context))]
          (js-await (.goto tab-b "http://localhost:18080/spa-test.html" #js {:timeout 1000}))
          (js-await (-> (expect (.locator tab-b "#test-marker"))
                        (.toContainText "ready")))

          ;; Switch to Tab B - icon should show DISCONNECTED (Tab B has no connection)
          (let [popup (js-await (create-popup-page context ext-id))
                tab-b-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/spa-test.html"))
                _ (js-await (activate-tab popup tab-b-id))
                _ (js-await (update-icon popup tab-b-id))
                state (js-await (wait-for-icon-state popup tab-b-id #js ["disconnected"] 2000))]
            (js-await (-> (expect (= state "disconnected"))
                          (.toBeTruthy)))
            (js-await (.close popup)))

          ;; Switch back to Tab A - should show connected again
          (let [popup (js-await (create-popup-page context ext-id))
                tab-a-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))
                _ (js-await (activate-tab popup tab-a-id))
                _ (js-await (update-icon popup tab-a-id))
                state (js-await (wait-for-icon-state popup tab-a-id #js ["connected"] 5000))]
            (js-await (-> (expect (= state "connected"))
                          (.toBeTruthy)))
            (js-await (assert-no-errors! popup))
            (js-await (.close popup)))

          (js-await (.close tab-b)))
        (js-await (.close tab-a)))

      (finally
        (js-await (.close context))))))

(.describe test "Popup Icon"
           (fn []
             (test "Popup Icon: toolbar icon reflects REPL connection state"
                   test_toolbar_icon_reflects_connection_state)

             (test "Popup Icon: icon reflects active tab connection state (tab-specific)"
                   test_icon_reflects_active_tab_connection_state)))
