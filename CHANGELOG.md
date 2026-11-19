# Changelog

Changes to Epupp

![Epupp logo/symbol](epupp-symbol-128x128.png)

## [Unreleased]

- Fix: Missing bundled Epupp libraries race condition

## [0.0.22] - 2026-07-19

- Bump Scittle to 0.8.32 (SCI 0.15.56 with CLJS JIT)
  - Scripts JIT-compile on pages that allow `eval`/`Function`; otherwise SCI falls back to the interpreter (e.g. GitHub, YouTube)


## [0.0.21] - 2026-07-14

- Add `epupp.storage` API
- Make **epupp.tools** available to userscripts via `epupp://epupp/tools.cljs`


## [0.0.20] - 2026-05-13

- Add CSS file injection support in `:epupp/inject` - CSS files (any URL ending in `.css`) are injected as `<link rel="stylesheet">` tags before scripts run


## [0.0.19] - 2026-05-03

- Connecting a tab waits for a server to respond (instead of giving up on no response)


## [0.0.18] - 2026-04-23

- Default to JPEG 75% for captures, to protect the repl connection from blowing up
- Fix evaluating element captures on Firefox

## [0.0.17] - 2026-04-22

- Add `epupp.tools` built-in library: `capture-element`, `capture-selector`, `capture-visible` for capturing DOM element and viewport screenshots as data URL images


## [0.0.16] - 2026-04-08

- Fix: Honor `:force true` with `fs/mv!`


## [0.0.15] - 2026-04-07

- Add support for Epupp dependencies (Epupp `.cljs` code as libraries)
  - Building on the existing `:epupp/inject` manifest functionality
  - [Make it possible to declare dependency on other scripts in the Epupp filesystem](https://github.com/PEZ/epupp/issues/10)
    - Local libraries injected via `epupp://SCRIPT-NAME` scheme
  - Transitive dependencies supported
  - External dependencies: inject code from GitHub raw content hosts, including gists, via HTTPS URLs
    - Supported hosts: `raw.githubusercontent.com`, `gist.githubusercontent.com`
    - SHA-pinned for immutable, reproducible dependency resolution
    - Fetched and cached on script save, injected from cache at page load
- Fix bug with document-start scripts being run twice


## [0.0.14] - 2026-03-19

- Support namespace-only manifest script names. (Still stored as normalized filename, replacing `.` with `/`.)


## [0.0.13] - 2026-03-18

- Auto-connect setting replaces on/off with three levels: Never, On page load, On page load + tab activation
- Fix: Disconnected tabs no longer reconnect on navigation


## [0.0.12] - 2026-03-15

- Fix: Changing default ports in Settings now correctly cascades to REPL Connect ports for all sites that have not been explicitly given different ports.
- Default Epupp REPL ports changed to 3339/3340.


## [0.0.11] - 2026-03-07

- Fix: repl WebSocket disconnecting after five minutes inactivity of the connected tab.


## [0.0.10] - 2026-03-05

- Add check and workflow for granting Epupp permissions to auto-running userscripts in Firefox


## [0.0.9] - 2026-03-04

- Minor styling fixes

## [0.0.8] - 2026-03-01

- Show REPL connect related settings in REPL UI when connected


## [0.0.7] - 2026-02-26

- New name, **Epupp: Live Tamper your Web**
- Userscripts, [Tampermonkey](https://www.tampermonkey.net/) style (albeit a much smaller take on it)
  - Scripts can be auto-run based on patterns, or manual (you click the play button to run them)
  - Scripts can start at different points in the page load life cycle, before, during, and after. (After is default)
  - Scripts can inject all Scittle libraries
  - There is a Browser Development Tools panel for editing userscripts and trying out code on the current page
- Major update to the REPL connect
  - Connect any number of tabs
  - You can auto-re-connect the current tab as you navigate
  - You can auto-connect any tab you visit
  - There is a filesystem REPL API for syncing your scripts between Epupp and your computer


## [0.0.6] - 2026-01-01

- Make the extension work in Safari


## [0.0.5] - 2026-01-01

- Stabilize the reconnect scenario


## [0.0.4] - 2025-12-31

- Fix: [Firefox doesn't connect on strict CSP sites (like GitHub)](https://github.com/PEZ/epupp/issues/3)
- Fix: [nREPL server invocation fails in some shells](https://github.com/PEZ/epupp/issues/2)


## [0.0.3] - 2025-12-30

- Fix: [Can't Jack-in to github.com things (CSP `connect-src 'self'`)](https://github.com/PEZ/epupp/issues/1)
  - WebSocket bridge connects the page's MAIN world with the extension's ISOLATED world, bringing localhost in reach

## [0.0.2] - 2025-12-28

- Fix: Extension now works on sites with strict Content Security Policy (YouTube, GitHub, etc.)
  - Patched Scittle's dynamic import polyfill to avoid `eval()`
  - Added Trusted Types policy for script injection
  - Removed unnecessary `eval` probe in CSP detection


## [0.0.1] - 2024-12-27

- Initial release
- Inject Scittle nREPL into any web page (that doesn't restrict `eval`)
- Connect your Clojure editor to the browser via WebSocket relay
- Per-site port configuration with local storage persistence
- Support for Chrome, Firefox, and Safari (Safari has WebSocket limitations)
