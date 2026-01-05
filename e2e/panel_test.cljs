(ns panel-test
  "E2E tests for the DevTools panel UI.
   Uses describe.serial with shared browser context to improve speed."
  (:require ["@playwright/test" :as playwright]
            [fixtures :refer [create-extension-context get-extension-id]]))

(def test playwright/test)
(def expect playwright/expect)
(def describe (.-describe test))

;; Shared state for the test suite
(def !shared-state (atom {}))

;; Use describe with shared context to improve speed (single browser launch)
(describe "Panel UI Tests"
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
    
    (test "panel HTML file loads successfully"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (-> (expect (.locator page "#app")) (.toBeVisible)))
          (let [styles (js-await (.getAttribute (.locator page "link[rel=\"stylesheet\"]") "href"))]
            (-> (expect (.includes styles "panel.css")) (.toBe true)))
          (let [script (js-await (.getAttribute (.locator page "script[src]") "src"))]
            (-> (expect (.includes script "panel.js")) (.toBe true)))
          (js-await (.close page)))))
    
    (test "panel renders main UI components"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (.waitForSelector page ".panel-root"))
          (js-await (-> (expect (.locator page ".panel-title")) (.toBeVisible)))
          (js-await (-> (expect (.locator page ".panel-title")) (.toContainText "Scittle Tamper")))
          (js-await (-> (expect (.locator page "textarea")) (.toBeVisible)))
          (js-await (-> (expect (.locator page ".btn-eval")) (.toBeVisible)))
          (js-await (-> (expect (.locator page ".btn-clear")) (.toBeVisible)))
          (js-await (-> (expect (.locator page ".save-script-section")) (.toBeVisible)))
          (js-await (-> (expect (.locator page ".results-area")) (.toBeVisible)))
          (js-await (.close page)))))
    
    (test "code textarea accepts input"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (.waitForSelector page "textarea"))
          (let [test-code "(+ 1 2 3)"]
            (js-await (.fill page "textarea" test-code))
            (js-await (-> (expect (.locator page "textarea")) (.toHaveValue test-code))))
          (js-await (.close page)))))
    
    (test "eval button is disabled when textarea is empty"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (.waitForSelector page ".btn-eval"))
          ;; Ensure textarea is actually empty (clear any cached content)
          (js-await (.fill page "textarea" ""))
          ;; Now textarea is empty, eval button should be disabled
          (js-await (-> (expect (.locator page ".btn-eval")) (.toBeDisabled)))
          (js-await (.fill page "textarea" "(+ 1 2)"))
          (js-await (-> (expect (.locator page ".btn-eval")) (.toBeEnabled)))
          (js-await (.fill page "textarea" ""))
          (js-await (-> (expect (.locator page ".btn-eval")) (.toBeDisabled)))
          (js-await (.close page)))))
    
    (test "save script section has required fields"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (.waitForSelector page ".save-script-section"))
          (js-await (-> (expect (.locator page "#script-name")) (.toBeVisible)))
          (js-await (-> (expect (.locator page "#script-match")) (.toBeVisible)))
          (js-await (-> (expect (.locator page ".btn-save")) (.toBeVisible)))
          (js-await (-> (expect (.locator page ".btn-use-url")) (.toBeVisible)))
          (js-await (.close page)))))
    
    (test "save script inputs accept values"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (.waitForSelector page "#script-name"))
          (js-await (.fill page "#script-name" "Test Script"))
          (js-await (-> (expect (.locator page "#script-name")) (.toHaveValue "Test Script")))
          (js-await (.fill page "#script-match" "https://example.com/*"))
          (js-await (-> (expect (.locator page "#script-match")) (.toHaveValue "https://example.com/*")))
          (js-await (.close page)))))
    
    (test "save button is disabled when required fields are empty"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (.waitForSelector page ".btn-save"))
          (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))
          (js-await (.fill page "#script-name" "Test"))
          (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))
          (js-await (.fill page "#script-name" ""))
          (js-await (.fill page "#script-match" "https://example.com/*"))
          (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))
          (js-await (.fill page "#script-match" ""))
          (js-await (.fill page "textarea" "(+ 1 2)"))
          (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))
          (js-await (.fill page "#script-name" "Test"))
          (js-await (.fill page "#script-match" "https://example.com/*"))
          (js-await (.fill page "textarea" "(+ 1 2)"))
          (js-await (-> (expect (.locator page ".btn-save")) (.toBeEnabled)))
          (js-await (.close page)))))
    
    (test "results area shows empty state initially"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (.waitForSelector page ".results-area"))
          (js-await (-> (expect (.locator page ".empty-results")) (.toBeVisible)))
          (js-await (-> (expect (.locator page ".empty-results")) (.toContainText "Scittle")))
          (js-await (-> (expect (.locator page ".empty-results-logos")) (.toBeVisible)))
          (js-await (.close page)))))
    
    (test "clear button clears results"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (.waitForSelector page ".btn-clear"))
          (js-await (-> (expect (.locator page ".btn-clear")) (.toBeVisible)))
          (js-await (.click page ".btn-clear"))
          (js-await (-> (expect (.locator page ".empty-results")) (.toBeVisible)))
          (js-await (.close page)))))
    
    (test "keyboard shortcut hint is displayed"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (.waitForSelector page ".shortcut-hint"))
          (js-await (-> (expect (.locator page ".shortcut-hint")) (.toBeVisible)))
          (js-await (-> (expect (.locator page ".shortcut-hint")) (.toContainText "Ctrl+Enter")))
          (js-await (.close page)))))
    
    (test "panel shows Scittle logo images"
      (^:async fn []
        (let [{:keys [context ext-id]} @!shared-state
              page (js-await (.newPage context))]
          (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
          (js-await (.waitForSelector page ".empty-results-logos"))
          (let [logos (.locator page ".empty-results-logos img")]
            (js-await (-> (expect logos) (.toHaveCount 3))))
          (js-await (.close page)))))))
