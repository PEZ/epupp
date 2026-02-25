(ns scittle-libs
  "Resolution of scittle:// URLs to bundled vendor libraries.
   Handles dependency ordering (e.g., re-frame needs reagent needs react).")

(def library-catalog
  "Catalog of bundled Scittle libraries and their dependencies.
   Keys are :scittle/lib-name keywords.
   Internal libraries (:catalog/internal true) are not directly requestable.
   :catalog/namespaces lists representative namespaces for load verification."
  {:scittle/pprint     {:catalog/file "scittle.pprint.js"
                        :catalog/deps #{:scittle/core}
                        :catalog/namespaces ["cljs.pprint"]}
   :scittle/promesa    {:catalog/file "scittle.promesa.js"
                        :catalog/deps #{:scittle/core}
                        :catalog/namespaces ["promesa.core"]}
   :scittle/replicant  {:catalog/file "scittle.replicant.js"
                        :catalog/deps #{:scittle/core}
                        :catalog/namespaces ["replicant.dom"]}
   :scittle/js-interop {:catalog/file "scittle.js-interop.js"
                        :catalog/deps #{:scittle/core}
                        :catalog/namespaces ["applied-science.js-interop"]}
   :scittle/reagent    {:catalog/file "scittle.reagent.js"
                        :catalog/deps #{:scittle/core :scittle/react}
                        :catalog/namespaces ["reagent.core"]}
   :scittle/re-frame   {:catalog/file "scittle.re-frame.js"
                        :catalog/deps #{:scittle/core :scittle/reagent}
                        :catalog/namespaces ["re-frame.core"]}
   :scittle/cljs-ajax  {:catalog/file "scittle.cljs-ajax.js"
                        :catalog/deps #{:scittle/core}
                        :catalog/namespaces ["cljs-http.client"]}
   ;; Internal dependencies (not directly requestable)
   :scittle/core       {:catalog/file "scittle.js"
                        :catalog/internal true}
   :scittle/react      {:catalog/files ["react.production.min.js" "react-dom.production.min.js"]
                        :catalog/internal true}})

(defn scittle-url?
  "Returns true if the URL is a scittle:// URL"
  [url]
  (and (string? url)
       (boolean (re-matches #"scittle://.*" url))))

(defn resolve-scittle-url
  "Resolve scittle:// URL to library key (:scittle/lib-name keyword).
   Returns nil for invalid URLs, unknown libraries, or internal libraries."
  [url]
  (when (string? url)
    (when-let [[_ lib-name] (re-matches #"scittle://(.+)\.js" url)]
      ;; In Squint, keywords are strings, so :scittle/pprint = "scittle/pprint"
      (let [lib-key (str "scittle/" lib-name)]
        (when-let [lib (get library-catalog lib-key)]
          (when-not (:catalog/internal lib)
            lib-key))))))

(defn get-library-files
  "Get the vendor file(s) for a library key.
   Returns vector of filenames in load order."
  [lib-key]
  (when-let [lib (get library-catalog lib-key)]
    (if (:catalog/files lib)
      (:catalog/files lib)
      [(:catalog/file lib)])))

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
                  (doseq [dep (:catalog/deps lib)]
                    (visit dep))
                  (when-not (:catalog/internal lib)
                    (swap! result conj k)))))]
      (visit lib-key)
      @result)))

(defn expand-inject
  "Expand a scittle:// require URL to ordered list of vendor files.
   Returns {:lib lib-key :files [...]} or nil if invalid.
   Includes all dependencies (internal deps like React injected first)."
  [url]
  (when-let [lib-key (resolve-scittle-url url)]
    (when-let [lib (get library-catalog lib-key)]
      (when-not (:catalog/internal lib)
        (let [all-deps (resolve-dependencies lib-key)
              ;; Check if any dep needs React
              needs-react? (some (fn [k]
                                   (when-let [l (get library-catalog k)]
                                     (contains? (:catalog/deps l) :scittle/react)))
                                 (conj all-deps lib-key))
              ;; Add internal deps first
              internal-files (if needs-react?
                               (get-library-files :scittle/react)
                               [])]
          {:inject/lib lib-key
           :inject/files (vec (concat internal-files
                                       (mapcat get-library-files all-deps)))})))))

(defn available-libraries
  "Returns vector of available library keys (excluding internal)."
  []
  (->> library-catalog
       (remove (fn [[_ v]] (:catalog/internal v)))
       (map first)
       sort
       vec))

(defn collect-lib-files
  "Collect all library files from multiple scripts.
   Processes each script's :script/inject and expands scittle:// URLs.
   Returns deduplicated vector of vendor filenames in correct load order.
   Non-scittle URLs are ignored (may be handled elsewhere)."
  [scripts]
  (let [all-injects (mapcat #(get % :script/inject []) scripts)
        scittle-urls (filter #(and (string? %) (.startsWith % "scittle://")) all-injects)
        expanded (keep expand-inject scittle-urls)
        all-files (mapcat :inject/files expanded)]
    ;; Dedupe while preserving order (earlier files stay)
    (vec (distinct all-files))))

(defn collect-lib-namespaces
  "Collect representative namespaces to verify for a set of scripts.
   Returns a vector of namespace name strings that should be available
   after all libraries are loaded."
  [scripts]
  (let [all-injects (mapcat #(get % :script/inject []) scripts)
        scittle-urls (filter #(and (string? %) (.startsWith % "scittle://")) all-injects)
        lib-keys (keep resolve-scittle-url scittle-urls)]
    (->> lib-keys
         (mapcat (fn [k] (:catalog/namespaces (get library-catalog k))))
         (filter some?)
         vec)))
