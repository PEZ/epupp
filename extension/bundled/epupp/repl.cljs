(ns epupp.repl
  "REPL session utilities.")

(defn- send-and-receive
  "Helper: send message to bridge and return promise of response."
  [msg-type payload response-type]
  (js/Promise.
    (fn [resolve reject]
      (letfn [(handler [e]
                (when (= (.-source e) js/window)
                  (let [msg (.-data e)]
                    (when (and msg
                               (= "epupp-bridge" (.-source msg))
                               (= response-type (.-type msg)))
                      (.removeEventListener js/window "message" handler)
                      (resolve msg)))))]
        (.addEventListener js/window "message" handler)
        (.postMessage js/window
          (clj->js (assoc payload :source "epupp-page" :type msg-type))
          "*")))))

(defn manifest!
  "Load Epupp manifest. Injects required Scittle libraries.
   Returns a promise that resolves when libraries are loaded.

   Example: (epupp.repl/manifest! {:epupp/inject [\"scittle://reagent.js\"]})"
  [m]
  (-> (send-and-receive "load-manifest" {:manifest m} "manifest-response")
      (.then (fn [msg]
               (if (.-success msg)
                 true
                 (throw (js/Error. (.-error msg))))))))
