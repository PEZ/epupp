(ns e2e.fs-ui-popup-refresh-test
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [assert-no-errors! wait-for-script-count wait-for-popup-ready]]
            [fs-ui-reactivity-helpers :as helpers]))

(defn- ^:async popup_refreshes_script_list_after_save []
  ;; Open popup and count initial scripts
  (let [popup (js-await (.newPage (helpers/get-context)))]
    (js-await (.goto popup
                     (str "chrome-extension://" (helpers/get-ext-id) "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (wait-for-popup-ready popup))

    ;; Get initial script count
    (let [initial-items (.locator popup ".script-item")
          initial-count (js-await (.count initial-items))]
      (js/console.log "=== Initial script count ===" initial-count)

      ;; Create a new script via REPL fs API
      (let [test-code "{:epupp/script-name \"ui-reactivity-test-script\"\n                                           :epupp/auto-run-match \"https://reactivity-test.com/*\"}\n                                          (ns ui-reactivity-test)"
            save-code (str "(def !ui-save-result (atom :pending))\n"
                           "(-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n"
                           "  (.then (fn [r] (reset! !ui-save-result r))))\n"
                           ":setup-done")]
        (let [setup-result (js-await (helpers/eval-in-browser save-code))]
          (-> (expect (.-success setup-result)) (.toBe true)))

        ;; Wait for save to complete (check atom is no longer :pending)
        (js-await (helpers/wait-for-eval-promise "!ui-save-result" 3000)))

      ;; Popup should automatically refresh and show the new script
      ;; Use Playwright's auto-waiting to poll for the new element
      (js-await (-> (expect (.locator popup ".script-item:has-text(\"ui_reactivity_test_script.cljs\")"))
                    (.toBeVisible #js {:timeout 2000})))

      ;; Script count should have increased by 1
      (js-await (wait-for-script-count popup (inc initial-count))))

    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))

(defn- ^:async popup_refreshes_after_rm []
  ;; First create a script to delete
  (let [test-code "{:epupp/script-name \"script-to-delete\"\n                                      :epupp/auto-run-match \"https://delete-test.com/*\"}\n                                      (ns script-to-delete)"
        save-code (str "(def !delete-setup (atom :pending))\n"
                       "(-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n"
                       "  (.then (fn [r] (reset! !delete-setup r))))\n"
                       ":setup-done")]
    (let [setup-result (js-await (helpers/eval-in-browser save-code))]
      (-> (expect (.-success setup-result)) (.toBe true)))
    (js-await (helpers/wait-for-eval-promise "!delete-setup" 3000)))

  ;; Open popup and verify script exists
  (let [popup (js-await (.newPage (helpers/get-context)))]
    (js-await (.goto popup
                     (str "chrome-extension://" (helpers/get-ext-id) "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (wait-for-popup-ready popup))

    ;; Verify script exists
    (js-await (-> (expect (.locator popup ".script-item:has-text(\"script_to_delete.cljs\")"))
                  (.toBeVisible #js {:timeout 2000})))

    (let [initial-count (js-await (.count (.locator popup ".script-item")))]
      ;; Delete the script via REPL
      (let [delete-code "(def !rm-ui-result (atom :pending))\n                                  (-> (epupp.fs/rm! \"script_to_delete.cljs\")\n                                    (.then (fn [r] (reset! !rm-ui-result r))))\n                                  :setup-done"
            del-result (js-await (helpers/eval-in-browser delete-code))]
        (-> (expect (.-success del-result)) (.toBe true)))
      (js-await (helpers/wait-for-eval-promise "!rm-ui-result" 3000))

      ;; Popup should automatically refresh and remove the script
      (js-await (-> (expect (.locator popup ".script-item:has-text(\"script_to_delete.cljs\")"))
                    (.not.toBeVisible #js {:timeout 2000})))

      ;; Script count should have decreased
      (js-await (wait-for-script-count popup (dec initial-count))))

    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))

(defn- ^:async popup_refreshes_after_mv []
  ;; First create a script to rename
  (let [test-code "{:epupp/script-name \"script-to-rename\"\n                                       :epupp/auto-run-match \"https://rename-test.com/*\"}\n                                      (ns script-to-rename)"
        save-code (str "(def !rename-setup (atom :pending))\n"
                       "(-> (epupp.fs/save! " (pr-str test-code) " {:fs/force? true})\n"
                       "  (.then (fn [r] (reset! !rename-setup r))))\n"
                       ":setup-done")]
    (let [setup-result (js-await (helpers/eval-in-browser save-code))]
      (-> (expect (.-success setup-result)) (.toBe true)))
    (js-await (helpers/wait-for-eval-promise "!rename-setup" 3000)))

  ;; Open popup and verify script exists
  (let [popup (js-await (.newPage (helpers/get-context)))]
    (js-await (.goto popup
                     (str "chrome-extension://" (helpers/get-ext-id) "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (wait-for-popup-ready popup))

    ;; Verify old name exists
    (js-await (-> (expect (.locator popup ".script-item:has-text(\"script_to_rename.cljs\")"))
                  (.toBeVisible #js {:timeout 2000})))

    ;; Rename the script via REPL
    (let [rename-code "(def !mv-ui-result (atom :pending))\n                                  (-> (epupp.fs/mv! \"script_to_rename.cljs\" \"renamed_via_repl.cljs\" {:fs/force? true})\n                                    (.then (fn [r] (reset! !mv-ui-result r))))\n                                  :setup-done"
          mv-result (js-await (helpers/eval-in-browser rename-code))]
      (-> (expect (.-success mv-result)) (.toBe true))
      (js-await (helpers/wait-for-eval-promise "!mv-ui-result" 3000)))

    ;; Popup should show new name, hide old name
    (js-await (-> (expect (.locator popup ".script-item:has-text(\"renamed_via_repl.cljs\")"))
                  (.toBeVisible #js {:timeout 2000})))
    (js-await (-> (expect (.locator popup ".script-item:has-text(\"script_to_rename.cljs\")"))
                  (.not.toBeVisible)))

    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))

(.describe test "REPL FS UI: popup refresh"
           (fn []
             (.beforeAll test (fn [] (helpers/setup-browser!)))

             (.afterAll test
                        (fn []
                          (helpers/close-browser!)))

             (test "REPL FS UI: popup refreshes script list after epupp.fs/save"
                   popup_refreshes_script_list_after_save)

             (test "REPL FS UI: popup refreshes after epupp.fs/rm"
                   popup_refreshes_after_rm)

             (test "REPL FS UI: popup refreshes after epupp.fs/mv"
                   popup_refreshes_after_mv)))
