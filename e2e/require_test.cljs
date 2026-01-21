(ns e2e.require-test
  "E2E tests for the :epupp/require feature (Scittle library dependencies).

   These tests verify that:
   1. Scripts with `:epupp/require` are saved correctly to storage
   2. The background worker reads the require field from storage
   3. Libraries are injected before userscript execution"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page wait-for-event
                              get-test-events wait-for-save-status wait-for-popup-ready
                              clear-test-events! assert-no-errors!]]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata including require."
  [{:keys [name match description run-at require code]
    :or {code "(println \"Test script\")"}}]
  (let [;; Format require as vector string
        require-str (when require
                      (str "[" (str/join " " (map #(str "\"" % "\"") require)) "]"))
        meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/site-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\""))
                     require (conj (str ":epupp/require " require-str)))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

;; =============================================================================
;; Test: Require field persisted to storage
;; =============================================================================

(defn- ^:async test_require_field_persisted_to_storage []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create a script with require in manifest
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Require Test"
                                      :match "http://localhost:18080/*"
                                      :require ["scittle://reagent.js"]
                                      :code "(js/console.log \"Has reagent!\")"})]
        (js/console.log "Test code:" code)
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Read storage to verify require was saved
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Read scripts from chrome.storage.local
        (let [storage-data (js-await (.evaluate popup
                                                (fn []
                                                  (js/Promise. (fn [resolve]
                                                                 (.get js/chrome.storage.local #js ["scripts"]
                                                                       (fn [result]
                                                                         (resolve result))))))))
              scripts (.-scripts storage-data)
              _ (js/console.log "Storage scripts:" (js/JSON.stringify scripts nil 2))
              our-script (.find scripts (fn [s] (= (.-name s) "require_test.cljs")))]

          ;; Verify the script exists
          (js-await (-> (expect our-script) (.toBeTruthy)))
          (js/console.log "Our script:" (js/JSON.stringify our-script nil 2))

          ;; CRITICAL: Verify require field was saved
          (js-await (-> (expect (.-require our-script)) (.toBeTruthy)))
          (js-await (-> (expect (aget (.-require our-script) 0)) (.toBe "scittle://reagent.js"))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: INJECTING_REQUIRES event is emitted
;; =============================================================================

(defn- ^:async test_injecting_requires_event_emitted []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create a script with require
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Require Event Test"
                                      :match "http://localhost:18080/*"
                                      :require ["scittle://pprint.js"]
                                      :code "(js/console.log \"Has pprint!\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable script via popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"require_event_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (-> (expect script-item) (.toBeVisible)))
          (when-not (js-await (.isChecked checkbox))
            (js-await (.click checkbox))))

        ;; Clear test events before navigation
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      ;; Navigate to trigger injection
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Open popup to get events - wait for SCRIPT_INJECTED first to ensure injection completed
        (let [popup (js-await (create-popup-page context ext-id))]
          (js/console.log "Waiting for SCRIPT_INJECTED event (page still open)...")
          (js-await (wait-for-event popup "SCRIPT_INJECTED" 10000))

          ;; Now check for INJECTING_REQUIRES event (should have happened before SCRIPT_INJECTED)
          (let [events (js-await (get-test-events popup))
                require-events (.filter events (fn [e] (= (.-event e) "INJECTING_REQUIRES")))]
            (js/console.log "All events:" (js/JSON.stringify events nil 2))
            (js/console.log "INJECTING_REQUIRES events:" (js/JSON.stringify require-events nil 2))

            ;; Should have at least one INJECTING_REQUIRES event
            (js-await (-> (expect (.-length require-events)) (.toBeGreaterThanOrEqual 1)))

            ;; Event should list the pprint file
            (let [first-event (aget require-events 0)
                  files (.-files (.-data first-event))]
              (js/console.log "Injected files:" (js/JSON.stringify files))
              (js-await (-> (expect (.some files (fn [f] (.includes f "pprint")))) (.toBe true)))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Reagent libraries actually injected into DOM
;; =============================================================================

(defn- ^:async test_reagent_library_files_injected []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create a script requiring reagent
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Reagent DOM Test"
                                      :match "http://localhost:18080/*"
                                      :require ["scittle://reagent.js"]
                                      :code "(js/console.log \"Reagent loaded?\" (boolean (aget js/window \"React\")))"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable script via popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"reagent_dom_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (-> (expect script-item) (.toBeVisible)))
          (when-not (js-await (.isChecked checkbox))
            (js-await (.click checkbox))))
        (js-await (.close popup)))

      ;; Navigate to trigger injection
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Wait for Reagent script tag to appear (poll instead of fixed 2s sleep)
        (let [wait-for-script (fn wait-for []
                                (js/Promise.
                                 (fn [resolve reject]
                                   (let [check (fn check [attempts]
                                                 (-> (.evaluate page
                                                                (fn []
                                                                  (let [scripts (js/document.querySelectorAll "script[src*='reagent']")]
                                                                    (pos? (.-length scripts)))))
                                                     (.then (fn [found]
                                                              (if found
                                                                (resolve true)
                                                                (if (>= attempts 50) ;; 5 second max
                                                                  (reject (js/Error. "Timeout waiting for reagent script"))
                                                                  (js/setTimeout #(check (inc attempts)) 100)))))))]
                                     (check 0)))))]
          (js-await (wait-for-script)))

        ;; Check for Reagent script tags in DOM
        (let [script-tags (js-await (.evaluate page
                                               (fn []
                                                 (let [scripts (js/document.querySelectorAll "script[src]")
                                                       urls (js/Array.from scripts (fn [s] (.-src s)))]
                                                   urls))))]
          (js/console.log "Script tags in DOM:" (js/JSON.stringify script-tags nil 2))

          ;; Should have React scripts
          (let [has-react (.some script-tags (fn [url] (.includes url "react.production")))]
            (js/console.log "Has React:" has-react)
            (js-await (-> (expect has-react) (.toBe true))))

          ;; Should have Reagent script
          (let [has-reagent (.some script-tags (fn [url] (.includes url "scittle.reagent")))]
            (js/console.log "Has Reagent:" has-reagent)
            (js-await (-> (expect has-reagent) (.toBe true)))))

        ;; Check for errors before closing
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: No INJECTING_REQUIRES when script has no require
;; =============================================================================

(defn- ^:async test_no_injecting_requires_when_script_has_no_require []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create a script WITHOUT require
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "No Require Test"
                                      :match "http://localhost:18080/*"
                                      ;; Note: no :require field
                                      :code "(js/console.log \"No deps!\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable script via popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"no_require_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (-> (expect script-item) (.toBeVisible)))
          (when-not (js-await (.isChecked checkbox))
            (js-await (.click checkbox))))

        ;; Clear test events before navigation
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      ;; Navigate to trigger injection
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Check for events - should NOT have INJECTING_REQUIRES
        (let [popup (js-await (create-popup-page context ext-id))
              events (js-await (get-test-events popup))
              require-events (.filter events (fn [e] (= (.-event e) "INJECTING_REQUIRES")))]
          (js/console.log "Events:" (js/JSON.stringify events nil 2))

          ;; Should have NO INJECTING_REQUIRES events (script has no require)
          (js-await (-> (expect (.-length require-events)) (.toBe 0)))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: pprint library script tags injected into DOM
;; =============================================================================

(defn- ^:async test_pprint_library_script_tags_injected []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create a script requiring pprint
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Pprint DOM Test"
                                      :match "http://localhost:18080/*"
                                      :require ["scittle://pprint.js"]
                                      :code "(js/console.log \"pprint loaded\")"})]
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Enable script via popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.addInitScript popup "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';"))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"pprint_dom_test.cljs\")")
              checkbox (.locator script-item "input[type='checkbox']")]
          (js-await (-> (expect script-item) (.toBeVisible)))
          (when-not (js-await (.isChecked checkbox))
            (js-await (.click checkbox))))
        (js-await (.close popup)))

      ;; Navigate to trigger injection
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Wait for script injection to complete
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-event popup "SCRIPT_INJECTED" 10000))
          (js-await (.close popup)))

        ;; Check for pprint script tag in DOM
        (let [script-tags (js-await (.evaluate page
                                               (fn []
                                                 (let [scripts (js/document.querySelectorAll "script[src]")
                                                       urls (js/Array.from scripts (fn [s] (.-src s)))]
                                                   urls))))]
          (js/console.log "Script tags in DOM:" (js/JSON.stringify script-tags nil 2))

          ;; Should have pprint script
          (let [has-pprint (.some script-tags (fn [url] (.includes url "scittle.pprint")))]
            (js/console.log "Has pprint:" has-pprint)
            (js-await (-> (expect has-pprint) (.toBe true)))))

        ;; Check for errors before closing
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Note: Additional test scenarios documented in scittle-dependencies-implementation.md:
;; - Panel evaluation with :epupp/require (tests the inject-requires message path)
;; - Popup Run button (uses mock tab ID in tests, requires different test approach)
;;
;; The current tests cover:
;; 1. Storage persistence of :script/require field
;; 2. INJECTING_REQUIRES event emission via auto-injection
;; 3. Reagent (with React deps) script tags in DOM
;; 4. Negative case: no INJECTING_REQUIRES for scripts without require
;; 5. Pprint script tags in DOM (verifies non-React library loading)
;; =============================================================================

(.describe test "Require"
           (fn []
             (test "Require: script with :epupp/require is saved with require field"
                   test_require_field_persisted_to_storage)

             (test "Require: INJECTING_REQUIRES event emitted when script has require"
                   test_injecting_requires_event_emitted)

             (test "Require: Reagent library files are injected into page DOM"
                   test_reagent_library_files_injected)

             (test "Require: no INJECTING_REQUIRES event when script has no require"
                   test_no_injecting_requires_when_script_has_no_require)

             (test "Require: pprint library script tags are injected into page DOM"
                   test_pprint_library_script_tags_injected)))
