# Epupp: Live Tamper your Web

A web browser extension that lets you tamper with web pages, live and/or with userscripts.

Epupp has two modes of operation:

1. **Live REPL connection from your editor to the web page**, letting you inspect and modify the page on the fly, with or without the assistance of an AI agent.
2. **Userscripts**: Tampermonkey style. Target all websites, or any subset of the web's pages, with prepared scripts that modify or query information from the page. You can also have userscripts that trigger only on demand. Userscripts can be triggered before the page loads, or after the DOM has settled.

The live REPL connection is a very efficient way to interactively develop userscripts, as well as doing one-off changes or data extractions.

Epupp is powered by [Scittle](https://github.com/babashka/scittle), which allows for scripting the page using [ClojureScript](https://clojurescript.org), a dynamic language enabling **Interactive Programming**.

## Example Use Cases

**Custom Data Dashboards**:
* **Problem**: Some web page you often visit keeps updated data, but doesn't present it aggregated the way you want it.
* **Solution**: A userscript automatically aggregates the data the way you want it and presents it the way you want it, every time you visit the page.

**One-off Data Extraction**:
* **Problem**: Some web page you visit one time has information you want to summarize (or just find).
* **Solution**: Connect your editor and/or AI agent and poke around the DOM of the web page until you understand enough to create a function that collects the data you need.

**Print-friendly Pages**:
* **Problem**: Some web page you visit is hard to print cleanly on your printer.
* **Solution**: Connect your editor and/or AI agent and poke around the DOM of the web page until you understand enough to create a function that isolates only the part you want to print. (This was the use case that made me create Epupp in the first place.) This can be generalized in a userscript that lets you use your mouse to point at the element you want to isolate on any web page.

**Missing UI Controls**:
* **Problem**: Some web app you often use lacks a button or input widget that would make your workflow convenient.
* **Solution**: A userscript automatically adds the buttons and widgets for you every time you use the app.

**AI-powered Web Inspection**:
* **Problem**: You want to show your AI agent some web app, in a way that it can read things and inspect whatever aspect of it you are interested in.
* **Solution**: Give the agent access to the page using the live REPL connection.

**AI-assisted Web Development**:
* **Problem**: You want your AI agent to help you with a page/app you are developing.
* **Solution**: Give the agent access to the page using the live REPL connection. While you and the agent are updating the page, the agent always has instant access to the DOM, styles, and everything to gather feedback on the changes. It can test that the app works as it should, and fulfill development tasks with much less help from you in manual testing.

When it comes to userscript use cases, a lot of things that you would use Tampermonkey for, you can use Epupp for instead. Tampermonkey can probably handle more use cases, but Epupp lets you develop userscripts in a much more dynamic way, with the shortest possible feedback loop.

With the live REPL connection, you will discover use cases you may not ever have thought about before, or thought about, but dismissed.

## The Epupp UI

The UI has three main components:

1. The extension **popup**. You access this from the Epupp extension icon. The popup hosts the REPL connection UI, lists userscripts, and provides accesss to Epupp settings.
2. A browser Developement Tools **panel**. The panel is for inspecting and editing userscripts, creating simple usercripts, and for dynamic interaction with the visited web page.
3. Your favorite **editor** and/or your favorite **AI agent** harness. This is enabled by the live REPL connection.

### Popup

### Panel

### REPL


## Userscripts Usage

> [!NOTE]
> Currently super **experimental**. I am fumbling quite a bit over the UI/Ux and APIs.

There is a script “editor” (a textarea) in the Development Tools tab named **Epupp**. It lets you edit and evaluate Clojure code directly in the execution context of the current page. The editor also has a button for saving the script. For this you need to also fill in:

* Script name: (Whatever for now, this is one of the things I am undecided about)
* Site pattern: a Tampermonkey compatible pattern targeting the sites where this script should be auto-injected and run.

Once you have saved the script, it will be added to a list of scripts in the extensions popup UI (the view opened when you click the extension icon in the browser's extensions UI.) It will also start as not enabled and not approved. Approve it and it will be run on any page you visit matching the site pattern.

### Using Scittle Libraries

Userscripts can load bundled Scittle ecosystem libraries via `:epupp/inject`:

```clojure
{:epupp/script-name "reagent_widget.cljs"
 :epupp/auto-run-match "*"
 :epupp/inject ["scittle://reagent.js"]}

(ns reagent-widget
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

(rdom/render [:h1 "Hello from Reagent!"]
             (doto (js/document.createElement "div")
               (->> (.appendChild js/document.body))))
```

**Available libraries:**

| Require URL | Provides |
|-------------|----------|
| `scittle://pprint.js` | `cljs.pprint` |
| `scittle://promesa.js` | `promesa.core` |
| `scittle://replicant.js` | Replicant UI library |
| `scittle://js-interop.js` | `applied-science.js-interop` |
| `scittle://reagent.js` | Reagent + React |
| `scittle://re-frame.js` | Re-frame (includes Reagent) |
| `scittle://cljs-ajax.js` | `cljs-http.client` |

Dependencies resolve automatically: `scittle://re-frame.js` loads Reagent and React.

For script timing, more library details, and examples, see the [User Guide](docs/user-guide.md).

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

## Extension Permissions

Epupp only asks for the permissions it strictly needs, even if the nature of the extension is such that it needs you to permit things like scripting (duh!). These are the permissions, and for what they are used:

- `scripting` - Inject userscripts
- `<all_urls>` - Inject on any site
- `storage` - Persist scripts/settings
- `webNavigation` - Auto-injection on page load
- `activeTab` - DevTools panel integration

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
