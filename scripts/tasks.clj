(ns tasks
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]))

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
    (println "✓ Scittle" version "bundled to" vendor-dir)))
