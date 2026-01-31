(ns e2e.builtin-reinstall-test
  "E2E tests for built-in reinstall strategy."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              wait-for-checkbox-state send-runtime-message
                              get-script-item assert-no-errors!]]))

(def ^:private builtin-id "epupp-builtin-web-userscript-installer")
(def ^:private builtin-name "epupp/web_userscript_installer.cljs")

(defn- ^:async sleep [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- ^:async get-builtin-script
  "Return the built-in script JS object from storage, or nil if missing."
  [popup]
  (let [result (js-await (send-runtime-message popup "e2e/get-storage" #js {:key "scripts"}))
        scripts (or (.-value result) #js [])]
    (.find scripts (fn [script] (= (.-id script) builtin-id)))))

(defn- ^:async wait-for-builtin-script
  "Wait until the built-in script exists in storage and return it."
  [popup timeout-ms]
  (let [start (.now js/Date)
        timeout-ms (or timeout-ms 5000)]
    (loop []
      (let [script (js-await (get-builtin-script popup))]
        (if script
          script
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. (str "Timeout waiting for built-in script: " builtin-id)))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async wait-for-builtin-code
  "Wait until the built-in script exists and its code contains the expected substring."
  [popup expected-substring timeout-ms]
  (let [start (.now js/Date)
        timeout-ms (or timeout-ms 5000)]
    (loop []
      (let [script (js-await (get-builtin-script popup))
            code (when script (.-code script))]
        (if (and script (string? code) (.includes code expected-substring))
          script
          (if (> (- (.now js/Date) start) timeout-ms)
            (let [snippet (if (and (string? code) (> (.-length code) 0))
                            (.slice code 0 120)
                            "<no code>")]
              (throw (js/Error. (str "Timeout waiting for built-in code: " expected-substring
                                     " | snippet: " snippet))))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async update-builtin-script!
  "Update the built-in script entry in storage with the provided mutator."
  [popup mutator]
  (let [result (js-await (send-runtime-message popup "e2e/get-storage" #js {:key "scripts"}))
        scripts (or (.-value result) #js [])
        updated (.map scripts
                      (fn [script]
                        (if (= (.-id script) builtin-id)
                          (mutator script)
                          script)))]
    (js-await (send-runtime-message popup "e2e/set-storage" #js {:key "scripts" :value updated}))))

(defn- ^:async add-stale-builtin!
  "Add a fake stale built-in script to storage for cleanup verification."
  [popup]
  (let [result (js-await (send-runtime-message popup "e2e/get-storage" #js {:key "scripts"}))
        scripts (or (.-value result) #js [])
        stale #js {:id "epupp-builtin-stale"
                   :code "(println \"stale\")"
                   :enabled true
                   :created "2020-01-01T00:00:00.000Z"
                   :modified "2020-01-01T00:00:00.000Z"
                   :builtin true}
        updated (.concat scripts #js [stale])]
    (js-await (send-runtime-message popup "e2e/set-storage" #js {:key "scripts" :value updated}))))

(defn- ^:async reload-extension!
  "Reload the extension via chrome.runtime.reload()."
  [context ext-id]
  (let [popup (js-await (create-popup-page context ext-id))]
    (js-await (.evaluate popup "() => chrome.runtime.reload()"))
    (js-await (.close popup))))

;; =============================================================================
;; Tests
;; =============================================================================

(defn- ^:async test_builtin_reload_updates_code_and_preserves_disabled []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Start clean, then reload to trigger built-in sync
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (.evaluate popup "() => chrome.storage.local.clear()"))
        (js-await (.close popup)))

      (js-await (reload-extension! context ext-id))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (send-runtime-message popup "e2e/ensure-builtin" #js {}))
        (js-await (.close popup)))

      ;; Force an outdated built-in and disable it
      (let [popup (js-await (create-popup-page context ext-id))
            _ (js-await (wait-for-builtin-script popup 5000))
            old-modified "2000-01-01T00:00:00.000Z"
            old-code "(println \"old builtin\")"]
        (js-await (update-builtin-script! popup
                                          (fn [script]
                                            (aset script "code" old-code)
                                            (aset script "modified" old-modified)
                                            (aset script "enabled" false)
                                            script)))
        (js-await (.close popup)))

      ;; Reload and verify sync restores code and preserves disabled state
      (js-await (reload-extension! context ext-id))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (send-runtime-message popup "e2e/ensure-builtin" #js {}))
        (js-await (.close popup)))

      (let [popup (js-await (create-popup-page context ext-id))
            builtin (js-await (wait-for-builtin-code popup "Web Userscript Installer" 5000))]
        (-> (expect (.includes (.-code builtin) "epupp/web_userscript_installer.cljs"))
            (.toBe true))
        (-> (expect (.-enabled builtin))
            (.toBe false))
        (-> (expect (.includes (.-code builtin) "Web Userscript Installer"))
            (.toBe true))
        (-> (expect (.-modified builtin))
            (.not.toBe "2000-01-01T00:00:00.000Z"))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_builtin_reload_preserves_enabled_and_removes_stale []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))
            _ (js-await (wait-for-builtin-script popup 5000))
            script-item (get-script-item popup builtin-name)
            checkbox (.locator script-item "input[type='checkbox']")]
        ;; Ensure enabled
        (js-await (-> (expect checkbox) (.toBeVisible)))
        (let [checked (js-await (.isChecked checkbox))]
          (when-not checked
            (js-await (.click checkbox))
            (js-await (wait-for-checkbox-state checkbox true))))
        ;; Add stale built-in
        (js-await (add-stale-builtin! popup))
        (js-await (.close popup)))

      (js-await (reload-extension! context ext-id))

      (let [popup (js-await (create-popup-page context ext-id))
            builtin (js-await (wait-for-builtin-script popup 5000))
            scripts-result (js-await (send-runtime-message popup "e2e/get-storage" #js {:key "scripts"}))
            scripts (or (.-value scripts-result) #js [])
            stale (.find scripts (fn [script] (= (.-id script) "epupp-builtin-stale")))]
        (-> (expect (.-enabled builtin))
            (.toBe true))
        (-> (expect stale)
          (.toBeUndefined))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(.describe test "Built-in reinstall"
           (fn []
             (test "Built-in reload updates code and preserves disabled state"
                   test_builtin_reload_updates_code_and_preserves_disabled)

             (test "Built-in reload preserves enabled state and removes stale built-ins"
                   test_builtin_reload_preserves_enabled_and_removes_stale)))
