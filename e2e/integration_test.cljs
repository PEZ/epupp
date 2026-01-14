(ns integration-test
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser
                              get-extension-id
                              create-panel-page
                              create-popup-page
                              clear-storage
                              wait-for-save-status
                              wait-for-checkbox-state
                              wait-for-edit-hint
                              wait-for-panel-ready
                              assert-no-errors!]]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata."
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
;; Integration: Script Lifecycle (panel -> popup -> panel -> popup)
;; =============================================================================

(test "Integration: script lifecycle - save, view, toggle, edit, delete"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Start with clean storage
            (let [temp-page (js-await (.newPage context))]
              (js-await (.goto temp-page (str "chrome-extension://" ext-id "/popup.html")))
              (js-await (clear-storage temp-page))
              (js-await (.close temp-page)))

            ;; === PHASE 1: Save script from panel ===
            ;; Input: "Lifecycle Test" -> Normalized: "lifecycle_test.cljs"
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "Lifecycle Test"
                                            :match "*://lifecycle.test/*"
                                            :code "(println \"Original code\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              ;; First save of new script shows "Created"
              (js-await (wait-for-save-status panel "Created"))
              (js-await (.close panel)))

            ;; === PHASE 2: Verify in popup, toggle, check edit hint ===
            ;; Script name is normalized to "lifecycle_test.cljs"
            (let [popup (js-await (create-popup-page context ext-id))
                  ;; Use specific locator for normalized name
                  script-item (.locator popup ".script-item:has-text(\"lifecycle_test.cljs\")")
                  checkbox (.locator script-item "input[type='checkbox']")
                  inspect-btn (.locator script-item "button.script-inspect")
                  hint (.locator popup ".script-edit-hint")]

              ;; Script appears with normalized name and pattern
              (js-await (-> (expect script-item) (.toContainText "lifecycle_test.cljs")))
              (js-await (-> (expect script-item) (.toContainText "*://lifecycle.test/*")))

              ;; Toggle enable/disable
              (js-await (-> (expect checkbox) (.toBeChecked)))
              (js-await (.click checkbox))
              (js-await (wait-for-checkbox-state checkbox false))
              (js-await (.click checkbox))
              (js-await (wait-for-checkbox-state checkbox true))

              ;; Edit hint appears on click
              (js-await (-> (expect hint) (.toHaveCount 0)))
              (js-await (.click inspect-btn))
              (js-await (wait-for-edit-hint popup))
              (js-await (-> (expect hint) (.toContainText "Developer Tools")))

              (js-await (.close popup)))

            ;; === PHASE 3: Edit script - panel receives it ===
            ;; Click inspect in popup first, then open panel (which reads editingScript on init)
            (let [popup (js-await (create-popup-page context ext-id))
                  ;; Use normalized name
                  script-item (.locator popup ".script-item:has-text(\"lifecycle_test.cljs\")")
                  inspect-btn (.locator script-item "button.script-inspect")]

              ;; Click inspect in popup to set editingScript in storage
              (js-await (.click inspect-btn))
              (js-await (wait-for-edit-hint popup))
              (js-await (.close popup)))

            ;; Now open panel - it will read editingScript on init
            (let [panel (js-await (create-panel-page context ext-id))
                  save-section (.locator panel ".save-script-section")
                  name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]

              ;; Wait for script to load - check name field shows loaded name
              (js-await (-> (expect name-field) (.toContainText "lifecycle_test.cljs")))

              ;; Modify code (keep same manifest name to update existing)
              (let [updated-code (code-with-manifest {:name "lifecycle_test.cljs"
                                                      :match "*://lifecycle.test/*"
                                                      :code "(println \"Updated code\")"})]
                (js-await (.fill (.locator panel "#code-area") updated-code)))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "Saved"))

              (js-await (.close panel)))

            ;; === PHASE 4: Delete script ===
            (let [popup (js-await (create-popup-page context ext-id))
                  ;; Use normalized name
                  script-item (.locator popup ".script-item:has-text(\"lifecycle_test.cljs\")")
                  delete-btn (.locator script-item "button.script-delete")]

              (.on popup "dialog" (fn [dialog] (.accept dialog)))
              (js-await (.click delete-btn))
              ;; Wait for script to disappear (Playwright auto-waits for count to match)
              (js-await (-> (expect script-item) (.toHaveCount 0)))

              (js-await (assert-no-errors! popup))
              (js-await (.close popup)))
            (finally
              (js-await (.close context)))))))

;; =============================================================================
;; Integration: Run-at Badge Display
;; =============================================================================

(test "Integration: run-at badges display correctly for script timing"
      (^:async fn []
        (let [context (js-await (launch-browser))
              ext-id (js-await (get-extension-id context))]
          (try
            ;; Start with clean storage
            (let [temp-page (js-await (.newPage context))]
              (js-await (.goto temp-page (str "chrome-extension://" ext-id "/popup.html")))
              (js-await (clear-storage temp-page))
              (js-await (.close temp-page)))

            ;; === PHASE 1: Create script with document-start timing ===
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "Early Script"
                                            :match "*://early.test/*"
                                            :run-at "document-start"
                                            :code "(println \"early script\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "Created"))
              (js-await (.close panel)))

            ;; === PHASE 2: Create script with document-end timing ===
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "DOM Ready Script"
                                            :match "*://domready.test/*"
                                            :run-at "document-end"
                                            :code "(println \"dom ready script\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "Created"))
              (js-await (.close panel)))

            ;; === PHASE 3: Create script with default timing (no run-at in manifest) ===
            (let [panel (js-await (create-panel-page context ext-id))
                  code (code-with-manifest {:name "Normal Script"
                                            :match "*://normal.test/*"
                                            :code "(println \"normal script\")"})]
              (js-await (.fill (.locator panel "#code-area") code))
              (js-await (.click (.locator panel "button.btn-save")))
              (js-await (wait-for-save-status panel "Created"))
              (js-await (.close panel)))

            ;; === PHASE 4: Verify badges in popup ===
            (let [popup (js-await (create-popup-page context ext-id))
                  early-item (.locator popup ".script-item:has-text(\"early_script.cljs\")")
                  domready-item (.locator popup ".script-item:has-text(\"dom_ready_script.cljs\")")
                  normal-item (.locator popup ".script-item:has-text(\"normal_script.cljs\")")]

              ;; Early script has bolt icon badge
              (let [badge (.locator early-item ".run-at-badge")]
                (js-await (-> (expect badge) (.toBeVisible)))
                (js-await (-> (expect (.locator badge "svg")) (.toBeVisible)))
                (js-await (-> (expect badge) (.toHaveAttribute "title" "Runs at document-start (before page loads)"))))

              ;; DOM ready script has flag icon badge
              (let [badge (.locator domready-item ".run-at-badge")]
                (js-await (-> (expect badge) (.toBeVisible)))
                (js-await (-> (expect (.locator badge "svg")) (.toBeVisible)))
                (js-await (-> (expect badge) (.toHaveAttribute "title" "Runs at document-end (when DOM is ready)"))))

              ;; Normal script has NO badge (document-idle is default)
              (let [badge (.locator normal-item ".run-at-badge")]
                (js-await (-> (expect badge) (.toHaveCount 0))))

              (js-await (assert-no-errors! popup))
              (js-await (.close popup)))

            (finally
              (js-await (.close context)))))))
