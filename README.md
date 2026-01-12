# Epupp: Live Tamper your Web

A web browser extension that lets you tamper with web pages, live and/or with userscripts. Powered by by [Scittle](https://github.com/babashka/scittle), Epupp lets you inspect and modify the pages you visit while you are there. You can save your tampers as userscripts, TamperMonkey style. For live tampers and interactive development of userscripts, you can jack in your favorite editor or AI tools to interactively tamper with the page.

## Userscripts Usage

> [!NOTE]
> Currently super **experimental**. I am fumbling quite a bit over the UI/Ux and APIs.

There is a script “editor” (a textarea) in the Development Tools tab named **Epupp**. It lets you edit and evaluate Clojure code directly in the execution context of the current page. The editor also has a button for saving the script. For this you need to also fill in:

* Script name: (Whatever for now, this is one of the things I am undecided about)
* Site pattern: a TamperMonkey compatible pattern targeting the sites where this script should be auto-injected and run.

Once you have saved the script, it will be added to a list of scripts in the extensions popup UI (the view opened when you click the extension icon in the browser's extensions UI.) It will also start as not enabled and not approved. Approve it and it will be run on any page you visit matching the site pattern.

### Advanced: Script Timing

By default, scripts run after the page has loaded (`document-idle`). For scripts that need to intercept page initialization or modify globals before page scripts run, you can specify early timing via metadata in your code:

```clojure
{:epupp/run-at "document-start"}
(do
  ;; This runs BEFORE page scripts
  (set! js/window.myGlobal "intercepted"))
```

Available timing values:
- `document-start`: Runs before any page scripts (useful for blocking/intercepting)
- `document-end`: Runs at DOMContentLoaded
- `document-idle`: Runs after page load (default)

> [!NOTE]
> **Safari limitation:** Script timing (`document-start`/`document-end`) is not supported in Safari due to API limitations. Scripts will run at `document-idle` regardless of the timing annotation. Chrome and Firefox support all timing values.

### Advanced: Using Scittle Libraries

Epupp bundles the Scittle ecosystem libraries. Add them to your scripts using the `:epupp/require` manifest key:

```clojure
{:epupp/script-name "Reagent Test"
 :epupp/site-match "*"
 :epupp/require ["scittle://reagent.js"]}

(ns reagent-test
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

;; Just test that Reagent is available
(js/console.log "Reagent loaded!" (some? r/atom))

;; Create our own container
(let [container (js/document.createElement "div")]
  (set! (.-id container) "epupp-reagent-test")
  (set! (.. container -style -cssText) "position: fixed; top: 10px; right: 10px; padding: 10px; background: #2ea44f; color: white; border-radius: 8px; z-index: 99999;")
  (.appendChild js/document.body container)
  (rdom/render [:div "Hello from Reagent! 🎉"] container))
```

#### Available Libraries

| Require URL | Provides |
|-------------|----------|
| `scittle://pprint.js` | `cljs.pprint` |
| `scittle://promesa.js` | `promesa.core` |
| `scittle://replicant.js` | Replicant UI library |
| `scittle://js-interop.js` | `applied-science.js-interop` |
| `scittle://reagent.js` | Reagent + React |
| `scittle://re-frame.js` | Re-frame (includes Reagent) |
| `scittle://cljs-ajax.js` | `cljs-http.client` |

Dependencies are resolved automatically. For example, requiring `scittle://re-frame.js` will also load Reagent and React.

I do not plan to build the code editor out much. Mostly because the preferred way to work with scripts is from your editor connected to the [REPL](#repl-usage) (or via your AI agent connected to the REPL). A thing I will probably add is to evaluate sub expressions (in addition to the whole script).

## REPL Usage

### Prerequisites

1. [Babashka](https://babashka.org)
2. A REPL client (such as a Clojure editor, like [Calva](https://calva.io))

On the web page where you want to jack-in your REPL client: open the **Epupp** extension
and follow the 1-2-3 step instructions.

![Epupp Popup UI](docs/browser-jack-in-screenshot.png)

Step **1** let's you copy a Babashka command line that starts the browser-nrepl server, which is sort of a relay between your editor and the browser page.

> [!NOTE]
> The extension does not tamper with the web pages until you connect the REPL. Once that is done the you evaluate code in the page context. It's similar to using the console in the development tools, but you do it from your editor, and instead of JavaScript you use ClojureScript.

### Demo

* https://www.youtube.com/watch?v=aJ06tdIjdy0


### REPL Troubleshooting

#### No scripting for you at the Extensions Gallery

If you try to connect the REPL, immediatelly after installing, you may see a message that you can't script the extension gallery.

![No scripting for you at the Extensions Gallery](docs/extension-gallary-no-scripting.png)

This is because you can't. Which is a pity! But the web is full of pages we can script.

(Same goes for `chrome://extensions/` and any other `chrome://` page.)

## Installing

Available on the Chrome Web Store: https://chromewebstore.google.com/detail/bfcbpnmgefiblppimmoncoflmcejdbei

Firefox pending review, but you can install the package manually in the browser's developer mode. Grab the extension zip file(s) from the latest [release](https://github.com/PEZ/browser-jack-in/releases).

I'm still pondering if I should submit to Safari App Store or not. Apple doesn't exactly love developers...

**Firefox:**

1. Go to `about:debugging#/runtime/this-firefox`
2. Click "Load Temporary Add-on"
3. Select any file in `epupp-firefox.zip` file

**Safari:**

1. Go to **Settings** -> **Developer**
2. **Add Temporary Extension...**

**Chrome:**

0. Unpack `epupp-chrome.zip` (will unpack a `chrome` folder)
1. Go to `chrome://extensions`
2. Enable "Developer mode"
3. Click "Load unpacked"
4. Select the `chrome` folder


## Troubleshooting

### No Epupp panel?

The extension fails at adding a Development Tools panel at any `chrome://` page, and also at the Extension Gallery itself. These are pages from where you may have installed Epupp the first time. Please navigate to other pages and look for the panel.

## Privacy

The extension does not collect any data whatsoever, and never will.

## Licence

[MIT](LICENSE)

(Free to use and open source. 🍻🗽)

## Development

To build and hack on the extension, see the [development docs](dev/docs/dev.md).

## Enjoy! ♥️

Epupp is created and maintained by Peter Strömberg a.k.a PEZ, and provided as open source and is free to use. A lot of my time is spent on bringing Epupp and related software to you, and keeping it supported, working and relevant.

* Please consider [sponsoring my open source work](https://github.com/sponsors/PEZ).
