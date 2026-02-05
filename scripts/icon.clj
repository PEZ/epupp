(ns icon
  "Generate extension toolbar icons from epupp-logo hiccup component"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [hiccup.core :as h]
            [icons :refer [epupp-logo]]))

(defn- svg-string
  "Convert hiccup SVG to complete SVG string with XML declaration"
  [hiccup-svg]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       (h/html hiccup-svg)))

(defn- generate-pngs!
  "Generate PNG files at multiple sizes from SVG using rsvg-convert"
  [svg-path base-name]
  (let [sizes [16 32 48 128]
        output-dir "extension/icons"]
    (doseq [size sizes]
      (let [output-path (str output-dir "/" base-name "-" size ".png")]
        (println (str "  → " output-path))
        (p/shell "rsvg-convert"
                 "-w" (str size)
                 "-h" (str size)
                 svg-path
                 "-o" output-path)))))

(defn- generate-icon!
  "Generate SVG and PNGs for one icon state"
  [connected? label]
  (println (str "Generating " label " icons..."))
  (let [output-dir "extension/icons"
        base-name (if connected? "icon-connected" "icon-disconnected")
        svg-path (str output-dir "/" base-name ".svg")
        logo-svg (epupp-logo {:size 100 :connected? connected?})
        svg-content (svg-string logo-svg)]
    (fs/create-dirs output-dir)
    (spit svg-path svg-content)
    (println (str "  ✓ " svg-path))
    (generate-pngs! svg-path base-name)
    (println (str "  ✓ Generated " base-name " PNGs"))))

(defn generate-all!
  "Generate all toolbar icons (connected and disconnected states)"
  []
  (println "Generating Epupp toolbar icons from hiccup...")
  (generate-icon! false "disconnected")
  (generate-icon! true "connected")
  (println "✓ All icons generated successfully!"))

;; Run when script is executed
(generate-all!)
