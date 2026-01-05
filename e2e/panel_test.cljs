(ns panel-test
  "E2E tests for the DevTools panel UI.
   Note: Panel is designed to run in DevTools context (via devtools.js),
   but we verify the HTML and basic structure loads correctly."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [with-extension]]))

(test "panel HTML file loads successfully"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Verify the page loaded and has the basic structure
            (js-await (-> (expect (.locator page "#app")) (.toBeVisible)))
            ;; Verify CSS is linked
            (let [styles (js-await (.getAttribute (.locator page "link[rel=\"stylesheet\"]") "href"))]
              (-> (expect (.includes styles "panel.css")) (.toBe true)))
            ;; Verify panel.js script is included
            (let [script (js-await (.getAttribute (.locator page "script[src]") "src"))]
              (-> (expect (.includes script "panel.js")) (.toBe true)))))))))