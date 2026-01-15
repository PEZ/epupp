# Epupp User Guide

(Almost completely AI authored for now, will fix a proper guide later)

Epupp is a browser extension for tampering with web pages; live, or with userscript (TamperMonkey style). You can evaluate code interactively, save scripts that run automatically on matching sites, or connect your editor via nREPL for full interactive development.

## Quick Start

### Installation

Install from your browser's extension store:
- [Chrome Web Store](https://chromewebstore.google.com/detail/bfcbpnmgefiblppimmoncoflmcejdbei)
- Firefox Add-ons (pending review)
- Safari: Manual install from [releases](https://github.com/PEZ/browser-jack-in/releases)

For manual installation, see the [README](../README.md#installing).

### Your First Evaluation

1. Open any web page (not a browser internal page like `chrome://extensions`)
2. Open DevTools
3. Find the **Epupp** panel tab
4. Enter some code:
   ```clojure
   (js/alert "Hello from Epupp!")
   ```
5. Click **Eval script** or press Ctrl+Enter

The alert appears. That's ClojureScript running in the page context.

### Install Example Scripts

The built-in **Gist Installer** lets you install scripts from GitHub Gists. A few examples to try:

Very silly examples...

- Document start timing test: https://gist.github.com/PEZ/282f263a6789ca4a502a82bbc27a1684
- GitHub Party (don't use if you are epileptic): https://gist.github.com/PEZ/3f499d088a742386c5a42761c6c06c5a
- Print elements and their selector to the console. Disable it in the extension popup, and then run it from the popup. It will toggle the inspecting at each run: https://gist.github.com/PEZ/9d2a9eec14998de59dde93979453247e
- ... TBD!

To install:
1. Visit the gist
2. Click the **Install to Epupp** button (that should appear)
3. Allow or Deny from the popup UI (on sites where the script matches)

### Save as Userscript

To save your code as an auto-running userscript:

1. Add a manifest map at the top of your code:
   ```clojure
   {:epupp/script-name "my_first_script.cljs"
    :epupp/site-match "*"}

   (js/console.log "Script loaded!")
   ```
2. Click **Save Script**
3. The script appears in the popup's script list
4. Enable it and approve it for the current site

---

## Using the DevTools Panel

The Epupp panel in DevTools is where you write and test code.

### Evaluating Code

Two ways to run code:

| Action | What it does |
|--------|--------------|
| **Eval script** button | Runs the entire editor contents |
| **Ctrl+Enter** (Cmd+Enter on Mac) | Runs selected text, or entire script if nothing selected |

Selection evaluation workflow:
1. Write a function
2. Select the function call
3. Press Ctrl+Enter to test it
4. Iterate

### Results Display

Results appear below the editor:
- **Input echo**: Shows what was evaluated
- **Output**: The return value
- **Errors**: Stack traces with line numbers

Click **Clear** to reset the results area. When empty, the results area shows a hint about Ctrl+Enter.

### New Button

Click **New** to clear the editor and start a fresh script. If you have unsaved changes, a confirmation dialog appears.

### Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| Ctrl+Enter | Evaluate (selection or all) |
| Ctrl+Z | Undo |
| Ctrl+Shift+Z | Redo |

---

## Using the REPL

Connect your editor to evaluate code in the browser.

### Prerequisites

- [Babashka](https://babashka.org/) installed
- A Clojure editor with nREPL support (VS Code with [Calva](https://calva.io/), Emacs with [CIDER](https://cider.mx/), IntelliJ with [Cursive](https://cursive-ide.com/), etc.)

### Setup

1. Open the Epupp popup (click extension icon)
2. Copy the server command (step 1 in the popup)
3. Run it in a terminal:
   ```bash
   bb -Sdeps ...
   ```
4. Connect your editor to nREPL port 12345
5. Navigate to a web page
6. Click **Connect** in the popup

### Evaluating Code

Once connected, evaluate code from your editor. It runs in the browser page:

```clojure
;; Access the DOM
(.-title js/document)
;; => "GitHub - PEZ/browser-jack-in"

;; Modify the page
(set! (.-title js/document) "Epupp was here")

;; Query elements
(js/document.querySelector "h1")
```

### Loading Libraries

Use `epupp/manifest!` to load Scittle libraries at runtime:

```clojure
(epupp/manifest! {:epupp/require ["scittle://reagent.js"]})

;; Now Reagent is available
(require '[reagent.core :as r])
(require '[reagent.dom :as rdom])
```

Safe to call multiple times. Already-loaded libraries are skipped.

### Multi-Tab Connections

You can connect multiple tabs to different relay servers:
1. Start a second relay on different ports (e.g., 12347/12348)
2. Configure different ports in the popup for each tab
3. Connect each tab independently

### Connection Tracking

The popup shows which tabs are connected. Each connected tab has a **Reveal** button to switch to that tab.

The toolbar icon changes to indicate connection state:
- Grey: No connection
- Colored: REPL connected

### Auto-Reconnect

When a connected page reloads, Epupp automatically reconnects. This is tab-specific: only tabs that were previously connected will auto-reconnect.

### Troubleshooting

**"Cannot script this page"**: Browser internal pages (`chrome://`, extension gallery) cannot be scripted. Navigate to a regular web page.

**Connection fails**: Check that the relay server is running and ports match.

**No response**: The page may have reloaded. Reconnect from the popup.

---

## Creating Userscripts

Userscripts are ClojureScript files that run automatically on matching pages.

### The Manifest Format

Every userscript starts with a manifest map:

```clojure
{:epupp/script-name "github_tweaks.cljs"
 :epupp/site-match "https://github.com/*"
 :epupp/description "Enhance GitHub UI"
 :epupp/run-at "document-idle"}

(ns github-tweaks)
;; Your code here
```

**Required keys:**
| Key | Description |
|-----|-------------|
| `:epupp/script-name` | Filename for the script (normalized automatically) |
| `:epupp/site-match` | URL pattern or vector of patterns |

**Optional keys:**
| Key | Description |
|-----|-------------|
| `:epupp/description` | Human-readable description |
| `:epupp/run-at` | When to run: `"document-start"`, `"document-end"`, or `"document-idle"` (default) |
| `:epupp/require` | Scittle libraries to load (see [Using Libraries](#using-scittle-libraries)) |

### URL Patterns

Patterns use glob syntax:
- `*` matches any characters
- `https://github.com/*` matches all GitHub pages
- `*://example.com/*` matches http and https

Multiple patterns:
```clojure
{:epupp/site-match ["https://github.com/*"
                    "https://gist.github.com/*"]}
```

### Saving Scripts

1. Write your code with manifest in the panel
2. The property table shows parsed metadata
3. Click **Save Script**
4. First save shows "Created", subsequent saves show "Saved"

### Name Normalization

Script names are normalized to filename format:
- `"My Cool Script"` becomes `my_cool_script.cljs`
- Spaces become underscores
- Uppercase becomes lowercase
- `.cljs` extension added if missing

### Editing and Renaming

To edit: Click the eye icon in the popup to load a script into the panel.

To rename: Change `:epupp/script-name` in the manifest and save. The script ID stays the same, only the display name changes.

To copy: Change the name and save. A new script is created with a new ID.

---

## Managing Scripts (Popup)

The popup shows all your scripts organized by relevance to the current page.

### Script Sections

- **Matching Scripts**: Scripts with patterns matching the current URL
- **Other Scripts**: Everything else

Within each section: user scripts alphabetically, then built-in scripts (grey border, cube icon).

### Script Actions

| Icon | Action |
|------|--------|
| Checkbox | Enable/disable the script |
| Eye | Load into DevTools panel for viewing/editing |
| Play | Run on current page (one-time) |
| X | Delete (not available for built-in scripts) |

### Approval Workflow

When you visit a page matching an enabled script's pattern for the first time:

1. The script shows an amber border
2. **Allow** and **Deny** buttons appear
3. Click **Allow** to approve and run the script
4. Click **Deny** to disable the script

Approvals are per-pattern. A script with multiple patterns needs approval for each.

### Built-in Scripts

Epupp includes the **Gist Installer** for installing scripts from GitHub Gists. Built-in scripts:
- Have a grey left border and cube icon
- Cannot be deleted
- Can be inspected (eye icon) but not modified
- To customize: inspect, copy code, change name, save as new script

---

## Script Timing

Scripts can run at different points in page loading.

### document-idle (default)

Runs after the page has fully loaded. Use this for most scripts.

```clojure
{:epupp/run-at "document-idle"}  ;; or omit entirely

;; DOM is ready, page scripts have run
(js/document.querySelector ".some-element")
```

### document-start

Runs before any page JavaScript. Use this to:
- Intercept globals before page scripts access them
- Block or modify network requests
- Polyfill APIs

```clojure
{:epupp/run-at "document-start"}

;; Runs BEFORE page scripts
;; document.body does not exist yet!
(set! js/window.myGlobal "intercepted")

;; Wait for DOM if needed
(js/document.addEventListener "DOMContentLoaded"
  (fn [] (js/console.log "Now DOM exists")))
```

**Warning**: At `document-start`, `document.body` is null. Do not try to access DOM elements.

### document-end

Runs at DOMContentLoaded. DOM exists but images/iframes may still be loading.

### Safari Limitation

Safari does not support early script timing. Scripts always run at `document-idle` regardless of `:epupp/run-at`.

---

## Using Scittle Libraries

Epupp bundles several Scittle ecosystem libraries.

### Available Libraries

| Require URL | Provides |
|-------------|----------|
| `scittle://pprint.js` | `cljs.pprint` |
| `scittle://promesa.js` | `promesa.core` |
| `scittle://replicant.js` | Replicant UI library |
| `scittle://js-interop.js` | `applied-science.js-interop` |
| `scittle://reagent.js` | Reagent + React |
| `scittle://re-frame.js` | Re-frame (includes Reagent) |
| `scittle://cljs-ajax.js` | `cljs-http.client` |

Dependencies resolve automatically: `re-frame.js` loads Reagent and React.

### In Userscripts

Add `:epupp/require` to your manifest:

```clojure
{:epupp/script-name "reagent_counter.cljs"
 :epupp/site-match "*"
 :epupp/require ["scittle://reagent.js"]}

(ns reagent-counter
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

;; Now use Reagent normally
```

### From the REPL

Use `epupp/manifest!`:

```clojure
(epupp/manifest! {:epupp/require ["scittle://pprint.js"]})
(require '[cljs.pprint :as pprint])
(pprint/pprint {:some "data"})
```

---

## Settings

Access settings from the gear icon in the popup.

### Custom Origins

The Gist Installer only works on allowed origins. By default:
- `https://gist.github.com`
- `https://gist.githubusercontent.com`

Add custom origins if you host gists elsewhere.

### Auto-Connect REPL

When enabled, Epupp automatically connects to the relay server when you open a page.

**Warning**: This evaluates code in every page you visit. Only enable if you understand the implications.

---

## Examples

### Hello World

```clojure
{:epupp/script-name "hello_world.cljs"
 :epupp/site-match "*"
 :epupp/description "Console greeting"}

(js/console.log "Hello from Epupp!")
```

### Floating Badge

```clojure
{:epupp/script-name "floating_badge.cljs"
 :epupp/site-match "*"
 :epupp/description "Visual indicator"}

(let [badge (js/document.createElement "div")]
  (set! (.-textContent badge) "Epupp Active")
  (set! (.. badge -style -cssText)
        "position: fixed; bottom: 10px; right: 10px; padding: 8px 12px;
         background: #6366f1; color: white; border-radius: 6px;
         font-family: system-ui; font-size: 12px; z-index: 99999;")
  (.appendChild js/document.body badge))
```

### Reagent Counter

```clojure
{:epupp/script-name "reagent_counter.cljs"
 :epupp/site-match "*"
 :epupp/require ["scittle://reagent.js"]
 :epupp/description "Interactive counter widget"}

(ns reagent-counter
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

(defonce !count (r/atom 0))

(defn widget []
  [:div {:style {:position "fixed" :top "10px" :right "10px"
                 :padding "16px" :background "#1e293b" :color "white"
                 :border-radius "8px" :font-family "system-ui" :z-index 99999}}
   [:div {:style {:font-size "24px" :text-align "center"}} @!count]
   [:div {:style {:display "flex" :gap "8px" :margin-top "8px"}}
    [:button {:on-click #(swap! !count dec)} "-"]
    [:button {:on-click #(swap! !count inc)} "+"]]])

(let [container (js/document.createElement "div")]
  (.appendChild js/document.body container)
  (rdom/render [widget] container))
```

### Pretty Printing Debug Helper

```clojure
{:epupp/script-name "debug_helper.cljs"
 :epupp/site-match "*"
 :epupp/require ["scittle://pprint.js"]
 :epupp/description "Pretty print page metadata"}

(ns debug-helper
  (:require [cljs.pprint :as pprint]))

(defn pp [label data]
  (js/console.group label)
  (js/console.log (with-out-str (pprint/pprint data)))
  (js/console.groupEnd))

(pp "Meta Tags"
  (->> (js/document.querySelectorAll "meta")
       (map #(hash-map :name (or (.getAttribute % "name")
                                 (.getAttribute % "property"))
                       :content (.getAttribute % "content")))
       (filter :name)
       vec))
```

### Fetch Interceptor (document-start)

```clojure
{:epupp/script-name "fetch_interceptor.cljs"
 :epupp/site-match "*"
 :epupp/run-at "document-start"
 :epupp/description "Log all fetch requests"}

(set! js/window.__fetchLog (atom []))

(let [original js/window.fetch]
  (set! js/window.fetch
        (fn [url & args]
          (swap! js/window.__fetchLog conj {:url (str url) :time (js/Date.now)})
          (js/console.log "[Fetch]" (str url))
          (apply original url args))))
```

---

## REPL File System API

Manage userscripts programmatically from the REPL using the `epupp.fs` namespace.

### Available Functions

| Function | Description |
|----------|-------------|
| `epupp.fs/ls` | List all scripts with metadata |
| `epupp.fs/cat` | Get script code by name |
| `epupp.fs/save!` | Create or update a script |
| `epupp.fs/mv!` | Rename a script |
| `epupp.fs/rm!` | Delete a script |

### Listing Scripts

```clojure
(epupp.fs/ls)
;; => [{:fs/name "my_script.cljs"
;;      :fs/enabled true
;;      :fs/match ["https://example.com/*"]
;;      :fs/modified "2025-01-15T12:00:00.000Z"}
;;     ...]
```

### Reading Script Code

```clojure
(epupp.fs/cat "my_script.cljs")
;; => "(ns my-script) ..."

(epupp.fs/cat "nonexistent.cljs")
;; => nil
```

### Creating/Updating Scripts

Save code with an embedded manifest:

```clojure
(epupp.fs/save!
  "{:epupp/script-name \"new_script.cljs\"
    :epupp/site-match \"https://example.com/*\"}

   (ns new-script)
   (js/console.log \"Hello!\")")
;; => {:fs/success true :fs/name "new_script.cljs" :fs/error nil}
```

If a script with that name exists, it's updated. Otherwise, a new script is created.

### Script Approval Requirement

Scripts created via `epupp.fs/save!` start with no approved patterns. Before they run automatically on matching sites, you must approve them in the popup UI:

1. Navigate to a page matching the script's pattern
2. Open the Epupp popup
3. The script shows an amber border indicating pending approval
4. Click **Allow** to approve and run the script

This security measure ensures you explicitly authorize each script to run on specific sites. The approval is per-pattern - a script with multiple `:epupp/site-match` patterns needs individual approval for each.

For immediate testing without approval workflow, use one-time evaluation:
- Click the **Play** button in the popup to run the script once on the current page
- Or evaluate code directly in the DevTools panel

### Renaming Scripts

```clojure
(epupp.fs/mv! "old_name.cljs" "new_name.cljs")
;; => {:fs/success true :fs/error nil}
```

Note: Built-in scripts cannot be renamed.

### Deleting Scripts

```clojure
(epupp.fs/rm! "unwanted_script.cljs")
;; => {:fs/success true :fs/error nil}
```

Note: Built-in scripts cannot be deleted.

### Return Value Format

All `epupp.fs` functions return promises. Results use namespaced keywords (`:fs/*`):

| Key | Description |
|-----|-------------|
| `:fs/success` | Boolean indicating operation success |
| `:fs/name` | Script name (in save!/mv! results) |
| `:fs/error` | Error message string, or nil |
| `:fs/enabled` | Whether script is enabled (in ls) |
| `:fs/match` | URL patterns (in ls) |
| `:fs/modified` | Last modification timestamp (in ls) |

### UI Reactivity

The popup automatically refreshes when scripts change via the fs API. You don't need to manually reload the popup to see changes made from the REPL.

### Pretty Printing Script Lists

For formatted output, use `cljs.pprint`:

```clojure
(epupp.repl/manifest! {:epupp/require ["scittle://pprint.js"]})
(require '[cljs.pprint :refer [print-table]])

;; Print all scripts as table
(-> (epupp.fs/ls)
    (.then #(print-table [:fs/name :fs/enabled] %)))
```

---

## Troubleshooting

### No Epupp Panel?

The panel does not appear on browser internal pages (`chrome://extensions`, `about:config`, etc.) or the extension gallery. Navigate to a regular web page.

### "Cannot Script This Page"

Same cause. Browser security prevents extensions from accessing internal pages.

### Connection Fails

1. Check that the relay server is running (`bb -Sdeps ...` command)
2. Verify ports match between terminal and popup
3. Try restarting the relay server

### Script Doesn't Run

1. Is the script enabled? (checkbox in popup)
2. Is the pattern approved? (amber border means pending approval)
3. Does the pattern match the URL? Check for typos
4. Open DevTools console for error messages

### CSP Errors

Some sites have strict Content Security Policies. Epupp patches Scittle to avoid `eval()`, but some operations may still fail. Check the console for CSP violation messages.

---

## Reference

### Manifest Keys

| Key | Required | Type | Description |
|-----|----------|------|-------------|
| `:epupp/script-name` | Yes | String | Script filename |
| `:epupp/site-match` | Yes | String or Vector | URL pattern(s) |
| `:epupp/description` | No | String | Human-readable description |
| `:epupp/run-at` | No | String | `"document-start"`, `"document-end"`, `"document-idle"` |
| `:epupp/require` | No | Vector | Scittle library URLs |

### Libraries

| URL | Namespace(s) |
|-----|--------------|
| `scittle://pprint.js` | `cljs.pprint` |
| `scittle://promesa.js` | `promesa.core` |
| `scittle://replicant.js` | `replicant.core` |
| `scittle://js-interop.js` | `applied-science.js-interop` |
| `scittle://reagent.js` | `reagent.core`, `reagent.dom` |
| `scittle://re-frame.js` | `re-frame.core` (+ Reagent) |
| `scittle://cljs-ajax.js` | `cljs-http.client` |

### Keyboard Shortcuts

| Context | Shortcut | Action |
|---------|----------|--------|
| Panel | Ctrl+Enter | Evaluate |
| Panel | Ctrl+Z | Undo |
| Panel | Ctrl+Shift+Z | Redo |
