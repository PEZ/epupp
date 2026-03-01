# Changelog

Changes to Epupp

![Epupp logo/symbol](epupp-symbol-128x128.png)

## [Unreleased]

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
