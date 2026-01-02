(ns tampers.github)

(defonce !db (atom {:db/foo "bar"}))

(comment

  @!db

  (swap! !db assoc :db/bar 42)

  (js/console.log "hello")
  (js/console.log "Hello from:" js/window.location.href)
  js/window.navigator.appCodeName

  ;; What page are we on?
  (.-href js/window.location)

  ;; Query all links
  (let [links (js/document.querySelectorAll "a")]
    (str "Found " (.-length links) " links on the page"))

  ;; Make the GitHub logo spin!
  (when-let [logo (js/document.querySelector ".octicon-mark-github")]
    (set! (.. logo -style -animation) "spin 2s linear infinite")
    ;; Add the keyframes for spinning
    (let [style (js/document.createElement "style")]
      (set! (.-textContent style)
            "@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }")
      (.appendChild js/document.head style)))

  ;; Change the page title
  (set! (.-title js/document) "🎉 Browser Jack-in works! 🎉")

  ;; Add festive gradient to the header
  (when-let [header (js/document.querySelector "header")]
    (set! (.. header -style -background) "linear-gradient(90deg, #91caff, #b8a3ff, #f0a8d0)")
    (set! (.. header -style -transition) "background 0.5s ease"))

  (js/alert "Hello!")

  :rcf)