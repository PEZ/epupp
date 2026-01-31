(ns e2e.settings-test
  "E2E tests for popup settings - origin management."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              clear-storage wait-for-popup-ready]]))

(defn- ^:async test_settings_view_and_origin_management []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Open settings section (now collapsible, not separate view) ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-storage popup))
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        ;; Settings section header visible (collapsed by default)
        (let [settings-header (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")
              settings-content (.locator popup ".settings-content")]
          (js-await (-> (expect settings-header) (.toBeVisible)))

          ;; Click to expand settings and wait for content
          (js-await (.click settings-header))
          (js-await (-> (expect settings-content) (.toBeVisible))))

        ;; Settings content renders with section titles (2 sections now)
        (js-await (-> (expect (.locator popup ".settings-section-title:text(\"Web Installer Sites\")"))
                      (.toBeVisible)))

        ;; Default origins list shows config origins
        (js-await (-> (expect (.locator popup ".origin-item-default"))
                      (.toHaveCount 7))) ;; dev config has 7 origins

        ;; No user origins initially
        (js-await (-> (expect (.locator popup ".no-origins"))
                      (.toContainText "No custom origins")))

        (js-await (.close popup)))

      ;; === PHASE 2: Add custom origin ===
      (let [popup (js-await (create-popup-page context ext-id))
            settings-content (.locator popup ".settings-content")]
        ;; Expand settings section
        (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
        (js-await (-> (expect settings-content) (.toBeVisible)))

        ;; Fill in valid origin and click Add
        (let [input (.locator popup ".add-origin-form input")
              add-btn (.locator popup "button.add-btn")
              user-origins (.locator popup ".origins-section:has(.origins-label:text(\"Your custom origins\")) .origin-item")]
          (js-await (.fill input "https://git.example.com/"))
          (js-await (.click add-btn))
          ;; Wait for origin to appear in user list
          (js-await (-> (expect user-origins) (.toHaveCount 1)))
          (js-await (-> (expect (.first user-origins)) (.toContainText "https://git.example.com/"))))

        ;; Input cleared
        (js-await (-> (expect (.locator popup ".add-origin-form input"))
                      (.toHaveValue "")))

        (js-await (.close popup)))

      ;; === PHASE 3: Invalid origin shows error ===
      (let [popup (js-await (create-popup-page context ext-id))
            settings-content (.locator popup ".settings-content")]
        (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
        (js-await (-> (expect settings-content) (.toBeVisible)))

        ;; Try adding invalid origin (no trailing slash)
        (let [input (.locator popup ".add-origin-form input")
              add-btn (.locator popup "button.add-btn")]
          (js-await (.fill input "https://invalid.com"))
          (js-await (.click add-btn)))

        ;; Error message appears in system banner (Playwright auto-waits)
        (js-await (-> (expect (.locator popup ".fs-error-banner"))
                      (.toBeVisible)))
        (js-await (-> (expect (.locator popup ".fs-error-banner"))
                      (.toContainText "http:// or https://")))

        (js-await (.close popup)))

      ;; === PHASE 4: Remove custom origin ===
      (let [popup (js-await (create-popup-page context ext-id))
            settings-content (.locator popup ".settings-content")]
        (js-await (.click (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\")) .section-header")))
        (js-await (-> (expect settings-content) (.toBeVisible)))

        ;; Verify origin still exists from phase 2
        (let [user-origins (.locator popup ".origins-section:has(.origins-label:text(\"Your custom origins\")) .origin-item")]
          (js-await (-> (expect user-origins) (.toHaveCount 1)))

          ;; Click delete button
          (let [delete-btn (.locator (.first user-origins) "button.origin-delete")]
            (js-await (.click delete-btn)))

          ;; Origin removed, shows empty message (Playwright auto-waits)
          (js-await (-> (expect user-origins) (.toHaveCount 0)))
          (js-await (-> (expect (.locator popup ".no-origins"))
                        (.toBeVisible))))

        (js-await (.close popup)))

      ;; === PHASE 5: Collapse/expand toggle works ===
      (let [popup (js-await (create-popup-page context ext-id))
            settings-section (.locator popup ".collapsible-section:has(.section-title:text(\"Settings\"))")
            settings-header (.locator settings-section ".section-header")
            settings-content (.locator popup ".settings-content")]
        ;; Settings starts collapsed (check class, not visibility - content stays in DOM for animations)
        (js-await (-> (expect settings-section) (.toHaveClass #"collapsed")))

        ;; Expand - wait for content to be visible
        (js-await (.click settings-header))
        (js-await (-> (expect settings-section) (.not.toHaveClass #"collapsed")))
        (js-await (-> (expect settings-content) (.toBeVisible)))

        ;; Collapse again - check class
        (js-await (.click settings-header))
        (js-await (-> (expect settings-section) (.toHaveClass #"collapsed")))

        ;; REPL Connect section is expanded by default
        (js-await (-> (expect (.locator popup "#nrepl-port"))
                      (.toBeVisible)))

        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(test "Settings: view and origin management"
      test_settings_view_and_origin_management)
