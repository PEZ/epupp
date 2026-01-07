(ns devtools
  "DevTools entry point - registers the Epupp panel")

(js/chrome.devtools.panels.create
 "Epupp"
 "icons/icon-32.png"
 "panel.html"
 (fn [_panel]
   (js/console.log "[DevTools] Panel created")))
