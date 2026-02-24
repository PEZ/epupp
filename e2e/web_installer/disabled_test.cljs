(ns e2e.web-installer.disabled-test
  "E2E test verifying that a disabled web installer does NOT inject.
   When the user unchecks the installer in the popup, navigating to a page
   with valid userscript manifests on a whitelisted origin should NOT
   produce install buttons."
  (:require ["@playwright/test" :refer [test expect]]
            ["./../fixtures.mjs" :refer [launch-browser get-extension-id create-popup-page
                                         wait-for-popup-ready wait-for-checkbox-state
                                         assert-no-errors!]]
            ["./helpers.mjs" :as h]))

;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private installer-id "epupp-builtin-web-userscript-installer")

(defn- ^:async disable-installer-in-popup!
  "Find the web installer script item in the popup and uncheck its enable checkbox.
   Returns the popup page."
  [popup]
  (let [script-item (.locator popup (str "[data-e2e-script-id=\"" installer-id "\"]"))
        checkbox (.locator script-item "input[type='checkbox']")]
    ;; Verify the installer item exists
    (js-await (-> (expect script-item) (.toBeVisible #js {:timeout 2000})))
    ;; Ensure checkbox is currently checked (enabled), then uncheck it
    (js-await (-> (expect checkbox) (.toBeChecked #js {:timeout 1000})))
    (js-await (.click checkbox))
    (js-await (wait-for-checkbox-state checkbox false))
    popup))

;; =============================================================================
;; Test: Disabled installer does not inject on manifest pages
;; =============================================================================

(defn- ^:async test_disabled_installer_no_injection []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup: clear storage, wait for builtin installer to be ready
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        ;; Disable the web installer via popup checkbox
        (js-await (disable-installer-in-popup! popup))
        (js-await (.close popup)))

      ;; Navigate to mock gist page (has manifests on whitelisted origin)
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Wait enough time for the scanner to have run
        ;; This is an absence assertion - we need to wait to prove nothing happens
        (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 1000))))

        ;; Verify no install buttons appeared
        (let [install-buttons (.locator page "[data-e2e-install-state]")]
          (js-await (-> (expect install-buttons) (.toHaveCount 0))))

        (js-await (.close page)))

      ;; Check for errors via popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Registration
;; =============================================================================

(.describe test "Web Installer: disabled"
           (fn []
             (test "disabled installer does not inject on manifest pages"
                   test_disabled_installer_no_injection)))
