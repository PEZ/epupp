(ns e2e.web-installer.format-test
  "E2E tests for web installer format detection (GitHub, GitLab, etc.)."
  (:require ["@playwright/test" :refer [test expect]]
            ["./../fixtures.mjs" :refer [launch-browser get-extension-id create-popup-page
                                          wait-for-popup-ready assert-no-errors!]]
            ["./helpers.mjs" :as h]))

(defn- ^:async test_github_style_block []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Verify GitHub-style table button is in .file-actions
        (let [install-btn (.locator page "#github-installable-gist .file-actions [data-e2e-install-state=\"install\"]")]
          (js-await (-> (expect install-btn)
                        (.toBeVisible #js {:timeout 2000})))
          (js/console.log "Install button found in .file-actions container")

          ;; Verify non-installable GitHub block has NO button
          (js-await (h/assert-no-install-button page "#github-non-installable .file-actions" "install" 500))
          (js/console.log "Confirmed: non-installable GitHub block has no Install button")

          ;; Install and verify
          (js-await (.click install-btn))

          (let [confirm-btn (.locator page "#epupp-confirm")]
            (js-await (-> (expect confirm-btn)
                          (.toBeVisible #js {:timeout 1000})))
            (js-await (.click confirm-btn)))

          (js-await (h/wait-for-install-button page "#github-installable-gist .file-actions" "installed" 1000))
          (js/console.log "GitHub-style script installed successfully"))

        (js-await (.close page)))

      ;; Verify in popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"github_test_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 1000}))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_gitlab_button_placement []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Verify GitLab-style snippet button is in .file-actions
        (js-await (h/wait-for-install-button page "#gitlab-installable-snippet .file-actions" "install" 2000))
        (js/console.log "Install button found in GitLab .file-actions container")

        ;; Verify non-installable GitLab block has NO button
        (js-await (h/assert-no-install-button page "#gitlab-non-installable .file-actions" "install" 500))
        (js/console.log "Confirmed: non-installable GitLab block has no Install button")

        ;; Install and verify
        (js-await (h/click-install-and-confirm!+ page "#gitlab-installable-snippet .file-actions" "installed"))
        (js/console.log "GitLab-style script installed successfully")

        (js-await (.close page)))

      ;; Verify in popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"gitlab_test_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 1000}))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_github_repo_button_placement []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Verify GitHub repo button appears (the installer places it near
        ;; the ButtonGroup, in the parent container)
        (js-await (h/wait-for-install-button page "#github-repo-installable" "install" 2000))
        (js/console.log "Install button found in GitHub repo container")

        ;; Install and confirm using helper
        (js-await (h/click-install-and-confirm!+ page "#github-repo-installable" "installed"))
        (js/console.log "GitHub repo script installed successfully")

        (js-await (.close page)))

      ;; Verify in popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"github_repo_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 1000}))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_github_gist_edit_skipped []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Verify NO install button appears for gist edit textarea
        ;; (code editor textareas are skipped)
        (js-await (h/assert-no-install-button page "#github-gist-edit-textarea" "install" 2000))
        (js/console.log "Confirmed: gist edit textarea correctly skipped (no install button)")

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Web Installer: format detection"
           (fn []
             (test "Web Installer: detects GitHub-style table code blocks"
                   test_github_style_block)

             (test "Web Installer: places GitLab buttons in .file-actions"
                   test_gitlab_button_placement)

             (test "Web Installer: places GitHub repo buttons in ButtonGroup"
                   test_github_repo_button_placement)

             (test "Web Installer: skips gist edit textareas (code editor)"
                   test_github_gist_edit_skipped)))
