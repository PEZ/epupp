(ns popup-test
  "E2E tests for the popup UI - user journey style.

   Tests are structured as user journeys - one browser context, multiple
   sequential operations that build on each other, like a real user session."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [with-extension sleep]]))

;; =============================================================================
;; Popup User Journey: Initial State and Port Configuration
;; =============================================================================

(test "Popup: initial state and port configuration"
      (^:async fn []
        (js-await
         (with-extension
          (^:async fn [context ext-id]
            (let [page (js-await (.newPage context))]
              (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))

              ;; 1. Port inputs render with defaults
              (js-await (-> (expect (.locator page "#nrepl-port")) (.toBeVisible)))
              (js-await (-> (expect (.locator page "#ws-port")) (.toBeVisible)))

              ;; 2. Copy command button exists
              (js-await (-> (expect (.locator page "button:has-text(\"Copy\")")) (.toBeVisible)))

              ;; 3. Connect button exists
              (js-await (-> (expect (.locator page "button:has-text(\"Connect\")")) (.toBeVisible)))

              ;; 4. Can change port values
              (js-await (.fill page "#nrepl-port" "9999"))
              (js-await (-> (expect (.locator page "#nrepl-port")) (.toHaveValue "9999")))

              (js-await (.fill page "#ws-port" "8888"))
              (js-await (-> (expect (.locator page "#ws-port")) (.toHaveValue "8888")))))))))
