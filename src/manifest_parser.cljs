(ns manifest-parser
  "Parses Epupp manifest metadata from script code.
   Returns rich structure with raw values, coerced values, and warnings."
  (:require ["edn-data" :as edn-data]
            [clojure.string :as string]
            [script-utils :as script-utils]))

(def valid-run-at-values
  #{"document-start" "document-end" "document-idle"})

(def default-run-at "document-idle")

(def known-epupp-keys
  "Set of known epupp manifest keys."
  #{"epupp/script-name" "epupp/site-match" "epupp/description" "epupp/run-at" "epupp/inject"})

(defn- get-epupp-keys
  "Returns vector of all epupp/ prefixed keys found in parsed object."
  [parsed]
  (when parsed
    (->> (js/Object.keys parsed)
         (filter #(string/starts-with? % "epupp/"))
         vec)))

(defn normalize-inject
  "Normalize :epupp/inject to vector of strings.
   Accepts nil, string, or vector/array."
  [require-value]
  (cond
    (nil? require-value) []
    (string? require-value) [require-value]
    (vector? require-value) (vec require-value)
    (array? require-value) (vec require-value)
    :else []))



(defn extract-manifest
  "Extracts Epupp manifest data from code string containing ^{:epupp/...} metadata.
   Returns a rich map with:
   - :script-name - normalized name (or nil if missing)
   - :raw-script-name - original name before normalization
   - :name-normalized? - true if normalization changed the name
   - :site-match - URL pattern(s), preserved as string or vector
   - :description - description text (validated as string)
   - :run-at - timing value (defaults to document-idle)
   - :raw-run-at - original run-at value
   - :run-at-invalid? - true if run-at was invalid and defaulted
   - :require - vector of require URLs (normalized from string/vector/nil)
   - :found-keys - vector of all epupp/* keys found
   - :unknown-keys - vector of unrecognized epupp/* keys
   Returns nil if no valid manifest found."
  [code]
  (let [parsed (edn-data/parseEDNString code #js {:mapAs "object" :keywordAs "string"})
        found-keys (get-epupp-keys parsed)]
    (when (seq found-keys)
      (let [;; Raw values from manifest
            raw-script-name (aget parsed "epupp/script-name")
            site-match (aget parsed "epupp/site-match")
            description (let [d (aget parsed "epupp/description")]
                          (when (string? d) d))
            raw-run-at (aget parsed "epupp/run-at")
            raw-inject (aget parsed "epupp/inject")
            ;; Coerced values
            script-name (when raw-script-name
                          (script-utils/normalize-script-name raw-script-name))
            name-normalized? (and raw-script-name
                                  (not= raw-script-name script-name))
            run-at-invalid? (and raw-run-at
                                 (not (contains? valid-run-at-values raw-run-at)))
            run-at (if (or (nil? raw-run-at) run-at-invalid?)
                     default-run-at
                     raw-run-at)
            inject-urls (normalize-inject raw-inject)
            ;; Identify unknown keys
            unknown-keys (->> found-keys
                              (remove #(contains? known-epupp-keys %))
                              vec)]
        {"script-name" script-name
         "raw-script-name" raw-script-name
         "name-normalized?" name-normalized?
         "site-match" site-match
         "description" description
         "run-at" run-at
         "raw-run-at" raw-run-at
         "run-at-invalid?" run-at-invalid?
         "inject" inject-urls
         "found-keys" found-keys
         "unknown-keys" unknown-keys}))))

(defn has-manifest?
  "Returns true if the code contains Epupp manifest metadata."
  [code]
  (boolean (extract-manifest code)))

(defn get-run-at
  "Extracts the run-at timing from code, defaulting to 'document-idle'."
  [code]
  (or (:run-at (extract-manifest code))
      default-run-at))
