(ns tasks
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.http-server :as server]
            [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- patch-scittle-for-csp
  "Patch scittle.js to remove eval() call that breaks on CSP-strict sites.
   The Closure Compiler adds a dynamic import polyfill using eval which
   triggers CSP violations on sites like YouTube, GitHub, etc."
  [scittle-path]
  (let [content (slurp scittle-path)
        patched (str/replace
                 content
                 "globalThis[\"import\"]=eval(\"(x) \\x3d\\x3e import(x)\");"
                 "try { globalThis[\"import\"]=eval(\"(x) => import(x)\"); } catch {};")]
    (when (= content patched)
      (println "  ⚠ Warning: eval pattern not found in scittle.js - may already be patched or pattern changed"))
    (spit scittle-path patched)
    (when (not= content patched)
      (println "  ✓ Patched scittle.js for CSP compatibility"))))

;; ============================================================
;; Config handling
;; ============================================================

(defn- read-config
  "Read config from config/dev.edn or config/prod.edn based on mode.
   Defaults to prod."
  [mode]
  (let [config-file (str "config/" (or mode "prod") ".edn")]
    (when-not (fs/exists? config-file)
      (println (str "Error: " config-file " not found"))
      (System/exit 1))
    (edn/read-string (slurp config-file))))

(defn- esbuild-define-flag
  "Build the --define flag for esbuild to inject EXTENSION_CONFIG.
   Reads EDN config and converts to JSON (which is valid JS)."
  [mode]
  (let [config (read-config mode)]
    (str "--define:EXTENSION_CONFIG=" (json/write-str config))))

(defn bundle-scittle
  "Download Scittle ecosystem libraries to extension/vendor.
   Skips download if all files already exist."
  []
  (let [scittle-version "0.7.30"
        react-version "18"
        scittle-base (str "https://cdn.jsdelivr.net/npm/scittle@" scittle-version "/dist/")
        react-base "https://cdn.jsdelivr.net/npm/"
        vendor-dir "extension/vendor"
        ;; Scittle core and plugins
        scittle-files ["scittle.js"
                       "scittle.nrepl.js"
                       "scittle.pprint.js"
                       "scittle.promesa.js"
                       "scittle.replicant.js"
                       "scittle.js-interop.js"
                       "scittle.reagent.js"
                       "scittle.re-frame.js"
                       "scittle.cljs-ajax.js"]
        ;; React dependencies for Reagent
        react-files [["react.production.min.js"
                      (str react-base "react@" react-version "/umd/react.production.min.js")]
                     ["react-dom.production.min.js"
                      (str react-base "react-dom@" react-version "/umd/react-dom.production.min.js")]]
        all-files (concat scittle-files (map first react-files))
        all-exist? (every? #(fs/exists? (str vendor-dir "/" %)) all-files)]
    (if all-exist?
      (println "✓ Scittle ecosystem already present, skipping download")
      (do
        (fs/create-dirs vendor-dir)
        ;; Download Scittle files
        (println (str "Downloading Scittle " scittle-version " ecosystem..."))
        (doseq [filename scittle-files]
          (println "  Downloading" filename "...")
          (let [url (str scittle-base filename)
                response (http/get url)]
            (spit (str vendor-dir "/" filename) (:body response))))
        ;; Download React files
        (println (str "Downloading React " react-version " (for Reagent)..."))
        (doseq [[filename url] react-files]
          (println "  Downloading" filename "...")
          (let [response (http/get url)]
            (spit (str vendor-dir "/" filename) (:body response))))
        ;; Patch scittle.js for CSP (only core needs patching)
        (patch-scittle-for-csp (str vendor-dir "/scittle.js"))
        (println (str "✓ Scittle " scittle-version " ecosystem bundled to " vendor-dir))))))

(defn compile-squint
  "Compile ClojureScript files with Squint and bundle with esbuild.
   Optional mode argument: 'dev' or 'prod' (default: prod)."
  [& [mode]]
  (let [define-flag (esbuild-define-flag mode)]
    (println (str "Building with " (or mode "prod") " config..."))
    (println "Compiling Squint...")
    (p/shell "npx squint compile")
    (println "Bundling with esbuild...")
    (fs/create-dirs "build")
    ;; Bundle all JS files as IIFE with EXTENSION_CONFIG injected
    ;; All bundles get config for consistent test instrumentation
    (doseq [[bundle-name entry] [["popup" "extension/popup.mjs"]
                                 ["background" "extension/background.mjs"]
                                 ["panel" "extension/panel.mjs"]
                                 ["content-bridge" "extension/content_bridge.mjs"]
                                 ["ws-bridge" "extension/ws_bridge.mjs"]
                                 ["devtools" "extension/devtools.mjs"]]]
      (println (str "  Bundling " bundle-name ".js..."))
      (p/shell "npx" "esbuild" entry "--bundle" "--format=iife"
               define-flag (str "--outfile=build/" bundle-name ".js")))
    ;; Copy static files
    (fs/copy "extension/popup.html" "build/popup.html" {:replace-existing true})
    (fs/copy "extension/design-tokens.css" "build/design-tokens.css" {:replace-existing true})
    (fs/copy "extension/components.css" "build/components.css" {:replace-existing true})
    (fs/copy "extension/base.css" "build/base.css" {:replace-existing true})
    (fs/copy "extension/popup.css" "build/popup.css" {:replace-existing true})
    (fs/copy "extension/devtools.html" "build/devtools.html" {:replace-existing true})
    (fs/copy "extension/panel.html" "build/panel.html" {:replace-existing true})
    (fs/copy "extension/panel.css" "build/panel.css" {:replace-existing true})
    ;; Copy early injection loaders (content scripts for document-start/document-end)
    (fs/copy "extension/userscript-loader.js" "build/userscript-loader.js" {:replace-existing true})
    (fs/copy "extension/trigger-scittle.js" "build/trigger-scittle.js" {:replace-existing true})
    ;; Copy userscripts (raw source, evaluated by Scittle)
    (fs/create-dirs "build/userscripts")
    (fs/create-dirs "build/userscripts/epupp")
    (fs/copy "extension/userscripts/epupp/web_userscript_installer.cljs" "build/userscripts/epupp/web_userscript_installer.cljs" {:replace-existing true})
    (println "✓ Squint + esbuild compilation complete")))

(defn- adjust-manifest
  "Adjust manifest.json for specific browser"
  [manifest browser]
  (case browser
    "firefox" (-> manifest
                  (dissoc :key) ; Remove Chrome-specific key field
                  (assoc :browser_specific_settings
                         {:gecko {:id "browser-jack-in@example.com"
                                  :strict_min_version "142.0"
                                  :data_collection_permissions
                                  {:required ["none"]}}})
                  (assoc :background {:scripts ["background.js"]})
                  (assoc :content_security_policy
                         {:extension_pages "script-src 'self'; connect-src 'self' ws://localhost:* ws://127.0.0.1:*;"}))
    "safari" (-> manifest
                 (dissoc :key) ; Remove Chrome-specific key field
                 (assoc :background {:scripts ["background.js"]})
                 (assoc :content_security_policy
                        {:extension_pages "script-src 'self'; connect-src 'self' ws://localhost:* ws://127.0.0.1:*;"}))
    manifest))

;; ============================================================
;; Manifest Version Helpers (used by build and publish)
;; ============================================================

(defn- read-manifest-version
  "Read current version from manifest.json"
  []
  (let [manifest (json/read-str (slurp "extension/manifest.json") :key-fn keyword)]
    (:version manifest)))

(defn- update-manifest-version!
  "Update version in manifest.json"
  [new-version]
  (let [manifest-path "extension/manifest.json"
        manifest (json/read-str (slurp manifest-path) :key-fn keyword)
        updated (assoc manifest :version new-version)]
    (spit manifest-path (json/write-str updated :indent true :escape-slash false))))

(defn- bump-dev-version!
  "Bump the dev version's build number: 0.0.3.0 -> 0.0.3.1
   Used during development to test version detection in panel."
  []
  (let [current (read-manifest-version)
        parts (str/split current #"\.")
        ;; Ensure we have 4 parts for dev version
        parts (if (< (count parts) 4)
                (conj (vec parts) "0")
                parts)
        build-num (Integer/parseInt (nth parts 3))
        new-version (str/join "." [(nth parts 0) (nth parts 1) (nth parts 2) (inc build-num)])]
    (update-manifest-version! new-version)
    (println (str "  Bumped dev version: " current " -> " new-version))
    new-version))

(defn build
  "Build extension for specified browser(s).
   Supports browser names (chrome/firefox/safari) and mode flags:
   - --dev: Uses dev config AND bumps the build number
   - --test: Uses test config WITHOUT bumping version (for e2e tests with event logging)
   - --prod: Uses prod config (default)
   Default mode is prod."
  [& args]
  (let [bump-version? (some #(= "--dev" %) args)
        config-mode (cond
                      (some #(= "--dev" %) args) "dev"
                      (some #(= "--test" %) args) "test"
                      (some #(= "--prod" %) args) "prod"
                      :else "prod")
        browsers (->> args
                      (remove #(str/starts-with? % "--"))
                      seq)
        browsers (or browsers ["chrome" "firefox" "safari"])
        extension-dir "extension"
        build-dir "build"
        dist-dir "dist"]
    ;; Bump version only in --dev mode (not --test)
    (when bump-version?
      (bump-dev-version!))
    ;; Ensure Scittle ecosystem is downloaded
    (bundle-scittle)
    ;; Compile Squint + bundle with esbuild
    (compile-squint config-mode)
    (fs/create-dirs dist-dir)
    (doseq [browser browsers]
      (println (str "Building for " browser "..."))
      (let [browser-dir (str dist-dir "/" browser)]
        ;; Clean and copy extension directory
        (when (fs/exists? browser-dir)
          (fs/delete-tree browser-dir))
        (fs/copy-tree extension-dir browser-dir)

        ;; Remove macOS metadata files
        (doseq [pattern [".DS_Store" "**/.DS_Store"]
                ds-store (fs/glob browser-dir pattern)]
          (fs/delete ds-store))

        ;; Copy bundled files over the intermediate ones
        (fs/copy (str build-dir "/popup.html") (str browser-dir "/popup.html")
                 {:replace-existing true})
        (fs/copy (str build-dir "/popup.js") (str browser-dir "/popup.js")
                 {:replace-existing true})
        (fs/copy (str build-dir "/design-tokens.css") (str browser-dir "/design-tokens.css")
                 {:replace-existing true})
        (fs/copy (str build-dir "/components.css") (str browser-dir "/components.css")
                 {:replace-existing true})
        (fs/copy (str build-dir "/base.css") (str browser-dir "/base.css")
                 {:replace-existing true})
        (fs/copy (str build-dir "/popup.css") (str browser-dir "/popup.css")
                 {:replace-existing true})
        (fs/copy (str build-dir "/content-bridge.js") (str browser-dir "/content-bridge.js")
                 {:replace-existing true})
        ;; Copy userscripts directory (raw source)
        (fs/copy-tree (str build-dir "/userscripts") (str browser-dir "/userscripts")
                      {:replace-existing true})
        ;; Copy early injection loaders (content scripts for document-start/document-end)
        (fs/copy (str build-dir "/userscript-loader.js") (str browser-dir "/userscript-loader.js")
                 {:replace-existing true})
        (fs/copy (str build-dir "/trigger-scittle.js") (str browser-dir "/trigger-scittle.js")
                 {:replace-existing true})
        (fs/copy (str build-dir "/ws-bridge.js") (str browser-dir "/ws-bridge.js")
                 {:replace-existing true})
        (fs/copy (str build-dir "/background.js") (str browser-dir "/background.js")
                 {:replace-existing true})
        (fs/copy (str build-dir "/devtools.html") (str browser-dir "/devtools.html")
                 {:replace-existing true})
        (fs/copy (str build-dir "/devtools.js") (str browser-dir "/devtools.js")
                 {:replace-existing true})
        (fs/copy (str build-dir "/panel.html") (str browser-dir "/panel.html")
                 {:replace-existing true})
        (fs/copy (str build-dir "/panel.js") (str browser-dir "/panel.js")
                 {:replace-existing true})
        (fs/copy (str build-dir "/panel.css") (str browser-dir "/panel.css")
                 {:replace-existing true})
        ;; Remove intermediate .mjs files (keep only bundled .js)
        (doseq [mjs-file (fs/glob browser-dir "*.mjs")]
          (fs/delete mjs-file))

        ;; Adjust manifest
        (let [manifest-path (str browser-dir "/manifest.json")
              manifest (json/read-str (slurp manifest-path) :key-fn keyword)
              adjusted (adjust-manifest manifest browser)]
          (spit manifest-path (json/write-str adjusted :indent true :escape-slash false)))

        ;; Create zip
        (let [zip-path (str dist-dir "/epupp-" browser ".zip")]
          (fs/delete-if-exists zip-path)
          (fs/zip zip-path browser-dir {:root browser-dir})
          (println (str "  Created: " zip-path)))))
    (println "Build complete!")))

;; ============================================================
;; Release/Publish workflow
;; ============================================================

(defn- git-clean?
  "Check if git working directory is clean"
  []
  (let [result (p/shell {:out :string} "git status --porcelain")]
    (str/blank? (:out result))))

(defn- current-branch
  "Get current git branch name"
  []
  (str/trim (:out (p/shell {:out :string} "git rev-parse --abbrev-ref HEAD"))))

(defn- parse-changelog
  "Parse CHANGELOG.md and extract unreleased content"
  []
  (let [content (slurp "CHANGELOG.md")
        ;; Find the Unreleased section
        unreleased-match (re-find #"(?s)## \[Unreleased\]\s*\n(.*?)(?=\n## \[|$)" content)]
    {:raw content
     :unreleased-content (when unreleased-match
                           (str/trim (second unreleased-match)))
     :has-unreleased? (some? unreleased-match)}))

(defn- increment-version
  "Increment patch version: 0.0.1 -> 0.0.2"
  [version]
  (let [parts (str/split version #"\.")
        patch (Integer/parseInt (nth parts 2))
        new-patch (inc patch)]
    (str/join "." [(first parts) (second parts) new-patch])))

(defn- release-version
  "Convert dev version to release version: 0.0.3.0 -> 0.0.3"
  [version]
  (let [parts (str/split version #"\.")]
    (str/join "." (take 3 parts))))

(defn- next-dev-version
  "Get next dev version: 0.0.3 -> 0.0.4.0"
  [version]
  (str (increment-version version) ".0"))

(defn- extract-issue-numbers
  "Extract GitHub issue numbers from changelog content"
  [content]
  (when content
    (->> (re-seq #"issues/(\d+)" content)
         (map second)
         distinct
         sort)))

(defn- update-changelog!
  "Update CHANGELOG.md: add version header, keep empty Unreleased"
  [new-version unreleased-content]
  (let [today (str (java.time.LocalDate/now))
        content (slurp "CHANGELOG.md")
        ;; Replace Unreleased section with empty one + new versioned section
        updated (str/replace
                 content
                 #"(?s)(## \[Unreleased\])\s*\n.*?(?=\n## \[)"
                 (str "$1\n\n## [" new-version "] - " today "\n\n" unreleased-content "\n\n"))]
    (spit "CHANGELOG.md" updated)))

(defn- confirm
  "Ask for user confirmation"
  [message]
  (print (str message " [y/N] "))
  (flush)
  (= "y" (str/lower-case (str/trim (read-line)))))

(defn publish
  "Publish a new version: update changelog, bump version, commit, tag, and push"
  []
  (println "\n🔍 Running pre-publish checks...\n")

  ;; Gather all check results
  (let [clean? (git-clean?)
        branch (current-branch)
        on-master? (= "master" branch)
        {:keys [has-unreleased? unreleased-content]} (parse-changelog)
        has-content? (and has-unreleased? (not (str/blank? unreleased-content)))
        current-dev-version (read-manifest-version)
        new-version (release-version current-dev-version)
        dev-version (next-dev-version new-version)
        issue-numbers (extract-issue-numbers unreleased-content)
        all-ok? (and clean? on-master? has-content?)]

    ;; Display check results
    (println (str (if clean? "✅" "❌") " Git working directory is clean"))
    (when-not clean?
      (println "   Run 'git status' to see uncommitted changes"))

    (println (str (if on-master? "✅" "❌") " On master branch (current: " branch ")"))

    (println (str (if has-content? "✅" "❌") " Changelog has Unreleased content"))
    (when (and has-unreleased? (not has-content?))
      (println "   The [Unreleased] section is empty"))

    (println)
    (println "📋 Release details:")
    (println (str "   Current dev version: " current-dev-version))
    (println (str "   Release version: " new-version))
    (println (str "   Next dev version: " dev-version))
    (when (seq issue-numbers)
      (println (str "   Fixes issues: " (str/join ", " (map #(str "#" %) issue-numbers)))))

    (when has-content?
      (println)
      (println "📝 Unreleased changes:")
      (doseq [line (str/split-lines unreleased-content)]
        (println (str "   " line))))

    (println)

    (if-not all-ok?
      (do
        (println "❌ Cannot proceed - please fix the issues above")
        (System/exit 1))

      (when (confirm "Proceed with release?")
        (println)
        (println "📦 Updating manifest.json to release version...")
        (update-manifest-version! new-version)

        (println "📝 Updating CHANGELOG.md...")
        (update-changelog! new-version unreleased-content)

        (println "💾 Committing release...")
        (p/shell "git add extension/manifest.json CHANGELOG.md")
        (let [commit-msg (str "Release v" new-version
                              (when (seq issue-numbers)
                                (str "\n\n"
                                     (str/join "\n" (map #(str "* Fixes #" %) issue-numbers)))))]
          (p/shell {:continue true} "git" "commit" "-m" commit-msg))

        (println (str "🏷️  Creating tag v" new-version "..."))
        (p/shell "git" "tag" (str "v" new-version))

        (println "📦 Bumping to next dev version...")
        (update-manifest-version! dev-version)
        (p/shell "git add extension/manifest.json")
        (p/shell {:continue true} "git" "commit" "-m" (str "Bump to dev version " dev-version))

        (println "🚀 Pushing to origin...")
        (p/shell "git push origin master")
        (p/shell "git" "push" "origin" (str "v" new-version))

        (println)
        (println (str "✅ Released v" new-version "!"))
        (println (str "   Now on dev version " dev-version))
        (println "   GitHub Actions will now build and create the release.")))))

;; ============================================================
;; Test Server Management
;; ============================================================

(def ^:private test-server-port 18080)

;; Two browser-nrepl servers for multi-tab testing
(def ^:private browser-nrepl-port-1 12345)
(def ^:private browser-ws-port-1 12346)
(def ^:private browser-nrepl-port-2 12347)
(def ^:private browser-ws-port-2 12348)

;; E2E log file for subprocess output (timestamped to prevent conflicts)
(def ^:private e2e-log-file
  (str "/tmp/epupp-e2e-" (System/currentTimeMillis) ".log"))

(defn- log-writer
  "Create an appending writer to the E2E log file."
  []
  (io/writer e2e-log-file :append true))

(defn- wait-for-port
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

(defn with-test-server
  "Execute f with an HTTP test server running on port 18080.
   Server is started before f and stopped after (even on exception)."
  [f]
  (let [stop-fn (server/serve {:port test-server-port :dir "test-data/pages"})]
    (try
      (println (format "Test server available at http://localhost:%d" test-server-port))
      (Thread/sleep 300) ; Give server time to fully start
      (f)
      (finally
        (stop-fn)
        (println "Test server stopped")))))

(defn- start-browser-nrepl-process
  "Start a browser-nrepl process on given ports.
   Output goes to e2e-log-file for clean console. Returns the process."
  [nrepl-port ws-port]
  (let [writer (log-writer)]
    (.write writer (str "\n=== browser-nrepl " nrepl-port "/" ws-port " ===\n"))
    (.flush writer)
    (p/process ["bb" "browser-nrepl"
                "--nrepl-port" (str nrepl-port)
                "--websocket-port" (str ws-port)]
               {:out writer :err writer})))

(defn with-browser-nrepls
  "Execute f with two browser-nrepl relay servers running.
   Enables multi-tab testing with different ports."
  [f]
  (println "Starting browser-nrepl servers...")
  (let [proc1 (start-browser-nrepl-process browser-nrepl-port-1 browser-ws-port-1)
        proc2 (start-browser-nrepl-process browser-nrepl-port-2 browser-ws-port-2)]
    (try
      (if (and (wait-for-port browser-nrepl-port-1 5000)
               (wait-for-port browser-nrepl-port-2 5000))
        (do
          (println (format "browser-nrepl #1 ready on ports %d / %d" browser-nrepl-port-1 browser-ws-port-1))
          (println (format "browser-nrepl #2 ready on ports %d / %d" browser-nrepl-port-2 browser-ws-port-2))
          (f))
        (throw (ex-info "browser-nrepl servers failed to start" {})))
      (finally
        (p/destroy-tree proc1)
        (p/destroy-tree proc2)
        (Thread/sleep 300)
        (println "browser-nrepl servers stopped")))))

(defn run-e2e-tests!
  "Run Playwright E2E tests with test server and two browser-nrepls.
   Subprocess output goes to temp file for clean console output.
   Exits with Playwright's exit code without Babashka stack trace noise."
  [args]
  (println (str "📝 E2E log file: " e2e-log-file))
  (with-test-server
    #(with-browser-nrepls
       (fn []
         (let [result (apply p/shell {:continue true} "npx playwright test" args)]
           (System/exit (:exit result)))))))

;; ============================================================
;; E2E Timing Report
;; ============================================================

(defn- extract-specs-from-suite
  "Recursively extract specs from a suite and its nested suites.
   Returns seq of {:name string :duration-ms int :file string}"
  [suite file]
  (let [file (or (:file suite) file)
        ;; Extract specs at this level
        direct-specs (for [spec (:specs suite)
                           test (:tests spec)
                           result (:results test)]
                       {:name (:title spec)
                        :file (or (:file spec) file)
                        :duration-ms (:duration result)})
        ;; Recursively extract from nested suites
        nested-specs (mapcat #(extract-specs-from-suite % file)
                             (:suites suite))]
    (concat direct-specs nested-specs)))

(defn- extract-test-timings
  "Extract test name and duration from Playwright JSON report structure.
   Handles nested suites created by .describe blocks.
   Returns seq of {:name string :duration-ms int :file string}"
  [json-data]
  (mapcat #(extract-specs-from-suite % nil) (:suites json-data)))

(defn- format-duration
  "Format milliseconds as human-readable string"
  [ms]
  (cond
    (>= ms 1000) (format "%.2fs" (/ ms 1000.0))
    :else (format "%dms" ms)))

(defn- print-timing-report
  "Print formatted timing report to stdout"
  [timings]
  (let [sorted (sort-by :duration-ms timings)
        total-ms (reduce + (map :duration-ms timings))
        test-count (count timings)
        avg-ms (if (pos? test-count) (/ total-ms test-count) 0)]
    (println)
    (println "E2E Test Timing Report")
    (println "======================")
    (println (format "Tests: %d | Total: %s | Average: %s"
                     test-count
                     (format-duration total-ms)
                     (format-duration (long avg-ms))))
    (println)
    (println "Tests sorted by duration (fastest first):")
    (println (str (apply str (repeat 60 "-"))))
    (doseq [{:keys [name duration-ms]} sorted]
      (println (format "%-7s  %s"
                       (format-duration duration-ms)
                       name)))
    (println (str (apply str (repeat 60 "-"))))
    (println)
    (println "Slowest 10 tests:")
    (doseq [{:keys [name file duration-ms]} (take-last 10 sorted)]
      (println (format "  %-7s  %s (%s)"
                       (format-duration duration-ms)
                       name
                       file)))))

;; ============================================================
;; E2E Testing
;; ============================================================

(def ^:private spinner-frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- with-spinner
  "Run a function while displaying an animated spinner with message.
   Clears the spinner line when done."
  [message f]
  (let [stop? (atom false)
        spinner-thread (Thread.
                        (fn []
                          (loop [i 0]
                            (when-not @stop?
                              (print (str "\r" (nth spinner-frames (mod i (count spinner-frames))) " " message))
                              (flush)
                              (Thread/sleep 80)
                              (recur (inc i))))))]
    (.start spinner-thread)
    (try
      (f)
      (finally
        (reset! stop? true)
        (.join spinner-thread 200)
        ;; Clear the spinner line
        (print (str "\r" (apply str (repeat (+ 3 (count message)) " ")) "\r"))
        (flush)))))

(defn- run-docker-shard
  "Run Docker container with Playwright's native sharding.
   Returns map with process and writer for cleanup."
  [shard-idx n-shards log-file extra-args]
  (let [writer (io/writer log-file)
        cmd (into ["docker" "run" "--rm" "epupp-e2e"
                   (str "--shard=" (inc shard-idx) "/" n-shards)]
                  extra-args)]
    (.write writer (str "\n=== Shard " (inc shard-idx) "/" n-shards " ===\n\n"))
    (.flush writer)
    {:process (p/process cmd {:out writer :err writer})
     :writer writer}))

(defn- strip-ansi-codes
  "Remove ANSI escape sequences from text (colors, formatting, etc.)"
  [text]
  (str/replace text #"\x1b\[[0-9;]*m" ""))

(defn- parse-playwright-summary
  "Parse Playwright summary from log output.
   Returns {:passed N :failed N :skipped N} or nil if not found.
   Handles both formats:
     Combined: '10 passed, 2 failed (5.2s)'
     Separate: '1 failed' on one line, '2 passed (3.0s)' on another"
  [log-content]
  (let [;; Strip ANSI color codes that Playwright adds
        clean-content (strip-ansi-codes log-content)
        ;; Separate patterns for counts on their own lines
        passed-pattern #"(?m)^\s*(\d+)\s+passed\s*(?:\([^)]+\))?\s*$"
        failed-pattern #"(?m)^\s*(\d+)\s+failed\s*$"
        skipped-pattern #"(?m)^\s*(\d+)\s+skipped\s*$"
        ;; Always check separate patterns (handles both combined and separate formats)
        passed-match (re-find passed-pattern clean-content)
        failed-match (re-find failed-pattern clean-content)
        skipped-match (re-find skipped-pattern clean-content)]
    (when passed-match
      {:passed (parse-long (nth passed-match 1))
       :failed (if failed-match (parse-long (nth failed-match 1)) 0)
       :skipped (if skipped-match (parse-long (nth skipped-match 1)) 0)})))

(defn- count-test-files-in-log
  "Count unique test files mentioned in Playwright output."
  [log-content]
  (let [;; Match file references like "build/e2e/popup_core_test.mjs:123:1" or "build/e2e/repl_ui_spec.mjs:123:1"
        file-pattern #"build/e2e/([a-z_]+(?:_test|_spec)\.mjs):\d+:\d+"
        matches (re-seq file-pattern log-content)]
    (count (distinct (map second matches)))))

(defn- aggregate-shard-results
  "Aggregate results from all shard log files.
   Returns {:files N :total N :passed N :failed N}."
  [log-files]
  (let [results (for [log-file log-files
                      :let [content (slurp log-file)
                            summary (parse-playwright-summary content)
                            files (count-test-files-in-log content)]
                      :when summary]
                  (assoc summary :files files))
        total-passed (reduce + (map :passed results))
        total-failed (reduce + (map :failed results))
        total-skipped (reduce + (map :skipped results))
        total-files (reduce + (map :files results))]
    {:files total-files
     :total (+ total-passed total-failed total-skipped)
     :passed total-passed
     :failed total-failed
     :skipped total-skipped}))

(defn- print-test-summary
  "Print a summary of test results.
   When failed-override is provided, uses it instead of the parsed :failed count.
   This handles cases where shards crash without producing a parseable summary."
  [{:keys [files total passed failed skipped]} & {:keys [failed-override]}]
  (let [actual-failed (or failed-override failed)]
    (println)
    (println (format "Files:   %d" files))
    (println (format "Total:   %d tests" total))
    (println (format "Passed:  %d" passed))
    (when (pos? skipped)
      (println (format "Skipped: %d" skipped)))
    (println (format "Failed:  %d%s" actual-failed
                     (if (and failed-override (not= failed-override failed))
                       (str " (" failed-override " shard(s) failed)")
                       "")))
    (if (zero? actual-failed)
      (println "Status:  ALL TESTS PASSED")
      (println "Status:  SOME TESTS FAILED"))))

(defn- run-build-step!
  "Run a build command, capturing output. Returns result map.
   Throws with output on failure."
  [cmd]
  (let [result (p/shell {:out :string :err :string :continue true} cmd)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Build failed: " cmd)
                      {:cmd cmd
                       :exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))
    result))

(defn- build-e2e!
  "Build E2E tests and Docker image.
   Suppresses build output unless failure. Exits on failure."
  []
  (try
    (with-spinner "Building tests and Docker image..."
      (fn []
        (run-build-step! "bb build:test")
        (run-build-step! "bb test:e2e:compile")
        (run-build-step! "docker build --platform linux/arm64 -f Dockerfile.e2e -t epupp-e2e .")))
    (catch clojure.lang.ExceptionInfo e
      (println "\n\n❌ Build failed!")
      (let [{:keys [cmd out err]} (ex-data e)]
        (println (str "Command: " cmd))
        (when (seq out)
          (println "\nStdout:")
          (println out))
        (when (seq err)
          (println "\nStderr:")
          (println err)))
      (System/exit 1))))

(defn- run-e2e-serial!
  "Run E2E tests sequentially in a single Docker container.
   Suppresses build output, shows test output, then prints summary."
  [extra-args {:keys [build?] :or {build? true}}]
  ;; Build phase (if requested)
  (when build?
    (build-e2e!))

  ;; Run tests - stream output in real-time, also capture for summary
  (println "Running tests...")
  (when (seq extra-args)
    (println (str "  Extra Playwright args: " (str/join " " extra-args))))
  (let [log-file "/tmp/epupp-e2e-serial.log"
        ;; Use tee to both display output in real-time and capture to file
        ;; pipefail ensures we get docker's exit code, not tee's (which always succeeds)
        docker-cmd (str "docker run --rm epupp-e2e " (str/join " " extra-args))
        tee-cmd (str "set -o pipefail; " docker-cmd " 2>&1 | tee " log-file)
        result (p/shell {:continue true} "bash" "-c" tee-cmd)
        log-content (slurp log-file)
        parsed-summary (parse-playwright-summary log-content)
        files (count-test-files-in-log log-content)
        exit-code (:exit result)
        ;; Build summary with safe defaults when parsing fails
        summary (if parsed-summary
                  (let [{:keys [passed failed skipped]} parsed-summary]
                    (assoc parsed-summary
                           :files files
                           :total (+ passed failed (or skipped 0))))
                  {:files files :total 0 :passed 0 :failed 0 :skipped 0})
        ;; When parsing fails but exit is non-zero, report 1 failure
        failed-override (when (and (nil? parsed-summary) (not (zero? exit-code))) 1)]
    ;; Always print summary (matches parallel mode behavior)
    (print-test-summary summary :failed-override failed-override)
    (when (nil? parsed-summary)
      (println "  (Warning: Could not parse Playwright summary from output)"))
    (if (zero? exit-code)
      (do
        (println "✅ All tests passed!")
        0)
      (do
        (println "❌ Some tests failed!")
        exit-code))))

(defn- run-e2e-parallel!
  "Run E2E tests in parallel Docker containers using Playwright's native sharding."
  [n-shards extra-args {:keys [build?] :or {build? true}}]
  ;; Build phase (if requested)
  (when build?
    (build-e2e!))

  ;; Run shards in parallel using Playwright's native sharding
  (println (str "Running " n-shards " parallel shards..."))
  (when (seq extra-args)
    (println (str "  Extra Playwright args: " (str/join " " extra-args))))
  (let [log-dir "/tmp/epupp-e2e-parallel"
        _ (fs/create-dirs log-dir)
        start-time (System/currentTimeMillis)

        shards (doall
                (for [idx (range n-shards)]
                  (let [log-file (str log-dir "/shard-" idx ".log")]
                    (println (format "  Starting shard %d/%d..." (inc idx) n-shards))
                    (let [{:keys [process writer]} (run-docker-shard idx n-shards log-file extra-args)]
                      {:idx idx
                       :process process
                       :writer writer
                       :log-file log-file
                       :done? (atom false)
                       :exit-code (atom nil)}))))

        ;; Poll for completion - report as they finish
        _ (loop []
            (let [still-running (filter #(not @(:done? %)) shards)]
              (when (seq still-running)
                (doseq [{:keys [idx process writer done? exit-code]} still-running]
                  (let [proc (:proc process)
                        alive? (.isAlive proc)]
                    (when-not alive?
                      (let [exit (.exitValue proc)
                            elapsed-s (/ (- (System/currentTimeMillis) start-time) 1000.0)]
                        ;; Close writer to flush output before we read the log
                        (.close writer)
                        (reset! exit-code exit)
                        (reset! done? true)
                        (println (format "  Shard %d/%d finished at %.1fs (exit %d)"
                                         (inc idx) n-shards elapsed-s exit))))))
                (Thread/sleep 100)
                (recur))))

        results (map (fn [{:keys [idx exit-code log-file]}]
                       {:idx idx :exit @exit-code :log-file log-file})
                     shards)
        elapsed-ms (- (System/currentTimeMillis) start-time)
        failed (filter #(not= 0 (:exit %)) results)
        log-files (map :log-file results)
        summary (aggregate-shard-results log-files)
        failed-count (count failed)]

    (println)
    (println (str "Completed " n-shards " shards in " (format "%.1fs" (/ elapsed-ms 1000.0))))

    ;; Always print test summary - use failed shard count as override when shards crashed
    (print-test-summary summary :failed-override (when (pos? failed-count) failed-count))

    (if (seq failed)
      (do
        (println (str "❌ " (count failed) " shard(s) failed:"))
        (doseq [{:keys [idx log-file]} failed]
          (println (str "  Shard " (inc idx) " - see " log-file)))
        (println "\nFailed shard output:")
        (doseq [{:keys [idx log-file]} failed]
          (println (str "\n=== Shard " (inc idx) " ==="))
          (println (slurp log-file)))
        1)
      (do
        (println "✅ All shards passed!")
        0))))

; Playwright's stupid sharding will make it vary a lot what n-shards is the best
(def ^:private default-n-shards 13)

(defn ^:export run-e2e!
  "Run E2E tests in Docker. Parallel by default, --serial for detailed output.

   Options:
     --shards N  Number of parallel shards (default: 13)
     --serial    Run sequentially for detailed output
     --repeat N  Run tests N times without rebuilding between runs

   Use -- to separate bb options from Playwright options:
     bb test:e2e --serial -- --grep \"popup\""
  [args]
  (let [{:keys [args opts]} (cli/parse-args args {:coerce {:shards :int :repeat :int}
                                                  :alias {:s :serial :r :repeat}})
        serial? (:serial opts)
        n-shards (or (:shards opts) default-n-shards)
        repeat-count (max 1 (or (:repeat opts) 1))]
    (if (= repeat-count 1)
      ;; Single run - build and run with exit
      (if serial?
        (let [exit-code (run-e2e-serial! args {:build? true})]
          (System/exit exit-code))
        (let [exit-code (run-e2e-parallel! n-shards args {:build? true})]
          (System/exit exit-code)))
      ;; Multiple runs - build once, then loop without rebuilding
      (do
        (build-e2e!)
        (loop [run 1
               failures 0]
          (println (str "Run " run "/" repeat-count))
          (let [exit-code (if serial?
                            (run-e2e-serial! args {:build? false})
                            (run-e2e-parallel! n-shards args {:build? false}))]
            (if (zero? exit-code)
              (if (< run repeat-count)
                (recur (inc run) failures)
                (do
                  (println (str "REPEAT RUNS DONE: " repeat-count "/" repeat-count " - 0 failures"))
                  (System/exit 0)))
              (let [new-failures (inc failures)]
                (println (str "REPEAT RUNS DONE: " run "/" repeat-count " - " new-failures " failures"))
                (System/exit exit-code)))))))))

(defn- extract-json-from-output
  "Extract JSON object from mixed output that may have log prefixes.
   Finds the first { and parses from there."
  [output]
  (when-let [json-start (str/index-of output "{")]
    (subs output json-start)))

(defn e2e-timing-report!
  "Run E2E tests in Docker with JSON reporter and print timing report.
   Sorted fastest-first so you can tail for slowest tests."
  [_args]
  ;; Build phase with spinner (output suppressed unless failure)
  (try
    (with-spinner "Building tests and Docker image..."
      (fn []
        (run-build-step! "bb build:test")
        (run-build-step! "bb test:e2e:compile")
        (run-build-step! "docker build --platform linux/arm64 -f Dockerfile.e2e -t epupp-e2e .")))
    (catch clojure.lang.ExceptionInfo e
      (println "\n\n❌ Build failed!")
      (let [{:keys [cmd out err]} (ex-data e)]
        (println (str "Command: " cmd))
        (when (seq out)
          (println "stdout:")
          (println out))
        (when (seq err)
          (println "stderr:")
          (println err)))
      (System/exit 1)))
  ;; Run tests with spinner (output captured for JSON parsing)
  (let [result (atom nil)]
    (with-spinner "Running E2E tests (collecting timing data)..."
      #(reset! result (p/shell {:out :string :err :string :continue true}
                               "docker" "run" "--rm" "epupp-e2e"
                               "--reporter=json")))
    (let [{:keys [exit out]} @result
          json-str (extract-json-from-output out)]
      (if (and (zero? exit) json-str)
        (let [json-data (json/read-str json-str :key-fn keyword)
              timings (extract-test-timings json-data)]
          (print-timing-report timings))
        (do
          (println "Tests failed or no JSON output - cannot generate timing report")
          (when-not (str/blank? out)
            (println "Output preview:")
            (println (subs out 0 (min 500 (count out)))))
          (println "Run 'bb test:e2e' to see full test output")
          (System/exit (if (zero? exit) 1 exit)))))))
