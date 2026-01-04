(ns devtools
  "DevTools entry point - registers the Browser Jack-in panel")

(js/chrome.devtools.panels.create
 "Browser Jack-in"
 "icons/icon-32.png"
 "panel.html"
 (fn [_panel]
   (js/console.log "[DevTools] Panel created")))
