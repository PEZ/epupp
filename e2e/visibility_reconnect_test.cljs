(ns e2e.visibility-reconnect-test
  "E2E tests for visibility-aware WebSocket reconnection.
   When a connected tab becomes visible after being hidden, if the WebSocket
   was lost, the extension should automatically reconnect."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           wait-for-popup-ready
                                           wait-for-connection ws-port-1 assert-no-errors!]]))

(defn- ^:async simulate-tab-visible!
  "Trigger the visibility-aware reconnect flow for a tab.
   Sends e2e/simulate-tab-visible to background, which dispatches
   visibility/ax.handle-tab-visible directly - bypassing the content bridge's
   DOM event listener that can't be reliably triggered cross-world."
  [ext-page tab-id]
  (js-await (fixtures/send-runtime-message ext-page "e2e/simulate-tab-visible" #js {:tabId tab-id})))

(defn- ^:async wait-for-no-connections
  "Poll until connection count drops to 0."
  [ext-page timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [connections (js-await (fixtures/get-connections ext-page))
            count (.-length connections)]
        (if (zero? count)
          true
          (if (> (- (.now js/Date) start) (or timeout-ms 2000))
            (throw (js/Error. (str "Timeout waiting for 0 connections. Count: " count)))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
              (recur))))))))

;; =============================================================================
;; Test: Reconnects on tab visible after disconnect
;; =============================================================================

(defn- ^:async test_reconnects_on_tab_visible_after_disconnect []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Verify auto-reconnect is enabled (default) ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (let [auto-reconnect-checkbox (.locator popup "#auto-reconnect-repl")]
          (js-await (-> (expect auto-reconnect-checkbox) (.toBeChecked))))
        (js-await (.close popup)))

      ;; === PHASE 2: Navigate and connect ===
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
          (js-await (fixtures/connect-tab popup tab-id ws-port-1))
          (js-await (wait-for-connection popup 5000))
          (js/console.log "Connected to tab" tab-id)

          (let [connections (js-await (fixtures/get-connections popup))]
            (js-await (-> (expect (.-length connections)) (.toBe 1))))

          ;; === PHASE 3: Disconnect (simulating WS loss while tab hidden) ===
          (js-await (fixtures/send-runtime-message popup "disconnect-tab" #js {:tabId tab-id}))
          (js-await (wait-for-no-connections popup 2000))
          (js/console.log "Tab disconnected, history preserved")

          ;; === PHASE 4: Simulate tab becoming visible ===
          (js-await (simulate-tab-visible! popup tab-id))
          (js/console.log "Simulated tab-became-visible")

          ;; Wait for reconnection
          (js-await (wait-for-connection popup 10000))
          (js/console.log "Reconnected after visibility change")

          (let [connections (js-await (fixtures/get-connections popup))]
            (js-await (-> (expect (.-length connections)) (.toBeGreaterThanOrEqual 1))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: No reconnect when auto-reconnect disabled
;; =============================================================================

(defn- ^:async test_no_reconnect_when_auto_reconnect_disabled []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Disable auto-reconnect ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))
        (let [auto-reconnect-checkbox (.locator popup "#auto-reconnect-repl")]
          (js-await (-> (expect auto-reconnect-checkbox) (.toBeChecked)))
          (js-await (.click auto-reconnect-checkbox))
          (js-await (-> (expect auto-reconnect-checkbox) (.not.toBeChecked)))
          (js/console.log "Auto-reconnect disabled"))
        (js-await (.close popup)))

      ;; === PHASE 2: Navigate, connect, then disconnect ===
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
          (js-await (fixtures/connect-tab popup tab-id ws-port-1))
          (js-await (wait-for-connection popup 5000))

          (js-await (fixtures/send-runtime-message popup "disconnect-tab" #js {:tabId tab-id}))
          (js-await (wait-for-no-connections popup 2000))
          (js/console.log "Tab disconnected with auto-reconnect disabled")

          ;; Clear test events for clean baseline
          (js-await (fixtures/clear-test-events! popup))

          ;; === PHASE 3: Simulate visibility and verify NO reconnection ===
          (js-await (simulate-tab-visible! popup tab-id))
          (js/console.log "Simulated tab-became-visible")

          ;; Assert no NAV_AUTO_CONNECT event occurs
          (js-await (fixtures/assert-no-new-event-within popup "NAV_AUTO_CONNECT" 0 300))
          (js/console.log "Verified: no reconnection when auto-reconnect disabled")

          (let [connections (js-await (fixtures/get-connections popup))]
            (js-await (-> (expect (.-length connections)) (.toBe 0))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: No reconnect when still connected
;; =============================================================================

(defn- ^:async test_no_reconnect_when_still_connected []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Navigate and connect ===
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (fixtures/find-tab-id popup "http://localhost:18080/*"))]
          (js-await (fixtures/connect-tab popup tab-id ws-port-1))
          (js-await (wait-for-connection popup 5000))
          (js/console.log "Tab connected")

          ;; Clear test events for clean baseline
          (js-await (fixtures/clear-test-events! popup))

          ;; === PHASE 2: Trigger visibility WITHOUT disconnect ===
          (js-await (simulate-tab-visible! popup tab-id))
          (js/console.log "Simulated tab-became-visible while still connected")

          ;; Assert no reconnection attempt (action short-circuits when WS exists)
          (js-await (fixtures/assert-no-new-event-within popup "NAV_AUTO_CONNECT" 0 300))
          (js/console.log "Verified: no reconnect when already connected")

          ;; Verify still connected (exactly 1 connection, unchanged)
          (let [connections (js-await (fixtures/get-connections popup))]
            (js-await (-> (expect (.-length connections)) (.toBe 1))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Visibility Reconnect"
           (fn []
             (test "Visibility Reconnect: reconnects on tab visible after disconnect"
                   test_reconnects_on_tab_visible_after_disconnect)
             (test "Visibility Reconnect: no reconnect when auto-reconnect disabled"
                   test_no_reconnect_when_auto_reconnect_disabled)
             (test "Visibility Reconnect: no reconnect when still connected"
                   test_no_reconnect_when_still_connected)))
