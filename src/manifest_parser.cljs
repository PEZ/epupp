(ns manifest-parser
  "Parses Epupp manifest metadata from script code."
  (:require ["edn-data" :as edn-data]
            [clojure.string :as string]))

(def valid-run-at-values
  #{"document-start" "document-end" "document-idle"})

(def default-run-at "document-idle")

(defn- has-epupp-keys?
  "Returns true if parsed EDN object has any epupp/ prefixed keys."
  [parsed]
  (when parsed
    (some #(string/starts-with? % "epupp/") (js/Object.keys parsed))))

(defn extract-manifest
  "Extracts Epupp manifest data from code string containing ^{:epupp/...} metadata.
   Returns a map with extracted fields (script-name, site-match, description, run-at)
   or nil if no valid manifest found."
  [code]
  (try
    (let [parsed (edn-data/parseEDNString code #js {:mapAs "object" :keywordAs "string"})]
      (when (has-epupp-keys? parsed)
        (let [script-name (aget parsed "epupp/script-name")
              site-match (aget parsed "epupp/site-match")
              description (aget parsed "epupp/description")
              raw-run-at (aget parsed "epupp/run-at")
              run-at (if (contains? valid-run-at-values raw-run-at)
                       raw-run-at
                       default-run-at)]
          {"script-name" script-name
           "site-match" site-match
           "description" description
           "run-at" run-at})))
    (catch :default _e nil)))

(defn has-manifest?
  "Returns true if the code contains Epupp manifest metadata."
  [code]
  (boolean (extract-manifest code)))

(defn get-run-at
  "Extracts the run-at timing from code, defaulting to 'document-idle'."
  [code]
  (or (:run-at (extract-manifest code))
      default-run-at))
