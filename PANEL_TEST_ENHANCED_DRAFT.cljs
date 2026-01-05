(ns panel-test-enhanced
  "Enhanced E2E tests for the DevTools panel UI.
   These tests verify panel UI components and interactions.
   
   NOTE: These tests run the panel in isolation (not in DevTools context),
   so they verify UI behavior but cannot test inspectedWindow APIs.
   
   For full DevTools integration testing, see PANEL_TESTING_RESEARCH.md"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [with-extension]]))

;; =============================================================================
;; Basic Panel Loading
;; =============================================================================

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

;; =============================================================================
;; Panel UI Component Rendering
;; =============================================================================

(test "panel UI renders all main components"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            
            ;; Panel header with title
            (js-await (-> (expect (.locator page ".panel-header")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".panel-title")) (.toContainText "Scittle Tamper")))
            
            ;; Code editor area
            (js-await (-> (expect (.locator page "textarea")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".btn-eval")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".btn-clear")) (.toBeVisible)))
            
            ;; Save script section
            (js-await (-> (expect (.locator page "#script-name")) (.toBeVisible)))
            (js-await (-> (expect (.locator page "#script-match")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeVisible)))
            
            ;; Results area
            (js-await (-> (expect (.locator page ".results-area")) (.toBeVisible)))))))))

(test "panel header shows extension icon"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Verify icon image is present
            (let [icon (.locator page ".panel-title img")]
              (js-await (-> (expect icon) (.toBeVisible)))
              (let [src (js-await (.getAttribute icon "src"))]
                (-> (expect (.includes src "icon-32.png")) (.toBe true))))))))))

(test "results area shows empty state with logos"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Empty state should be visible initially
            (js-await (-> (expect (.locator page ".empty-results")) (.toBeVisible)))
            ;; Should show Clojure ecosystem logos
            (js-await (-> (expect (.locator page ".empty-results-logos")) (.toBeVisible)))
            ;; Should have links to SCI, ClojureScript, Clojure
            (js-await (-> (expect (.locator page "a[href*='github.com/babashka/sci']")) (.toBeVisible)))
            (js-await (-> (expect (.locator page "a[href*='clojurescript.org']")) (.toBeVisible)))
            (js-await (-> (expect (.locator page "a[href*='clojure.org']")) (.toBeVisible)))))))))

;; =============================================================================
;; Code Editor Functionality
;; =============================================================================

(test "code editor accepts and displays input"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))
                test-code "(def x 42)\n(+ x 8)"]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Type code into textarea
            (js-await (.fill page "textarea" test-code))
            ;; Verify value is set
            (js-await (-> (expect (.locator page "textarea")) (.toHaveValue test-code)))))))))

(test "code editor shows placeholder text"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Verify placeholder includes eval hint
            (let [placeholder (js-await (.getAttribute (.locator page "textarea") "placeholder"))]
              (-> (expect (.includes placeholder "Ctrl+Enter")) (.toBe true)))))))))

(test "eval button is disabled when code is empty"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Initially, textarea is empty and button should be disabled
            (js-await (-> (expect (.locator page ".btn-eval")) (.toBeDisabled)))))))))

(test "eval button becomes enabled when code is entered"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Initially disabled
            (js-await (-> (expect (.locator page ".btn-eval")) (.toBeDisabled)))
            ;; Enter code
            (js-await (.fill page "textarea" "(+ 1 2)"))
            ;; Button should now be enabled
            (js-await (-> (expect (.locator page ".btn-eval")) (.toBeEnabled)))))))))

(test "clear button is always visible"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (-> (expect (.locator page ".btn-clear")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".btn-clear")) (.toContainText "Clear")))))))))

(test "keyboard shortcut hint is displayed"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (-> (expect (.locator page ".shortcut-hint")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".shortcut-hint")) (.toContainText "Ctrl+Enter"))))))))

;; =============================================================================
;; Save Script Form
;; =============================================================================

(test "save script form has all required fields"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Name input
            (js-await (-> (expect (.locator page "#script-name")) (.toBeVisible)))
            (let [name-label (.locator page "label[for=\"script-name\"]")]
              (js-await (-> (expect name-label) (.toBeVisible))))
            ;; URL pattern input
            (js-await (-> (expect (.locator page "#script-match")) (.toBeVisible)))
            (let [match-label (.locator page "label[for=\"script-match\"]")]
              (js-await (-> (expect match-label) (.toBeVisible))))
            ;; Save button
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeVisible)))))))))

(test "script name field accepts input"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))
                script-name "My Test Script"]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.fill page "#script-name" script-name))
            (js-await (-> (expect (.locator page "#script-name")) (.toHaveValue script-name)))))))))

(test "URL pattern field accepts input"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))
                url-pattern "https://example.com/*"]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.fill page "#script-match" url-pattern))
            (js-await (-> (expect (.locator page "#script-match")) (.toHaveValue url-pattern)))))))))

(test "URL pattern field shows placeholder"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (let [placeholder (js-await (.getAttribute (.locator page "#script-match") "placeholder"))]
              (-> (expect (.includes placeholder "example.com")) (.toBe true))))))))

(test "use current URL button is visible"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Button with arrow (↵) should be visible
            (js-await (-> (expect (.locator page ".btn-use-url")) (.toBeVisible)))
            (js-await (-> (expect (.locator page ".btn-use-url")) (.toContainText "↵"))))))))

(test "save button is disabled when required fields are empty"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; All fields empty - button should be disabled
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))))))))

(test "save button is disabled when only name is filled"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.fill page "#script-name" "Test"))
            ;; Still missing code and pattern
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))))))))

(test "save button is disabled when only pattern is filled"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.fill page "#script-match" "https://test.com/*"))
            ;; Still missing code and name
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))))))))

(test "save button is disabled when only code is filled"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            (js-await (.fill page "textarea" "(+ 1 2)"))
            ;; Still missing name and pattern
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeDisabled)))))))))

(test "save button is enabled when all required fields are filled"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Fill all required fields
            (js-await (.fill page "textarea" "(+ 1 2)"))
            (js-await (.fill page "#script-name" "Test Script"))
            (js-await (.fill page "#script-match" "https://test.com/*"))
            ;; Button should now be enabled
            (js-await (-> (expect (.locator page ".btn-save")) (.toBeEnabled)))))))))

(test "save script section header shows correct text for new script"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Should show "Save as Userscript" for new scripts
            (js-await (-> (expect (.locator page ".save-script-header")) 
                         (.toContainText "Save as Userscript")))))))))

;; =============================================================================
;; Panel Layout and Styling
;; =============================================================================

(test "panel has proper CSS styling"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Verify panel root has proper class
            (let [panel-root (.locator page ".panel-root")]
              (js-await (-> (expect panel-root) (.toBeVisible))))
            ;; Verify content area exists
            (let [panel-content (.locator page ".panel-content")]
              (js-await (-> (expect panel-content) (.toBeVisible))))))))))

(test "code input area has proper structure"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Verify code-input-area container
            (js-await (-> (expect (.locator page ".code-input-area")) (.toBeVisible)))
            ;; Verify code-actions section
            (js-await (-> (expect (.locator page ".code-actions")) (.toBeVisible)))))))))

(test "save script form has proper structure"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Verify save-script-section
            (js-await (-> (expect (.locator page ".save-script-section")) (.toBeVisible)))
            ;; Verify save-script-form
            (js-await (-> (expect (.locator page ".save-script-form")) (.toBeVisible)))
            ;; Verify save-actions
            (js-await (-> (expect (.locator page ".save-actions")) (.toBeVisible)))))))))

;; =============================================================================
;; Accessibility and UX
;; =============================================================================

(test "form labels are properly associated with inputs"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Script name label
            (let [name-label (.locator page "label[for=\"script-name\"]")]
              (js-await (-> (expect name-label) (.toBeVisible))))
            ;; URL pattern label
            (let [match-label (.locator page "label[for=\"script-match\"]")]
              (js-await (-> (expect match-label) (.toBeVisible))))))))))

(test "buttons have descriptive text"
  (^:async fn []
    (js-await
      (with-extension
        (^:async fn [context ext-id]
          (let [page (js-await (.newPage context))]
            (js-await (.goto page (str "chrome-extension://" ext-id "/panel.html")))
            ;; Eval button
            (js-await (-> (expect (.locator page ".btn-eval")) (.toContainText "Eval")))
            ;; Clear button
            (js-await (-> (expect (.locator page ".btn-clear")) (.toContainText "Clear")))
            ;; Save button
            (js-await (-> (expect (.locator page ".btn-save")) (.toContainText "Save"))))))))))
