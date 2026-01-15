* [x] Rename to Epupp: Live Tamper your Web
* [s] Build-in scripts should have special meta, and special UI
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
* [ ] Badge counter not reliable enough
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
* [ ] Solve download of files from the repl
* [ ] Solve upload of files from the repl
* [ ] Create a template Epupp REPL project
* [ ] Fix fallback port scittle behaviour in Calva
* [ ] Consider basic Epupp support in Calva (may not be needed if template project can cover)
* [ ] Scittle nrepl injected twice