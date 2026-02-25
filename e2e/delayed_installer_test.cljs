(ns delayed-installer-test
  "E2E tests for installer retry on initial page load.
   Verifies that the background scanner retries on non-SPA navigation,
   catching DOM elements that appear after onCompleted fires (GitLab-like)."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              wait-for-popup-ready assert-no-errors!]]
            ["./web_installer/helpers.mjs" :as h]))

;; ============================================================
;; Helpers
;; ============================================================

(def ^:private check-scittle-in-page
  "JS expression to check if Scittle has been loaded in the page."
  "(() => ({ hasScittle: !!(window.scittle && window.scittle.core) }))()")

;; ============================================================
;; Test: Delayed manifest (100ms) detected on initial navigation
;; ============================================================

(defn- ^:async test_delayed_manifest_100ms []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      (let [page (js-await (h/navigate-to-delayed-manifest context 100))]
        ;; Manifest appears after 100ms - retry at 300ms should catch it
        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.not.toHaveCount 0 #js {:timeout 8000}))))

        (let [status (js-await (.evaluate page check-scittle-in-page))]
          (-> (expect (.-hasScittle status)) (.toBe true)))

        (js-await (.close page)))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; ============================================================
;; Test: Delayed manifest (500ms) detected on initial navigation
;; ============================================================

(defn- ^:async test_delayed_manifest_500ms []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      (let [page (js-await (h/navigate-to-delayed-manifest context 500))]
        ;; Manifest appears after 500ms - retry at 1000ms should catch it
        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.not.toHaveCount 0 #js {:timeout 8000}))))

        (let [status (js-await (.evaluate page check-scittle-in-page))]
          (-> (expect (.-hasScittle status)) (.toBe true)))

        (js-await (.close page)))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; ============================================================
;; Test: Delayed manifest (1500ms) detected on initial navigation
;; ============================================================

(defn- ^:async test_delayed_manifest_1500ms []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      (let [page (js-await (h/navigate-to-delayed-manifest context 1500))]
        ;; Manifest appears after 1500ms - retry at 3000ms should catch it
        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.not.toHaveCount 0 #js {:timeout 8000}))))

        (let [status (js-await (.evaluate page check-scittle-in-page))]
          (-> (expect (.-hasScittle status)) (.toBe true)))

        (js-await (.close page)))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; ============================================================
;; Test: Immediate manifest still works (no regression)
;; ============================================================

(defn- ^:async test_immediate_manifest_still_works []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; mock-gist has manifests immediately in DOM
      (let [page (js-await (h/navigate-to-mock-gist context))]
        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.not.toHaveCount 0 #js {:timeout 2000}))))

        (js-await (.close page)))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; ============================================================
;; Test Registration
;; ============================================================

(test "Delayed manifest (100ms) detected on initial navigation" test_delayed_manifest_100ms)
(test "Delayed manifest (500ms) detected on initial navigation" test_delayed_manifest_500ms)
(test "Delayed manifest (1500ms) detected on initial navigation" test_delayed_manifest_1500ms)
(test "Immediate manifest still works with unified retry" test_immediate_manifest_still_works)
