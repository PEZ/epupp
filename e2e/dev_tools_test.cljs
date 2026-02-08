(ns dev-tools-test
  "E2E tests for the Dev Tools UI section in the popup.

   Coverage:
   - Dev Tools section visibility in dev builds
   - Sponsor username input persistence
   - Heart button URL with dev username
   - Reset Sponsor Status functionality
   - Dev log button visibility"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              wait-for-popup-ready send-runtime-message
                              assert-no-errors!]]))

;; =============================================================================
;; Dev Tools Section Visibility
;; =============================================================================

(defn- ^:async test_dev_tools_section_visible_in_dev_builds []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Dev Tools section should be visible via its data-e2e-section attribute
        (let [dev-tools-section (.locator popup "[data-e2e-section=\"dev-tools\"]")]
          (js-await (-> (expect dev-tools-section) (.toBeVisible #js {:timeout 500}))))

        ;; The dev-sponsor-username input should be present
        (let [username-input (.locator popup "#dev-sponsor-username")]
          (js-await (-> (expect username-input) (.toBeVisible #js {:timeout 500}))))

        (js-await (assert-no-errors! popup)))
      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Sponsor Username Persistence
;; =============================================================================

(defn- ^:async test_sponsor_username_persists_value []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Open popup and set a custom username
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [username-input (.locator popup "#dev-sponsor-username")]
          ;; Clear and type a custom username
          (js-await (.fill username-input "richhickey"))
          ;; Trigger blur to fire the change event
          (js-await (.dispatchEvent username-input "change"))

          ;; Close and reopen popup
          (js-await (.close popup))
          (let [popup2 (js-await (create-popup-page context ext-id))]
            (js-await (wait-for-popup-ready popup2))

            ;; Verify the username persisted
            (let [username-input2 (.locator popup2 "#dev-sponsor-username")]
              (js-await (-> (expect username-input2)
                            (.toHaveValue "richhickey" #js {:timeout 3000}))))

            (js-await (assert-no-errors! popup2)))))
      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Heart Button URL with Dev Username
;; =============================================================================

(defn- ^:async test_heart_button_uses_dev_username []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Set a custom sponsor username
        (let [username-input (.locator popup "#dev-sponsor-username")]
          (js-await (.fill username-input "richhickey"))
          (js-await (.dispatchEvent username-input "change")))

        ;; The heart click effect constructs:
        ;;   "https://github.com/sponsors/" + (username || "PEZ")
        ;; Verify the stored value via background worker message
        ;; (chrome.tabs.create is frozen and cannot be mocked)
        (let [result (js-await (send-runtime-message popup "e2e/get-storage"
                                                     #js {:key "sponsor/sponsored-username"}))]
          (js-await (-> (expect (.-value result)) (.toBe "richhickey"))))

        (js-await (assert-no-errors! popup)))
      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Reset Sponsor Status
;; =============================================================================

(defn- ^:async test_reset_sponsor_status_clears_storage []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Set sponsor status active in storage
        (js-await (send-runtime-message popup "e2e/set-storage"
                                        #js {:key "sponsorStatus" :value true}))
        (js-await (send-runtime-message popup "e2e/set-storage"
                                        #js {:key "sponsorCheckedAt" :value (.now js/Date)}))

        ;; Reload popup to pick up storage values
        (js-await (.reload popup))
        (js-await (wait-for-popup-ready popup))

        ;; Verify the heart shows as filled
        (let [heart-icon (.locator popup ".sponsor-heart .sponsor-heart-filled")]
          (js-await (-> (expect heart-icon) (.toBeVisible #js {:timeout 3000}))))

        ;; Click "Reset Sponsor Status" button
        (let [reset-btn (.locator popup "button:has-text(\"Reset Sponsor Status\")")]
          (js-await (.click reset-btn)))

        ;; Verify the heart is no longer filled
        (let [heart-icon (.locator popup ".sponsor-heart .sponsor-heart-filled")]
          (js-await (-> (expect heart-icon) (.not.toBeVisible #js {:timeout 3000}))))

        (js-await (assert-no-errors! popup)))
      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Dev Log Button
;; =============================================================================

(defn- ^:async test_dev_log_button_visible_and_clickable []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [dev-log-btn (.locator popup ".dev-log-btn")]
          (js-await (-> (expect dev-log-btn) (.toBeVisible #js {:timeout 500})))
          (js-await (-> (expect dev-log-btn) (.toBeEnabled #js {:timeout 500}))))

        (js-await (assert-no-errors! popup)))
      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Dev Username Updates Sponsor Script Match
;; =============================================================================

(defn- ^:async test_dev_username_updates_script_match []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Set sponsor username to "richhickey"
        (let [username-input (.locator popup "#dev-sponsor-username")]
          (js-await (.fill username-input "richhickey"))
          (js-await (.dispatchEvent username-input "change")))

        ;; Poll storage until the background has updated the sponsor script's match
        (js-await (-> (.poll expect
                             (fn []
                               (-> (send-runtime-message popup "e2e/get-storage" #js {:key "scripts"})
                                   (.then (fn [result]
                                            (let [scripts (or (.-value result) #js [])
                                                  sponsor (.find scripts (fn [s] (= "epupp-builtin-sponsor-check" (.-id s))))]
                                              (when sponsor
                                                (let [match (.-match sponsor)]
                                                  (when (and match (pos? (.-length match)))
                                                    (aget match 0)))))))))
                             #js {:timeout 3000})
                      (.toBe "https://github.com/sponsors/richhickey*")))

        (js-await (assert-no-errors! popup)))
      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test Registration
;; =============================================================================

(.describe test "Dev Tools: popup UI"
           (fn []
             (test "Dev Tools: section is visible in dev builds"
                   test_dev_tools_section_visible_in_dev_builds)

             (test "Dev Tools: sponsor username persists value"
                   test_sponsor_username_persists_value)

             (test "Dev Tools: heart button URL uses dev username"
                   test_heart_button_uses_dev_username)

             (test "Dev Tools: reset sponsor status clears storage"
                   test_reset_sponsor_status_clears_storage)

             (test "Dev Tools: dev log button is visible and clickable"
                   test_dev_log_button_visible_and_clickable)

             (test "Dev Tools: dev username updates sponsor script match"
                   test_dev_username_updates_script_match)))
