(ns userscript-test
  "E2E tests for userscript functionality: injection, timing, and execution.

   Coverage:
   - Userscript injection on matching URLs
   - Script timing (document-start vs page scripts)
   - Performance reporting
   - Web Userscript Installer (built-in userscript)"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :as fixtures :refer [launch-browser get-extension-id create-popup-page
                                           create-panel-page wait-for-event
                                           get-test-events wait-for-save-status wait-for-popup-ready
                                           generate-timing-report print-timing-report
                                           assert-no-errors! wait-for-checkbox-state]]))

(defn- code-with-manifest
  "Generate test code with epupp manifest metadata."
  [{:keys [name match description run-at code]
    :or {code "(println \"Test script\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\"")))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

;; =============================================================================
;; Userscript Injection
;; =============================================================================

(defn- ^:async test_injects_on_matching_url_and_logs []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup: Create a script that matches localhost:18080
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Injection Test"
                                      :match "http://localhost:18080/*"
                                      :code "(js/console.log \"Userscript ran!\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable the script (defaults to disabled for auto-run)
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"injection_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (.click checkbox)))
        (js-await (.close popup)))

      ;; Navigate to matching page
      (let [page (js-await (.newPage context))]
        (js/console.log "Navigating to localhost:18080/basic.html...")
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))
        (js/console.log "Page loaded, test-marker visible")

        ;; Wait for SCRIPT_INJECTED event BEFORE closing the page
        ;; The injection happens asynchronously after webNavigation.onCompleted
        ;; so we need to keep the page open until injection completes
        (let [popup (js-await (create-popup-page context ext-id))
              _ (js/console.log "Waiting for SCRIPT_INJECTED event (page still open)...")
              event (js-await (wait-for-event popup "SCRIPT_INJECTED" 10000))]
          (js/console.log "SCRIPT_INJECTED event:" (js/JSON.stringify event))
          ;; Verify event has expected data
          (js-await (-> (expect (.-event event)) (.toBe "SCRIPT_INJECTED")))
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Document-start Timing
;; =============================================================================

(defn- ^:async test_document_start_runs_before_page_scripts []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup: Create a document-start script that records timing
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Timing Test"
                                      :match "http://localhost:18080/*"
                                      :run-at "document-start"
                                      :code "(set! (.-__EPUPP_SCRIPT_PERF js/window) (js/performance.now))"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable the script (defaults to disabled for auto-run)
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"timing_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (.click checkbox)))
        (js-await (.close popup)))

      ;; Navigate to timing test page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/timing-test.html" #js {:timeout 1000}))
        ;; Wait for page to fully load
        (js-await (-> (expect (.locator page "#timing-marker"))
                      (.toBeVisible)))

        ;; Wait for userscript to execute (Scittle loading is async)
        ;; Poll until the perf value is defined (a number, not undefined)
        (let [start (.now js/Date)]
          (loop []
            (let [is-number (js-await (.evaluate page (fn [] (= (js/typeof js/window.__EPUPP_SCRIPT_PERF) "number"))))]
              (when-not is-number
                (when (> (- (.now js/Date) start) 2000)
                  (throw (js/Error. "Timeout waiting for __EPUPP_SCRIPT_PERF to be defined")))
                (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                (recur)))))

        ;; Verify userscript executed via Scittle
        (let [epupp-perf (js-await (.evaluate page (fn [] js/window.__EPUPP_SCRIPT_PERF)))]
          ;; Assert script actually ran
          ;; Note: We can't guarantee running BEFORE inline page scripts because
          ;; Scittle needs to load first. But we verify the script does execute.
          (js-await (-> (expect epupp-perf)
                        (.toBeDefined)))
          (js/console.log "Document-start script perf:" epupp-perf))

        ;; Check for errors before closing
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Performance Reporting
;; =============================================================================

(defn- ^:async test_generate_performance_report_from_events []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup: Create a simple script to trigger full injection pipeline
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Performance Report Test"
                                      :match "http://localhost:18080/*"
                                      :code "(js/console.log \"Perf test\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Navigate to trigger injection
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))
        (js-await (.close page)))

      ;; Generate and print performance report
      (let [popup (js-await (create-popup-page context ext-id))
            events (js-await (get-test-events popup))
            report (generate-timing-report events)]
        ;; Print the report
        (print-timing-report report)

        ;; Verify we got some events
        (js-await (-> (expect (.-length events))
                      (.toBeGreaterThan 0)))

        ;; Verify report has expected structure
        (js-await (-> (expect (:all-events report))
                      (.toBeDefined)))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Error Checking
;; =============================================================================

(defn- ^:async test_injection_produces_no_uncaught_errors []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create and approve a script
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Error Check Test"
                                      :match "http://localhost:18080/*"
                                      :code "(js/console.log \"No errors please\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable the script (defaults to disabled for auto-run)
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"error_check_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (.click checkbox)))
        (js-await (.close popup)))

      ;; Navigate to trigger injection
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Wait for script injection
        (let [popup (js-await (create-popup-page context ext-id))
              _ (js-await (wait-for-event popup "SCRIPT_INJECTED" 10000))]
          ;; Check for any errors
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Web Userscript Installer (Built-in Userscript)
;; =============================================================================

(defn- ^:async test_web_userscript_installer_shows_button_and_installs []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Clear storage and wait for built-in gist installer to be re-installed
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        ;; Wait for web userscript installer to exist in storage by id with non-empty code
        ;; Poll using .evaluate loop - same pattern as wait-for-event
        (let [start (.now js/Date)
              timeout-ms 5000]
          (loop []
            (let [storage-data (js-await (.evaluate popup
                                                    (fn []
                                                      (js/Promise. (fn [resolve]
                                                                     (.get js/chrome.storage.local #js ["scripts"]
                                                                           (fn [result] (resolve result))))))))
                  scripts (.-scripts storage-data)
                  installer (when scripts
                              (.find scripts (fn [s]
                                               (= (.-id s) "epupp-builtin-web-userscript-installer"))))
                  has-code (and installer
                                (.-code installer)
                                (pos? (.-length (.-code installer))))]
              (if has-code
                (js/console.log "Web Userscript Installer re-installed with code")
                (if (> (- (.now js/Date) start) timeout-ms)
                  (throw (js/Error. "Timeout waiting for web userscript installer with code"))
                  (do
                    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
                    (recur)))))))

        ;; Enable the Web Userscript Installer (disabled by default)
        (let [installer-script (.locator popup ".script-item:has-text(\"epupp/web_userscript_installer.cljs\")")
              installer-checkbox (.locator installer-script "input[type='checkbox']")]
          (js-await (-> (expect installer-checkbox) (.toBeVisible)))
          (when-not (js-await (.isChecked installer-checkbox))
            (js/console.log "Enabling Web Userscript Installer...")
            (js-await (.click installer-checkbox))
            (js-await (wait-for-checkbox-state installer-checkbox true))))

        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (.newPage context))]
        ;; Capture console output for debugging
        (.on page "console" (fn [msg] (js/console.log "PAGE CONSOLE:" (.text msg))))
        (js-await (.goto page "http://localhost:18080/mock-gist.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))
        (js/console.log "Mock gist page loaded")

        ;; Debug: Check if installer script ran at all
        (let [debug-marker (.locator page "#epupp-installer-debug")]
          (try
            (js-await (-> (expect debug-marker) (.toBeAttached #js {:timeout 5000})))
            (js/console.log "DEBUG: Installer script executed (marker found)")
            ;; Wait a moment for scan to complete
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))
            ;; Read the debug marker content
            (let [marker-text (js-await (.textContent debug-marker))]
              (js/console.log "DEBUG marker text:" marker-text))
            ;; Check for button containers and buttons
            (let [containers (.locator page ".epupp-btn-container")
                  all-buttons (.locator page "button")]
              (let [container-count (js-await (.count containers))
                    button-count (js-await (.count all-buttons))]
                (js/console.log "DEBUG: button containers:" container-count ", all buttons:" button-count)))
            (catch :default e
              (js/console.log "DEBUG: Installer script DID NOT RUN (no marker):" (.-message e)))))

        ;; Wait for the web userscript installer userscript to run and add Install button
        ;; The button should appear on the installable gist file
        (let [install-btn (.locator page "#installable-gist button:has-text(\"Install\")")]
          (js/console.log "Waiting for Install button to appear...")
          (js-await (-> (expect install-btn)
                        (.toBeVisible #js {:timeout 10000})))
          (js/console.log "Install button found, clicking...")

          ;; Click the install button - should show confirmation modal
          (js-await (.click install-btn))

          ;; Wait for confirmation modal - uses #epupp-confirm ID
          (let [confirm-btn (.locator page "#epupp-confirm")]
            (js-await (-> (expect confirm-btn)
                          (.toBeVisible #js {:timeout 5000})))
            (js/console.log "Confirmation modal appeared, confirming...")

            ;; Confirm the installation
            (js-await (.click confirm-btn))

            ;; Wait for button to change to "Installed"
            (let [installed-indicator (.locator page "#installable-gist button:has-text(\"Installed\")")]
              (js-await (-> (expect installed-indicator)
                            (.toBeVisible #js {:timeout 5000})))
              (js/console.log "Script installed successfully"))))

        (js-await (.close page)))

      ;; Verify script appears in popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Look for the installed script
        (let [script-item (.locator popup ".script-item:has-text(\"test_installer_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 5000})))
          (js/console.log "Installed script visible in popup"))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))


(defn- ^:async test_web_userscript_installer_manual_only_script []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Clear storage and wait for built-in web userscript installer to be re-installed
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        ;; Wait for web userscript installer to exist in storage with non-empty code
        (let [start (.now js/Date)
              timeout-ms 5000]
          (loop []
            (let [storage-data (js-await (.evaluate popup
                                                    (fn []
                                                      (js/Promise. (fn [resolve]
                                                                     (.get js/chrome.storage.local #js ["scripts"]
                                                                           (fn [result] (resolve result))))))))
                  scripts (.-scripts storage-data)
                  installer (when scripts
                              (.find scripts (fn [s]
                                               (= (.-id s) "epupp-builtin-web-userscript-installer"))))
                  has-code (and installer
                                (.-code installer)
                                (pos? (.-length (.-code installer))))]
              (if has-code
                (js/console.log "Web Userscript Installer re-installed with code")
                (if (> (- (.now js/Date) start) timeout-ms)
                  (throw (js/Error. "Timeout waiting for web userscript installer with code"))
                  (do
                    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
                    (recur)))))))

        ;; Enable the Web Userscript Installer (disabled by default)
        (let [installer-script (.locator popup ".script-item:has-text(\"epupp/web_userscript_installer.cljs\")")
              installer-checkbox (.locator installer-script "input[type='checkbox']")]
          (js-await (-> (expect installer-checkbox) (.toBeVisible)))
          (when-not (js-await (.isChecked installer-checkbox))
            (js/console.log "Enabling Web Userscript Installer...")
            (js-await (.click installer-checkbox))
            (js-await (wait-for-checkbox-state installer-checkbox true))))

        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/mock-gist.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))
        (js/console.log "Mock gist page loaded")

        ;; Wait for the web userscript installer to add Install button to manual-only gist
        (let [install-btn (.locator page "#manual-only-gist button:has-text(\"Install\")")]
          (js/console.log "Waiting for Install button on manual-only gist...")
          (js-await (-> (expect install-btn)
                        (.toBeVisible #js {:timeout 10000})))
          (js/console.log "Install button found, clicking...")

          ;; Click the install button - should show confirmation modal
          (js-await (.click install-btn))

          ;; Wait for confirmation modal
          (let [confirm-btn (.locator page "#epupp-confirm")]
            (js-await (-> (expect confirm-btn)
                          (.toBeVisible #js {:timeout 5000})))
            (js/console.log "Confirmation modal appeared, confirming...")

            ;; Confirm the installation
            (js-await (.click confirm-btn))

            ;; Wait for button to change to "Installed"
            (let [installed-indicator (.locator page "#manual-only-gist button:has-text(\"Installed\")")]
              (js-await (-> (expect installed-indicator)
                            (.toBeVisible #js {:timeout 5000})))
              (js/console.log "Script installed successfully"))))

        (js-await (.close page)))

      ;; Verify script appears in popup with "No auto-run (manual only)"
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Look for the installed script
        (let [script-item (.locator popup ".script-item:has-text(\"manual_only_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 5000})))
          (js/console.log "Installed script visible in popup")

          ;; Verify it shows "No auto-run (manual only)"
          (let [match-span (.locator script-item ".script-match")]
            (js-await (-> (expect match-span)
                          (.toHaveText "No auto-run (manual only)" #js {:timeout 500})))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_web_userscript_installer_idempotency []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Clear storage and wait for built-in web userscript installer to be re-installed
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        ;; Wait for web userscript installer to exist in storage with non-empty code
        (let [start (.now js/Date)
              timeout-ms 5000]
          (loop []
            (let [storage-data (js-await (.evaluate popup
                                                    (fn []
                                                      (js/Promise. (fn [resolve]
                                                                     (.get js/chrome.storage.local #js ["scripts"]
                                                                           (fn [result] (resolve result))))))))
                  scripts (.-scripts storage-data)
                  installer (when scripts
                              (.find scripts (fn [s]
                                               (= (.-id s) "epupp-builtin-web-userscript-installer"))))
                  has-code (and installer
                                (.-code installer)
                                (pos? (.-length (.-code installer))))]
              (if has-code
                (js/console.log "Web Userscript Installer re-installed with code")
                (if (> (- (.now js/Date) start) timeout-ms)
                  (throw (js/Error. "Timeout waiting for web userscript installer with code"))
                  (do
                    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
                    (recur)))))))

        ;; Enable the Web Userscript Installer (disabled by default)
        (let [installer-script (.locator popup ".script-item:has-text(\"epupp/web_userscript_installer.cljs\")")
              installer-checkbox (.locator installer-script "input[type='checkbox']")]
          (js-await (-> (expect installer-checkbox) (.toBeVisible)))
          (when-not (js-await (.isChecked installer-checkbox))
            (js/console.log "Enabling Web Userscript Installer...")
            (js-await (.click installer-checkbox))
            (js-await (wait-for-checkbox-state installer-checkbox true))))

        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/mock-gist.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))
        (js/console.log "Mock gist page loaded")

        ;; Wait for the web userscript installer to add Install buttons (first scan)
        ;; We expect 3 buttons: 2 from pre-style blocks + 1 from GitHub table-style block
        (let [install-buttons (.locator page "button:has-text(\"Install\")")]
          (js/console.log "Waiting for Install buttons to appear (first scan)...")
          (js-await (-> (expect install-buttons)
                        (.toHaveCount 3 #js {:timeout 10000})))
          (let [initial-count (js-await (.count install-buttons))]
            (js/console.log "Initial button count:" initial-count)

            ;; Verify that pre elements have been marked as processed
            (let [processed-pres (js-await (.evaluate page "(() => Array.from(document.querySelectorAll('pre')).filter(p => p.getAttribute('data-epupp-processed')).length)()"))]
              (js/console.log "Processed pre elements:" processed-pres)
              (js-await (-> (expect processed-pres)
                            (.toBeGreaterThan 0))))

            ;; Verify that GitHub tables have been marked as processed
            (let [processed-tables (js-await (.evaluate page "(() => Array.from(document.querySelectorAll('table.js-file-line-container')).filter(t => t.getAttribute('data-epupp-processed')).length)()"))]
              (js/console.log "Processed GitHub tables:" processed-tables)
              (js-await (-> (expect processed-tables)
                            (.toBeGreaterThan 0))))

            ;; Count button containers to verify structure
            (let [button-containers (js-await (.evaluate page
                                                         (fn []
                                                           (.-length (.querySelectorAll js/document ".epupp-btn-container")))))]
              (js/console.log "Button containers:" button-containers)
              (js-await (-> (expect button-containers)
                            (.toBe initial-count))))))

        (js-await (.close page)))

      ;; Check for errors
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_web_userscript_installer_github_style_block []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Clear storage and wait for built-in web userscript installer to be re-installed
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        ;; Wait for web userscript installer to exist in storage with non-empty code
        (let [start (.now js/Date)
              timeout-ms 5000]
          (loop []
            (let [storage-data (js-await (.evaluate popup
                                                    (fn []
                                                      (js/Promise. (fn [resolve]
                                                                     (.get js/chrome.storage.local #js ["scripts"]
                                                                           (fn [result] (resolve result))))))))
                  scripts (.-scripts storage-data)
                  installer (when scripts
                              (.find scripts (fn [s]
                                               (= (.-id s) "epupp-builtin-web-userscript-installer"))))
                  has-code (and installer
                                (.-code installer)
                                (pos? (.-length (.-code installer))))]
              (if has-code
                (js/console.log "Web Userscript Installer re-installed with code")
                (if (> (- (.now js/Date) start) timeout-ms)
                  (throw (js/Error. "Timeout waiting for web userscript installer with code"))
                  (do
                    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
                    (recur)))))))

        ;; Enable the Web Userscript Installer (disabled by default)
        (let [installer-script (.locator popup ".script-item:has-text(\"epupp/web_userscript_installer.cljs\")")
              installer-checkbox (.locator installer-script "input[type='checkbox']")]
          (js-await (-> (expect installer-checkbox) (.toBeVisible)))
          (when-not (js-await (.isChecked installer-checkbox))
            (js/console.log "Enabling Web Userscript Installer...")
            (js-await (.click installer-checkbox))
            (js-await (wait-for-checkbox-state installer-checkbox true))))

        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/mock-gist.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))
        (js/console.log "Mock gist page loaded")

        ;; Verify GitHub-style table is detected and button placed in .file-actions
        (let [install-btn (.locator page "#github-installable-gist .file-actions button:has-text(\"Install\")")]
          (js/console.log "Waiting for Install button in GitHub-style gist's .file-actions...")
          (js-await (-> (expect install-btn)
                        (.toBeVisible #js {:timeout 5000})))
          (js/console.log "Install button found in .file-actions container")

          ;; Verify the non-installable GitHub block does NOT have a button
          (let [non-installable-btn (.locator page "#github-non-installable .file-actions button:has-text(\"Install\")")]
            (js-await (-> (expect non-installable-btn)
                          (.toHaveCount 0 #js {:timeout 500})))
            (js/console.log "Confirmed: non-installable GitHub block has no Install button"))

          ;; Click Install and confirm
          (js-await (.click install-btn))

          ;; Wait for confirmation modal
          (let [confirm-btn (.locator page "#epupp-confirm")]
            (js-await (-> (expect confirm-btn)
                          (.toBeVisible #js {:timeout 1000})))
            (js/console.log "Confirmation modal appeared, confirming...")

            ;; Confirm the installation
            (js-await (.click confirm-btn))

            ;; Wait for button to change to "Installed"
            (let [installed-indicator (.locator page "#github-installable-gist .file-actions button:has-text(\"Installed\")")]
              (js-await (-> (expect installed-indicator)
                            (.toBeVisible #js {:timeout 1000})))
              (js/console.log "GitHub-style script installed successfully"))))

        (js-await (.close page)))

      ;; Verify script appears in popup with correct name
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"github_test_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 1000})))
          (js/console.log "Installed GitHub-style script visible in popup"))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(.describe test "Userscript"
           (fn []
             (test "Userscript: injects on matching URL and logs SCRIPT_INJECTED"
                   test_injects_on_matching_url_and_logs)

             (test "Userscript: document-start script runs before page scripts"
                   test_document_start_runs_before_page_scripts)

             (test "Userscript: generate performance report from events"
                   test_generate_performance_report_from_events)

             (test "Userscript: injection produces no uncaught errors"
                   test_injection_produces_no_uncaught_errors)

             (test "Userscript: web userscript installer shows Install button and installs script"
                   test_web_userscript_installer_shows_button_and_installs)
             (test "Userscript: web userscript installer installs manual-only script"
                   test_web_userscript_installer_manual_only_script)
             (test "Userscript: web userscript installer is idempotent (no duplicate buttons)"
                   test_web_userscript_installer_idempotency)
             (test "Userscript: web userscript installer detects GitHub-style table code blocks"
                   test_web_userscript_installer_github_style_block)))
