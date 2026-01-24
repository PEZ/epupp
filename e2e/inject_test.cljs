(ns e2e.inject-test
  "E2E tests for the :epupp/inject feature (Scittle library injection).

   These tests verify that:
   1. Scripts with `:epupp/inject` are saved correctly to storage
   2. The background worker reads the inject field from storage
   3. Libraries are injected before userscript execution"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page wait-for-event
                              get-test-events wait-for-save-status wait-for-popup-ready
                              clear-test-events! assert-no-errors!]]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata including inject."
  [{:keys [name match description run-at inject code]
    :or {code "(println \"Test script\")"}}]
  (let [;; Format inject as vector string
        inject-str (when inject
                     (str "[" (str/join " " (map #(str "\"" % "\"") inject)) "]"))
        meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/site-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\""))
                     inject (conj (str ":epupp/inject " inject-str)))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

;; =============================================================================
;; Test: Inject field persisted to storage
;; =============================================================================

(defn- ^:async test_inject_field_persisted_to_storage []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create a script with inject in manifest
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Inject Test"
                                      :match "http://localhost:18080/*"
                                      :inject ["scittle://reagent.js"]
                                      :code "(js/console.log \"Has reagent!\")"})]
        (js/console.log "Test code:" code)
        (js-await (.fill (.locator panel "#code-area") code))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; Read storage to verify inject was saved
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
              our-script (.find scripts (fn [s] (= (.-name s) "inject_test.cljs")))]

          ;; Verify the script exists
          (js-await (-> (expect our-script) (.toBeTruthy)))
          (js/console.log "Our script:" (js/JSON.stringify our-script nil 2))

          ;; CRITICAL: Verify inject field was saved
          (js-await (-> (expect (.-inject our-script)) (.toBeTruthy)))
          (js-await (-> (expect (aget (.-inject our-script) 0)) (.toBe "scittle://reagent.js"))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: INJECTING_LIBS event is emitted
;; =============================================================================

(defn- ^:async test_injecting_libs_event_emitted []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create a script with inject
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Inject Event Test"
                                      :match "http://localhost:18080/*"
                                      :inject ["scittle://pprint.js"]
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

        (let [script-item (.locator popup ".script-item:has-text(\"inject_event_test.cljs\")")
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

          ;; Now check for INJECTING_LIBS event (should have happened before SCRIPT_INJECTED)
          (let [events (js-await (get-test-events popup))
                inject-events (.filter events (fn [e] (= (.-event e) "INJECTING_LIBS")))]
            (js/console.log "All events:" (js/JSON.stringify events nil 2))
            (js/console.log "INJECTING_LIBS events:" (js/JSON.stringify inject-events nil 2))

            ;; Should have at least one INJECTING_LIBS event
            (js-await (-> (expect (.-length inject-events)) (.toBeGreaterThanOrEqual 1)))

            ;; Event should list the pprint file
            (let [first-event (aget inject-events 0)
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
      ;; Create a script injecting reagent
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Reagent DOM Test"
                                      :match "http://localhost:18080/*"
                                      :inject ["scittle://reagent.js"]
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
;; Test: No INJECTING_LIBS when script has no inject
;; =============================================================================

(defn- ^:async test_no_injecting_libs_when_script_has_no_inject []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Create a script WITHOUT inject
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "No Inject Test"
                                      :match "http://localhost:18080/*"
                                      ;; Note: no :inject field
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

        (let [script-item (.locator popup ".script-item:has-text(\"no_inject_test.cljs\")")
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

        ;; Check for events - should NOT have INJECTING_LIBS
        (let [popup (js-await (create-popup-page context ext-id))
              events (js-await (get-test-events popup))
              inject-events (.filter events (fn [e] (= (.-event e) "INJECTING_LIBS")))]
          (js/console.log "Events:" (js/JSON.stringify events nil 2))

          ;; Should have NO INJECTING_LIBS events (script has no inject)
          (js-await (-> (expect (.-length inject-events)) (.toBe 0)))

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
      ;; Create a script injecting pprint
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest {:name "Pprint DOM Test"
                                      :match "http://localhost:18080/*"
                                      :inject ["scittle://pprint.js"]
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
;; - Panel evaluation with :epupp/inject (tests the inject-libs message path)
;; - Popup Run button (uses mock tab ID in tests, requires different test approach)
;;
;; The current tests cover:
;; 1. Storage persistence of :script/inject field
;; 2. INJECTING_LIBS event emission via auto-injection
;; 3. Reagent (with React deps) script tags in DOM
;; 4. Negative case: no INJECTING_LIBS for scripts without inject
;; 5. Pprint script tags in DOM (verifies non-React library loading)
;; =============================================================================

(.describe test "Inject"
           (fn []
             (test "Inject: script with :epupp/inject is saved with inject field"
                   test_inject_field_persisted_to_storage)

             (test "Inject: INJECTING_LIBS event emitted when script has inject"
                   test_injecting_libs_event_emitted)

             (test "Inject: Reagent library files are injected into page DOM"
                   test_reagent_library_files_injected)

             (test "Inject: no INJECTING_LIBS event when script has no inject"
                   test_no_injecting_libs_when_script_has_no_inject)

             (test "Inject: pprint library script tags are injected into page DOM"
                   test_pprint_library_script_tags_injected)))
