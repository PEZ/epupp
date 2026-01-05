(ns repl-test
  "Integration tests for the full REPL pipeline:
   Editor -> nREPL -> browser-nrepl -> Extension -> Scittle -> Page"
  (:require [babashka.nrepl-client :as nrepl]
            [babashka.process :as p]
            [babashka.fs :as fs]
            [babashka.http-server :as http-server]
            [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]))

;; ============================================================
;; Test Infrastructure
;; ============================================================

(def nrepl-port 12345)
(def ws-port 12346)
(def http-port 8765)

(defn wait-for-port
  "Wait for a port to become available, with timeout."
  [port timeout-ms]
  (let [start (System/currentTimeMillis)
        deadline (+ start timeout-ms)]
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        false
        (if (try
              (with-open [_ (java.net.Socket. "localhost" port)]
                true)
              (catch Exception _ false))
          true
          (do
            (Thread/sleep 100)
            (recur)))))))

(defn start-browser-nrepl!
  "Start the browser-nrepl relay server. Returns the process."
  []
  (println "Starting browser-nrepl server...")
  (let [proc (p/process ["bb" "browser-nrepl"
                         "--nrepl-port" (str nrepl-port)
                         "--websocket-port" (str ws-port)]
                        {:out :inherit :err :inherit})]
    (when-not (wait-for-port nrepl-port 5000)
      (p/destroy proc)
      (throw (ex-info "browser-nrepl failed to start" {:port nrepl-port})))
    (println "  browser-nrepl ready on ports" nrepl-port "/" ws-port)
    proc))

(defn create-test-page!
  "Create a simple test HTML page and start a local HTTP server.
   Returns the server (which can be stopped with .close)."
  []
  (println "Creating test page...")
  (fs/create-dirs "build/e2e/test-pages")
  (spit "build/e2e/test-pages/index.html"
        "<!DOCTYPE html>
<html>
<head><title>Test Page</title></head>
<body>
  <h1 id=\"heading\">Test Page</h1>
  <div id=\"result\"></div>
</body>
</html>")
  (println "  Starting HTTP server on port" http-port "...")
  (let [server (http-server/serve {:port http-port
                                   :dir "build/e2e/test-pages"})]
    (Thread/sleep 500) ;; Give server a moment to start
    (println "  Test page ready at http://localhost:" http-port)
    server))

(defn stop-process!
  "Stop a process gracefully."
  [proc]
  (when proc
    (p/destroy proc)
    (Thread/sleep 500)))

(defn stop-server!
  "Stop an HTTP server (babashka.http-server/serve returns a stop fn)."
  [stop-fn]
  (when stop-fn
    (stop-fn)))

(defn run-playwright-connect!
  "Run Playwright to launch browser, load extension, navigate to test page,
   and connect via background worker APIs (no popup interaction needed).
   Returns process (browser stays open for eval tests)."
  []
  (println "Launching browser with extension...")
  (let [extension-path (str (fs/absolutize "dist/chrome"))
        test-page-url (str "http://localhost:" http-port "/")
        helper-script (str "
const { chromium } = require('@playwright/test');

(async () => {
  const extensionPath = '" extension-path "';
  const testPageUrl = '" test-page-url "';
  const wsPort = " ws-port ";
  console.log('Extension path:', extensionPath);

  try {
    const context = await chromium.launchPersistentContext('', {
      headless: false,
      args: [
        '--no-sandbox',
        '--allow-file-access-from-files',
        '--enable-features=ExtensionsManifestV3Only',
        `--disable-extensions-except=${extensionPath}`,
        `--load-extension=${extensionPath}`
      ]
    });

    // Get extension ID from service worker
    const workers = context.serviceWorkers();
    let extId;
    if (workers.length > 0) {
      extId = workers[0].url().split('/')[2];
    } else {
      const sw = await context.waitForEvent('serviceworker');
      extId = sw.url().split('/')[2];
    }
    console.log('Extension ID:', extId);

    // Open test page
    const testPage = await context.newPage();
    await testPage.goto(testPageUrl);
    console.log('Test page loaded:', testPageUrl);

    // Give page a moment to settle
    await new Promise(r => setTimeout(r, 500));

    // Use background worker APIs to find tab and connect
    // We evaluate in the context of an extension page to access chrome.runtime
    console.log('Opening extension helper page...');
    const bgPage = await context.newPage();
    console.log('Navigating to popup.html...');
    await bgPage.goto(`chrome-extension://${extId}/popup.html`, { waitUntil: 'networkidle' });
    await bgPage.waitForLoadState('domcontentloaded');
    console.log('Popup page loaded');

    // Wait for popup to initialize and for chrome.runtime to be available
    await new Promise(r => setTimeout(r, 2000));
    console.log('Starting find-tab-id call...');

    // Ask background for tab ID matching our test page URL
    const findTabResult = await bgPage.evaluate(async (urlPattern) => {
      return new Promise((resolve) => {
        chrome.runtime.sendMessage(
          { type: 'e2e/find-tab-id', urlPattern },
          (response) => resolve(response)
        );
      });
    }, 'http://localhost:*/*');

    console.log('Find tab result:', JSON.stringify(findTabResult));

    if (!findTabResult || !findTabResult.success) {
      console.log('ERROR: Could not find test page tab:', findTabResult?.error || 'unknown');
      process.exit(1);
    }

    const tabId = findTabResult.tabId;
    console.log('Found test page tab ID:', tabId);

    // Request background to connect this tab
    const connectResult = await bgPage.evaluate(async ({ tabId, wsPort }) => {
      return new Promise((resolve) => {
        chrome.runtime.sendMessage(
          { type: 'connect-tab', tabId, wsPort },
          (response) => resolve(response)
        );
      });
    }, { tabId, wsPort });

    console.log('Connect result:', JSON.stringify(connectResult));

    if (!connectResult || !connectResult.success) {
      console.log('ERROR: Connection failed:', connectResult?.error || 'unknown');
      process.exit(1);
    }

    // Close the helper page (optional, keeps things tidy)
    await bgPage.close();

    console.log('READY');

    // Keep browser open - parent process will kill us
    await new Promise(() => {});
  } catch (err) {
    console.log('ERROR:', err.message);
    process.exit(1);
  }
})();
")
        script-path "build/e2e/connect-helper.cjs"]
    (fs/create-dirs "build/e2e")
    (spit script-path helper-script)
    (let [proc (p/process ["node" script-path]
                          {:out :pipe :err :inherit})]
      ;; Wait for READY signal with timeout
      (let [reader (java.io.BufferedReader.
                    (java.io.InputStreamReader. (:out proc)))
            deadline (+ (System/currentTimeMillis) 30000)]
        (loop []
          (if (> (System/currentTimeMillis) deadline)
            (do
              (println "  Timeout waiting for browser connection")
              (p/destroy proc)
              (throw (ex-info "Playwright connection timeout" {})))
            (when-let [line (.readLine reader)]
              (println "  Playwright:" line)
              (cond
                (= line "READY")
                (println "  Browser connected and ready")

                (str/starts-with? line "ERROR")
                (do
                  (p/destroy proc)
                  (throw (ex-info (str "Playwright error: " line) {})))

                :else
                (recur))))))
      proc)))

(defn eval-in-browser
  "Send an eval request to the browser REPL via nrepl-client."
  [code]
  (try
    (let [result (nrepl/eval-expr {:port nrepl-port
                                   :expr code
                                   :timeout 10000})]
      {:success true
       :values (:vals result)
       :output (:out result)
       :error (:err result)})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

;; ============================================================
;; Tests
;; ============================================================

(deftest simple-eval-test
  (testing "Simple arithmetic evaluation"
    (let [result (eval-in-browser "(+ 1 2 3)")]
      (is (:success result) (str "Eval failed: " (:error result)))
      (is (= ["6"] (:values result))))))

(deftest string-eval-test
  (testing "String operations"
    (let [result (eval-in-browser "(str \"Hello\" \" \" \"World\")")]
      (is (:success result))
      (is (= ["\"Hello World\""] (:values result))))))

(deftest dom-access-test
  (testing "DOM access in page context"
    (let [result (eval-in-browser "(.-title js/document)")]
      (is (:success result))
      ;; Our test page has title "Test Page"
      (is (str/includes? (first (:values result)) "Test Page")))))

(deftest multi-form-test
  (testing "Multiple forms evaluation"
    (let [result (eval-in-browser "(def x 10) (def y 20) (+ x y)")]
      (is (:success result))
      ;; Last value should be 30
      (is (some #(= "30" %) (:values result))))))

;; ============================================================
;; Test Infrastructure Management
;; ============================================================

(defn ^:export start-servers!
  "Start only the servers (browser-nrepl + HTTP test page).
   Used by Playwright UI mode where Playwright manages the browser.
   Returns a map with :cleanup-fn to call when done."
  []
  (println "\n=== Starting Servers ===\n")
  (let [browser-nrepl (atom nil)
        test-server (atom nil)]
    (try
      (reset! browser-nrepl (start-browser-nrepl!))
      (reset! test-server (create-test-page!))
      (println "\n=== Servers Ready ===\n")
      {:cleanup-fn (fn []
                     (println "\nCleaning up servers...")
                     (stop-server! @test-server)
                     (stop-process! @browser-nrepl)
                     (println "Done."))}
      (catch Exception e
        (println "Server setup failed:" (.getMessage e))
        (.printStackTrace e)
        (stop-server! @test-server)
        (stop-process! @browser-nrepl)
        (throw e)))))

;; ============================================================
;; Test Runner
;; ============================================================

(defn ^:export run-integration-tests
  "Run all REPL integration tests with proper setup/teardown."
  []
  (println "\n=== REPL Integration Tests ===\n")
  (let [browser-nrepl (atom nil)
        test-server (atom nil)
        playwright (atom nil)]
    (try
      ;; Setup
      (reset! browser-nrepl (start-browser-nrepl!))
      (reset! test-server (create-test-page!))
      (reset! playwright (run-playwright-connect!))

      ;; Give everything a moment to stabilize
      (Thread/sleep 2000)

      ;; Run tests
      (let [results (run-tests 'repl-test)]
        (println "\n=== Test Summary ===")
        (println "Pass:" (:pass results))
        (println "Fail:" (:fail results))
        (println "Error:" (:error results))

        ;; Return exit code
        (if (and (zero? (:fail results))
                 (zero? (:error results)))
          0
          1))

      (catch Exception e
        (println "Test setup failed:" (.getMessage e))
        (.printStackTrace e)
        1)

      (finally
        ;; Teardown
        (println "\nCleaning up...")
        (stop-process! @playwright)
        (stop-server! @test-server)
        (stop-process! @browser-nrepl)
        (println "Done.")))))

