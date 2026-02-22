{:epupp/script-name "epupp/sponsor.cljs"
 :epupp/auto-run-match "https://github.com/sponsors/PEZ*"
 :epupp/description "Detects GitHub sponsor status for Epupp"
 :epupp/run-at "document-idle"}

(ns epupp.sponsor)

;; Privacy: This script only sends a single boolean (sponsor/not-sponsor)
;; back to Epupp. No GitHub username or other personal data is collected.
;; The source code is visible in the Epupp popup script list for anyone
;; to verify.

;; ============================================================
;; Extension communication
;; ============================================================

(defonce !icon-url (atom nil))
(defonce !sponsored-username (atom nil))

(defn- send-and-receive
  "Send a message to the content bridge and return a promise of the response.
   Resolves with the response message object, or nil on timeout."
  [msg-type response-type]
  (js/Promise.
   (fn [resolve _reject]
     (let [req-id (str "sponsor-" (js/Date.now) "-" (js/Math.random))
           timeout-id (atom nil)
           handler (fn handler [e]
                     (when (= (.-source e) js/window)
                       (let [msg (.-data e)]
                         (when (and msg
                                    (= "epupp-bridge" (.-source msg))
                                    (= response-type (.-type msg))
                                    (= req-id (.-requestId msg)))
                           (when-let [tid @timeout-id]
                             (js/clearTimeout tid))
                           (.removeEventListener js/window "message" handler)
                           (resolve msg)))))]
       (.addEventListener js/window "message" handler)
       (reset! timeout-id
               (js/setTimeout
                (fn []
                  (.removeEventListener js/window "message" handler)
                  (resolve nil))
                2000))
       (.postMessage js/window
                     #js {:source "epupp-page"
                          :type msg-type
                          :requestId req-id}
                     "*")))))

(defn ^:async fetch-icon-url!+
  "Fetch the Epupp icon URL from the extension via content bridge.
   Returns a Promise that resolves with the URL string or nil on timeout."
  []
  (let [msg (await (send-and-receive "get-icon-url" "get-icon-url-response"))]
    (when msg (.-url msg))))

(defn ^:async fetch-sponsored-username!+
  "Fetch the expected sponsor username from the extension via content bridge.
   Returns a Promise that resolves with the username string or nil on timeout."
  []
  (let [msg (await (send-and-receive "get-sponsored-username" "get-sponsored-username-response"))]
    (when msg (.-username msg))))
;; ============================================================
;; Forever sponsors
;; ============================================================

(def ^:private forever-sponsors
  {"PEZ" "Thanks to myself for Epupp, Calva, Joyride, and Backseat Driver!"
   "borkdude" "Thanks for SCI, Squint, Babashka, Scittle, Joyride, and all the things! You have status in my heart as a forever sponsor of Epupp and Calva."
   "richhickey" "Thanks for Clojure! You have status in my heart as a forever sponsor of Epupp and Calva."
   "swannodette" "Thanks for stewarding ClojureScript for all these years! You have status in my heart as a forever sponsor of Epupp and Calva."
   "thheller" "Thanks for shadow-cljs! You have status in my heart as a forever sponsor of Epupp and Calva."})

;; ============================================================
;; Messaging
;; ============================================================

(defn- send-sponsor-status! []
  (js/window.postMessage
   #js {:source "epupp-userscript"
        :type "sponsor-status"
        :sponsor true}
   "*"))

;; ============================================================
;; Branded banner rendering
;; ============================================================

(def ^:private banner-styles
  "<style>
  .epupp-sponsor-banner {
    background-image: linear-gradient(color-mix(in srgb, var(--bgColor-attention-muted) 75%, transparent), color-mix(in srgb, var(--bgColor-attention-muted) 75%, transparent)) !important;
  }
  .epupp-sponsor-banner-inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    margin: 0 auto;

  }
  .epupp-sponsor-brand {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 16px;
  }
  .epupp-sponsor-brand img {
    width: 32px;
    height: 32px;
  }
  .epupp-sponsor-brand-name {
    font-weight: 600;
  }
  .epupp-sponsor-brand-tagline {
    font-style: italic;
  }
  .epupp-sponsor-message {
    text-align: right;
    flex-shrink: 0;
  }
</style>")

(defn- remove-existing-banner!
  "Remove any previously inserted Epupp sponsor banner."
  []
  (doseq [el (array-seq (js/document.querySelectorAll "[data-epupp-sponsor-banner]"))]
    (.remove el)))

(defn render-banner!
  "Render a branded Epupp banner at the top of <main> with the given message."
  [message-html]
  (remove-existing-banner!)
  (let [main-el (js/document.querySelector "main")]
    (when main-el
      (let [banner (js/document.createElement "div")
            icon-url @!icon-url
            icon-html (if icon-url
                        (str "<img src=\"" icon-url "\" alt=\"Epupp\" />")
                        "")]
        (.add (.-classList banner) "flash" "flash-warn" "flash-full" "epupp-sponsor-banner")
        (.setAttribute banner "data-epupp-sponsor-banner" "true")
        (set! (.-innerHTML banner)
              (str banner-styles
                   "<div class='epupp-sponsor-banner-inner'>"
                   "<div class='epupp-sponsor-brand'>"
                   icon-html
                   "<span class='epupp-sponsor-brand-name'>Epupp</span>"
                   "<span class='epupp-sponsor-brand-tagline'>Live Tamper your Web</span>"
                   "</div>"
                   "<div class='epupp-sponsor-message'>" message-html "</div>"
                   "</div>"))
        (.insertBefore main-el banner (.-firstChild main-el))))))

;; ============================================================
;; Detection and action
;; ============================================================

(def ^:private heart-span "<span style=\"color: #e91e63;\">&#9829;</span>")

(defn detect-and-act! []
  (let [expected-username @!sponsored-username
        expected-path (when expected-username (str "/sponsors/" expected-username))]
    ;; Only run detection when we know the expected username and we're on their page
    (when (and expected-path
               (.startsWith (.-pathname js/window.location) expected-path))
      (let [params (js/URLSearchParams. (.-search js/window.location))
            just-sponsored? (= "true" (.get params "success"))
            user-login (.-content (js/document.querySelector "meta[name='user-login']"))
            logged-in? (and (string? user-login) (not (empty? user-login)))
            forever-message (when logged-in? (get forever-sponsors user-login))
            h1 (js/document.querySelector "h1.f2")
            h1-text (when h1 (.trim (.-textContent h1)))
            body-text (.-textContent js/document.body)
            has-sponsoring-as? (re-find #"Sponsoring as" body-text)]
        (cond
          ;; Forever sponsor - personalized thank-you, always send true
          forever-message
          (do
            (render-banner! (str forever-message " " heart-span))
            (send-sponsor-status!))

          ;; Just completed a sponsorship (one-time or recurring confirmation)
          just-sponsored?
          (do
            (render-banner! (str "Thanks for sponsoring me! " heart-span))
            (send-sponsor-status!))

          ;; Not logged in
          (not logged-in?)
          (render-banner! "Log in to GitHub to update your Epupp sponsor status")

          ;; Logged in, "Become a sponsor" heading present - not sponsoring
          (and h1-text (re-find #"Become a sponsor" h1-text))
          (render-banner! (str "Sponsor PEZ to light up your Epupp sponsor heart! " heart-span))

          ;; Logged in, "Sponsoring as" present - confirmed recurring sponsor
          has-sponsoring-as?
          (do
            (render-banner! (str "Thanks for sponsoring me! " heart-span))
            (send-sponsor-status!))

          ;; Unknown state - do nothing (graceful degradation)
          :else nil)))))

;; ============================================================
;; SPA navigation guard (defonce prevents listener stacking)
;; ============================================================

(defonce !nav-registered (atom false))

;; ============================================================
;; Initialization
;; ============================================================

(defn- ^:async init! []
  ;; Fetch icon URL once (stored in defonce atom, survives re-injections)
  (when-not @!icon-url
    (try
      (let [url (await (fetch-icon-url!+))]
        (when url
          (reset! !icon-url url)
          ;; Re-render banner with icon if one is already showing
          (when (js/document.querySelector "[data-epupp-sponsor-banner]")
            (detect-and-act!))))
      (catch :default _ nil)))

  ;; Fetch sponsored username once, then run detection
  (if @!sponsored-username
    (detect-and-act!)
    (try
      (let [username (await (fetch-sponsored-username!+))]
        (when username
          (reset! !sponsored-username username))
        (detect-and-act!))
      (catch :default _
        (detect-and-act!))))

  ;; Register SPA navigation listener (once)
  (when (and (not @!nav-registered) js/window.navigation)
    (reset! !nav-registered true)
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
                                       (js/setTimeout detect-and-act! 300)))))))))

(init!)
