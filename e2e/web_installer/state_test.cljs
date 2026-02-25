(ns e2e.web-installer.state-test
  "E2E tests for web installer state and idempotency."
  (:require ["@playwright/test" :refer [test expect]]
            ["./../fixtures.mjs" :refer [launch-browser get-extension-id create-popup-page
                                         assert-no-errors!]]
            ["./helpers.mjs" :as h]))

(defn- ^:async test_idempotency []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Wait for install buttons - expect 8 total:
        ;; 2 original pre blocks + 1 GitHub table + 1 GitLab snippet + 1 GitHub repo
        ;; + 3 from Batch C: nested code pre, syntax highlighted pre, generic textarea
        ;; (gist edit textarea is skipped - inside .js-code-editor)
        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.toHaveCount 8 #js {:timeout 2000})))
          (let [initial-count (js-await (.count install-buttons))]
            (js/console.log "Initial button count:" initial-count)

            ;; Verify processed markers exist
            (let [processed-pres (js-await (.evaluate page
                                                      "(() => Array.from(document.querySelectorAll('pre')).filter(p => p.getAttribute('data-epupp-processed')).length)()"))]
              (js-await (-> (expect processed-pres)
                            (.toBeGreaterThan 0))))

            (let [processed-tables (js-await (.evaluate page
                                                        "(() => Array.from(document.querySelectorAll('table.js-file-line-container')).filter(t => t.getAttribute('data-epupp-processed')).length)()"))]
              (js-await (-> (expect processed-tables)
                            (.toBeGreaterThan 0))))

            ;;Verify button containers match button count
            (let [container-count (js-await (.evaluate page
                                                       "(function() { return document.querySelectorAll('.epupp-btn-container').length; })()"))]
              (js-await (-> (expect container-count)
                            (.toBe initial-count))))))
        (js-await (.close page)))

      ;; Check for errors
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_shows_installed_on_reload []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate and install
      (let [page (js-await (h/navigate-to-mock-gist context))]
        (.on page "console" (fn [msg] (js/console.log "PAGE:" (.text msg))))

        ;; Install the script
        (js-await (h/wait-for-install-button page "#installable-gist" "install" 2000))
        (js-await (h/click-install-and-confirm!+ page "#installable-gist" "installed"))
        (js/console.log "Script installed, reloading page...")

        ;; Reload the page
        (js-await (.reload page))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Should NOT show "install" button
        (js-await (h/assert-no-install-button page "#installable-gist" "install" 1000))

        ;; Should show "installed" button
        (js-await (h/wait-for-install-button page "#installable-gist" "installed" 500))
        (js/console.log "SUCCESS: Button correctly shows 'installed' after reload")

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Web Installer: state"
           (fn []
             (test "Web Installer: is idempotent (no duplicate buttons)"
                   test_idempotency)

             (test "Web Installer: shows Installed status after reload"
                   test_shows_installed_on_reload)))
