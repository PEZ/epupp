(ns e2e.popup-connection-test
  "E2E tests for popup REPL connection tracking and status."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           wait-for-popup-ready
                                           wait-for-connection ws-port-1 assert-no-errors!]]))

;; =============================================================================
;; Popup User Journey: Connection Tracking and Management
;; =============================================================================

(defn- ^:async test_connection_tracking_displays_connected_tabs []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Navigate to a test page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Open popup and connect
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))

          ;; Initially no connections
          (let [no-conn-msg (.locator popup ".no-connections")]
            (js-await (-> (expect no-conn-msg)
                          (.toBeVisible))))

          ;; Find and connect the page
          (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))]
            (js-await (fixtures/connect-tab popup tab-id ws-port-1))

            ;; Wait for connection event then reload popup
            (js-await (wait-for-connection popup 5000))
            (js-await (.reload popup))
            (js-await (wait-for-popup-ready popup))

            ;; Should now show 1 connected tab
            (let [connected-items (.locator popup ".connected-tab-item")]
              (js-await (-> (expect connected-items)
                            (.toHaveCount 1))))

            ;; Connected tab should show port number
            (let [port-elem (.locator popup ".connected-tab-port")]
              (js-await (-> (expect port-elem)
                            (.toContainText ":12346"))))

            ;; Tab should have a reveal or disconnect button
            (let [action-btns (.locator popup ".reveal-tab-btn, .disconnect-tab-btn")]
              (js-await (-> (expect action-btns)
                            (.toBeVisible)))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Popup User Journey: Connection Status Feedback
;; =============================================================================

(defn- ^:async test_connection_failure_shows_error_status []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Navigate to a test page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))

          ;; Click Connect - will fail (permissions issue with UI-based connect)
          (let [connect-btn (.locator popup "#connect")]
            (js-await (.click connect-btn)))

          ;; Should show failure in system banner
          (let [banner (.locator popup ".system-banner")]
            (js-await (-> (expect banner)
                          (.toBeVisible)))
            (js-await (-> (expect banner)
                          (.toContainText "Failed")))
            ;; Failed status should have error banner class
            (js-await (-> (expect banner)
                          (.toHaveClass #"fs-error-banner"))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_successful_connection_via_api_updates_ui []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))

          ;; Initially no connections
          (let [no-conn-msg (.locator popup ".no-connections")]
            (js-await (-> (expect no-conn-msg)
                          (.toBeVisible))))

          ;; Connect via direct API (bypasses UI button permission issues)
          (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))]
            (js-await (fixtures/connect-tab popup tab-id ws-port-1))

            ;; Wait for connection event then reload popup
            (js-await (wait-for-connection popup 5000))
            (js-await (.reload popup))
            (js-await (wait-for-popup-ready popup))

            ;; Should now show connection in UI
            (let [connected-items (.locator popup ".connected-tab-item")]
              (js-await (-> (expect connected-items)
                            (.toHaveCount 1)))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Popup User Journey: Connection API
;; =============================================================================

(defn- ^:async test_get_connections_returns_active_connections []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Navigate to a test page first
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))
        (js/console.log "Test page loaded")

        ;; Open popup
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))

          ;; Check initial state - no connections yet
          (let [initial-conns (js-await (fixtures/get-connections popup))]
            (js/console.log "Initial connections:" (.-length initial-conns))
            (js-await (-> (expect (.-length initial-conns))
                          (.toBe 0))))

          ;; Find the test page tab and connect
          (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
            (js/console.log "Found test page tab ID:" tab-id)
            (js-await (fixtures/connect-tab popup tab-id ws-port-1))
            (js/console.log "Connected to tab via WebSocket port" ws-port-1)

            ;; Wait for connection event
            (js-await (wait-for-connection popup 5000))

            ;; Now get-connections should return the connection
            (let [connections (js-await (fixtures/get-connections popup))
                  conn-count (.-length connections)]
              (js/console.log "Connections after connect:" conn-count)
              (js/console.log "Connection data:" (js/JSON.stringify connections))

              ;; Should have exactly 1 connection now
              (js-await (-> (expect conn-count)
                            (.toBe 1)))

              ;; Verify the connection has the tab-id
              (when (> conn-count 0)
                (let [conn (aget connections 0)
                      conn-tab-id (aget conn "tab-id")]
                  (js/console.log "Connected tab ID:" conn-tab-id "expected:" tab-id)
                  (js-await (-> (expect (str conn-tab-id))
                                (.toBe (str tab-id))))))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_ui_updates_immediately_after_connecting []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Navigate to a test page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Open popup - keep it open throughout the test
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))

          ;; Initially should show no connections
          (let [no-conn-msg (.locator popup ".no-connections")]
            (js-await (-> (expect no-conn-msg)
                          (.toBeVisible)))
            (js/console.log "Initial state: no connections shown"))

          ;; Connect to the test page while popup is still open
          (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
            (js-await (fixtures/connect-tab popup tab-id ws-port-1))
            (js/console.log "Connected to tab" tab-id)

            ;; Wait for connection event
            (js-await (wait-for-connection popup 5000))

            ;; WITHOUT reloading or switching tabs, the UI should update
            ;; BUG: Currently requires popup reload or tab switch
            (let [connected-items (.locator popup ".connected-tab-item")]
              (js-await (-> (expect connected-items)
                            (.toHaveCount 1 #js {:timeout 2000})))
              (js/console.log "UI updated with connected tab")))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_disconnect_button_disconnects_current_tab []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))

          ;; Connect via direct API
          (let [tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/basic.html"))]
            (js-await (fixtures/connect-tab popup tab-id ws-port-1))
            (js-await (wait-for-connection popup 5000))

            ;; Ensure the connected tab is the active tab for popup actions
            (js-await (.evaluate popup
                                 (fn [target-tab-id]
                                   (js/Promise.
                                    (fn [resolve]
                                      (js/chrome.tabs.update target-tab-id #js {:active true}
                                                             (fn [] (resolve true))))))
                                 tab-id))

            (js-await (.reload popup))
            (js-await (wait-for-popup-ready popup))

            (let [connected-items (.locator popup ".connected-tab-item")]
              (js-await (-> (expect connected-items)
                            (.toHaveCount 1 #js {:timeout 1000}))))

            (js-await (-> (expect (.locator popup "#disconnect"))
                          (.toBeVisible #js {:timeout 1000})))

            ;; Click disconnect button
            (js-await (.click (.locator popup "#disconnect")))

            ;; Reload to see updated state
            (js-await (.reload popup))
            (js-await (wait-for-popup-ready popup))

            ;; Connected tabs list should be empty
            (js-await (-> (expect (.locator popup ".no-connections"))
                          (.toBeVisible #js {:timeout 1000})))
            (js-await (-> (expect (.locator popup "#connect"))
                          (.toBeVisible #js {:timeout 1000}))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Popup Connection"
           (fn []
             (test "Popup Connection: connection tracking displays connected tabs with reveal buttons"
                   test_connection_tracking_displays_connected_tabs)

             (test "Popup Connection: connection failure shows error status near Connect button"
                   test_connection_failure_shows_error_status)

             (test "Popup Connection: successful connection via API updates UI correctly"
                   test_successful_connection_via_api_updates_ui)

             (test "Popup Connection: get-connections API returns active REPL connections"
                   test_get_connections_returns_active_connections)

             (test "Popup Connection: UI updates immediately after connecting (no tab switch needed)"
                   test_ui_updates_immediately_after_connecting)

             (test "Popup Connection: disconnect button disconnects current tab"
                   test_disconnect_button_disconnects_current_tab)))
