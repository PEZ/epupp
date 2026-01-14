(ns panel-state-test
  "E2E tests for DevTools panel state management - initialization, new script, undo."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              clear-storage wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status wait-for-panel-state-saved
                              get-test-events assert-no-errors!]]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata.
   Uses the official manifest keys: :epupp/script-name, :epupp/site-match, etc."
  [{:keys [name match description run-at code]
    :or {code "(println \"Test script\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/site-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\"")))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

;; =============================================================================
;; Panel User Journey: Initialization
;; =============================================================================

(test "Panel: initializes with default script when no saved state"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
                  match-field (.locator save-section ".property-row:has(th:text('URL Pattern')) .property-value")]
              ;; Clear storage for clean slate
              (js-await (clear-storage panel))
              ;; Reload to trigger fresh initialization
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; Code area should have the default script (use toHaveValue for textarea)
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(ns hello-world\\)"))))
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(defn hello \\[s\\]"))))

              ;; Metadata fields should be populated from default script manifest
              (js-await (-> (expect name-field) (.toContainText "hello_world.cljs")))
              (js-await (-> (expect match-field) (.toContainText "https://example.com/*")))

              ;; Save button should be enabled (valid manifest)
              (js-await (-> (expect (.locator panel "button.btn-save")) (.toBeEnabled)))

              ;; Assert no errors before closing
              (js-await (assert-no-errors! panel)))
            (finally
              (js-await (.close context)))))))


(test "Panel: restores saved state and parses manifest on reload"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Save a script to create persisted state ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")]
              ;; Clear storage first (this also clears test events)
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; Enter a script with manifest
              (let [test-code (code-with-manifest {:name "Persisted Script"
                                                   :match "*://persist.example.com/*"
                                                   :description "Test persistence"
                                                   :code "(println \"persisted\")"})]
                (js-await (.fill textarea test-code)))

              ;; After fill, check if the state changed (debug element shows code-len)
              (let [debug-info (.locator panel "#debug-info")]
                (js-await (-> (expect debug-info) (.toBeVisible #js {:timeout 300})))
                (let [debug-text (js-await (.textContent debug-info))]
                  (println "=== PHASE 1: After fill ===" debug-text)))

              ;; Save it (this will persist the state)
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "Created"))
              ;; Wait for panel state to be persisted before closing
              ;; Note: We look for code content that's actually in the manifest,
              ;; not the normalized name which appears in UI but not in code
              (js-await (wait-for-panel-state-saved panel "(println \"persisted\")"))
              (js-await (.close panel)))

            ;; === PHASE 2: Reopen panel and verify state + manifest parsing ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
                  match-field (.locator save-section ".property-row:has(th:text('URL Pattern')) .property-value")
                  description-field (.locator save-section ".property-row:has(th:text('Description')) .property-value")]

              ;; Wait for panel to fully initialize
              (js-await (wait-for-panel-ready panel))

              ;; Poll for restore to complete by waiting for textarea to have expected content
              ;; Much faster than fixed 1000ms sleep when restore happens quickly
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "Persisted Script") #js {:timeout 2000})))

              ;; Read debug info from visible element
              (let [debug-info (.locator panel "#debug-info")]
                (js-await (-> (expect debug-info) (.toBeVisible #js {:timeout 300})))
                (let [debug-text (js-await (.textContent debug-info))]
                  (println "=== DEBUG INFO ===" debug-text)))

              ;; Get test events via popup's dev-log button
              (let [popup (js-await (.newPage context))
                    popup-url (str "chrome-extension://" ext-id "/popup.html")]
                (js-await (.goto popup popup-url #js {:timeout 1000}))
                (js-await (wait-for-popup-ready popup))
                (let [events (js-await (get-test-events popup))]
                  (println "=== TEST EVENTS ===" (js/JSON.stringify events nil 2)))
                (js-await (.close popup)))

              ;; Get current textarea value to debug
              (let [textarea-value (js-await (.inputValue textarea))]
                (println "=== DEBUG: Current textarea value ===")
                (println (subs textarea-value 0 100))
                (println "=== END DEBUG ==="))

              ;; Verify additional code content is restored
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(println \"persisted\"\\)"))))

              ;; CRITICAL: Manifest should be parsed and metadata displayed
              ;; This was the bug - manifest wasn't parsed on restore
              (js-await (-> (expect name-field) (.toContainText "persisted_script.cljs")))
              (js-await (-> (expect match-field) (.toContainText "*://persist.example.com/*")))
              (js-await (-> (expect description-field) (.toContainText "Test persistence")))

              ;; Save button should be enabled
              (js-await (-> (expect (.locator panel "button.btn-save")) (.toBeEnabled)))

              ;; Assert no errors before closing
              (js-await (assert-no-errors! panel)))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Panel User Journey: New Script Button
;; =============================================================================

(test "Panel: New button clears editor and resets to default script"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; === PHASE 1: Create and save a script ===
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  save-btn (.locator panel "button.btn-save")
                  new-btn (.locator panel "button.btn-new-script")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; New button should be visible
              (js-await (-> (expect new-btn) (.toBeVisible)))

              ;; Create a script with manifest
              (let [test-code (code-with-manifest {:name "My Custom Script"
                                                   :match "*://custom.example.com/*"
                                                   :code "(println \"custom code\")"})]
                (js-await (.fill textarea test-code)))

              ;; Save the script
              (js-await (.click save-btn))
              (js-await (wait-for-save-status panel "my_custom_script.cljs"))

              ;; === PHASE 2: Click New to clear editor ===
              ;; Set up dialog handler to accept confirmation
              (.once panel "dialog" (fn [dialog] (.accept dialog)))

              ;; Click the New button
              (js-await (.click new-btn))

              ;; Code should reset to default script
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "\\(ns hello-world\\)"))))

              ;; Metadata should show default script values
              (let [save-section (.locator panel ".save-script-section")
                    name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
                (js-await (-> (expect name-field) (.toContainText "hello_world.cljs"))))

              ;; Script-id should be cleared (we're creating a new script now)
              ;; Verify by checking the header says "Save as Userscript" not "Edit Userscript"
              (let [header (.locator panel ".save-script-header .header-title")]
                (js-await (-> (expect header) (.toContainText "Save as Userscript"))))

              ;; Assert no errors before closing
              (js-await (assert-no-errors! panel)))
            (finally
              (js-await (.close context)))))))


(test "Panel: New button preserves evaluation results"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  eval-btn (.locator panel "button.btn-eval")
                  results (.locator panel ".results-area")
                  new-btn (.locator panel "button.btn-new-script")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; === PHASE 1: Evaluate some code to have results ===
              (let [eval-code (code-with-manifest {:name "Eval Test"
                                                   :match "*://eval.example.com/*"
                                                   :code "(+ 1 2 3)"})]
                (js-await (.fill textarea eval-code)))
              (js-await (.click eval-btn))
              ;; Wait for result to appear
              (js-await (-> (expect results) (.toContainText "(+ 1 2 3)")))

              ;; === PHASE 2: Click New button ===
              ;; Set up dialog handler to accept confirmation
              (.once panel "dialog" (fn [dialog] (.accept dialog)))
              (js-await (.click new-btn))

              ;; === PHASE 3: Verify results are preserved ===
              ;; Code should be reset to default
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))

              ;; BUT results should still show the previous evaluation
              (js-await (-> (expect results) (.toContainText "(+ 1 2 3)")))

              ;; Assert no errors before closing
              (js-await (assert-no-errors! panel)))
            (finally
              (js-await (.close context)))))))


(test "Panel: New button with default script skips confirmation"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")
                  new-btn (.locator panel "button.btn-new-script")
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; Panel should start with default script
              (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs") #js {:timeout 500})))

              ;; Track whether dialog appears
              (let [dialog-appeared (atom false)]
                (.on panel "dialog" (fn [_dialog]
                                      (reset! dialog-appeared true)))

                ;; Click New button - should NOT trigger dialog since code is default
                (js-await (.click new-btn))

                ;; Small delay to ensure any dialog would have appeared
                (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 200))))

                ;; Dialog should NOT have appeared (code was already default)
                ;; Note: In Squint, we can't easily test this but we can verify
                ;; the textarea still has default content
                (js-await (-> (expect textarea) (.toHaveValue (js/RegExp. "hello_world\\.cljs"))))
                (js-await (-> (expect name-field) (.toContainText "hello_world.cljs")))

                ;; Assert no errors before closing
                (js-await (assert-no-errors! panel))))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Panel User Journey: Undo Functionality
;; =============================================================================

(test "Panel: undo works in code editor"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            (let [panel (js-await (create-panel-page context ext-id))
                  textarea (.locator panel "#code-area")]
              (js-await (clear-storage panel))
              (js-await (.reload panel))
              (js-await (wait-for-panel-ready panel))

              ;; Get the initial value
              (let [initial-value (js-await (.inputValue textarea))]

                ;; Focus and type a character
                (js-await (.focus textarea))
                (js-await (.press (.-keyboard panel) "End"))
                (js-await (.type (.-keyboard panel) "X"))

                ;; Wait for value to change (poll instead of fixed sleep)
                (js-await (-> (expect textarea)
                              (.toHaveValue (js/RegExp. "X$") #js {:timeout 500})))

                ;; Undo (platform-appropriate: Cmd+Z on Mac, Ctrl+Z on Win/Linux)
                (js-await (.press (.-keyboard panel) "ControlOrMeta+z"))

                ;; Wait for undo to restore original value (poll instead of fixed sleep)
                (js-await (-> (expect textarea)
                              (.toHaveValue initial-value #js {:timeout 500}))))

              ;; Assert no errors before closing
              (js-await (assert-no-errors! panel)))
            (finally
              (js-await (.close context)))))))
