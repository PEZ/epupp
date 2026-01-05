(ns popup-test
  "E2E tests for the popup UI."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [with-extension]]))

(test "extension loads and popup renders"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
            ;; Verify port inputs render
            (js-await (-> (expect (.locator page "#nrepl-port")) (.toBeVisible)))
            (js-await (-> (expect (.locator page "#ws-port")) (.toBeVisible)))
            ;; Verify copy command button exists
            (js-await (-> (expect (.locator page "button:has-text(\"Copy\")")) (.toBeVisible)))))))))

(test "popup shows connect button"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
            ;; Connect button should be visible
            (js-await (-> (expect (.locator page "button:has-text(\"Connect\")")) (.toBeVisible)))))))))

(test "port inputs accept values"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
            ;; Clear and fill nREPL port
            (js-await (.fill page "#nrepl-port" "9999"))
            (js-await (-> (expect (.locator page "#nrepl-port")) (.toHaveValue "9999")))
            ;; Clear and fill WebSocket port
            (js-await (.fill page "#ws-port" "8888"))
            (js-await (-> (expect (.locator page "#ws-port")) (.toHaveValue "8888")))))))))
