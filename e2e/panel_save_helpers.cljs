(ns panel-save-helpers
  (:require [clojure.string :as string]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata.
   Uses the official manifest keys: :epupp/script-name, :epupp/site-match, etc."
  [{:keys [name match description run-at code]
    :or {code "(println \"Test script\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/site-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\"")))
        meta-block (when (seq meta-parts)
                     (str "{" (string/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))
