(ns installer-spa-cleanup-test
  "E2E tests for web installer DOM cleanup during SPA navigation.
   Verifies that rescan! properly removes button containers and
   data-epupp-processed attributes when navigating between pages."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              wait-for-popup-ready assert-no-errors!
                              http-port]]
            ["./web_installer/helpers.mjs" :as h]))

;; ============================================================
;; Helpers
;; ============================================================

(defn- ^:async navigate-to-spa-cleanup-test
  "Navigate to SPA cleanup test page and wait for it to be ready."
  [context]
  (let [page (js-await (.newPage context))]
    (js-await (.goto page (str "http://localhost:" http-port "/spa-cleanup-test.html")
                     #js {:timeout 2000}))
    (js-await (-> (expect (.locator page "#test-marker"))
                  (.toContainText "ready")))
    page))

;; ============================================================
;; Test: SPA navigation cleans up install buttons from DOM
;; ============================================================

(defn- ^:async test_spa_navigation_cleans_up_buttons []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      (let [page (js-await (navigate-to-spa-cleanup-test context))]
        ;; === Phase 1: Navigate to gist view - buttons should appear ===
        (js-await (.click (.locator page "#nav-gist")))

        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.not.toHaveCount 0 #js {:timeout 5000}))))

        ;; === Phase 2: Navigate to home - buttons should be cleaned up ===
        (js-await (.click (.locator page "#nav-home")))

        ;; Button containers should be removed from DOM by rescan!
        (let [btn-containers (.locator page ".epupp-btn-container")]
          (js-await (-> (expect btn-containers)
                        (.toHaveCount 0 #js {:timeout 3000}))))

        ;; === Phase 3: Navigate back to gist - buttons should reappear ===
        (js-await (.click (.locator page "#nav-gist")))

        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.not.toHaveCount 0 #js {:timeout 5000}))))

        (js-await (.close page)))

      ;; Check for errors
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; ============================================================
;; Test Registration
;; ============================================================

(test "SPA navigation: install buttons cleaned up when navigating away" test_spa_navigation_cleans_up_buttons)
