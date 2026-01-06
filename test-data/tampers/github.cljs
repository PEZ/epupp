(ns tampers.github)

(defonce !db (atom {:db/foo "bar"}))

(comment

  @!db

  (swap! !db assoc :db/bar 42)

  (js/console.log "hello")
  (js/console.log "Hello from:" js/window.location.href)
  js/window.navigator.appCodeName

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
  (set! (.-title js/document) "🎉 Scittle Tamper works! 🎉")

  ;; Add festive gradient to the header
  (when-let [header (js/document.querySelector "header")]
    (set! (.. header -style -background) "linear-gradient(90deg, #91caff, #b8a3ff, #f0a8d0)")
    (set! (.. header -style -transition) "background 0.5s ease"))

  (js/alert "Hello!")

  :rcf)

(comment
  ;; GitHub Party Mode - Scittle Tamper Userscript
  ;; Pattern: https://github.com/*

  ;; Add all the keyframe animations
  (let [style-el (js/document.createElement "style")]
    (set! (.-textContent style-el)
          "
  @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
  @keyframes bounce { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-20px); } }
  @keyframes shake { 0%, 100% { transform: translateX(0); } 25% { transform: translateX(-10px); } 75% { transform: translateX(10px); } }
  @keyframes rainbow { 0% { filter: hue-rotate(0deg); } 100% { filter: hue-rotate(360deg); } }
  @keyframes pulse { 0%, 100% { transform: scale(1); } 50% { transform: scale(1.1); } }
  @keyframes disco { 0% { background: red; } 20% { background: yellow; } 40% { background: lime; } 60% { background: cyan; } 80% { background: magenta; } 100% { background: red; } }
  @keyframes wiggle { 0%, 100% { transform: rotate(-3deg); } 50% { transform: rotate(3deg); } }
  @keyframes floatUp { 0% { bottom: -50px; opacity: 1; transform: rotate(0deg); }
                       100% { bottom: 110vh; opacity: 0; transform: rotate(720deg); } }
  @keyframes gradientMove { 0% { background-position: 0% 50%; } 50% { background-position: 100% 50%; } 100% { background-position: 0% 50%; } }

  * { cursor: url('data:image/svg+xml,<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"32\" height=\"32\"><text y=\"24\" font-size=\"24\">✨</text></svg>'), auto !important; }
          ")
    (.appendChild js/document.head style-el))

  ;; Rainbow spinning images
  (doseq [img (array-seq (js/document.querySelectorAll "img"))]
    (set! (-> img .-style .-animation) "rainbow 2s linear infinite, spin 4s linear infinite"))

  ;; Bouncing links
  (doseq [link (array-seq (js/document.querySelectorAll "a"))]
    (let [delay (str (* (rand) 0.5) "s")]
      (set! (-> link .-style .-animation) (str "bounce 0.5s ease-in-out infinite " delay))))

  ;; DISCO BODY!
  (set! (-> js/document.body .-style .-animation) "disco 1s linear infinite")

  ;; Shaking headings with neon shadows
  (doseq [h (array-seq (js/document.querySelectorAll "h1, h2, h3, h4"))]
    (let [style (.-style h)]
      (set! (.-animation style) "shake 0.3s ease-in-out infinite")
      (set! (.-textShadow style) "2px 2px 4px magenta, -2px -2px 4px cyan")))

  ;; Pulsing buttons
  (doseq [btn (array-seq (js/document.querySelectorAll "button, .btn"))]
    (set! (-> btn .-style .-animation) "pulse 0.8s ease-in-out infinite"))

  ;; Wiggling divs
  (doseq [div (take 50 (array-seq (js/document.querySelectorAll "div")))]
    (let [delay (str (* (rand) 2) "s")]
      (set! (-> div .-style .-animation) (str "wiggle 0.5s ease-in-out infinite " delay))))

  ;; Rainbow text
  (doseq [p (array-seq (js/document.querySelectorAll "p, span"))]
    (set! (-> p .-style .-animation) "rainbow 1s linear infinite"))

  ;; Floating emojis
  (defn create-floating-emoji []
    (let [emoji (js/document.createElement "div")
          emojis ["🎉" "🚀" "✨" "🔥" "💜" "🎸" "🦄" "🌈" "⚡" "🎪" "🍕" "λ"]
          x (rand-int 100)
          duration (+ 3 (rand-int 5))]
      (set! (.-textContent emoji) (nth emojis (rand-int (count emojis))))
      (set! (-> emoji .-style .-cssText)
            (str "position: fixed; left: " x "%; bottom: -50px; font-size: 40px;
                  z-index: 9999; animation: floatUp " duration "s linear forwards; pointer-events: none;"))
      (.appendChild js/document.body emoji)
      (js/setTimeout #(.remove emoji) (* duration 1000))))

  (js/setInterval create-floating-emoji 200)

  ;; BOUNCING PARTY BANNER (Breakout ball style!)
  (let [banner (js/document.createElement "div")
        state (atom {:x 100 :y 100 :vx 4 :vy 3 :scale 1 :scale-dir 0.02})]

    (set! (.-innerHTML banner) "🎉 CLOJURE PARTY MODE 🎉")
    (.setAttribute banner "data-party-banner" "true")
    (set! (-> banner .-style .-cssText)
          "position: fixed; padding: 15px 25px; font-size: 24px; font-weight: bold;
           text-align: center; z-index: 10000; border-radius: 20px;
           background: linear-gradient(90deg, red, orange, yellow, green, blue, indigo, violet);
           background-size: 400% 400%; animation: gradientMove 2s ease infinite;
           color: white; text-shadow: 2px 2px black; white-space: nowrap;
           box-shadow: 0 0 20px rgba(255,255,255,0.5);")
    (.appendChild js/document.body banner)

    (defn animate []
      (let [{:keys [x y vx vy scale scale-dir]} @state
            rect (.getBoundingClientRect banner)
            w (.-width rect)
            h (.-height rect)
            max-x (- js/window.innerWidth w)
            max-y (- js/window.innerHeight h)
            new-vx (if (or (< x 0) (> x max-x)) (- vx) vx)
            new-vy (if (or (< y 0) (> y max-y)) (- vy) vy)
            new-x (+ x new-vx)
            new-y (+ y new-vy)
            new-scale (+ scale scale-dir)
            new-scale-dir (cond (> new-scale 1.3) -0.02 (< new-scale 0.7) 0.02 :else scale-dir)]
        (reset! state {:x new-x :y new-y :vx new-vx :vy new-vy :scale new-scale :scale-dir new-scale-dir})
        (set! (-> banner .-style .-left) (str new-x "px"))
        (set! (-> banner .-style .-top) (str new-y "px"))
        (set! (-> banner .-style .-transform) (str "scale(" new-scale ")"))
        (js/requestAnimationFrame animate)))

    (js/requestAnimationFrame animate))

  (println "🎉 GitHub Party Mode Activated!"))