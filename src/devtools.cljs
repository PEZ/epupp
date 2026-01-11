(ns devtools
  "DevTools entry point - registers the Epupp panel"
  (:require [log :as log]))

(js/chrome.devtools.panels.create
 "Epupp"
 "icons/icon-32.png"
 "panel.html"
 (fn [_panel]
   (log/info "DevTools" nil "Panel created")))
