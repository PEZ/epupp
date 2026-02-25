(ns view-elements
  "Shared UI components used by popup and panel."
  (:require [icons :as icons]))

(defn app-header
  "Common header component for both popup and panel.
   Options:
   - :elements/wrapper-class: CSS class for the wrapper div
   - :elements/header-class: CSS class for the header div
   - :elements/icon: hiccup for the icon (default: icon-32.png image)
   - :elements/permanent-banner: optional banner element to show above header (e.g., extension update)
   - :elements/temporary-banner: optional banner element to show below header (e.g., FS sync feedback)
   - :elements/sponsor-status: boolean indicating sponsor status
   - :elements/on-sponsor-click: click handler for sponsor heart button"
  [{:elements/keys [wrapper-class header-class icon permanent-banner temporary-banner
                    sponsor-status on-sponsor-click]}]
  [:div {:class (str "app-header-wrapper " wrapper-class)}
   [:div {:class (str "app-header " header-class)}
    [:div.app-header-title
     (or icon [:img {:src "icons/icon-32.png" :alt ""}])
     [:span.app-header-title-text
      "Epupp"
      [:span.tagline "Live Tamper your Web"]]]
    [:button.sponsor-heart
     {:on-click on-sponsor-click
      :title (if sponsor-status
               "Thank you for sponsoring!"
               "Click to update sponsor status")}
     [icons/heart {:size 14
                   :filled? sponsor-status
                   :class (str "sponsor-heart-icon"
                               (when sponsor-status " sponsor-heart-filled"))}]]]
   (when permanent-banner permanent-banner)
   (when temporary-banner temporary-banner)])

(defn- creator-menu [{:elements/keys [on-sponsor-click sponsor-status on-creator-menu-close]}]
  [:div.creator-menu
   [:div.creator-menu-header
    [:span "Peter Strömberg a.k.a. PEZ"]
    [:button.creator-menu-close
     {:on-click on-creator-menu-close
      :title "Close"}
     [icons/close {:size 14}]]]
   [:a.creator-menu-item {:href "https://github.com/PEZ" :target "_blank"}
    [icons/github {:size 16}] [:span "@PEZ"]
    " • "
    [:button.footer-sponsor-heart
     {:on-click (fn [e]
                  (on-sponsor-click e)
                  (.preventDefault e))}
     [icons/heart {:size 14 :filled? sponsor-status
                   :class (str "sponsor-heart-icon"
                               (when sponsor-status " sponsor-heart-filled"))}]
     [:span (if sponsor-status "Thank you for sponsoring!" "Please sponsor me")]]]
   [:a.creator-menu-item {:href "https://www.youtube.com/CalvaTV" :target "_blank"}
    [icons/youtube {:size 14}] [:span "CalvaTV"]]
   [:a.creator-menu-item {:href "https://www.linkedin.com/in/cospaia/" :target "_blank"}
    [icons/linkedin {:size 14 :class "icon-linkedin"}] [:span "Peter PEZ Strömberg"]]
   [:a.creator-menu-item {:href "https://x.com/pappapez" :target "_blank"}
    [icons/twitter-x {:size 14}] [:span "@pappapez"]]])

(defn app-footer
  "Common footer component for both popup and panel.
   Options:
   - :elements/wrapper-class: CSS class for view-specific styling
   - :elements/sponsor-status: boolean indicating sponsor status
   - :elements/on-sponsor-click: click handler for sponsor heart action
   - :elements/creator-menu-open?: boolean indicating if creator menu is visible
   - :elements/on-creator-trigger-click: click handler for creator trigger
   - :elements/on-creator-menu-close: click handler to close creator menu"
  [{:elements/keys [wrapper-class sponsor-status on-sponsor-click
                    creator-menu-open? on-creator-trigger-click on-creator-menu-close]}]
  [:div {:class (str "app-footer " wrapper-class)}
   [:div.footer-powered
    "Epupp " (.-version (.getManifest js/chrome.runtime)) ". Powered by "
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
    [:span.iconed-link
     [icons/github {:size 16}]
     [:a {:href "https://github.com/PEZ/epupp"
          :target "_blank"
          :title "https://github.com/PEZ/epupp"}
      "Open Source"]]
    [:span.creator-trigger {:on-click on-creator-trigger-click}
     "Created by "
     [:span.creator-link "Peter Strömberg"]
     " a.k.a. PEZ"]
    [:button.footer-sponsor-heart
     {:on-click on-sponsor-click
      :title (when-not sponsor-status
               "Click to update sponsor status")}
     [icons/heart {:size 14
                   :filled? sponsor-status
                   :class (str "sponsor-heart-icon"
                               (when sponsor-status " sponsor-heart-filled"))}]
     (if sponsor-status "Thank you for sponsoring!" "Please sponsor me")]]
   (when creator-menu-open?
     [:div
      [creator-menu {:elements/on-sponsor-click on-sponsor-click
                     :elements/sponsor-status sponsor-status
                     :elements/on-creator-menu-close on-creator-menu-close}]])])

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
                     #_"btn-untitled "
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

(defn- banner-class [type]
  (case type
    "success" "success-banner"
    "info" "info-banner"
    "warning" "warning-banner"
    "error" "error-banner"
    "info-banner"))

(defn system-banner
  "Single banner component for system messages.
   Options:
   - :type - 'success', 'info', or 'error' (determines banner style)
   - :message - text to display
   - :favicon - optional favicon URL to display before message
   - :leaving - when true, applies leaving animation class"
  [{:keys [type message favicon leaving]}]
  [:div.banner.system-banner {:class (str (banner-class type)
                                          (when leaving " leaving"))
                              :data-e2e-banner-type type}
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

(defn page-banner
  "Persistent banner for page-level status (e.g., unscriptable page).
   Unlike system banners, does not auto-dismiss."
  [{:keys [type message]}]
  [:div.banner.page-banner {:class (banner-class type)
                            :data-e2e-page-banner type}
   [:span message]])
