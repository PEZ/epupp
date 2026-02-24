(ns e2e.web-installer.security-test
  "E2E tests for web installer domain whitelist security model.
   Verifies that:
   - Whitelisted domains (localhost in Docker) can save via web-installer-save-script
   - check-script-exists works from any domain
   - save-script (REPL path) requires FS REPL Sync even with URL-based scriptSource"
  (:require ["@playwright/test" :refer [test expect]]
            ["./../fixtures.mjs" :refer [launch-browser get-extension-id create-popup-page
                                          wait-for-popup-ready send-runtime-message
                                          clear-fs-scripts assert-no-errors!]]
            ["./helpers.mjs" :as h]))

;; ============================================================
;; check-script-exists Tests
;; ============================================================

(defn- ^:async test_check_script_exists_not_found []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (clear-fs-scripts popup))

        ;; Check for a script that doesn't exist
        (let [response (js-await (send-runtime-message popup "check-script-exists"
                                                       #js {:name "nonexistent.cljs"
                                                            :code "(println \"test\")"}))]
          (-> (expect (.-success response)) (.toBe true))
          (-> (expect (.-exists response)) (.toBe false)))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_check_script_exists_identical []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup: install a script first
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Install a script
        (js-await (h/wait-for-install-button page "#installable-gist" "install" 2000))
        (js-await (h/click-install-and-confirm!+ page "#installable-gist" "installed"))
        (js-await (.close page)))

      ;; Now check if it exists with the same code
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Get the script's code from storage and check existence with identical code
        ;; Note: raw storage scripts don't have a name field (it's derived from
        ;; manifest at load time), so find by code content instead
        (let [storage-result (js-await (send-runtime-message popup "e2e/get-storage"
                                                             #js {:key "scripts"}))
              scripts (.-value storage-result)
              test-script (.find scripts (fn [s]
                                           (and (.-code s)
                                                (.includes (.-code s) "Test Installer Script"))))
              script-code (.-code test-script)
              response (js-await (send-runtime-message popup "check-script-exists"
                                                       #js {:name "test_installer_script.cljs"
                                                            :code script-code}))]
          (-> (expect (.-success response)) (.toBe true))
          (-> (expect (.-exists response)) (.toBe true))
          (-> (expect (.-identical response)) (.toBe true)))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_check_script_exists_different_code []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup: install a script first
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Install a script
        (js-await (h/wait-for-install-button page "#installable-gist" "install" 2000))
        (js-await (h/click-install-and-confirm!+ page "#installable-gist" "installed"))
        (js-await (.close page)))

      ;; Now check with different code
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [response (js-await (send-runtime-message popup "check-script-exists"
                                                       #js {:name "test_installer_script.cljs"
                                                            :code "(println \"totally different code\")"}))]
          (-> (expect (.-success response)) (.toBe true))
          (-> (expect (.-exists response)) (.toBe true))
          (-> (expect (.-identical response)) (.toBe false)))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

;; ============================================================
;; web-installer-save-script on Whitelisted Domain (localhost)
;; ============================================================

(defn- ^:async test_whitelisted_domain_can_save []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page (served on localhost, which is whitelisted)
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Install via the web installer UI
        (js-await (h/wait-for-install-button page "#installable-gist" "install" 2000))
        (js-await (h/click-install-and-confirm!+ page "#installable-gist" "installed"))
        (js-await (.close page)))

      ;; Verify the script was saved
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"test_installer_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 1000}))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

;; ============================================================
;; save-script (REPL path) FS REPL Sync enforcement
;; ============================================================

(defn- ^:async test_non_whitelisted_origin_rejected []
  ;; Send web-installer-save-script from the popup (extension page).
  ;; Extension pages have no sender.tab, so the origin check rejects them -
  ;; same code path as a non-whitelisted domain.
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [code "{:epupp/script-name \"sneaky.cljs\" :epupp/auto-run-match \"*\"}\n(println \"pwned\")"
              response (js-await (send-runtime-message popup "web-installer-save-script"
                                                       #js {:code code}))]
          ;; Should be rejected
          (-> (expect (.-success response)) (.toBe false))
          (-> (expect (.-error response)) (.toContain "Domain not allowed")))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_save_script_with_url_source_bypasses_fs_sync []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Save via REPL path with URL-based scriptSource
        ;; Web-install paths bypass the FS Sync gate (they have their own auth model)
        (let [code "{:epupp/script-name \"from_gist.cljs\" :epupp/auto-run-match \"*://example.com/*\"}\n(println \"hello\")"
              response (js-await (send-runtime-message popup "save-script"
                                                       #js {:code code
                                                            :scriptSource "https://gist.github.com/user/abc123"}))]
          ;; Should succeed - web-install path bypasses FS Sync gate
          (-> (expect (.-success response)) (.toBe true)))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

;; ============================================================
;; Test Registration
;; ============================================================

(defn- ^:async test_non_whitelisted_domain_no_installer []
  ;; Navigate to mock gist via non-whitelisted hostname.
  ;; The installer should NOT be injected because the origin is not whitelisted.
  ;; This verifies the background's origin-gating in maybe-inject-installer!.
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate via non-whitelisted hostname
      (let [page (js-await (h/navigate-to-mock-gist-non-whitelisted context))]
        ;; Wait a bit for any potential injection to complete
        (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 2000))))

        ;; No install buttons should appear (installer not injected)
        (js-await (h/assert-no-install-button page "#installable-gist" "install" 500))

        (js-await (.close page)))

      ;; Verify no errors
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(.describe test "Web Installer: security"
           (fn []
             (test "check-script-exists returns not found for missing script"
                   test_check_script_exists_not_found)

             (test "check-script-exists returns identical for matching code"
                   test_check_script_exists_identical)

             (test "check-script-exists returns not identical for different code"
                   test_check_script_exists_different_code)

             (test "whitelisted domain (localhost) can save via web-installer-save-script"
                   test_whitelisted_domain_can_save)

             (test "non-whitelisted origin is rejected by web-installer-save-script"
                   test_non_whitelisted_origin_rejected)

             (test "save-script with URL scriptSource bypasses FS Sync gate"
                   test_save_script_with_url_source_bypasses_fs_sync)

             (test "non-whitelisted domain does not inject installer"
                   test_non_whitelisted_domain_no_installer)))
