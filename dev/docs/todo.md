* [x] Rename to Epupp: Live Tamper your Web
* [x] Build-in scripts should have special meta, and special UI
* [x] Scripts should have descriptions
* [x] Script names should be valid clojurescript filenames
  * [x] Friendly UI to help with this
  * [x] Script names can have slashes to denote folders
* [x] Popup UI can be with collapsible sections for scripts, built-in scripts, and repl connect
* [x] Popup UI Sticky header
* [ ] Figure out how to specify dependencies
  * [x] Scittle dependencie (replicant, etc)
  * [ ] Code files ("src"-ish)
  * [ ] Urls?
* [x] Move epupp namespace to separate file (library support)
  * [ ] ? Support non-script library files in Epupp
  * [ ] ? epupp ns as built-in library auto-prepended to scripts
* [x] Popup UI footer with info and sponsor link and such
* [ ] Update README screenshot
* [x] Installing scripts from script installer should add them as not approved
* [x] Script installer not adding button with Firefox and Safari
* [ ] Gist installer not working in Firefox and Safari
  - Firefox: [Gist Installer] Install failed: NetworkError when attempting to fetch resource. scittle.js:315:382
  - Safari: [Gist Installer] Install failed:"Load failed"
* [x] Add setting for re-establishing the repl/websocket connection after reload
* [x] Figure out how to make the repl connection follow the user when navigating, switching tabs, etc
* [ ] Figure out REPL connection reliability when switching away from the repl tab
* [~] Badge counter not reliable enough
* [x] Editing array matches doesn't work/is unclear
* [x] Consider moving all metadata to the manifest
* [x] Undo doesn't work in editor
* [s] Change to plain data for the manifest for installer scripts
* [x] Keep track on when we use the websocket for a new tab we are no longer connected on a previous connected tab using the same port number
* [x] Add config/setting for keeping the current tab connected while navigating, if it has been connected.
  * [ ] Clarify the "connect all pages on load" setting in the light of the “re-connect connected tabs” setting
* [x] E2E test the script installer
* [x] E2E test multiple tabs REPL connected and disconnected
* [x] E2E performance tuning
* [x] Blank slates for different sections of the UI
* [x] Unify css between panel and popup
* [ ] Consider if a version of Uniflow could be included as an Epupp library
* [x] Solve download of files from the repl
* [x] Solve upload of files from the repl
* [ ] Create a template Epupp REPL project
* [ ] Fix fallback port scittle behaviour in Calva
* [ ] Consider basic Epupp support in Calva (may not be needed if template project can cover)
* [ ] Scittle nrepl injected twice
* [~] Enable allow process also for non-matching
  * [x] Or remove allow, and instead always add new scripts as disabled
* [x] Have a repl file sync mode that can be enabled and which skips confirmation requirements
* [ ] Call userscripts via the REPL, even if they are not injected
* [ ] Should we evaluate the epupp.fs/* stuff inte existance instead of injecting?
* [ ] Is there a way to make println print in the repl output?
* [ ] Add scittle repls to the popup and panel in dev builds.
* [ ] The panel should be able to connect a repl (this will give all inspectors repl access, I think)
* [x] We need to be able to Disconnect the REPL.
* [x] Rename `:epupp/require` to something else. It conflicts with Clojure `require` semantics (renamed to `:epupp/inject`)
* [ ] Consider making `https://example.com/*` also match `https://example.com/` and `https://example.com`
* [x] Enforce no user provided userscripts use epupp namespaces/directories
* [x] Get logging under control
* [x] Add auto-run or some such to manifest? Or make no match pattern mean, not intended for auto-run...
* [x] BUG: Scripts not run from the popup play button.
* [x] Chase down Uniflow violations in panel and popup
    * [x] There are some non-Uniflow attrocities in panel.cljs
* [x] Centralize UI announcements to the banner
* [x] (Started) Use e2e-prefixed classes for e2e needed checks
* [x] The new-script template should add a pattern matching the current page
* [x] Built in scripts should be fully replaced on extension update
* [ ] Never promote `*://` for script matching ever
* [ ] All repl fs rejects should be system banner in the popup and panel
* [ ] Fix `epupp/` prefix error messages to not call it namespace
* [ ] Protect `epupp/` files from being deleted
* [ ] `mv!`, wrong delete count, when some are rejected
* [ ] Web installer fails to install with miss-spelled `:epupp/` keys in the manifest. Should it?
* [x] BUG: Gist installer fails to install when manifest does not declare `:epupp/auto-run-match`
* [x] Add setting for default repl ports
* [~] Make popup show when a script is injected
* [x] Run scripts on SPA page navigation
* [x] Sort built-in scripts last
* [x] Dev doc for web installer
* [x] Render built-in scripts last
* [x] Make sponsor link in footer use sponsor-heart status
* [x] Add CalvaTV to footer
* [ ] Add Joyride mention in README
* [x] Make the banner truly system wide and shared.
* [ ] Enable scripts to define popup UI, at least for the script entry
* [ ] Make Epupp css fetchable from epupp scripts
* [ ] Add some basic Epupp-styled UI widgets for userscripts
* [ ] Make an extension page/url for fs sync
* [ ] Custom script icon in the manifest
* [ ] Popup: Structure scripts:
  - Manual scripts
  - Matching auto-run
  - Non-matching auto-run
* [ ] Change Mozilla Addons slug
    ```
    curl -X PATCH "https://addons.mozilla.org/api/v5/addons/addon/browser-jack-in/" \
    -H "Authorization: Bearer <your-token>" \
    -H "Content-Type: application/json" \
    -d '{"slug": "epupp"}'
    ```
* [ ] Add run script to web installer identified scripts
* [ ] Add evaluate selection to web installer identified scripts
* [ ] Add evaluate current form to web installer identified scripts