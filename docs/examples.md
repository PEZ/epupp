# Epupp Script Examples

Copy-paste ready examples for common userscript patterns.

## Basic: Hello World

The simplest userscript - shows a message on any page.

```clojure
{:epupp/script-name "hello_world.cljs"
 :epupp/site-match "*"
 :epupp/description "Shows a greeting in the console"}

(js/console.log "Hello from Epupp!")
```

## Basic: Page Modification

Add a floating badge to any page.

```clojure
{:epupp/script-name "floating_badge.cljs"
 :epupp/site-match "*"
 :epupp/description "Adds a floating badge to the page"}

(let [badge (js/document.createElement "div")]
  (set! (.-id badge) "epupp-badge")
  (set! (.-textContent badge) "Epupp Active")
  (set! (.. badge -style -cssText)
        "position: fixed; bottom: 10px; right: 10px; padding: 8px 12px;
         background: #6366f1; color: white; border-radius: 6px;
         font-family: system-ui; font-size: 12px; z-index: 99999;
         box-shadow: 0 2px 8px rgba(0,0,0,0.2);")
  (.appendChild js/document.body badge))
```

## Intermediate: Using Libraries

Using Reagent to render a React component.

```clojure
{:epupp/script-name "reagent_demo.cljs"
 :epupp/site-match "*"
 :epupp/require ["scittle://reagent.js"]
 :epupp/description "Renders a Reagent component"}

(ns reagent-demo
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

(defonce !counter (r/atom 0))

(defn counter-widget []
  [:div {:style {:position "fixed" :top "10px" :right "10px"
                 :padding "16px" :background "#1e293b" :color "white"
                 :border-radius "8px" :font-family "system-ui"
                 :z-index 99999 :box-shadow "0 4px 12px rgba(0,0,0,0.3)"}}
   [:div {:style {:font-size "24px" :text-align "center"}} @!counter]
   [:div {:style {:display "flex" :gap "8px" :margin-top "8px"}}
    [:button {:on-click #(swap! !counter dec)
              :style {:padding "4px 12px" :cursor "pointer"}} "-"]
    [:button {:on-click #(swap! !counter inc)
              :style {:padding "4px 12px" :cursor "pointer"}} "+"]]])

(let [container (js/document.createElement "div")]
  (set! (.-id container) "epupp-reagent-demo")
  (.appendChild js/document.body container)
  (rdom/render [counter-widget] container))
```

## Advanced: Script Coordination

Multiple scripts working together using different timings.

### Script 1: Fetch Interceptor (document-start)

This script runs **before** any page JavaScript, allowing it to intercept network requests.

```clojure
{:epupp/script-name "fetch_interceptor.cljs"
 :epupp/site-match "*"
 :epupp/run-at "document-start"
 :epupp/description "Intercepts fetch requests before page scripts run"}

;; Store intercepted data in a global atom
(set! js/window.__epuppInterceptedFetches (atom []))

;; Wrap the original fetch
(let [original-fetch js/window.fetch]
  (set! js/window.fetch
        (fn [url & args]
          (let [url-str (str url)]
            (swap! js/window.__epuppInterceptedFetches conj
                   {:url url-str
                    :time (js/Date.now)})
            (js/console.log "[Interceptor] Fetch:" url-str))
          (apply original-fetch url args))))

(js/console.log "[Interceptor] Fetch interception active")
```

### Script 2: Fetch Dashboard (document-idle)

This script runs **after** the page loads and displays the data collected by Script 1.

```clojure
{:epupp/script-name "fetch_dashboard.cljs"
 :epupp/site-match "*"
 :epupp/require ["scittle://reagent.js"]
 :epupp/description "Displays intercepted fetch requests"}

(ns fetch-dashboard
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

;; Use the data from the interceptor script
(def !fetches (or js/window.__epuppInterceptedFetches (r/atom [])))

(defn dashboard []
  (let [fetches @!fetches]
    [:div {:style {:position "fixed" :bottom "10px" :left "10px"
                   :width "400px" :max-height "300px" :overflow-y "auto"
                   :padding "12px" :background "#1e293b" :color "white"
                   :border-radius "8px" :font-family "monospace"
                   :font-size "11px" :z-index 99999
                   :box-shadow "0 4px 12px rgba(0,0,0,0.3)"}}
     [:div {:style {:font-weight "bold" :margin-bottom "8px"
                    :padding-bottom "8px" :border-bottom "1px solid #475569"}}
      "Fetch Requests (" (count fetches) ")"]
     (if (seq fetches)
       [:div
        (for [[idx {:keys [url]}] (map-indexed vector (take-last 10 fetches))]
          ^{:key idx}
          [:div {:style {:padding "4px 0" :border-bottom "1px solid #334155"
                         :word-break "break-all"}}
           url])]
       [:div {:style {:color "#94a3b8"}} "No requests yet..."])]))

(let [container (js/document.createElement "div")]
  (set! (.-id container) "epupp-fetch-dashboard")
  (.appendChild js/document.body container)
  (rdom/render [dashboard] container))
```

**How it works:**
1. The interceptor runs at `document-start`, before any page JavaScript
2. It wraps `window.fetch` and logs all requests to an atom on `window`
3. The dashboard runs at `document-idle` (default), after the page loads
4. It reads the shared atom and displays the collected data
5. Both scripts share the page context via `window`

**To test:**
1. Save both scripts in the Epupp panel
2. Approve both for a site (e.g., `*`)
3. Navigate to any page that makes fetch requests
4. The dashboard shows intercepted URLs

## Advanced: Pretty Printing

Using pprint for debugging.

```clojure
{:epupp/script-name "debug_helper.cljs"
 :epupp/site-match "*"
 :epupp/require ["scittle://pprint.js"]
 :epupp/description "Pretty prints page data for debugging"}

(ns debug-helper
  (:require [cljs.pprint :as pprint]))

(defn pprint-to-console [label data]
  (js/console.group label)
  (js/console.log (with-out-str (pprint/pprint data)))
  (js/console.groupEnd))

;; Example: inspect page metadata
(pprint-to-console "Page Meta Tags"
  (->> (js/document.querySelectorAll "meta")
       (map (fn [el]
              {:name (or (.getAttribute el "name")
                         (.getAttribute el "property"))
               :content (.getAttribute el "content")}))
       (filter :name)
       vec))
```

## Tips

- **Test incrementally**: Use the panel's Eval button to test snippets before saving
- **Check the console**: Use `js/console.log` liberally while developing
- **Use the REPL**: Connect your editor for interactive development
- **Start simple**: Begin with `document-idle` (default), only use early timing when needed
