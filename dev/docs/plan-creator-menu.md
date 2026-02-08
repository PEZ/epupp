# Plan: "Created by" Social Links Menu

## Goal

Replace the static "Created by Peter Strömberg" link in the footer with a clickable trigger that opens a small floating menu showing social links and a sponsor button, each with an appropriate icon.

## Current State

The footer is a shared component in [src/view_elements.cljs](../src/view_elements.cljs) (`app-footer`), rendered by both popup and panel. The "Created by" section is a `[:span]` containing an `[:a]` linking directly to `https://github.com/PEZ`. The sponsor button is a separate element below it.

Icons are inline SVG components defined in [src/icons.cljc](../src/icons.cljc), using Codicon SVG path data (CC BY 4.0). Each icon is a function returning hiccup with `:width`, `:height`, `:viewBox "0 0 16 16"`, and `:fill "currentColor"`.

## Design

### Menu Content

The trigger text remains the *Peter Strömberg* part of: `Created by Peter Strömberg a.k.a. PEZ` - styled as a link (as today).

Clicking it toggles a floating menu anchored above the bottom of the footer. The menu contains:

| Item | Icon | Text | Action |
|------|------|------|--------|
| Header | - | **Peter Strömberg a.k.a. PEZ** | Close button to the right |
| GitHub | `github` (exists) | @PEZ - Please follow | Link to https://github.com/PEZ |
| Sponsor | `heart` (exists) | Sponsor - same behavior as footer button | Calls `on-sponsor-click` |
| YouTube | `youtube` (exists) | CalvaTV - Please subscribe | Link to https://www.youtube.com/CalvaTV |
| LinkedIn | `linkedin` (new) | LinkedIn - Please follow | Link to https://www.linkedin.com/in/cospaia/ |
| X/Twitter | `twitter-x` (new) | @pappapez - Please follow | Link to https://x.com/pappapez |

### Menu Behavior

- Toggle on click (click trigger to open, close with the close button)
- Close on click outside the menu
- Close on Escape key
- Menu appears anchored at the bottom of the footer, horizontally centered
- Same horizontal padding as footer content (12px from `app-footer` padding)

### Visual Style

- Floating card with `var(--color-bg-elevated)` background
- `var(--color-border)` border, small border-radius
- Subtle box-shadow for elevation
- Each item is a row with icon + text, consistent with `.iconed-link` pattern
- The sponsor item uses the same heart icon styling as the current footer button
- Menu width: auto, but constrained to not exceed the footer width

## Implementation Steps

### 1. Add New Icons to icons.cljc

Source SVG path data for two new icons. Codicons do not include brand logos, so we use:

- **`linkedin`** - from Simple Icons (LinkedIn brand mark, MIT license) - a custom SVG path
- **`twitter-x`** - from **Codicons** - a custom SVG path

The existing `youtube` and `github` icons are already available.

Each new icon follows the existing pattern:

```clojure
(defn linkedin [{:keys [size class] :or {size 16}}]
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width size :height size
         :viewBox "0 0 24 24"
         :fill "currentColor"
         :class class}
   [:path {:d "M..."}]])
```

For brand icons the viewBox may differ (Simple Icons use `0 0 24 24`) - keep native viewBox and let `:width`/`:height` scale. Add a comment noting the source and license for brand icons.

### 2. Add Menu State Management

In [src/view_elements.cljs](../src/view_elements.cljs), add a local atom for menu open/close state:

```clojure
(defonce !creator-menu-open? (atom false))
```

Or, if the footer already receives state from popup/panel via the options map, add a key like `:elements/creator-menu-open?` and pass the atom from the parent. The simpler approach is a local atom since this is purely UI state.

### 3. Create the Menu Component

In view_elements.cljs, add a `creator-menu` component:

```clojure
(defn- creator-menu [{:elements/keys [on-sponsor-click sponsor-status]}]
  [:div.creator-menu
   [:div.creator-menu-header
    [:span "Peter Strömberg a.k.a. PEZ"]
    [:button.creator-menu-close
     {:on-click #(reset! !creator-menu-open? false)
      :title "Close"}
     [icons/close {:size 14}]]]
   [:a.creator-menu-item {:href "https://github.com/PEZ" :target "_blank"}
    [icons/github {:size 14}] [:span "@PEZ"] [:span.menu-cta "Please follow"]]
   [:button.creator-menu-item
    {:on-click on-sponsor-click}
    [icons/heart {:size 14 :filled? sponsor-status
                  :class (str "sponsor-heart-icon"
                              (when sponsor-status " sponsor-heart-filled"))}]
    [:span "Sponsor"]
    [:span.menu-cta (if sponsor-status "Thank you!" "Please sponsor")]]
   [:a.creator-menu-item {:href "https://www.youtube.com/CalvaTV" :target "_blank"}
    [icons/youtube {:size 14}] [:span "CalvaTV"] [:span.menu-cta "Please subscribe"]]
   [:a.creator-menu-item {:href "https://www.linkedin.com/in/cospaia/" :target "_blank"}
    [icons/linkedin {:size 14}] [:span "LinkedIn"] [:span.menu-cta "Please follow"]]
   [:a.creator-menu-item {:href "https://x.com/pappapez" :target "_blank"}
    [icons/twitter-x {:size 14}] [:span "@pappapez"] [:span.menu-cta "Please follow"]]])
```

### 4. Modify the Footer

Replace the current "Created by" `[:span]` with a clickable trigger. The menu itself is rendered as a child of the `.app-footer` div (not inside the trigger wrapper) so it can be positioned relative to the footer bottom:

```clojure
;; In the footer-credits section, replace the Created by span:
[:span.creator-trigger {:on-click #(swap! !creator-menu-open? not)}
 "Created by "
 [:span.creator-link "Peter Strömberg"]
 " a.k.a. PEZ"]

;; As a last child of the .app-footer div:
(when @!creator-menu-open?
  [creator-menu {:elements/on-sponsor-click on-sponsor-click
                 :elements/sponsor-status sponsor-status}])
```

The `.app-footer` gets `position: relative` so the absolutely-positioned menu anchors to its bottom edge.

The standalone sponsor button remains in the footer as-is.

### 5. Add Click-Outside Handler

Add a click-outside handler. In Squint/Scittle, use a global click listener:

```clojure
;; In a lifecycle effect or setup
(.addEventListener js/document "click"
  (fn [e]
    (when (and @!creator-menu-open?
               (not (.closest (.-target e) ".creator-menu"))
               (not (.closest (.-target e) ".creator-trigger")))
      (reset! !creator-menu-open? false))))
```

Also handle Escape:

```clojure
(.addEventListener js/document "keydown"
  (fn [e]
    (when (and (= "Escape" (.-key e)) @!creator-menu-open?)
      (reset! !creator-menu-open? false))))
```

Since this is Reagent-like (Scittle), these listeners should be set up once. Consider using `defonce` for the listener setup or attaching in an init function.

### 6. Add CSS

Add styles to [build/base.css](../build/base.css) (shared footer styles section):

```css
/* Creator menu */
.app-footer {
  position: relative; /* anchor for the menu */
}

.creator-trigger {
  cursor: pointer;
}

.creator-trigger:hover .creator-link {
  text-decoration: underline;
}

.creator-link {
  color: var(--clojure-blue);
}

.creator-menu {
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  background: var(--color-bg-elevated);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  padding: 8px 0;
  min-width: 240px;
  z-index: 100;
  text-align: left;
}

.creator-menu-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  font-weight: 600;
  font-size: 12px;
  color: var(--text-primary);
  border-bottom: 1px solid var(--color-border);
  margin-bottom: 4px;
}

.creator-menu-close {
  display: inline-flex;
  align-items: center;
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px;
  color: var(--text-muted);
}

.creator-menu-close:hover {
  color: var(--text-primary);
}

.creator-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 11px;
  cursor: pointer;
  background: none;
  border: none;
  font: inherit;
  width: 100%;
}

.creator-menu-item:hover {
  background: var(--color-bg-hover);
  color: var(--text-primary);
  text-decoration: none;
}

.menu-cta {
  margin-left: auto;
  color: var(--text-muted);
  font-style: italic;
  font-size: 10px;
}
```

### 7. Standalone Sponsor Button

The sponsor heart button remains as a direct child of `.footer-credits` unchanged. The menu provides an additional path to the sponsor action.

## Files Changed

| File | Change |
|------|--------|
| `src/icons.cljc` | Add `linkedin`, `twitter-x` icon functions |
| `src/view_elements.cljs` | Add `creator-menu` component, modify footer, add state atom, add click-outside/escape handlers |
| `build/base.css` | Add `.creator-menu-*` styles |

## Testing Strategy

- **Unit tests**: Test that menu state toggles correctly (if we extract pure logic)
- **E2E tests**: Test that clicking "Created by" opens the menu, menu items are visible, clicking outside closes it, Escape closes it
- **Manual verification**: Visual check in both popup and panel views

## Decisions

1. The standalone sponsor button remains in the footer. The menu provides a second path to sponsor.
2. The menu appears in both popup and panel footers (shared `app-footer` component).

## Risks

- **Click-outside handling**: Global event listeners in Scittle/Reagent need careful lifecycle management to avoid memory leaks or stale closures.
- **Brand icon licensing**: Simple Icons are CC0/MIT, but verify before shipping. Alternative: use generic codicons (`globe` for LinkedIn, `comment` for X) if brand icons are problematic.
- **Popup viewport**: The menu is anchored at the footer bottom. In the popup view (which has fixed height), ensure the menu doesn't overflow the viewport.

---

## Original Plan-producing Prompt

Create a plan to replace the "Created by" link in the Epupp extension footer with a clickable trigger that opens a floating social links menu. The menu should contain:

- **Peter Strömberg a.k.a. PEZ** (header)
- [@PEZ](https://github.com/PEZ) with GitHub codicon - "Please follow"
- **Sponsor** with heart icon (same behavior as existing footer sponsor button)
- [CalvaTV](https://www.youtube.com/CalvaTV) with youtube icon - "Please subscribe"
- [LinkedIn](https://www.linkedin.com/in/cospaia/) with LinkedIn icon - "Please follow"
- [@pappapez](https://x.com/pappapez) with X/Twitter icon - "Please follow"

The menu should be anchored to the bottom of the footer with matching margins. New icons (linkedin, twitter-x) should be added to icons.cljc following the existing inline SVG codicon pattern (youtube and github already exist). Brand icons may need to come from Simple Icons rather than Codicons. The standalone sponsor button stays in the footer (the menu duplicates it as a secondary path). Include click-outside and Escape-to-close behavior. Write the plan to dev/docs/plan-creator-menu.md with an "Original Plan-producing Prompt" section.
