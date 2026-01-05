(ns tasks
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- patch-scittle-for-csp
  "Patch scittle.js to remove eval() call that breaks on CSP-strict sites.
   The Closure Compiler adds a dynamic import polyfill using eval which
   triggers CSP violations on sites like YouTube, GitHub, etc."
  [scittle-path]
  (let [content (slurp scittle-path)
        ;; Replace: globalThis["import"]=eval("(x) => import(x)");
        ;; With:    globalThis["import"]=function(x){return import(x);};
        patched (str/replace
                 content
                 "globalThis[\"import\"]=eval(\"(x) \\x3d\\x3e import(x)\");"
                 "globalThis[\"import\"]=function(x){return import(x);};")]
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
  "Download Scittle and nREPL plugin to extension/vendor"
  []
  (let [version "0.7.30"
        base-url "https://cdn.jsdelivr.net/npm/scittle@"
        vendor-dir "extension/vendor"
        files [["scittle.js" (str base-url version "/dist/scittle.js")]
               ["scittle.nrepl.js" (str base-url version "/dist/scittle.nrepl.js")]]]
    (fs/create-dirs vendor-dir)
    (doseq [[filename url] files]
      (println "Downloading" filename "...")
      (let [response (http/get url)]
        (spit (str vendor-dir "/" filename) (:body response))))
    (patch-scittle-for-csp (str vendor-dir "/scittle.js"))
    (println "✓ Scittle" version "bundled to" vendor-dir)))

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
    ;; Bundle all JS files as IIFE
    ;; popup.mjs needs EXTENSION_CONFIG injected
    (println "  Bundling popup.js...")
    (p/shell "npx" "esbuild" "extension/popup.mjs" "--bundle" "--format=iife"
             define-flag "--outfile=build/popup.js")
    ;; background.mjs also needs EXTENSION_CONFIG for dev-only test hooks
    (println "  Bundling background.js...")
    (p/shell "npx" "esbuild" "extension/background.mjs" "--bundle" "--format=iife"
             define-flag "--outfile=build/background.js")
    ;; Other bundles don't need config
    (doseq [[name entry] [["content-bridge" "extension/content_bridge.mjs"]
                          ["ws-bridge" "extension/ws_bridge.mjs"]
                          ["devtools" "extension/devtools.mjs"]
                          ["panel" "extension/panel.mjs"]]]
      (println (str "  Bundling " name ".js..."))
      (p/shell "npx" "esbuild" entry "--bundle" "--format=iife" (str "--outfile=build/" name ".js")))
    ;; Copy static files
    (fs/copy "extension/popup.html" "build/popup.html" {:replace-existing true})
    (fs/copy "extension/popup.css" "build/popup.css" {:replace-existing true})
    (fs/copy "extension/devtools.html" "build/devtools.html" {:replace-existing true})
    (fs/copy "extension/panel.html" "build/panel.html" {:replace-existing true})
    (fs/copy "extension/panel.css" "build/panel.css" {:replace-existing true})
    (println "✓ Squint + esbuild compilation complete")))

(defn- adjust-manifest
  "Adjust manifest.json for specific browser"
  [manifest browser]
  (case browser
    "firefox" (-> manifest
                  (assoc :browser_specific_settings
                         {:gecko {:id "scittle-tamper@example.com"
                                  :strict_min_version "142.0"
                                  :data_collection_permissions
                                  {:required ["none"]}}})
                  (assoc :background {:scripts ["background.js"]})
                  (assoc :content_security_policy
                         {:extension_pages "script-src 'self'; connect-src 'self' ws://localhost:* ws://127.0.0.1:*;"}))
    "safari" (-> manifest
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
   Supports browser names (chrome/firefox/safari) and mode flag (--dev or --prod).
   Default mode is prod. In dev mode, bumps the build number for version detection testing."
  [& args]
  (let [mode (cond
               (some #(= "--dev" %) args) "dev"
               (some #(= "--prod" %) args) "prod"
               :else "prod")
        browsers (->> args
                      (remove #(str/starts-with? % "--"))
                      seq)
        browsers (or browsers ["chrome" "firefox" "safari"])
        extension-dir "extension"
        build-dir "build"
        dist-dir "dist"]
    ;; Bump version in dev mode for testing version detection
    (when (= "dev" mode)
      (bump-dev-version!))
    ;; Compile Squint + bundle with esbuild
    (compile-squint mode)
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
        (fs/copy (str build-dir "/popup.css") (str browser-dir "/popup.css")
                 {:replace-existing true})
        (fs/copy (str build-dir "/content-bridge.js") (str browser-dir "/content-bridge.js")
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
        (let [zip-path (str dist-dir "/scittle-tamper-" browser ".zip")]
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
