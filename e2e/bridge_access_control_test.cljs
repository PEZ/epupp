(ns bridge-access-control-test
  "E2E tests for content bridge access control.
   Verifies that unregistered message types and wrong-source messages
   are silently dropped by the bridge."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           find-tab-id connect-tab wait-for-connection
                                           assert-no-errors!]]))

(def ^:private test-page-url "http://localhost:18080/bridge-test.html")

(defn- ^:async post-page-message!
  "Post a message to the page window via a script tag.
   page.evaluate runs in Playwright's utility context which can't reach the
   content bridge. Script tags execute in the true MAIN world."
  [page source type request-id]
  (js-await (.addScriptTag page
                           #js {:content (str "window.postMessage({ source: '" source
                                              "', type: '" type
                                              "', requestId: '" request-id
                                              "' }, '*');")})))

(defn- ^:async setup-test-page
  "Navigate to bridge-test.html and trigger bridge injection via REPL connect.
   Returns the page with an active bridge and DOM-based message collector."
  [context popup]
  (let [page (js-await (.newPage context))]
    (js-await (.goto page test-page-url #js {:timeout 2000}))
    (js-await (-> (expect (.locator page "#test-marker"))
                  (.toContainText "ready")))
    ;; Trigger bridge injection by connecting REPL
    (let [tab-id (js-await (find-tab-id popup (str test-page-url "*")))]
      (js-await (connect-tab popup tab-id fixtures/ws-port-1))
      (js-await (wait-for-connection popup 5000)))
    ;; Wait for bridge-ready via DOM attribute (set by bridge-test.html inline script)
    (js-await (-> (expect (.locator page "html"))
                  (.toHaveAttribute "data-bridge-ready" "true" #js {:timeout 5000})))
    ;; Probe the bridge to confirm message response path works
    (js-await (post-page-message! page "epupp-page" "get-icon-url" "bridge-probe"))
    (js-await (-> (expect (.locator page "html"))
                  (.toHaveAttribute "data-resp-bridge-probe" "1" #js {:timeout 5000})))
    page))

(defn- ^:async test_unregistered_message_is_silently_dropped []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))
            page (js-await (setup-test-page context popup))]
        ;; Post unregistered message type
        (js-await (post-page-message! page "epupp-page" "evil-message" "bad-unreg"))
        ;; Post sentinel - locally handled, always responds
        (js-await (post-page-message! page "epupp-page" "get-icon-url" "sentinel-unreg"))
        ;; Wait for sentinel response via DOM attribute
        (js-await (-> (expect (.locator page "html"))
                      (.toHaveAttribute "data-resp-sentinel-unreg" "1" #js {:timeout 5000})))
        ;; Sentinel arrived - verify no response had the bad requestId (attribute absent)
        (js-await (-> (expect (.locator page "html"))
                      (.not.toHaveAttribute "data-resp-bad-unreg")))
        (js-await (assert-no-errors! popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_wrong_source_message_is_rejected []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))
            page (js-await (setup-test-page context popup))]
        ;; Post list-scripts with wrong source (epupp-userscript instead of epupp-page)
        (js-await (post-page-message! page "epupp-userscript" "list-scripts" "bad-src"))
        ;; Post sentinel - locally handled, always responds
        (js-await (post-page-message! page "epupp-page" "get-icon-url" "sentinel-src"))
        ;; Wait for sentinel response via DOM attribute
        (js-await (-> (expect (.locator page "html"))
                      (.toHaveAttribute "data-resp-sentinel-src" "1" #js {:timeout 5000})))
        ;; Sentinel arrived - verify no response had the bad requestId (attribute absent)
        (js-await (-> (expect (.locator page "html"))
                      (.not.toHaveAttribute "data-resp-bad-src")))
        (js-await (assert-no-errors! popup)))
      (finally
        (js-await (.close context))))))

(.describe test "Bridge: access control"
           (fn []
             (test "unregistered message type is silently dropped"
                   test_unregistered_message_is_silently_dropped)
             (test "wrong source message is rejected"
                   test_wrong_source_message_is_rejected)))
