(ns icon
  "Generate extension toolbar icons from epupp-logo hiccup component"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [hiccup2.core :as h]
            [icons :refer [epupp-logo]]))

(defn- svg-string
  "Convert hiccup SVG to complete SVG string with XML declaration"
  [hiccup-svg]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       (h/html hiccup-svg)))

(defn- generate-pngs!
  "Generate PNG files at multiple sizes from SVG using rsvg-convert.
   When also-default? is true, also generates icon-{size}.png for manifest."
  [svg-path base-name also-default?]
  (let [sizes [16 32 48 128]
        output-dir "extension/icons"]
    (doseq [size sizes]
      (let [output-path (str output-dir "/" base-name "-" size ".png")]
        (println (str "  → " output-path))
        (p/shell "rsvg-convert"
                 "-w" (str size)
                 "-h" (str size)
                 svg-path
                 "-o" output-path)
        (when also-default?
          (let [default-path (str output-dir "/icon-" size ".png")]
            (println (str "  → " default-path " (manifest default)"))
            (fs/copy output-path default-path {:replace-existing true})))))))

(defn- generate-icon!
  "Generate SVG and PNGs for one icon state.
   When also-default? is true, also generates icon-*.png for manifest."
  [connected? label also-default?]
  (println (str "Generating " label " icons..."))
  (let [output-dir "extension/icons"
        base-name (if connected? "icon-connected" "icon-disconnected")
        svg-path (str output-dir "/" base-name ".svg")
        logo-svg (epupp-logo {:size 100 :connected? connected?})
        svg-content (svg-string logo-svg)]
    (fs/create-dirs output-dir)
    (spit svg-path svg-content)
    (println (str "  ✓ " svg-path))
    (generate-pngs! svg-path base-name also-default?)
    (println (str "  ✓ Generated " base-name " PNGs"))))

(defn generate-all!
  "Generate all toolbar icons (connected and disconnected states).
   Connected icons also generate icon-*.png and icon.svg for manifest default (gold brand identity)."
  []
  (println "Generating Epupp toolbar icons from hiccup...")
  (generate-icon! false "disconnected" false)
  (generate-icon! true "connected" true)   ; also-default? = true for connected (gold)
  ;; Also copy connected SVG to icon.svg for web installer
  (let [src "extension/icons/icon-connected.svg"
        dest "extension/icons/icon.svg"]
    (fs/copy src dest {:replace-existing true})
    (println (str "  → " dest " (web installer)")))
  (println "✓ All icons generated successfully!"))

;; Run when script is executed
(generate-all!)
