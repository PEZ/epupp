(ns scittle-libs
  "Resolution of scittle:// URLs to bundled vendor libraries.
   Handles dependency ordering (e.g., re-frame needs reagent needs react).")

(def library-catalog
  "Catalog of bundled Scittle libraries and their dependencies.
   Keys are library names as strings (Squint uses strings for keywords).
   Internal libraries (:internal true) are not directly requestable."
  {"pprint"     {:file "scittle.pprint.js"
                 :deps #{"core"}}
   "promesa"    {:file "scittle.promesa.js"
                 :deps #{"core"}}
   "replicant"  {:file "scittle.replicant.js"
                 :deps #{"core"}}
   "js-interop" {:file "scittle.js-interop.js"
                 :deps #{"core"}}
   "reagent"    {:file "scittle.reagent.js"
                 :deps #{"core" "react"}}
   "re-frame"   {:file "scittle.re-frame.js"
                 :deps #{"core" "reagent"}}
   "cljs-ajax"  {:file "scittle.cljs-ajax.js"
                 :deps #{"core"}}
   ;; Internal dependencies (not directly requestable)
   "core"       {:file "scittle.js"
                 :internal true}
   "react"      {:files ["react.production.min.js" "react-dom.production.min.js"]
                 :internal true}})

(defn scittle-url?
  "Returns true if the URL is a scittle:// URL"
  [url]
  (and (string? url)
       (boolean (re-matches #"scittle://.*" url))))

(defn resolve-scittle-url
  "Resolve scittle:// URL to library key (string).
   Returns nil for invalid URLs, unknown libraries, or internal libraries."
  [url]
  (when (string? url)
    (when-let [[_ lib-name] (re-matches #"scittle://(.+)\.js" url)]
      (when-let [lib (get library-catalog lib-name)]
        (when-not (:internal lib)
          lib-name)))))

(defn get-library-files
  "Get the vendor file(s) for a library key.
   Returns vector of filenames in load order."
  [lib-key]
  (when-let [lib (get library-catalog lib-key)]
    (if (:files lib)
      (:files lib)
      [(:file lib)])))

(defn resolve-dependencies
  "Resolve all dependencies for a library key.
   Returns vector of library keys in load order (topological sort).
   Excludes internal libraries from result."
  [lib-key]
  (let [visited (atom #{})
        result (atom [])]
    (letfn [(visit [k]
              (when-not (contains? @visited k)
                (swap! visited conj k)
                (when-let [lib (get library-catalog k)]
                  (doseq [dep (:deps lib)]
                    (visit dep))
                  (when-not (:internal lib)
                    (swap! result conj k)))))]
      (visit lib-key)
      @result)))

(defn expand-require
  "Expand a scittle:// require URL to ordered list of vendor files.
   Returns {:lib lib-key :files [...]} or nil if invalid.
   Includes all dependencies (internal deps like React injected first)."
  [url]
  (when-let [lib-key (resolve-scittle-url url)]
    (when-let [lib (get library-catalog lib-key)]
      (when-not (:internal lib)
        (let [all-deps (resolve-dependencies lib-key)
              ;; Check if any dep needs React
              needs-react? (some (fn [k]
                                   (when-let [l (get library-catalog k)]
                                     (contains? (:deps l) "react")))
                                 (conj all-deps lib-key))
              ;; Add internal deps first
              internal-files (if needs-react?
                               (get-library-files "react")
                               [])]
          {:lib lib-key
           :files (vec (concat internal-files
                               (mapcat get-library-files all-deps)))})))))

(defn available-libraries
  "Returns vector of available library keys (excluding internal)."
  []
  (->> library-catalog
       (remove (fn [[_ v]] (:internal v)))
       (map first)
       sort
       vec))

(defn collect-require-files
  "Collect all required library files from multiple scripts.
   Processes each script's :script/require and expands scittle:// URLs.
   Returns deduplicated vector of vendor filenames in correct load order.
   Non-scittle URLs are ignored (may be handled elsewhere)."
  [scripts]
  (let [all-requires (mapcat #(get % :script/require []) scripts)
        scittle-urls (filter #(and (string? %) (.startsWith % "scittle://")) all-requires)
        expanded (keep expand-require scittle-urls)
        all-files (mapcat :files expanded)]
    ;; Dedupe while preserving order (earlier files stay)
    (vec (distinct all-files))))
