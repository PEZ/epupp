(ns e2e.unscriptable-banner-test
  "E2E tests for unscriptable page banner in popup.
   Verifies that a banner appears when the active tab URL is not scriptable
   (e.g., chrome://, about:, extension pages) and does not appear on normal pages."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           wait-for-popup-ready assert-no-errors!]]))

;; =============================================================================
;; Popup User Journey: Unscriptable Page Banner
;; =============================================================================

(defn- ^:async test_banner_appears_on_chrome_scheme_url []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'chrome://settings';"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        ;; Page banner should be visible with info type
        (let [banner (.locator popup "[data-e2e-page-banner=\"info\"]")]
          (js-await (-> (expect banner) (.toBeVisible #js {:timeout 3000})))
          (js-await (-> (expect banner) (.toContainText "chrome:"))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup))

        ;; Verify banner does NOT appear on a normal http URL
        (let [popup2 (js-await (create-popup-page context ext-id))]
          (js-await (.addInitScript popup2 "window.__scittle_tamper_test_url = 'https://example.com/page';"))
          (js-await (.reload popup2))
          (js-await (wait-for-popup-ready popup2))

          (let [banner (.locator popup2 "[data-e2e-page-banner]")]
            (js-await (-> (expect banner) (.toHaveCount 0))))

          (js-await (assert-no-errors! popup2))
          (js-await (.close popup2))))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_banner_appears_on_about_blank []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'about:blank';"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        (let [banner (.locator popup "[data-e2e-page-banner=\"info\"]")]
          (js-await (-> (expect banner) (.toBeVisible #js {:timeout 3000})))
          (js-await (-> (expect banner) (.toContainText "about:"))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_banner_appears_on_extension_url []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'chrome-extension://abc123/popup.html';"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        (let [banner (.locator popup "[data-e2e-page-banner=\"info\"]")]
          (js-await (-> (expect banner) (.toBeVisible #js {:timeout 3000})))
          (js-await (-> (expect banner) (.toContainText "chrome-extension:"))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_no_banner_on_http_page []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        ;; No page banner should exist
        (let [banner (.locator popup "[data-e2e-page-banner]")]
          (js-await (-> (expect banner) (.toHaveCount 0))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(.describe test "Unscriptable Page Banner"
           (fn []
             (test "Popup: banner appears on chrome:// URL"
                   test_banner_appears_on_chrome_scheme_url)

             (test "Popup: banner appears on about:blank"
                   test_banner_appears_on_about_blank)

             (test "Popup: banner appears on extension URL"
                   test_banner_appears_on_extension_url)

             (test "Popup: no banner on http page"
                   test_no_banner_on_http_page)))
