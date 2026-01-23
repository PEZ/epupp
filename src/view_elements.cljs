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
   - :elements/permanent-banner: optional banner element to show above header (e.g., extension update)
   - :elements/temporary-banner: optional banner element to show below header (e.g., FS sync feedback)"
  [{:elements/keys [wrapper-class header-class icon status permanent-banner temporary-banner]}]
  [:div {:class (str "app-header-wrapper " wrapper-class)}
   (when permanent-banner permanent-banner)
   [:div {:class (str "app-header " header-class)}
    [:div.app-header-title
     (or icon [:img {:src "icons/icon-32.png" :alt ""}])
     [:span.app-header-title-text
      "Epupp"
      [:span.tagline "Live Tamper your Web"]]]
    (when status
      [:div.app-header-status status])]
   (when temporary-banner temporary-banner)])

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

;; ============================================================
;; Shared UI Components
;; ============================================================

(defn action-button
  "Reusable button component with consistent styling.
   Options (namespaced under :button/):
   - :variant - :primary (blue), :success (green), :secondary (ghost), :danger (red)
   - :size - :sm, :md (default), :lg
   - :disabled? - boolean
   - :icon - optional icon component (rendered before label)
   - :on-click - click handler
   - :class - additional CSS classes
   - :title - tooltip text
   - :id - element id

   Note: In Squint, keywords are strings so :primary becomes \"primary\""
  [{:button/keys [variant size disabled? icon on-click class title id]} label]
  (let [;; In Squint, keywords are strings, so (str \"btn-\" :primary) => \"btn-primary\"
        variant-class (when variant (str "btn-" variant))
        size-class (case size
                     :sm "btn-sm"
                     :lg "btn-lg"
                     nil)
        classes (str "btn "
                     "btn-untitled "
                     (when variant-class (str " " variant-class))
                     (when size-class (str " " size-class))
                     (when class (str " " class)))]
    [:button {:class classes
              :disabled disabled?
              :on-click on-click
              :title title
              :id id}
     (when icon [icon {:size (case size :sm 12 :lg 16 14)}])
     (when (and icon label) " ")
     label]))

(defn icon-button
  "Icon-only button component for compact UI actions.
   Options (namespaced under :button/):
   - :icon - required icon component
   - :on-click - click handler
   - :title - tooltip text (required for accessibility)
   - :class - additional CSS classes
   - :disabled? - boolean"
  [{:button/keys [icon on-click title class disabled?]}]
  [:button {:class (str "icon-button" (when class (str " " class)))
            :on-click on-click
            :title title
            :disabled disabled?}
   [icon {:size 16}]])

(defn status-indicator
  "Status indicator with left border accent.
   Options (namespaced under :status/):
   - :type - :success, :error, :warning, :info (determines border color)
   - :class - additional CSS classes

   Note: In Squint, keywords are strings so :success becomes \"success\""
  [{:status/keys [type class]} & children]
  ;; In Squint, keywords are strings, so (str \"status-bar--\" :success) works
  (let [type-class (when type (str "status-bar--" type))]
    (into [:div {:class (str "status-bar " type-class (when class (str " " class)))}]
          children)))

(defn status-text
  "Inline status text with semantic coloring.
   Options (namespaced under :status/):
   - :type - :success, :error (determines text color)
   - :class - additional CSS classes

   Note: In Squint, keywords are strings so :success becomes \"success\""
  [{:status/keys [type class]} text]
  ;; In Squint, keywords are strings, so (str \"status-text--\" :success) works
  (let [type-class (when type (str "status-text--" type))]
    [:span {:class (str "status-text " type-class (when class (str " " class)))}
     text]))

(defn empty-state
  "Empty state placeholder with centered content.
   Options (namespaced under :empty/):
   - :class - additional CSS classes"
  [{:empty/keys [class]} & children]
  (into [:div {:class (str "empty-state " class)}]
        children))

(defn system-banner
  "Single banner component for system messages.
   Options:
   - :type - 'success', 'info', or 'error' (determines banner style)
   - :message - text to display
   - :favicon - optional favicon URL to display before message
   - :leaving - when true, applies leaving animation class"
  [{:keys [type message favicon leaving]}]
  [:div {:class (str "system-banner "
                     (case type
                       "success" "fs-success-banner"
                       "info" "fs-info-banner"
                       "fs-error-banner")
                     (when leaving " leaving"))}
   (when favicon
     [:img.system-banner-favicon {:src favicon :width 16 :height 16}])
   [:span message]])

(defn system-banners
  "Renders a stacked list of system banners.
   Each banner has {:id :type :message :leaving} and expires independently.
   Banners stack vertically in declaration order (oldest at top)."
  [banners]
  (when (seq banners)
    [:div.system-banners-container
     (for [banner banners]
       ^{:key (:id banner)}
       [system-banner banner])]))
