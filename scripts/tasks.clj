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
