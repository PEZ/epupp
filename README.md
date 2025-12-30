# Browser Jack-in

A web browser extension that lets you inject a [Scittle](https://github.com/babashka/scittle) REPL server into the browser page. Then you can connect your favorite Clojure editor and inspect and manipulate the DOM to your heart's content. Or let your AI agent loose on the page.

## Prerequisite

1. [Babashka](https://babashka.org)
2. A REPL client (such as a Clojure editor, like [Calva](https://calva.io))

## Usage

Assuming you have [installed the extension](#installing).

On the web page where you want to jack-in your REPL client: open the **Browser Jack-in** extension
and follow the 1-2-3 step instructions.

![Browser Jack-in Popup UI](browser-jack-in-screenshot.png)

Step **1** let's you copy a Babashka command line that starts the browser-nrepl server, which is sort of a relay between your editor and the browser page.

> [!NOTE]
> The extension does not tamper with the web pages until you connect the REPL. Once that is done the you evaluate code in the page context. It's similar to using the console in the development tools, but you do it from your editor, and instead of JavaScript you use ClojureScript.

## Demo

* https://www.youtube.com/watch?v=aJ06tdIjdy0

## How it Works

You connect to the browser's page execution environment using an nREPL client in your editor. The nREPL client is in turn connected to the Babashka **browser-nrepl** server which bridges nREPL (port 12345) to WebSocket (port 12346). This WebSocket port is what the browser extension connects to.

```mermaid
flowchart
    Human["You"] --> Editor

    subgraph Editor["Your Favorite Editor"]
         editor-nREPL["nREPL Client"]
    end

    AI["Your AI Agent"] --> nREPL["nREPL Client"]

    Editor -->|"nrepl://localhost:12345"| nPort
    nREPL -->|"nrepl://localhost:12345"| nPort

    nPort["Babashka browser-nrepl"]

    nPort <-->|ws://localhost:12346| Ext

    subgraph Browser["Browser"]
        Ext["Extension"]
        Ext <-->|"postMessage"| Scittle
        Ext -->|"Injects"| Scittle
        subgraph Page
          DOM["DOM/Execution Environment"]
          Scittle["Scittle REPL"]
          Scittle <--> DOM
        end
    end
```

In the browser yet another WebSocket Bridge is used so that the Scittle REPL can connect also when strict Content Security Policies (like with GitHub) would otherwise block WebSocket connections to localhost.

## Installing

Available on the Chrome Web Store: https://chromewebstore.google.com/detail/bfcbpnmgefiblppimmoncoflmcejdbei

Firefox pending review, but you can install the package manually in the browser's developer mode. Grab the extension zip file(s) from the latest [release](https://github.com/PEZ/browser-jack-in/releases).

**Firefox:**

1. Go to `about:debugging#/runtime/this-firefox`
2. Click "Load Temporary Add-on"
3. Select any file in `browser-jack-in-firefox.zip` file

**Chrome:**

0. Unpack `browser-jack-in-chrome.zip` (will unpack a `chrome` folder)
1. Go to `chrome://extensions`
2. Enable "Developer mode"
3. Click "Load unpacked"
4. Select the `chrome` folder

**Safari:**

I think I may skip publishing to the Safari App Store, because I value my sanity. (And actually the extension yet fails to establish the websocket connection in Safari. It tries to open it as a secure socket. If you know how to fix it, please file a PR.)

1. Safari → Settings → Developer → Click "Add Temporary Extension"
2. Select the `browser-jack-in-safari.zip` file
3. Ensure the extension is enabled in Safari → Settings → Extensions

## Privacy

The extension does not collect any data whatsoever, and never will.

## Licence

[MIT](LICENSE)

## Development

To build and hack on the extension, see the [development docs](docs/dev.md).

## Enjoy! ♥️

Please consider [sponsoring my open source work](https://github.com/sponsors/PEZ).

