# Browser Jack-in

A web browser extension that lets you inject a [Scittle](https://github.com/babashka/scittle) REPL server into the browser page. Then you can connect your favorite Clojure editor and inspect and manipulate the DOM to your heart's content. Or let your AI agent loose on the page.

![Browser Jack-in Popup UI](browser-jack-in-screenshot.png)

## Prerequisite

1. [Babashka](https://babashka.org)
2. A REPL client (such as a Clojure editor, like [Calva](https://calva.io))

## Usage

Assuming you have [installed the extension](#installing).

On the web page where you want to jack-in your REPL client: open the **Browser Jack-in** extension
and follow the 1-2-3 step instructions.

## Installing

In waiting for this extension to be available on the extension web stores, you'll need to install the packages manually in the browser's developer mode. Grab the extension zip file(s) from the latest [release](https://github.com/PEZ/browser-jack-in/releases).

**Chrome:**

0. Unpack `browser-jack-in-chrome.zip` (will unpack a `chrome` folder)
1. Go to `chrome://extensions`
2. Enable "Developer mode"
3. Click "Load unpacked"
4. Select the `chrome` folder

**Firefox:**

1. Go to `about:debugging#/runtime/this-firefox`
2. Click "Load Temporary Add-on"
3. Select any file in `browser-jack-in-firefox.zip` file

**Safari:**

(Actually the extension fails to establish the websocket connection in Safari. It tries to open it as a secure socket. If you know how to fix it, please file a PR.)

1. Safari → Settings → Developer → Click "Add Temporary Extension"
2. Select the `browser-jack-in-safari.zip` file
3. Ensure the extension is enabled in Safari → Settings → Extensions

## Enjoy! ♥️

Please consider [sponsoring my open source work](https://github.com/sponsors/PEZ).

## Licence

MIT