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

          ;; Should show failure in system banner (filter to error banner with "Failed")
          (let [banner (.locator popup ".system-banner:has-text(\"Failed\")")]
            (js-await (-> (expect banner)
                          (.toBeVisible #js {:timeout 500})))
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

(.describe test "Popup Connection"
           (fn []
             (test "Popup Connection: connection tracking displays connected tabs with reveal buttons"
                   test_connection_tracking_displays_connected_tabs)

             (test "Popup Connection: connection failure shows error status near Connect button"
                   test_connection_failure_shows_error_status)

             (test "Popup Connection: successful connection via API updates UI correctly"
                   test_successful_connection_via_api_updates_ui)))
