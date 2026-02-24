(ns e2e.web-installer.helpers
  "Helper functions for web userscript installer E2E tests.
   Consolidates common setup and selector patterns."
  (:require ["@playwright/test" :refer [expect]]
            ["./../fixtures.mjs" :as fixtures-external :refer [create-popup-page
                                                                wait-for-popup-ready]]))

;; =============================================================================
;; Install Button Selectors (using data-e2e attributes)
;; =============================================================================

(defn get-install-button
  "Get locator for install button by state and optional container.
   Uses data-e2e-install-state attribute.

   state: one of \"install\", \"update\", \"installed\", \"installing\", \"error\"
   container-selector: optional CSS selector to scope the search"
  ([page state]
   (.locator page (str "[data-e2e-install-state=\"" state "\"]")))
  ([page container-selector state]
   (.locator page (str container-selector " [data-e2e-install-state=\"" state "\"]"))))

(defn ^:async wait-for-install-button
  "Wait for install button with given state within container.
   Uses data-e2e-install-state attribute."
  [page container-selector state timeout-ms]
  (let [btn (get-install-button page container-selector state)]
    (js-await (-> (expect btn)
                  (.toBeVisible #js {:timeout timeout-ms})))
    btn))

(defn ^:async assert-no-install-button
  "Assert no install button with given state exists in container."
  [page container-selector state timeout-ms]
  (let [btn (get-install-button page container-selector state)]
    (js-await (-> (expect btn)
                  (.toHaveCount 0 #js {:timeout timeout-ms})))))

;; =============================================================================
;; Common Patterns
;; =============================================================================

(defn ^:async click-install-and-confirm!+
  "Click install button and confirm in modal. Returns when button shows expected final state.

   page: Playwright page
   container-selector: CSS selector for container with the install button
   expected-final-state: state the button should show after confirmation (e.g., \"installed\")"
  [page container-selector expected-final-state]
  (let [install-btn (get-install-button page container-selector "install")]
    (js-await (.click install-btn))

    ;; Wait for and click confirmation modal
    (let [confirm-btn (.locator page "#epupp-confirm")]
      (js-await (-> (expect confirm-btn)
                    (.toBeVisible #js {:timeout 1000})))
      (js-await (.click confirm-btn)))

    ;; Wait for button to reach expected state
    (js-await (wait-for-install-button page container-selector expected-final-state 1000))))

;; =============================================================================
;; Installer Setup
;; =============================================================================

(defn- ^:async wait-for-installer-in-storage
  "Poll until web userscript installer exists in storage with non-empty code."
  [popup timeout-ms]
  (let [start (.now js/Date)]
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
          (js/console.log "Web Userscript Installer found in storage with code")
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for web userscript installer with code"))
            (do
              (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
              (recur))))))))

(defn ^:async setup-installer!+
  "Set up web installer for testing: clear storage, wait for built-in to be
   re-installed with code.
   The installer is now background-managed (no auto-run-match) - it gets injected
   when the scanner finds userscript blocks, regardless of popup enable state.
   Returns the popup page (caller should close it when done with setup)."
  [context ext-id]
  (let [popup (js-await (create-popup-page context ext-id))]
    ;; Clear storage to ensure clean state
    (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
    (js-await (.reload popup))
    (js-await (wait-for-popup-ready popup))

    ;; Wait for installer to be re-installed with code
    (js-await (wait-for-installer-in-storage popup 2000))

    popup))

(defn ^:async navigate-to-mock-gist
  "Navigate to mock gist page and wait for it to be ready."
  [context]
  (let [page (js-await (.newPage context))]
    (js-await (.goto page "http://localhost:18080/mock-gist.html" #js {:timeout 2000}))
    (js-await (-> (expect (.locator page "#test-marker"))
                  (.toContainText "ready")))
    page))

(defn ^:async navigate-to-mock-gist-non-whitelisted
  "Navigate to mock gist page via non-whitelisted hostname.
   Uses 'not-whitelisted.test' which resolves to 127.0.0.1 via /etc/hosts
   (configured in Dockerfile.e2e entrypoint for Docker and in CI workflow)."
  [context]
  (let [page (js-await (.newPage context))]
    (js-await (.goto page "http://not-whitelisted.test:18080/mock-gist.html" #js {:timeout 5000}))
    (js-await (-> (expect (.locator page "#test-marker"))
                  (.toContainText "ready")))
    page))

(defn ^:async navigate-to-delayed-manifest
  "Navigate to delayed manifest page (simulates GitLab-like delayed DOM).
   delay-ms controls how long before the manifest element appears."
  [context delay-ms]
  (let [page (js-await (.newPage context))]
    (js-await (.goto page (str "http://localhost:18080/delayed-manifest.html?delay=" delay-ms)
                     #js {:timeout 5000}))
    (js-await (-> (expect (.locator page "#test-marker"))
                  (.toContainText "ready")))
    page))
