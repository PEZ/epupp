(ns popup-test
  "E2E tests for the popup UI.
   Uses describe.serial with shared browser context to improve speed."
  (:require ["@playwright/test" :as playwright]
            [fixtures :refer [create-extension-context get-extension-id]]))

(def test playwright/test)
(def expect playwright/expect)
(def describe (.-describe test))

;; Shared state for the test suite
(def !shared-state (atom {}))

;; Use describe with shared context to improve speed (single browser launch)
(describe "Popup UI Tests"
  (fn []
    (.beforeAll test
      (^:async fn []
        (let [context (js-await (create-extension-context))
              ext-id (js-await (get-extension-id context))]
          (swap! !shared-state assoc :context context :ext-id ext-id))))
    
    (.afterAll test
      (^:async fn []
        (when-let [context (:context @!shared-state)]
          (js-await (.close context)))))
    
    (test "extension loads and popup renders"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
          (js-await (-> (expect (.locator page "#nrepl-port")) (.toBeVisible)))
          (js-await (-> (expect (.locator page "#ws-port")) (.toBeVisible)))
          (js-await (-> (expect (.locator page "button:has-text(\"Copy\")")) (.toBeVisible)))
          (js-await (.close page)))))
    
    (test "popup shows connect button"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
          (js-await (-> (expect (.locator page "button:has-text(\"Connect\")")) (.toBeVisible)))
          (js-await (.close page)))))
    
    (test "port inputs accept values"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/popup.html")))
          (js-await (.fill page "#nrepl-port" "9999"))
          (js-await (-> (expect (.locator page "#nrepl-port")) (.toHaveValue "9999")))
          (js-await (.fill page "#ws-port" "8888"))
          (js-await (-> (expect (.locator page "#ws-port")) (.toHaveValue "8888")))
          (js-await (.close page)))))))
