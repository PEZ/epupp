(ns view-elements
  "Shared UI components used by popup and panel."
  (:require [icons :as icons]))

(defn app-header
  "Common header component for both popup and panel.
   Options:
   - :elements/wrapper-class: CSS class for the wrapper div
   - :elements/header-class: CSS class for the header div
   - :elements/icon: hiccup for the icon (default: icon-32.png image)
   - :elements/status: optional status text (panel shows 'Ready')
   - :elements/banner: optional banner element to show above header"
  [{:elements/keys [wrapper-class header-class icon status banner]}]
  [:div {:class (str "app-header-wrapper " wrapper-class)}
   (when banner banner)
   [:div {:class (str "app-header " header-class)}
    [:div.app-header-title
     (or icon [:img {:src "icons/icon-32.png" :alt ""}])
     [:span.app-header-title-text
      "Epupp"
      [:span.tagline "Live Tamper your Web"]]]
    (when status
      [:div.app-header-status status])]])

(defn app-footer
  "Common footer component for both popup and panel.
   Options:
   - :elements/wrapper-class: CSS class for view-specific styling"
  [{:elements/keys [wrapper-class]}]
  [:div {:class (str "app-footer " wrapper-class)}
   [:div.footer-powered
    "Powered by "
    [:a {:href "https://github.com/babashka/scittle"
         :target "_blank"
         :title "Scittle - Small Clojure Interpreter exposed for script tags"}
     "Scittle"]]
   [:div.footer-logos
    [:a {:href "https://github.com/babashka/sci"
         :target "_blank"
         :title "SCI - Small Clojure Interpreter"}
     [:img {:src "images/sci.png" :alt "SCI"}]]
    [:a {:href "https://clojurescript.org/"
         :target "_blank"
         :title "ClojureScript"}
     [:img {:src "images/cljs.svg" :alt "ClojureScript"}]]
    [:a {:href "https://clojure.org/"
         :target "_blank"
         :title "Clojure"}
     [:img {:src "images/clojure.png" :alt "Clojure"}]]]
   [:div.footer-credits
    [:span "Created by "
     [:a {:href "https://github.com/PEZ"
          :target "_blank"
          :title "https://github.com/PEZ"}
      "Peter Strömberg"]
     " a.k.a. PEZ"]
    [:span.iconed-link
     [icons/github {:size 14}]
     [:a {:href "https://github.com/PEZ/browser-jack-in"
          :target "_blank"
          :title "https://github.com/PEZ/browser-jack-in"}
      "Open Source"]]
    [:span.iconed-link
     [icons/heart {:size 14 :class "heart-icon"}]
     [:a {:href "https://github.com/sponsors/PEZ"
          :target "_blank"
          :title "https://github.com/sponsors/PEZ"}
      "Sponsor"]]]])
