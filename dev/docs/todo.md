* [x] Rename to Epupp: Live Tamper your Web
* [s] Build-in scripts should have special meta, and special UI
* [x] Scripts should have descriptions
* [x] Script names should be valid clojurescript filenames
  * [x] Friendly UI to help with this
  * [x] Script names can have slashes to denote folders
* [x] Popup UI can be with collapsible sections for scripts, built-in scripts, and repl connect
* [x] Popup UI Sticky header
* [ ] Figure out how to specify dependencies
  * [ ] Scittle dependencie (replicant, etc)
  * [ ] Code files ("src"-ish)
  * [ ] Urls?
* [ ] Popup UI footer with info and sponsor link and such
  * [ ] Update README screenshot
* [x] Installing scripts from script installer should add them as not approved
* [ ] Script installer not adding button with Firefox and Safari
* [ ] Add setting for re-establishing the repl/websocket connection after reload
  * [ ] Figure out how to make the repl connection follow the user when navigating, switching tabs, etc
* [ ] Figure out REPL connection reliability when switching away from the repl tab
* [ ] Badge counter not reliable enough
* [ ] Editing array matches doesn't work/is unclear
* [ ] Consider moving all metadata to the manifest