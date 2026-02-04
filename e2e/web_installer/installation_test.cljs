(ns e2e.web-installer.installation-test
  "E2E tests for basic web userscript installation flows."
  (:require ["@playwright/test" :refer [test expect]]
            ["./../fixtures.mjs" :refer [launch-browser get-extension-id create-popup-page
                                          wait-for-popup-ready assert-no-errors!]]
            ["./helpers.mjs" :as h]))

(defn- ^:async test_shows_button_and_installs []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Wait for Install button to appear
        (js-await (h/wait-for-install-button page "#installable-gist" "install" 2000))
        (js/console.log "Install button found")

        ;; Click install and confirm
        (js-await (h/click-install-and-confirm!+ page "#installable-gist" "installed"))
        (js/console.log "Script installed successfully")

        (js-await (.close page)))

      ;; Verify script appears in popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"test_installer_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 1000}))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_manual_only_script []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Install the manual-only script
        (js-await (h/wait-for-install-button page "#manual-only-gist" "install" 2000))
        (js-await (h/click-install-and-confirm!+ page "#manual-only-gist" "installed"))
        (js/console.log "Manual-only script installed")

        (js-await (.close page)))

      ;; Verify script shows "No auto-run (manual only)"
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"manual_only_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 1000})))

          ;; Verify it shows "No auto-run (manual only)"
          (let [match-span (.locator script-item ".script-match")]
            (js-await (-> (expect match-span)
                          (.toHaveText "No auto-run (manual only)" #js {:timeout 500})))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(.describe test "Web Installer: installation"
           (fn []
             (test "Web Installer: shows Install button and installs script"
                   test_shows_button_and_installs)

             (test "Web Installer: installs manual-only script"
                   test_manual_only_script)))
