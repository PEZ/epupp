# Epupp: Live Tamper your Web

A web browser extension that lets you tamper with web pages, live and/or with userscripts.

<div align="center">
  <a href="https://www.youtube.com/watch?v=CuEWN5yYVa8">
    <img src="https://img.youtube.com/vi/CuEWN5yYVa8/maxresdefault.jpg" alt="Epupp Demo Video">
  </a>
  <br>
  <sup><a href="https://www.youtube.com/watch?v=CuEWN5yYVa8">▶ Watch the demo on YouTube</a></sup>
</div>

Epupp has two modes of operation:

1. **Live REPL connection from your editor to the web page**, letting you inspect and modify the page on the fly, with or without the assistance of an AI agent.
2. **Userscripts**: [Tampermonkey](https://www.tampermonkey.net) style. Target all websites, or any subset of the web's pages, with prepared scripts that modify or query information from the page. Userscripts can be configured to start before the page loads (`document-start`), when the DOM is ready but resources are still loading (`document-end`), or after everything has settled (`document-idle`).

The two form a powerful pair. The live REPL connection, while happily supporting one-off changes or data extractions, is also a very efficient and fun means to interactively develop userscripts.

> [!NOTE]
> To make this easier to get started with, and using, I have created a template project with some configuration and instructions for humans and AIs:
> * https://github.com/PEZ/my-epupp-hq
>
> But please read this below first.

## Example Epupp Use Cases

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

## Get Started

### Install

1. Install Epupp from the Chrome and Firefox extension/addon stores.
    * Chrome Web Store: [Epupp: Live Tamper Your Web](https://chromewebstore.google.com/detail/bfcbpnmgefiblppimmoncoflmcejdbei)
    * Firefox Browser Addons: https://addons.mozilla.org/firefox/addon/epupp/ (Please note that I haven't yet figured out all Firefox nuances, so some things may not work. Please file issues for things you note not working.)
    * <details>
      <summary>Safari</summary>

      I'm still pondering wether I should submit to Safari App Store. Apple doesn't exactly love developers... But you can still use Epupp with Safari:

      Grab the extension zip file(s) from the Epupp repository, latest [release](https://github.com/PEZ/epupp/releases). In the case of Safari, download `epupp-safari.zip`. Then in Safari:
      1. Open **Settings** -> **Developer**
      2. Click **Add Temporary Extension...**

      Please note that I haven't yet figured out all Safari nuances, so some things may not work. Please file issues for things you note not working.
      </details>
2. Pin Epupp to always be visible in the browser location bar. I also recommend to allow Epupp in Private Browsing for maximum utility. The Extension does not collect any data whatsoever.
3. Navigate away from the extension store, these pages can't be scripted.

### Userscript: Hello World

Create a userscript and run it in some different ways:

1. Open the Developers Tools panel in your browser
2. Select the **Epupp** tab
3. Last in the script text area, add a new line and enter:
   ```clojure
   (js/alert "Hello World!")
   ```
4. Click **Eval Script** to see the alert happen (and the browser console will print things too, assuming you kept the default script.)
5. Save the script using the **Save Script** button.
6. Open the Epupp popup by clicking the Eppup extension in the browser toolbar. You will see the script there, named `hello_world.cljs`.
7. Click the `Play` button on the script. The alert shows again.
8. Reload the page. The alert show again. The default example code includes a script manifest that will make the script trigger on domain you are currently visiting.
9. Navigate to another domain and the alert will not show. Navigate back to the previous domain,  and the alert shows.
10. Annoyed by the alert? Either delete the script or edit it to not alert. The Epupp popup lets you both delete the script and load it in the panel for editing.

### REPL: Hello World

While the Epupp panel let's you script the page, Live Tampering comes to life when you are powered by your favorite development environment, which could be a code editor, an AI agent harness, or both. As the creator of [Calva](https://calva.io) I choose to describe how it can be done using [VS Code](https://code.visualstudio.com) + Calva, and with [VS Code Copilot](https://github.com/features/copilot) and [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver). Please see [connecting-to-epupp](docs/connecting-to-epupp.md) README for info about connecting editors and AIs.

0. Install [Babashka](https://babashka.org) and VS Code. In VS Code, install the Calva extension
1. On a GitHub page (this one will do fine), open the Epupp popup and, copy the browser-nrepl command line, using the default ports
1. Paste the command line in a terminal and run it
1. From the Epupp popup, click **Connect**
1. In VS Code create a file `hello_world.cljs`
1. Click the REPL button in the VS Code status bar and select **Connect to a running REPL in your project**
1. Select **scittle** from the Project Types menu
1. In the file, type:
   ```clojure
   (js/alert "Hello World!")
   ```
   And press <kbd>alt</kbd>+<kbd>enter</kbd> <br>
   (The <kbd>alt</kbd> key is sometimes labeled <kbd>opt</kbd> or <kbd>option</kbd>.)
1. Replace the contents of the file with:
   ```clojure
     ;; Make the GitHub logo spin!
     (when-let [logo (js/document.querySelector ".octicon-mark-github")]
       (set! (.. logo -style -animation) "spin 2s linear infinite")
       ;; Add the keyframes for spinning
       (let [style (js/document.createElement "style")]
         (set! (.-textContent style)
               "@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }")
         (.appendChild js/document.head style)))
   ```
   Press <kbd>alt</kbd>+<kbd>enter</kbd>. The GitHub icon should start spinning.

Note: The <kbd>alt</kbd>+<kbd>enter</kbd> shortcut evaluates the current top level form/expression, which in the above cases happen to be everything in the file. If you are new to Clojure, for now you can think about it as executing the code in the connected browser tab. Please visit [calva.io](https://calva.io) for how to use and get started with Calva (and Clojure).

### AI Agent: Hello World

As the creator of Calva, and Calva Backseat Driver I chose to desceibe how to connect an AI agent using VS Code, Copilot and Calva Backseat Driver. See https://github.com/PEZ/my-epupp-hq for a growing list of instructions for other AI harnesses.

0. In addition to the above, install Copilot and Calva Backseat Driver to VS Code.
1. Ask Copilot to try the Epupp REPL to do something fun.

> [!NOTE]
> Copilot will need to know some basics about Epupp to be really effective with it. Consider copying the https://github.com/PEZ/my-epupp-hq template project and use that as a start for you and your AI to explore the Epupp REPL with.


### Install a userscript

An Epupp userscript is just a text file which starts with a script manifest and some code. You can install scripts in three ways:

1. Pasting/typing a script in the Epupp panel and clicking **Save Script**.
2. The **Web Userscript Installer** script. The extension has a built-in script that will identify Epupp scripts on code-hosting pages and add an **Install** button near them. On whitelisted domains (GitHub, GitHub Gists, GitLab, Codeberg, localhost) it can install scripts into Epupp. On other sites, it shows copy-paste instructions instead. Try it on this gist: https://gist.github.com/PEZ/3b0fdc406e7593eaaef609b6fb4a687d (It's the script created in the demo video.)
3. Using the REPL. There's a `epupp.fs` namespace for listing/reading/writing/renaming scripts in the Epupp extension storage.

## The Epupp UI

The UI has three main components:

1. The extension **popup**. You access this from the Epupp extension icon. The popup hosts the REPL connection UI, lists userscripts, and provides accesss to Epupp settings.
2. A browser Developement Tools **panel**. The panel is for inspecting and editing userscripts, creating simple usercripts, and for dynamic interaction with the visited web page.
3. Your favorite **editor** and/or your favorite **AI agent** harness. This is enabled by the live REPL connection.

### Popup

The popup has the following sections:

1. **REPL Connect**. Shows how to connect the current tab's REPL to your editor and/or AI agent. Also shows which tabs are currently connected.
2. Userscripts sections:
   * **Manual/on-demand**. Scripts that do not auto-run on any page, use the **play** button to run them.
   * **Auto-run for this page**. Scripts that has an `:epupp/auto-run-match` pattern than matches the current page.
   * *Auto-run not matching this page*. Scripts that auto-runs on some other pages, but not the current one.
   * **Special**. Built-in scripts that has some special way of being triggered to start. (Currently only the **Web Userscript Installer**)
3. **Settings**. Click the gear icon.

#### Script Management

Use the eye icon to load a script into the panel editor. From there it's the usual loop: edit, save, test.

Rename by changing `:epupp/script-name` in the manifest and saving. Copying is the same trick: give `:epupp/script-name` a new name and save, and you get a new script.

Built-in scripts are readable but not editable in place. Inspect one, copy it, rename it, then make it yours.

#### Settings

**Auto-reconnect** is per tab: if a tab was connected before reload/navigation, Epupp re-establishes the connection. Tabs that were never connected stay that way.

**Auto-connect REPL** makes Epupp try to connect right away on all pages you open.

**Allow REPL FS Sync for this tab** turns on `epupp.fs` for that tab. Enabling sync gives access to code on that tab to install userscripts. Only enable this on a page you trust. See [REPL FS Sync](docs/repl-fs-sync.md) for the API.

**Diagnostics logging** adds noisy logs for when things are weird.

**Export / Import** is for backups and moving scripts/settings between browser profiles or machines.

### Panel

The Browser Development Tools panel you can create and experiment with userscripts.

**New** clears the editor, with a safety prompt if you have unsaved changes. **Save Script** stores the script, as long as it starts with a valid manifest map.

Use **Eval Script** to run the whole editor buffer. `Ctrl/Cmd+Enter` / `Cmd+Enter` runs selection if you have one, otherwise the whole script. This is the fastest feedback loop in Epupp (without editor connection): define a helper, select just the call, press `Ctrl/Cmd+Enter`, inspect the result, repeat.

Results show up below the script textarea - both what ran and what came back (or the error). Use **Clear** when you want a clean slate.

### REPL

The REPL connection is there so that you can connect your code editor and/or AI agent harness to Epupp and live tamper the connected tabs. The system has these components:

* The Epupp **REPL** (a program running in the browser that can evaluate/execute Epupp/Scittle code in the connected tab's environment). The Epupp REPL listens to messages over a WebSocket.
* The **browser-nrepl** relay. This is a program you run on your computer that relays between the **REPL client** (using the nREPL protocol) and the connected browser tab (the WebSocket).
* The **REPL client**. Really the **nREPL** client. A program connecting software such as editors and AI agent harnesses to an nREPL server (the browser-nrepl relay, in this case). In the [REPL: Hello World](#repl-hello-world) example above the nREPL Client is Calva.

The procedure to connect a browser tab to your editor is:

1. **Start the browser-nrepl relay** (you can copy the command from the Epupp extension popup)
2. **Connect the browser tab**: Click **Connect** in the Epupp popup
3. **Connect your editor/AI harness**: This will depend on what editor/harness you use. _TL;DR_: You need a Clojure plugin/extension for your coding editor, and/or some Clojure hook or MCP server for your AI agent. (See above for using VS Code and Calva.)

See https://github.com/PEZ/my-epupp-hq for a template project that you can use to keep the Epupp REPL in easy reach.

The popup shows currently connected tabs in the REPL section. Each one gets a **Reveal** button so you can jump straight to connected tabs. The toolbar icon mirrors connection status of the current tab: white when it's not connected, gold when it is.

You can keep multiple tabs connected at once. Run multiple `browser-nrepl` relays (different port pairs), then point each tab at the relay you want. Handy when you want GitHub and GitLab live at the same time.

## The Anatomy of a Userscript

An Epupp userscript starts with a manifest map, followed by ClojureScript code:

```clojure
{:epupp/script-name "github_tweaks.cljs"
 :epupp/auto-run-match "https://github.com/*"
 :epupp/description "Make the GitHub logo spin"
 :epupp/run-at "document-idle"
 :epupp/inject ["scittle://replicant.js"]}

(ns github-tweaks)

(when-let [logo (js/document.querySelector ".octicon-mark-github")]
  (let [style (js/document.createElement "style")]
    (set! (.-textContent style)
          "@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }")
    (.appendChild js/document.head style))
  (set! (.. logo -style -animation) "spin 2s linear infinite"))
```

The manifest is a plain Clojure map at the top of the file. Your code runs in the page context with full DOM and JavaScript environment access.

### Manifest Keys

| Key | Required | Default | Description |
|-----|----------|---------|-------------|
| `:epupp/script-name` | Yes | - | Filename, auto-normalized to `snake_case.cljs`. Cannot start with `epupp/` (reserved for built-in scripts). |
| `:epupp/auto-run-match` | No | - | URL glob pattern(s). String or vector of strings. Omit for manual-only scripts. |
| `:epupp/description` | No | - | Shown in the popup UI. |
| `:epupp/run-at` | No | `"document-idle"` | When to run: `"document-start"`, `"document-end"`, or `"document-idle"`. |
| `:epupp/inject` | No | `[]` | Scittle library URLs to load before the script runs. |

Scripts with `:epupp/auto-run-match` start disabled. Enable them in the popup for auto-injection on matching pages. Scripts without this key only run when you click the Play button in the popup.

Script names are auto-normalized to valid ClojureScript file names: `snake_case.cljs` (so `"My Cool Script"` becomes `my_cool_script.cljs`).

Scripts can also be managed programmatically via the [FS API](docs/repl-fs-sync.md).

### URL Patterns

`:epupp/auto-run-match` uses glob syntax. `*` matches any characters:

```clojure
;; Single pattern
{:epupp/auto-run-match "https://github.com/*"}

;; Multiple patterns
{:epupp/auto-run-match ["https://github.com/*"
                        "https://gist.github.com/*"]}

;; Match both http and https
{:epupp/auto-run-match "*://example.com/*"}
```

### Script Timing

Scripts can run at different points during page load:

- `"document-idle"` (default) - After the page has fully loaded.
- `"document-end"` - At DOMContentLoaded. DOM exists but images/iframes may still be loading.
- `"document-start"` - Before any page JavaScript. `document.body` does not exist yet.

If your script is using `document-start`, you need to wait for the DOM if your code needs it:

```clojure
{:epupp/script-name "early_intercept.cljs"
 :epupp/run-at "document-start"}

;; This runs before any page scripts
(set! js/window.myGlobal "intercepted")

;; Wait for DOM if needed
(js/document.addEventListener "DOMContentLoaded"
  (fn [] (js/console.log "Now DOM exists")))
```

> [!NOTE]
> Safari does not support early script timing. Scripts always run at `document-idle` regardless of `:epupp/run-at`.

### Using [Scittle](https://github.com/babashka/scittle) Libraries

Userscripts can load bundled Scittle ecosystem libraries via `:epupp/inject`:

```clojure
{:epupp/script-name "replicant_widget.cljs"
 :epupp/auto-run-match "*"
 :epupp/inject ["scittle://replicant.js"]}

(ns replicant-widget
  (:require [replicant.dom :as r]))

(r/render
 (doto (js/document.createElement "div")
   (->> (.appendChild js/document.body)))
 [:h1 "Hello from Replicant!"])
```

**Available libraries:**

| Require URL | Provides |
|-------------|----------|
| `scittle://pprint.js` | `cljs.pprint` |
| `scittle://promesa.js` | `promesa.core` |
| `scittle://replicant.js` | Replicant UI library |
| `scittle://js-interop.js` | `applied-science.js-interop` |
| `scittle://reagent.js` | [Reagent](https://reagent-project.github.io) + React |
| `scittle://re-frame.js` | [Re-frame](https://github.com/day8/re-frame) (includes Reagent) |
| `scittle://cljs-ajax.js` | `cljs-http.client` |

Dependencies resolve automatically: `scittle://re-frame.js` loads Reagent and React.

From a live REPL session, you can load libraries at runtime with `epupp.repl/manifest!`:

```clojure
(epupp.repl/manifest! {:epupp/inject ["scittle://pprint.js"]})
(require '[cljs.pprint :as pprint])
(pprint/pprint {:some "data"})
```

Safe to call multiple times - already-loaded libraries are skipped.

## Demo

* https://www.youtube.com/watch?v=CuEWN5yYVa8

## REPL Pitfalls

### Navigation Hangs the REPL

On non-SPA sites, setting `js/window.location` (or clicking a link) from a REPL eval can tear down the page and its REPL. The eval response never returns - the connection hangs until you reconnect the repl. Very disruptive!

**Fix: Defer navigation with `setTimeout`:**

```clojure
;; BAD - eval never completes, connection hangs
(set! (.-location js/window) "https://example.com/page")

;; GOOD - returns immediately, navigates after response completes
(js/setTimeout
  #(set! (.-location js/window) "https://example.com/page")
  50)
;; => timeout ID returned instantly
```

After navigation, wait for the new page to load and REPL to reconnect. All prior definitions will be gone - redefine utilities or bake them into a userscript.

### Clipboard Access Blocked

`navigator.clipboard.writeText` is often blocked, needing user gesture, due to permissions policy. Use a textarea workaround:

```clojure
(defn copy-to-clipboard! [text]
  (let [el (js/document.createElement "textarea")]
    (set! (.-value el) text)
    (.appendChild js/document.body el)
    (.select el)
    (js/document.execCommand "copy")
    (.removeChild js/document.body el)))
```

## REPL Troubleshooting

### No scripting for you at the Extensions Gallery

If you try to connect the REPL, immediatelly after installing, you may see a message that you can't script the extension gallery.

![No scripting for you at the Extensions Gallery](docs/extension-gallary-no-scripting.png)

This is because you can't. Which is a pity! But the web is full of pages we can script.

(Same goes for `chrome://extensions/` and any other `chrome://` page.)


## Troubleshooting

### No Epupp panel?

The extension fails at adding a Development Tools panel at any `chrome://` page, and also at the Extension Gallery itself. These are pages from where you may have installed Epupp the first time. Please navigate to other pages and look for the panel.

### Connection fails?

Check that the relay server is running and ports match between terminal and popup. If it still won't connect, try restarting the relay server.

### Script doesn't run?

1. Is auto-run enabled? (the checkbox in the popup)
2. Does the pattern match the URL? Check for typos.
3. Open DevTools console for error messages.

### CSP errors?

Some sites have strict Content Security Policies. Epupp mostly should do things in ways that are allowed by these oliciies, but still, check the console for CSP violation messages.

## Extension Permissions

Epupp only asks for the permissions it strictly needs, even if the nature of the extension is such that it needs you to permit things like scripting (duh!). These are the permissions, and for what they are used:

- `scripting` - Inject userscripts
- `<all_urls>` - Inject on any site
- `storage` - Persist scripts/settings
- `webNavigation` - Auto-injection on page load
- `activeTab` - DevTools panel integration

## Scittle

Epupp is powered by [Scittle](https://github.com/babashka/scittle), which allows for scripting the page using [ClojureScript](https://clojurescript.org), a dynamic language supporting **Interactive Programming**.

## Privacy

The extension does not collect any data whatsoever, and never will.

## Licence

[MIT](LICENSE)

(Free to use and open source. 🍻🗽)

## Further Reading

- [Connecting editors and AI harnesses](docs/connecting-to-epupp.md)
- [REPL FS Sync and the `epupp.fs` API](docs/repl-fs-sync.md)

## Development

To build and hack on the extension, see the [development docs](dev/docs/dev.md).

## Enjoy! ♥️

Epupp is created and maintained by Peter Strömberg a.k.a PEZ, and provided as open source and is free to use. A lot of my time is spent on bringing Epupp and related software to you, and keeping it supported, working and relevant.

* Please consider [sponsoring Epupp](https://github.com/sponsors/PEZ).
