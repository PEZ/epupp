{:epupp/script-name "epupp/sponsor.cljs"
 :epupp/auto-run-match "https://github.com/sponsors/PEZ*"
 :epupp/description "Detects GitHub sponsor status for Epupp"
 :epupp/run-at "document-idle"}

(ns epupp.sponsor)

;; Privacy: This script only sends a single boolean (sponsor/not-sponsor)
;; back to Epupp. No GitHub username or other personal data is collected.
;; The source code is visible in the Epupp popup script list for anyone
;; to verify.

(def forever-sponsors
  {"PEZ" "Thanks for Epupp and Calva! You have status in my heart as a forever sponsor."
   "borkdude" "Thanks for SCI, Squint, Babashka, Scittle, Joyride, and all the things! You have status in my heart as a forever sponsor of Epupp and Calva."
   "richhickey" "Thanks for Clojure! You have status in my heart as a forever sponsor of Epupp and Calva."
   "swannodette" "Thanks for stewarding ClojureScript for all these years! You have status in my heart as a forever sponsor of Epupp and Calva."
   "thheller" "Thanks for shadow-cljs! You have status in my heart as a forever sponsor of Epupp and Calva."})

(defn send-sponsor-status! []
  (js/window.postMessage
   #js {:source "epupp-userscript"
        :type "sponsor-status"
        :sponsor true}
   "*"))

(defn insert-banner! [text style]
  (let [container (js/document.querySelector ".container-lg.p-responsive")
        msg (js/document.createElement "div")]
    (set! (.-innerHTML msg)
          (str "<div style='" style "'>" text "</div>"))
    (when container
      (.insertBefore container msg (.-firstChild container)))))

(def sponsor-banner-style
  "padding: 12px 16px; margin: 8px 0; background: #e8f5e9; border-left: 3px solid #4caf50; border-radius: 4px;")

(def encourage-banner-style
  "padding: 12px 16px; margin: 8px 0; background: #e3f2fd; border-left: 3px solid #5881d8; border-radius: 4px;")

(defn detect-and-act! []
  (let [params (js/URLSearchParams. (.-search js/window.location))
        just-sponsored? (= "true" (.get params "success"))
        user-login (.-content (js/document.querySelector "meta[name='user-login']"))
        logged-in? (and (string? user-login) (not (empty? user-login)))
        forever-message (when logged-in? (get forever-sponsors user-login))
        h1 (js/document.querySelector "h1.f2")
        h1-text (when h1 (.trim (.-textContent h1)))
        body-text (.-textContent js/document.body)
        has-sponsoring-as? (re-find #"Sponsoring as" body-text)
        login-flash (->> (array-seq (js/document.querySelectorAll ".flash.flash-warn"))
                         (filter #(re-find #"must be logged in" (.-textContent %)))
                         first)]
    (cond
      forever-message
      (do
        (insert-banner! (str forever-message " <span style=\"color: #e91e63;\">&#9829;</span>")
                        sponsor-banner-style)
        (send-sponsor-status!))

      just-sponsored?
      (do
        (insert-banner! "Thanks for sponsoring me! <span style=\"color: #e91e63;\">&#9829;</span>"
                        sponsor-banner-style)
        (send-sponsor-status!))

      (not logged-in?)
      (when login-flash
        (let [msg (js/document.createElement "div")]
          (set! (.-textContent msg) "Log in to GitHub to update your Epupp sponsor status.")
          (set! (.. msg -style -cssText)
                "padding: 8px 16px; color: #856404; background: #fff3cd; text-align: center;")
          (.insertAdjacentElement login-flash "afterend" msg)))

      (and h1-text (re-find #"Become a sponsor" h1-text))
      (insert-banner! (str "Sponsor PEZ to light up your Epupp sponsor heart! "
                           "<span style=\"color: #e91e63;\">&#9829;</span>")
                      encourage-banner-style)

      has-sponsoring-as?
      (do
        (insert-banner! "Thanks for sponsoring me! <span style=\"color: #e91e63;\">&#9829;</span>"
                        sponsor-banner-style)
        (send-sponsor-status!))

      :else nil)))

(detect-and-act!)

(when js/window.navigation
  (let [!nav-timeout (atom nil)
        !last-url (atom js/window.location.href)]
    (.addEventListener js/window.navigation "navigate"
                       (fn [evt]
                         (let [new-url (.-url (.-destination evt))]
                           (when (not= new-url @!last-url)
                             (reset! !last-url new-url)
                             (when-let [tid @!nav-timeout]
                               (js/clearTimeout tid))
                             (reset! !nav-timeout
                                     (js/setTimeout detect-and-act! 300))))))))
