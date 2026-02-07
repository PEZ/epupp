(ns e2e.sponsor-builtin-test
  "E2E tests for built-in sponsor script presence and behavior in popup."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              get-script-item
                              send-runtime-message assert-no-errors!]]))

(def ^:private builtin-id "epupp-builtin-sponsor-check")
(def ^:private builtin-name "epupp/sponsor.cljs")

(defn- ^:async get-sponsor-builtin
  "Return the sponsor built-in script from storage, or nil if missing."
  [popup]
  (let [result (js-await (send-runtime-message popup "e2e/get-storage" #js {:key "scripts"}))
        scripts (or (.-value result) #js [])]
    (.find scripts (fn [script] (= (.-id script) builtin-id)))))

;; =============================================================================
;; Tests
;; =============================================================================

(defn- ^:async test_sponsor_script_visible_in_popup []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))
            item (get-script-item popup builtin-name)]
        (js-await (-> (expect item) (.toBeVisible #js {:timeout 3000})))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_sponsor_script_has_no_delete_button []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))
            item (get-script-item popup builtin-name)
            delete-btn (.locator item ".script-delete")]
        (js-await (-> (expect item) (.toBeVisible #js {:timeout 3000})))
        (js-await (-> (expect delete-btn) (.toHaveCount 0)))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_sponsor_script_synced_to_storage []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))
            script (js-await (get-sponsor-builtin popup))]
        (js-await (-> (expect (some? script)) (.toBe true)))
        (js-await (-> (expect (.-builtin script)) (.toBe true)))
        (js-await (-> (expect (.includes (.-code script) "epupp.sponsor")) (.toBe true)))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_sponsor_script_has_no_toggle_checkbox []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))
            item (get-script-item popup builtin-name)
            checkbox (.locator item ".pattern-checkbox")]
        (js-await (-> (expect item) (.toBeVisible #js {:timeout 3000})))
        (js-await (-> (expect checkbox) (.toHaveCount 0)))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_sponsor_script_always_enabled_in_storage []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))
            script (js-await (get-sponsor-builtin popup))]
        (js-await (-> (expect (some? script)) (.toBe true)))
        (js-await (-> (expect (.-enabled script)) (.toBe true)))
        (js-await (-> (expect (.-alwaysEnabled script)) (.toBe true)))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(.describe test "Sponsor builtin"
           (fn []
             (test "Sponsor script is visible in popup script list"
                   test_sponsor_script_visible_in_popup)

             (test "Sponsor script has no delete button (builtin)"
                   test_sponsor_script_has_no_delete_button)

             (test "Sponsor script is synced to storage on init"
                   test_sponsor_script_synced_to_storage)

             (test "Sponsor script has no toggle checkbox"
                   test_sponsor_script_has_no_toggle_checkbox)

             (test "Sponsor script is always enabled in storage"
                   test_sponsor_script_always_enabled_in_storage)))
