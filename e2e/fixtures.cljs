(ns fixtures
  "Playwright fixtures for testing Chrome extension.
   Provides helpers for launching Chrome with the extension loaded,
   creating panel/popup pages, and managing test state."
  (:require ["@playwright/test" :refer [test chromium expect]]
            ["path" :as path]
            ["url" :as url]))

(def ^:private __dirname
  (path/dirname (url/fileURLToPath js/import.meta.url)))

(def extension-path
  "Absolute path to the built extension directory (dist/chrome after bb build)."
  (path/resolve __dirname ".." ".." "dist" "chrome"))

;; =============================================================================
;; Browser Helpers
;; =============================================================================

(defn ^:async sleep
  "Promise-based sleep for waiting between operations.
   DEPRECATED: Prefer using Playwright's built-in waiting mechanisms:
   - expect(locator).toBeVisible() / toContainText() / etc.
   - locator.waitFor()
   - Assertions with timeout options"
  [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

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

;; Alias for backward compatibility
(def launch-browser create-extension-context)

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

;; =============================================================================
;; DevTools Panel Helpers
;; =============================================================================

(def mock-devtools-script
  "JavaScript to inject mock chrome.devtools APIs for panel testing.
   Mocks inspectedWindow.eval, network.onNavigated, and panels.themeName."
  "
  // Mock chrome.devtools APIs for panel testing
  window.__mockEvalCalls = [];
  window.__mockHostname = 'test.example.com';

  if (!chrome.devtools) {
    chrome.devtools = {
      inspectedWindow: {
        eval: function(expr, callback) {
          window.__mockEvalCalls.push(expr);
          if (expr.includes('location.hostname')) {
            callback(window.__mockHostname, undefined);
          } else if (expr.includes('location.href')) {
            callback('https://test.example.com/some/path', undefined);
          } else if (expr.includes('scittle') && !expr.includes('eval_string')) {
            callback({ hasScittle: true, hasNrepl: false }, undefined);
          } else if (expr.includes('eval_string')) {
            callback({ value: 'mock-result', error: null }, undefined);
          } else {
            callback(undefined, undefined);
          }
        },
        tabId: 99999
      },
      network: {
        onNavigated: { addListener: function() {} }
      },
      panels: { themeName: 'dark' }
    };
  }
")

(defn ^:async create-panel-page
  "Create a page with mocked chrome.devtools and navigate to panel.html.
   Waits for panel to be ready by checking for the code textarea."
  [context ext-id]
  (let [panel-page (js-await (.newPage context))
        panel-url (str "chrome-extension://" ext-id "/panel.html")]
    (js-await (.addInitScript panel-page mock-devtools-script))
    (js-await (.goto panel-page panel-url #js {:timeout 10000}))
    ;; Wait for panel to be fully initialized - code-area indicates JS has loaded
    (js-await (-> (expect (.locator panel-page "#code-area"))
                  (.toBeVisible #js {:timeout 5000})))
    panel-page))

;; =============================================================================
;; Popup Helpers
;; =============================================================================

(defn ^:async create-popup-page
  "Create a popup page.
   Waits for popup to be ready by checking for the nREPL port input."
  [context ext-id]
  (let [popup-page (js-await (.newPage context))
        popup-url (str "chrome-extension://" ext-id "/popup.html")]
    (js-await (.goto popup-page popup-url #js {:timeout 10000}))
    ;; Wait for popup to be fully initialized - nrepl-port input indicates JS has loaded
    (js-await (-> (expect (.locator popup-page "#nrepl-port"))
                  (.toBeVisible #js {:timeout 5000})))
    popup-page))

;; =============================================================================
;; Storage Helpers
;; =============================================================================

(defn ^:async clear-storage
  "Clear extension storage to ensure clean test state"
  [page]
  (js-await (.evaluate page "() => chrome.storage.local.clear()")))

(defn ^:async seed-scripts
  "Seed scripts directly into chrome.storage for testing.
   Scripts should be JS objects with script/id, script/name, script/match, etc."
  [page scripts]
  ;; Playwright evaluates arrow functions directly. Just ensure we return the Promise.
  (js-await (.evaluate page
                       "async (data) => {
                          console.log('[seed] data received:', data?.length);
                          await chrome.storage.local.set({ scripts: data });
                          return 'done';
                        }"
                       scripts)))

(defn ^:async get-popup-state
  "Get current popup state via the exported get-state function."
  [page]
  (js-await (.evaluate page "() => window.popup.get_state()")))

(defn ^:async get-stored-scripts
  "Get scripts directly from chrome.storage."
  [page]
  (js-await (.evaluate page "(async () => { const r = await chrome.storage.local.get('scripts'); return r.scripts || []; })()")))

;; Re-export test for convenience
(def base-test test)

;; =============================================================================
;; Test URL Override (for approval workflow testing)
;; =============================================================================

(defn ^:async set-test-url
  "Set a test URL override that popup.cljs will use instead of querying chrome.tabs.
   This allows testing URL-dependent features like approval workflow in Playwright.
   Call before creating popup page (use addInitScript for reliability)."
  [page url]
  (js-await (.evaluate page (str "window.__scittle_tamper_test_url = '" url "';"))))

;; =============================================================================
;; Wait Helpers - Use these instead of sleep for reliable tests
;; =============================================================================

(defn ^:async wait-for-script-count
  "Wait for the script list to have exactly n items.
   Use after save/delete operations instead of sleep."
  [page n]
  (js-await (-> (expect (.locator page ".script-item"))
                (.toHaveCount n #js {:timeout 5000}))))

(defn ^:async wait-for-save-status
  "Wait for save status to appear with expected text (e.g., 'Created', 'Saved').
   Use after clicking save button instead of sleep."
  [page text]
  (js-await (-> (expect (.locator page ".save-status"))
                (.toContainText text #js {:timeout 5000}))))

(defn ^:async wait-for-checkbox-state
  "Wait for checkbox to reach expected checked state.
   Use after toggling checkboxes instead of sleep."
  [checkbox checked?]
  (if checked?
    (js-await (-> (expect checkbox) (.toBeChecked #js {:timeout 2000})))
    (js-await (-> (expect checkbox) (.not.toBeChecked #js {:timeout 2000})))))

(defn ^:async wait-for-panel-ready
  "Wait for panel to be ready after reload/navigation.
   Useful after .reload() calls instead of sleep."
  [panel]
  (js-await (-> (expect (.locator panel "#code-area"))
                (.toBeVisible #js {:timeout 5000}))))

(defn ^:async wait-for-popup-ready
  "Wait for popup to be ready after reload/navigation.
   Useful after .reload() calls instead of sleep."
  [popup]
  (js-await (-> (expect (.locator popup "#nrepl-port"))
                (.toBeVisible #js {:timeout 5000}))))

(defn ^:async wait-for-edit-hint
  "Wait for the edit hint message to appear in popup.
   Use after clicking edit button instead of sleep.
   The hint message is generic ('Open the Epupp panel in Developer Tools'),
   so this just waits for visibility."
  [popup]
  (js-await (-> (expect (.locator popup ".script-edit-hint"))
                (.toBeVisible #js {:timeout 3000}))))
