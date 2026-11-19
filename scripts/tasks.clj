(ns tasks
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

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
  (let [scittle-version "0.8.32"
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
                                 ["userscript-loader" "extension/userscript_loader.mjs"]
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
    ;; userscript-loader is now bundled via esbuild (from src/userscript_loader.cljs)
    (fs/copy "extension/trigger-scittle.js" "build/trigger-scittle.js" {:replace-existing true})
    (fs/copy "extension/disable-scittle-auto-eval.js" "build/disable-scittle-auto-eval.js" {:replace-existing true})
    ;; Copy userscripts (raw source, evaluated by Scittle)
    (fs/create-dirs "build/userscripts")
    (fs/create-dirs "build/userscripts/epupp")
    (fs/copy "extension/userscripts/epupp/web_userscript_installer.cljs" "build/userscripts/epupp/web_userscript_installer.cljs" {:replace-existing true})
    (fs/copy "extension/userscripts/epupp/web_installer.css" "build/userscripts/epupp/web_installer.css" {:replace-existing true})
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

(def ^:private build-copy-files
  ["popup.html" "popup.js" "design-tokens.css" "components.css" "base.css" "popup.css"
   "content-bridge.js" "userscript-loader.js" "trigger-scittle.js"
   "disable-scittle-auto-eval.js" "ws-bridge.js" "background.js"
   "devtools.html" "devtools.js" "panel.html" "panel.js" "panel.css"])

(defn- build-for-browser! [browser build-dir dist-dir]
  (println (str "Building for " browser "..."))
  (let [browser-dir (str dist-dir "/" browser)]
    ;; Clean and copy extension directory
    (when (fs/exists? browser-dir)
      (fs/delete-tree browser-dir))
    (fs/copy-tree "extension" browser-dir)

    ;; Remove macOS metadata files
    (doseq [pattern [".DS_Store" "**/.DS_Store"]
            ds-store (fs/glob browser-dir pattern)]
      (fs/delete ds-store))

    ;; Copy bundled files over the intermediate ones
    (doseq [f build-copy-files]
      (fs/copy (str build-dir "/" f) (str browser-dir "/" f) {:replace-existing true}))

    ;; Copy userscripts directory (raw source)
    (fs/copy-tree (str build-dir "/userscripts") (str browser-dir "/userscripts")
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
      (build-for-browser! browser build-dir dist-dir))
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

(defn- print-git-check! [clean?]
  (println (str (if clean? "✅" "❌") " Git working directory is clean"))
  (when-not clean?
    (println "   Run 'git status' to see uncommitted changes")))

(defn- print-changelog-check! [has-content? has-unreleased?]
  (println (str (if has-content? "✅" "❌") " Changelog has Unreleased content"))
  (when (and has-unreleased? (not has-content?))
    (println "   The [Unreleased] section is empty")))

(defn- print-unreleased-content! [has-content? unreleased-content]
  (when has-content?
    (println)
    (println "📝 Unreleased changes:")
    (doseq [line (str/split-lines unreleased-content)]
      (println (str "   " line)))))

(defn- print-publish-checks [{:keys [clean? on-master? branch has-content? has-unreleased?
                                     current-dev-version new-version dev-version
                                     issue-numbers unreleased-content]}]
  (print-git-check! clean?)
  (println (str (if on-master? "✅" "❌") " On master branch (current: " branch ")"))
  (print-changelog-check! has-content? has-unreleased?)
  (println)
  (println "📋 Release details:")
  (println (str "   Current dev version: " current-dev-version))
  (println (str "   Release version: " new-version))
  (println (str "   Next dev version: " dev-version))
  (when (seq issue-numbers)
    (println (str "   Fixes issues: " (str/join ", " (map #(str "#" %) issue-numbers)))))
  (print-unreleased-content! has-content? unreleased-content)
  (println))

(defn- execute-release! [new-version dev-version unreleased-content issue-numbers]
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
  (println "   GitHub Actions will now build and create the release."))

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

    (print-publish-checks {:clean? clean?
                           :on-master? on-master?
                           :branch branch
                           :has-content? has-content?
                           :has-unreleased? has-unreleased?
                           :current-dev-version current-dev-version
                           :new-version new-version
                           :dev-version dev-version
                           :issue-numbers issue-numbers
                           :unreleased-content unreleased-content})

    (if-not all-ok?
      (do
        (println "❌ Cannot proceed - please fix the issues above")
        (System/exit 1))

      (when (confirm "Proceed with release?")
        (execute-release! new-version dev-version unreleased-content issue-numbers)))))

(defn test-watch-squint!
  "Watch src/ and test/; full squint compile into build/test on change.
   Avoids squint watch nested-directory relative-import bugs."
  []
  (p/shell "node" "scripts/watch-squint-tests.mjs"))

;; ============================================================
;; Taking turns between checkouts
;; ============================================================

(defn run-locked!
  "Run `bb <task> <args>` through `bbg lock <lock>`, so only one checkout of
   epupp runs it at a time, and return its exit code. Without bbg on the
   PATH, as for contributors, the task runs directly."
  [lock task args]
  (let [cmd (into ["bb" task] args)]
    (:exit (p/shell {:continue true
                     :cmd (if (fs/which "bbg")
                            (into ["bbg" "lock" lock "--"] cmd)
                            cmd)}))))
