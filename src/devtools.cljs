(ns devtools
  "DevTools entry point for Browser Jack-in extension")

;; This file creates the DevTools panel
(when (and js/chrome js/chrome.devtools js/chrome.devtools.panels)
  (.create js/chrome.devtools.panels
           "Browser Jack-in"                    ; Panel title
           "icons/icon-16.png"                  ; Panel icon
           "panel.html"                         ; Panel page
           (fn [panel]
             (println "Browser Jack-in DevTools panel created"))))
