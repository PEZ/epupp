---
description: 'Effective Reagami usages'
applyTo: '**'
---

# Reagami

Reagami is a minimal React-like UI library for Squint/ClojureScript, inspired by React's component model but simplified for direct DOM manipulation without a virtual DOM.

## Reagami is not Reagent

Key differences from Reagent:
* No fragment `:<>` - use wrapping divs instead
* No ratoms - use Uniflow for state management
* Components are plain functions returning hiccup vectors

## Component Patterns

### Basic Component

Components are functions returning hiccup vectors:

```clojure
(defn greeting [{:keys [name]}]
  [:div.greeting
   [:h1 "Hello, " name "!"]])
```

### Components with Children

Pass children as part of the props or use nested hiccup:

```clojure
(defn card [{:keys [title]} & children]
  [:div.card
   [:h2 title]
   [:div.card-body children]])

;; Usage
[card {:title "My Card"}
  [:p "Card content here"]]
```

### Event Handlers

Use `:on-click`, `:on-input`, etc:

```clojure
(defn button [{:keys [on-click label]}]
  [:button {:on-click on-click} label])

(defn input-field [{:keys [value on-change]}]
  [:input {:type "text"
           :value value
           :on-input (fn [e] (on-change (.. e -target -value)))}])
```

### Conditional Rendering

Use `when` and `if`:

```clojure
(defn status-badge [{:keys [status]}]
  [:div
   (when status
     [:span.badge {:class (when (= status "error") "badge-error")}
      status])])
```

### Lists with Keys

Use `^{:key ...}` metadata for list items:

```clojure
(defn item-list [{:keys [items]}]
  [:ul
   (for [item items]
     ^{:key (:id item)}
     [:li (:name item)])])
```

### Class Shorthand

Use `.class` syntax in element keywords:

```clojure
[:div.container.main
  [:span.label "Text"]]
;; Equivalent to:
[:div {:class "container main"}
  [:span {:class "label"} "Text"]]
```

### Dynamic Classes

Combine static and dynamic classes:

```clojure
[:div.item {:class (str (when active "active ")
                        (when selected "selected"))}
  content]
```

## Event Handling

Reagami components dispatch actions via Uniflow. See [../dev/docs/architecture/uniflow.md](../dev/docs/architecture/uniflow.md) for the full event system documentation.

```clojure
;; In components, call the module's dispatch! function
[:button {:on-click #(dispatch! [[:editor/ax.save-script]])} "Save"]

;; Multiple actions in one dispatch
[:button {:on-click #(dispatch! [[:editor/ax.clear-results]
                                  [:editor/ax.check-scittle]])} "Reset"]
```

## Nested Components

Call components as vector first elements:

```clojure
(defn parent [{:keys [items]}]
  [:div
   (for [item items]
     ^{:key (:id item)}
     [child-component item])])  ; Note: [component props] not (component props)
```

## Common Patterns from Epupp

### Port Input Component

```clojure
(defn port-input [{:keys [id label value on-change]}]
  [:span
   [:label {:for id} label]
   [:input {:type "number"
            :id id
            :value value
            :min "1"
            :max "65535"
            :on-input (fn [e] (on-change (.. e -target -value)))}]])
```

### Item with Actions

Components use Uniflow dispatch for state changes:

```clojure
(defn script-item [{:keys [script/name script/enabled] script-id :script/id}]
  [:div.script-item
   [:div.script-info
    [:span.script-name name]]
   [:div.script-actions
    [:input {:type "checkbox"
             :checked enabled
             :on-change #(dispatch! [[:toggle-script script-id]])}]
    [:button.delete {:on-click #(dispatch! [[:delete-script script-id]])}
     "Delete"]]])
```

## The :on-render Hook

Reagami provides an `:on-render` hook for lifecycle management. It fires on mount, update, and unmount.

**Signature:** `(fn [node lifecycle data] ...)`

- `node` - the DOM node
- `lifecycle` - one of `:mount`, `:update`, or `:unmount`
- `data` - return value from previous invocation (for carrying state across lifecycles)

```clojure
[:div {:on-render (fn [node lifecycle data]
                    (case lifecycle
                      :mount
                      (do
                        ;; Element just added to DOM
                        (.add (.-classList node) "entering")
                        (js/requestAnimationFrame
                          #(.remove (.-classList node) "entering"))
                        nil)

                      :update
                      ;; Element re-rendered (can track update count via data)
                      data

                      :unmount
                      ;; Element being removed from DOM
                      ;; NOTE: Too late to delay removal - element is already leaving
                      nil))}
 content]
```

**Use cases:**
- Mount animations (add class on mount, remove after 1 frame to trigger CSS transition)
- Third-party library integration (initialize on mount, cleanup on unmount)
- Tracking update counts via returned data

**Limitation for exit animations:** By the time `:unmount` fires, Reagami has already decided to remove the element. For exit animations that need to delay removal, use state-level patterns (like list-watchers) instead.

See [Reagami README](https://github.com/borkdude/reagami) for more details.