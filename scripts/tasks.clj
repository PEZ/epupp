(ns tasks
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.data.json :as json]
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

(defn bundle-scittle
  "Download Scittle and nREPL plugin to src/vendor"
  []
  (let [version "0.7.30"
        base-url "https://cdn.jsdelivr.net/npm/scittle@"
        vendor-dir "src/vendor"
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
  "Compile ClojureScript files with Squint and bundle with Vite"
  []
  (println "Compiling Squint...")
  (p/shell "npx squint compile")
  (println "Bundling with Vite...")
  (p/shell "npx vite build")
  (println "✓ Squint + Vite compilation complete"))

(defn- adjust-manifest
  "Adjust manifest.json for specific browser"
  [manifest browser]
  (case browser
    "firefox" (assoc manifest
                     :browser_specific_settings
                     {:gecko {:id "browser-jack-in@example.com"
                              :strict_min_version "142.0"
                              :data_collection_permissions
                              {:required ["none"]}}})
    manifest))

(defn build
  "Build extension for specified browser(s)"
  [& browsers]
  (let [browsers (if (seq browsers) browsers ["chrome" "firefox" "safari"])
        src-dir "src"
        vite-dir "dist-vite"
        dist-dir "dist"]
    ;; Compile Squint + bundle with Vite
    (compile-squint)
    (fs/create-dirs dist-dir)
    (doseq [browser browsers]
      (println (str "Building for " browser "..."))
      (let [browser-dir (str dist-dir "/" browser)]
        ;; Clean and copy source
        (when (fs/exists? browser-dir)
          (fs/delete-tree browser-dir))
        (fs/copy-tree src-dir browser-dir)

        ;; Copy Vite-bundled popup files over the source ones
        (fs/copy (str vite-dir "/popup.html") (str browser-dir "/popup.html")
                 {:replace-existing true})
        (fs/copy (str vite-dir "/popup.js") (str browser-dir "/popup.js")
                 {:replace-existing true})
        (fs/copy (str vite-dir "/popup.css") (str browser-dir "/popup.css")
                 {:replace-existing true})
        ;; Remove the unbundled mjs file
        (fs/delete-if-exists (str browser-dir "/popup.mjs"))

        ;; Adjust manifest
        (let [manifest-path (str browser-dir "/manifest.json")
              manifest (json/read-str (slurp manifest-path) :key-fn keyword)
              adjusted (adjust-manifest manifest browser)]
          (spit manifest-path (json/write-str adjusted :indent true)))

        ;; Create zip
        (let [zip-path (str dist-dir "/browser-jack-in-" browser ".zip")]
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

(defn- read-manifest-version
  "Read current version from manifest.json"
  []
  (let [manifest (json/read-str (slurp "src/manifest.json") :key-fn keyword)]
    (:version manifest)))

(defn- increment-version
  "Increment patch version: 0.0.1 -> 0.0.2"
  [version]
  (let [parts (str/split version #"\.")
        patch (Integer/parseInt (last parts))
        new-patch (inc patch)]
    (str/join "." (concat (butlast parts) [new-patch]))))

(defn- update-manifest-version!
  "Update version in manifest.json"
  [new-version]
  (let [manifest-path "src/manifest.json"
        manifest (json/read-str (slurp manifest-path) :key-fn keyword)
        updated (assoc manifest :version new-version)]
    (spit manifest-path (json/write-str updated :indent true))))

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
        current-version (read-manifest-version)
        new-version (increment-version current-version)
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
    (println (str "   Current version: " current-version))
    (println (str "   New version:     " new-version))

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
        (println "📦 Updating manifest.json...")
        (update-manifest-version! new-version)

        (println "📝 Updating CHANGELOG.md...")
        (update-changelog! new-version unreleased-content)

        (println "💾 Committing changes...")
        (p/shell "git add src/manifest.json CHANGELOG.md")
        (p/shell {:continue true} "git" "commit" "-m" (str "Release v" new-version))

        (println (str "🏷️  Creating tag v" new-version "..."))
        (p/shell "git" "tag" (str "v" new-version))

        (println "🚀 Pushing to origin...")
        (p/shell "git push origin master")
        (p/shell "git" "push" "origin" (str "v" new-version))

        (println)
        (println (str "✅ Released v" new-version "!"))
        (println "   GitHub Actions will now build and create the release.")))))
