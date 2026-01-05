(ns connect-helper
  "Playwright helper script for E2E testing.
   Launches browser with extension, connects to test page via background worker APIs.

   Usage: node build/e2e/connect_helper.mjs <extension-path> <test-page-url> <ws-port>"
  (:require ["@playwright/test" :refer [chromium]]))

(def args (vec (drop 2 js/process.argv)))

(defn get-arg [idx]
  (get args idx))

(def extension-path (get-arg 0))
(def test-page-url (get-arg 1))
(def ws-port (js/parseInt (get-arg 2) 10))

(defn ^:async sleep [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn ^:async get-extension-id [context]
  (let [workers (.serviceWorkers context)]
    (if (pos? (.-length workers))
      (-> (aget workers 0)
          (.url)
          (.split "/")
          (aget 2))
      (let [sw (js-await (.waitForEvent context "serviceworker"))]
        (-> (.url sw)
            (.split "/")
            (aget 2))))))

(defn ^:async find-tab-id [bg-page url-pattern]
  (.evaluate bg-page
             (fn [pattern]
               (js/Promise.
                (fn [resolve]
                  (js/chrome.runtime.sendMessage
                   #js {:type "e2e/find-tab-id" :urlPattern pattern}
                   (fn [response] (resolve response))))))
             url-pattern))

(defn ^:async connect-tab [bg-page tab-id ws-port]
  (.evaluate bg-page
             (fn [opts]
               (js/Promise.
                (fn [resolve]
                  (js/chrome.runtime.sendMessage
                   #js {:type "connect-tab"
                        :tabId (.-tabId opts)
                        :wsPort (.-wsPort opts)}
                   (fn [response] (resolve response))))))
             #js {:tabId tab-id :wsPort ws-port}))

(defn ^:async main []
  (js/console.log "Extension path:" extension-path)
  (js/console.log "Test page URL:" test-page-url)
  (js/console.log "WebSocket port:" ws-port)

  (try
    (let [context (js-await (.launchPersistentContext
                             chromium
                             ""
                             #js {:headless false
                                  :args #js ["--no-sandbox"
                                             "--allow-file-access-from-files"
                                             "--enable-features=ExtensionsManifestV3Only"
                                             (str "--disable-extensions-except=" extension-path)
                                             (str "--load-extension=" extension-path)]}))
          ext-id (js-await (get-extension-id context))
          test-page (js-await (.newPage context))]
      (js/console.log "Extension ID:" ext-id)

      ;; Open test page
      (js-await (.goto test-page test-page-url))
      (js/console.log "Test page loaded:" test-page-url)

      ;; Give page a moment to settle
      (js-await (sleep 500))

      ;; Open extension popup page to access chrome.runtime
      (js/console.log "Opening extension helper page...")
      (let [bg-page (js-await (.newPage context))
            popup-url (str "chrome-extension://" ext-id "/popup.html")]
        (js/console.log "Navigating to popup.html...")
        (js-await (.goto bg-page popup-url #js {:waitUntil "networkidle"}))
        (js-await (.waitForLoadState bg-page "domcontentloaded"))
        (js/console.log "Popup page loaded")

        ;; Wait for popup to initialize
        (js-await (sleep 2000))
        (js/console.log "Starting find-tab-id call...")

        ;; Find test page tab
        (let [find-result (js-await (find-tab-id bg-page "http://localhost:*/*"))]
          (js/console.log "Find tab result:" (js/JSON.stringify find-result))

          (when-not (and find-result (.-success find-result))
            (js/console.log "ERROR: Could not find test page tab:"
                            (or (.-error find-result) "unknown"))
            (js/process.exit 1))

          (let [tab-id (.-tabId find-result)
                connect-result (js-await (connect-tab bg-page tab-id ws-port))]
            (js/console.log "Found test page tab ID:" tab-id)
            (js/console.log "Connect result:" (js/JSON.stringify connect-result))

            (when-not (and connect-result (.-success connect-result))
              (js/console.log "ERROR: Connection failed:"
                              (or (.-error connect-result) "unknown"))
              (js/process.exit 1))

            ;; Close helper page
            (js-await (.close bg-page))
            (js/console.log "READY")

            ;; Keep browser open - parent process will kill us
            (js-await (js/Promise. (fn [])))))))

    (catch :default err
      (js/console.log "ERROR:" (.-message err))
      (js/process.exit 1))))

(main)
