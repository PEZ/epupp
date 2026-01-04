(ns config
  "Build configuration for Browser Jack-in.
   
   GENERATED FILE - Do not edit directly!
   Edit config/dev.edn or config/prod.edn instead.")

;; Generated from config/prod.edn
(def dev? false)

(def sci-nrepl-coords
  {:git/sha "1042578d5784db07b4d1b6d974f1db7cabf89e3f"})

(defn format-deps-string
  "Format the sci.nrepl coords for the bb -Sdeps command"
  []
  (str "{:deps {io.github.babashka/sci.nrepl {:git/sha \"1042578d5784db07b4d1b6d974f1db7cabf89e3f\"}}}"))
