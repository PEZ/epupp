(ns devtools
  "DevTools entry point - registers the Scittle Tamper panel")

(js/chrome.devtools.panels.create
 "Scittle Tamper"
 "icons/icon-32.png"
 "panel.html"
 (fn [_panel]
   (js/console.log "[DevTools] Panel created")))
