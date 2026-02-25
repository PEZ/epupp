(ns e2e.popup-disconnect-test
  "E2E tests for popup connection API and disconnect functionality."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           wait-for-popup-ready
                                           wait-for-connection ws-port-1 assert-no-errors!]]))

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

            ;; Click disconnect button on the connected tab item
            (js-await (-> (expect (.locator popup ".disconnect-tab-btn"))
                          (.toBeVisible #js {:timeout 1000})))
            (js-await (.click (.locator popup ".disconnect-tab-btn")))

            ;; Reload to see updated state (exit animation completes async but
            ;; we rely on post-reload state verification, not animation timing)
            (js-await (.reload popup))
            (js-await (wait-for-popup-ready popup))

            ;; Connected tabs list should be empty and connect UI should reappear
            (js-await (-> (expect (.locator popup ".no-connections"))
                          (.toBeVisible #js {:timeout 1000})))
            (js-await (-> (expect (.locator popup "#connect"))
                          (.toBeVisible #js {:timeout 1000}))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Popup Disconnect"
           (fn []
             (test "Popup Disconnect: get-connections API returns active REPL connections"
                   test_get_connections_returns_active_connections)

             (test "Popup Disconnect: UI updates immediately after connecting (no tab switch needed)"
                   test_ui_updates_immediately_after_connecting)

             (test "Popup Disconnect: disconnect button disconnects current tab"
                   test_disconnect_button_disconnects_current_tab)))
