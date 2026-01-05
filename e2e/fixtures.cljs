(ns fixtures
  "Playwright fixtures for testing Chrome extension.
   Provides helpers for launching Chrome with the extension loaded."
  (:require ["@playwright/test" :refer [test chromium]]
            ["path" :as path]
            ["url" :as url]))

(def ^:private __dirname
  (path/dirname (url/fileURLToPath js/import.meta.url)))

(def extension-path
  "Absolute path to the built extension directory (dist/chrome after bb build)."
  (path/resolve __dirname ".." ".." "dist" "chrome"))

(defn ^:async create-extension-context
  "Launch Chrome with the extension loaded.
   Returns a persistent browser context.
   Note: Extensions require headed mode (headless: false)."
  []
  (js-await
    (.launchPersistentContext chromium ""
      #js {:headless false
           :args #js ["--no-sandbox"
                      (str "--disable-extensions-except=" extension-path)
                      (str "--load-extension=" extension-path)]})))

(defn ^:async get-extension-id
  "Extract extension ID from service worker URL.
   Waits for service worker if not immediately available."
  [context]
  (let [workers (.serviceWorkers context)]
    (if (pos? (.-length workers))
      ;; Workers available - get URL from first one
      (let [sw-url (-> (aget workers 0) (.url))]
        (-> sw-url (.split "/") (aget 2)))
      ;; Wait for serviceworker event - returns a Worker object
      (let [sw (js-await (.waitForEvent context "serviceworker"))
            sw-url (.url sw)]
        (-> sw-url (.split "/") (aget 2))))))

(defn ^:async with-extension
  "Helper that sets up extension context, runs test fn, then cleans up.
   Test fn receives [context extension-id]."
  [test-fn]
  (let [context (js-await (create-extension-context))
        ext-id (js-await (get-extension-id context))]
    (try
      (js-await (test-fn context ext-id))
      (finally
        (js-await (.close context))))))

;; Re-export test for convenience
(def base-test test)
