(ns e2e.security-probe-test
  "E2E test for the security probe built-in userscript.
   Runs the manual-only probe via the popup play button on a test page,
   and asserts the expected access control pattern across message types
   and sources."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              find-tab-id send-runtime-message
                              wait-for-popup-ready assert-no-errors!
                              http-port]]))

(def ^:private test-page-url (str "http://localhost:" http-port "/basic.html"))
(def ^:private probe-builtin-id "epupp-builtin-security-probe")

(defn- assert-status!
  "Assert that a probe result entry has the expected status."
  [results source msg-type expected-status]
  (let [source-results (aget results source)
        entry (when source-results (aget source-results msg-type))
        actual-status (when entry (.-status entry))]
    (-> (expect actual-status)
        (.toBe expected-status))))

(defn- ^:async test_security_probe_bridge_access_control []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Navigate to test page ===
      (let [page (js-await (.newPage context))]
        (js-await (.goto page test-page-url #js {:timeout 5000}))
        (js-await (.waitForLoadState page "domcontentloaded"))

        ;; === PHASE 2: Open popup and run probe via play button ===
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))

          ;; Make test page the active tab for script injection
          (let [tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
            (js-await (.evaluate popup
                                 (fn [target-tab-id]
                                   (js/Promise.
                                    (fn [resolve]
                                      (js/chrome.tabs.update target-tab-id #js {:active true}
                                                             (fn [] (resolve true))))))
                                 tab-id)))

          ;; Find probe by e2e data attribute and click play
          (let [probe-item (.locator popup (str "[data-e2e-script-id=\"" probe-builtin-id "\"]"))
                run-btn (.locator probe-item "button.script-run")]
            (js-await (-> (expect run-btn) (.toBeVisible #js {:timeout 2000})))
            (js-await (.click run-btn)))

          ;; === PHASE 3: Wait for probe to complete on test page ===
          (js-await (-> (expect (.locator page "body[data-security-probe]"))
                        (.toBeAttached #js {:timeout 30000})))

          ;; Parse results
          (let [result-json (js-await (.getAttribute (.locator page "body") "data-security-probe"))
                results (js/JSON.parse result-json)]

            ;; =========================================================
            ;; epupp-page source: response-bearing messages
            ;; =========================================================
            (assert-status! results "epupp-page" "list-scripts" "responded")
            (assert-status! results "epupp-page" "get-script" "responded")
            (assert-status! results "epupp-page" "save-script" "responded")
            (assert-status! results "epupp-page" "rename-script" "responded")
            (assert-status! results "epupp-page" "delete-script" "responded")
            (assert-status! results "epupp-page" "load-manifest" "responded")
            (assert-status! results "epupp-page" "get-sponsored-username" "responded")

            ;; epupp-page source: fire-and-forget messages
            (assert-status! results "epupp-page" "ws-connect" "no-response")
            (assert-status! results "epupp-page" "ws-send" "no-response")

            ;; epupp-page source: unregistered messages
            (assert-status! results "epupp-page" "evil-message" "dropped")
            (assert-status! results "epupp-page" "request-save-token" "dropped")

            ;; =========================================================
            ;; epupp-userscript source: response-bearing messages
            ;; =========================================================
            (assert-status! results "epupp-userscript" "save-script" "responded")
            (assert-status! results "epupp-userscript" "load-manifest" "dropped")
            (assert-status! results "epupp-userscript" "install-userscript" "responded")

            ;; epupp-userscript source: fire-and-forget messages
            (assert-status! results "epupp-userscript" "sponsor-status" "no-response")

            ;; epupp-userscript source: unregistered messages
            (assert-status! results "epupp-userscript" "evil-message" "dropped")
            (assert-status! results "epupp-userscript" "request-save-token" "dropped"))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))
      (finally
        (js-await (.close context))))))

(.describe test "Security probe: bridge access control"
           (fn []
             (test "probe validates expected access pattern"
                   test_security_probe_bridge_access_control)))
