(ns e2e.security-probe-test
  "E2E test for the security probe built-in userscript.
   Enables the dev/test-only security probe, navigates to a test page,
   waits for the probe to complete, and asserts the expected access
   control pattern across message types and sources."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              send-runtime-message assert-no-errors!]]))

(def ^:private test-page-url "http://localhost:18080/basic.html")
(def ^:private probe-builtin-id "epupp-builtin-security-probe")

(defn- ^:async enable-security-probe! [popup]
  (let [result (js-await (send-runtime-message popup "e2e/get-storage" #js {:key "scripts"}))
        scripts (or (.-value result) #js [])
        updated (.map scripts (fn [s]
                                (if (= probe-builtin-id (.-id s))
                                  (do (aset s "enabled" true) s)
                                  s)))]
    (js-await (send-runtime-message popup "e2e/set-storage" #js {:key "scripts" :value updated}))))

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
      ;; Ensure built-ins are synced, then enable the probe
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (send-runtime-message popup "e2e/ensure-builtin" #js {}))
        (js-await (enable-security-probe! popup))

        ;; Navigate to a test page - the probe auto-matches "*"
        (let [page (js-await (.newPage context))]
          (js-await (.goto page test-page-url #js {:timeout 5000}))

          ;; Wait for probe to complete (writes data-security-probe attribute)
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
            (assert-status! results "epupp-userscript" "request-save-token" "dropped")))

        (js-await (assert-no-errors! popup)))
      (finally
        (js-await (.close context))))))

(.describe test "Security probe: bridge access control"
           (fn []
             (test "probe validates expected access pattern"
                   test_security_probe_bridge_access_control)))
