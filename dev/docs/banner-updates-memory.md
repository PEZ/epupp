# Banner Updates Memory

Complete implementation guide for banner positioning with permanent/temporary split and proper Uniflow state management.

## Overview

**Goal**: Display FS write banner messages BELOW the header, while keeping permanent banners (extension update) ABOVE the header.

## 1. Update app-header Component (view_elements.cljs)

Split the single `:elements/banner` slot into two:
- `:elements/permanent-banner` - Renders BEFORE the header div (above)
- `:elements/temporary-banner` - Renders AFTER the header div (below)

```clojure
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
```

## 2. Update popup.cljs

Change from `:elements/banner` to `:elements/temporary-banner`:

```clojure
[view-elements/app-header
 {:elements/wrapper-class "popup-header-wrapper"
  :elements/header-class "popup-header"
  :elements/icon [icons/jack-in {:size 28}]
  :elements/temporary-banner (when-let [fs-event (:ui/fs-event state)]
                               [fs-event-banner fs-event])}]
```

## 3. Update panel.cljs

Use both banner types:

```clojure
(defn panel-header [{:panel/keys [needs-refresh? fs-event]}]
  [view-elements/app-header
   {:elements/wrapper-class "panel-header-wrapper"
    :elements/header-class "panel-header"
    :elements/status "Ready"
    :elements/permanent-banner (when needs-refresh? [refresh-banner])
    :elements/temporary-banner (when fs-event [fs-event-banner fs-event])}])
```

## 4. FS Event State Must Go Through Uniflow

**Problem**: Direct `swap!` on state atom outside Uniflow dispatch bypasses proper state management.

**Bad pattern**:
```clojure
;; DON'T do this - bypasses Uniflow
(swap! !state assoc :ui/fs-event {:type event-type :message banner-msg})
(swap! !state update-in [:ui/fs-bulk-names bulk-id] (fnil conj []) script-name)
```

**Good pattern**:
```clojure
;; DO this - proper Uniflow dispatch
(dispatch! [[:popup/ax.show-fs-event event-type banner-msg]])
(dispatch! [[:popup/ax.track-bulk-name bulk-id script-name]])
```

### Actions to Add in popup_actions.cljs

Add these before `:uf/unhandled-ax`:

```clojure
;; FS sync event actions
:popup/ax.show-fs-event
(let [[event-type message] args]
  {:uf/db (assoc state :ui/fs-event {:type event-type :message message})})

:popup/ax.clear-fs-event
{:uf/db (assoc state :ui/fs-event nil)}

:popup/ax.track-bulk-name
(let [[bulk-id script-name] args]
  {:uf/db (update-in state [:ui/fs-bulk-names bulk-id] (fnil conj []) script-name)})

:popup/ax.clear-bulk-names
(let [[bulk-id] args]
  {:uf/db (update state :ui/fs-bulk-names dissoc bulk-id)})
```

### Update FS Event Listener in popup.cljs init!

Replace direct `swap!` calls with dispatch:

```clojure
(when bulk-id
  (dispatch! [[:popup/ax.track-bulk-name bulk-id script-name]]))
;; ...
(when show-banner?
  (dispatch! [[:popup/ax.show-fs-event event-type banner-msg]])
  ;; ... logging ...
  ;; Auto-dismiss after 3 seconds (when ready to enable)
  #_(dispatch! [[:uf/fx.defer-dispatch [[:popup/ax.clear-fs-event]] 3000]]))
(when (and bulk-id bulk-final?)
  (dispatch! [[:popup/ax.clear-bulk-names bulk-id]]))
```

## Why Uniflow Matters

- Actions are testable pure functions
- State transitions are predictable and traceable
- Consistent with rest of the codebase patterns
- Effects like auto-dismiss can use `:uf/fx.defer-dispatch`

## Testing Notes

- Auto-dismiss is commented out with `#_` during development for DOM inspection
- Re-enable by uncommenting the `defer-dispatch` call when ready
