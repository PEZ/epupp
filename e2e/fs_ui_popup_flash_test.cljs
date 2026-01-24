(ns e2e.fs-ui-popup-flash-test
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [assert-no-errors! wait-for-popup-ready]]
            [fs-ui-reactivity-helpers :as helpers]))

(defn- ^:async popup_flashes_on_save_conflict []
  (let [test-code "{:epupp/script-name \"no-overwrite\"\n                                       :epupp/auto-run-match \"https://no-overwrite.com/*\"}\n                                      (ns no-overwrite)"
        save-code (str "(def !no-overwrite-setup (atom :pending))\n"
                       "(-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n"
                       "  (.then (fn [r] (reset! !no-overwrite-setup r))))\n"
                       ":setup-done")
        conflict-code (str "(def !no-overwrite-conflict (atom :pending))\n"
                            "(-> (epupp.fs/save! " (pr-str test-code) ")\n"
                            "  (.then (fn [r] (reset! !no-overwrite-conflict {:resolved r})))\n"
                            "  (.catch (fn [e] (reset! !no-overwrite-conflict {:rejected (.-message e)}))))\n"
                            ":setup-done")]
    ;; Create a script to conflict with
    (let [setup-result (js-await (helpers/eval-in-browser save-code))]
      (-> (expect (.-success setup-result)) (.toBe true)))
    (js-await (helpers/wait-for-eval-promise "!no-overwrite-setup" 3000))

    (let [popup (js-await (.newPage (helpers/get-context)))]
      (js-await (.goto popup
                       (str "chrome-extension://" (helpers/get-ext-id) "/popup.html")
                       #js {:waitUntil "networkidle"}))
      (js-await (wait-for-popup-ready popup))

      ;; Verify script exists and does NOT have flash class initially
      (let [script-item (.locator popup ".script-item:not(.leaving):has-text(\"no_overwrite.cljs\")")]
        (js-await (-> (expect script-item) (.toBeVisible #js {:timeout 2000})))
        (js-await (-> (expect script-item) (.not.toHaveClass (js/RegExp. "script-item-fs-modified")))))

      ;; Attempt save without force - should reject and flash
      (let [conflict-result (js-await (helpers/eval-in-browser conflict-code))]
        (-> (expect (.-success conflict-result)) (.toBe true)))
      (js-await (helpers/wait-for-eval-promise "!no-overwrite-conflict" 3000))

      ;; The script item should now have the flash animation class
      (let [script-item (.locator popup ".script-item:not(.leaving):has-text(\"no_overwrite.cljs\")")]
        (js-await (-> (expect script-item)
                      (.toHaveClass (js/RegExp. "script-item-fs-modified") #js {:timeout 2000}))))

      (js-await (assert-no-errors! popup))
      (js-await (.close popup)))

    ;; Cleanup
    (js-await (helpers/eval-in-browser "(epupp.fs/rm! \"no_overwrite.cljs\")"))
    (js-await (helpers/sleep 100))))

(defn- ^:async popup_shows_flash_animation_on_script_modification []
  ;; First create a script to modify
  (let [test-code "{:epupp/script-name \"flash-test-script\"\n                                       :epupp/auto-run-match \"https://flash-test.com/*\"}\n                                      (ns flash-test)"
        save-code (str "(def !flash-setup (atom :pending))\n"
                       "(-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n"
                       "  (.then (fn [r] (reset! !flash-setup r))))\n"
                       ":setup-done")]
    (let [setup-result (js-await (helpers/eval-in-browser save-code))]
      (-> (expect (.-success setup-result)) (.toBe true)))
    (js-await (helpers/wait-for-eval-promise "!flash-setup" 3000)))

  ;; Open popup and verify script exists
  (let [popup (js-await (.newPage (helpers/get-context)))]
    (js-await (.goto popup
                     (str "chrome-extension://" (helpers/get-ext-id) "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (wait-for-popup-ready popup))

    ;; Verify script exists and does NOT have flash class initially
    (let [script-item (.locator popup ".script-item:not(.leaving):has-text(\"flash_test_script.cljs\")")]
      (js-await (-> (expect script-item) (.toBeVisible #js {:timeout 2000})))
      (js-await (-> (expect script-item) (.not.toHaveClass (js/RegExp. "script-item-fs-modified")))))

    ;; Modify the script via REPL (save with force to update existing)
    (let [updated-code "{:epupp/script-name \"flash-test-script\"\n                                       :epupp/auto-run-match \"https://flash-test.com/*\"}\n                                      (ns flash-test)\n                                      (js/console.log \"Updated!\")"
          update-code (str "(def !flash-update (atom :pending))\n"
                           "(-> (epupp.fs/save! " (pr-str updated-code) " {:fs/force? true})\n"
                           "  (.then (fn [r] (reset! !flash-update r))))\n"
                           ":setup-done")]
      (let [update-result (js-await (helpers/eval-in-browser update-code))]
        (-> (expect (.-success update-result)) (.toBe true)))
      (js-await (helpers/wait-for-eval-promise "!flash-update" 3000)))

    ;; The script item should now have the flash animation class
    (let [script-item (.locator popup ".script-item:not(.leaving):has-text(\"flash_test_script.cljs\")")]
      (js-await (-> (expect script-item)
                    (.toHaveClass (js/RegExp. "script-item-fs-modified") #js {:timeout 2000}))))

    ;; Note: We don't test auto-clear timing to avoid 2+ second waits.
    ;; CSS handles the visual duration; we trust the deferred dispatch works.

    (js-await (assert-no-errors! popup))
    (js-await (.close popup)))

  ;; Cleanup
  (js-await (helpers/eval-in-browser "(epupp.fs/rm! \"flash_test_script.cljs\")"))
  (js-await (helpers/sleep 100)))

(.describe test "REPL FS UI: popup flashes"
           (fn []
             (.beforeAll test (fn [] (helpers/setup-browser!)))

             (.afterAll test
                        (fn []
                          (helpers/close-browser!)))

             (test "REPL FS UI: popup flashes on save conflict"
                   popup_flashes_on_save_conflict)

             (test "REPL FS UI: popup shows flash animation on script modification"
                   popup_shows_flash_animation_on_script_modification)))
