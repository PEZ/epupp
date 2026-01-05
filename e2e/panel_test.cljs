(ns panel-test
  "E2E tests for the DevTools panel UI.
   Note: Panel is designed to run in DevTools context (via devtools.js),
   but we test the panel.html directly to verify UI functionality.
   
   The panel provides:
   1. Code editor (textarea) for ClojureScript code
   2. Eval button and Ctrl+Enter shortcut
   3. Results area showing input/output/errors
   4. Save script section (name, match pattern, save button)
   5. Status indicators and error messages"
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

(test "panel renders main UI components"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Wait for panel to initialize
            (js-await (.waitForSelector page ".panel-root"))
            ;; Verify header with title
            (js-await (-> (expect (.locator page ".panel-title")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".panel-title")) (.toContainText "Scittle Tamper")))
            ;; Verify code input area
            (js-await (-> (expect (.locator page "textarea")) (.toBeVisible)))
            ;; Verify eval and clear buttons
            (js-await (-> (expect (.locator page ".btn-eval")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".btn-clear")) (.toBeVisible)))
            ;; Verify save script section
            (js-await (-> (expect (.locator page ".save-script-section")) (.toBeVisible)))
            ;; Verify results area
            (js-await (-> (expect (.locator page ".results-area")) (.toBeVisible)))))))))

(test "code textarea accepts input"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.waitForSelector page "textarea"))
            ;; Type code into textarea
            (let [test-code "(+ 1 2 3)"]
              (js-await (.fill page "textarea" test-code))
              (js-await (-> (expect (.locator page "textarea")) (.toHaveValue test-code))))))))))

(test "eval button is disabled when textarea is empty"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.waitForSelector page ".btn-eval"))
            ;; Initially textarea is empty, eval button should be disabled
            (js-await (-> (expect (.locator page ".btn-eval")) (.toBeDisabled)))
            ;; Add some code
            (js-await (.fill page "textarea" "(+ 1 2)"))
            ;; Eval button should now be enabled
            (js-await (-> (expect (.locator page ".btn-eval")) (.toBeEnabled)))
            ;; Clear the code
            (js-await (.fill page "textarea" ""))
            ;; Eval button should be disabled again
            (js-await (-> (expect (.locator page ".btn-eval")) (.toBeDisabled)))))))))

(test "save script section has required fields"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.waitForSelector page ".save-script-section"))
            ;; Verify script name input
            (js-await (-> (expect (.locator page "#script-name")) (.toBeVisible)))
            ;; Verify URL pattern input
            (js-await (-> (expect (.locator page "#script-match")) (.toBeVisible)))
            ;; Verify save button
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeVisible)))
            ;; Verify "Use current URL" button
            (js-await (-> (expect (.locator page ".btn-use-url")) (.toBeVisible)))))))))

(test "save script inputs accept values"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.waitForSelector page "#script-name"))
            ;; Fill script name
            (js-await (.fill page "#script-name" "Test Script"))
            (js-await (-> (expect (.locator page "#script-name")) (.toHaveValue "Test Script")))
            ;; Fill URL pattern
            (js-await (.fill page "#script-match" "https://example.com/*"))
            (js-await (-> (expect (.locator page "#script-match")) (.toHaveValue "https://example.com/*")))))))))

(test "save button is disabled when required fields are empty"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.waitForSelector page ".btn-save"))
            ;; Initially all fields empty, save should be disabled
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))
            ;; Fill only name
            (js-await (.fill page "#script-name" "Test"))
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))
            ;; Fill only match (clear name first)
            (js-await (.fill page "#script-name" ""))
            (js-await (.fill page "#script-match" "https://example.com/*"))
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))
            ;; Fill only code (clear match first)
            (js-await (.fill page "#script-match" ""))
            (js-await (.fill page "textarea" "(+ 1 2)"))
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))
            ;; Fill all three fields
            (js-await (.fill page "#script-name" "Test"))
            (js-await (.fill page "#script-match" "https://example.com/*"))
            (js-await (.fill page "textarea" "(+ 1 2)"))
            ;; Now save button should be enabled
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeEnabled)))))))))

(test "results area shows empty state initially"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.waitForSelector page ".results-area"))
            ;; Should show empty results message
            (js-await (-> (expect (.locator page ".empty-results")) (.toBeVisible)))
            ;; Should contain Scittle link
            (js-await (-> (expect (.locator page ".empty-results")) (.toContainText "Scittle")))
            ;; Should show logos
            (js-await (-> (expect (.locator page ".empty-results-logos")) (.toBeVisible)))))))))

(test "clear button clears results"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.waitForSelector page ".btn-clear"))
            ;; Clear button should be visible
            (js-await (-> (expect (.locator page ".btn-clear")) (.toBeVisible)))
            ;; Click clear button (even though there are no results yet, it should work)
            (js-await (.click page ".btn-clear"))
            ;; Empty state should still be visible
            (js-await (-> (expect (.locator page ".empty-results")) (.toBeVisible)))))))))

(test "keyboard shortcut hint is displayed"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.waitForSelector page ".shortcut-hint"))
            ;; Verify keyboard shortcut hint
            (js-await (-> (expect (.locator page ".shortcut-hint")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".shortcut-hint")) (.toContainText "Ctrl+Enter")))))))))

(test "panel shows Scittle logo images"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.waitForSelector page ".empty-results-logos"))
            ;; Check for logo images in the empty state
            (let [logos (.locator page ".empty-results-logos img")]
              (js-await (-> (expect logos) (.toHaveCount 3))))))))))
