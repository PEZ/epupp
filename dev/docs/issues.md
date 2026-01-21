# Known Issues

## Safari: Limited Extension Functionality

**Status:** Not investigated

**Symptoms:**
- **DevTools Panel:** No scripts work from panel - always returns "tab not found" error
- **Userscripts:** Auto-injection doesn't trigger on any site
- **Popup "Run":** Works on some sites (calva.io) but fails on CSP-strict sites (GitHub, YouTube)

**Notes:** The panel issue appears to be a general Safari DevTools API limitation, not specific to any feature. Needs investigation to determine if these are fixable or platform constraints.

* [ ] No tab icon in the connected tabs list
* [ ] Can't connect on YouTube

---

## Log Module Needs Console Targeting

**Status:** Future enhancement

**Current state:** The `log` module provides consistent prefixed logging (`[Epupp:Module:Context]`) but all logs go to the extension's console (popup, panel, or background worker depending on where the code runs). Some logs, like FS sync notifications, need to appear in the page console so users see them without inspecting extension pages.

**Workaround:** FS logging currently uses `js/console.info` directly with manual prefix formatting. See TODO comments in:
- `src/popup.cljs` - `:popup/fx.log-fs-banner` effect
- `src/panel.cljs` - fs-event listener

**Desired state:** Upgrade the log module to support targeting:
- Extension console (current behavior)
- Page console (via content script messaging or `chrome.devtools.inspectedWindow.eval`)
- Both

**Design considerations:**
- API: `(log/info "Module" "Context" :target :page "message")` or separate functions?
- Page console requires routing through content bridge
- May need different approaches for popup/panel vs background worker


## Firefox

* [ ] Script import doesn't work. File dialog opens, but nothing happens when opening a file.

## Safari

## e2e tests

* [ ] **HIGH PRIORITY**: There is flakyness in fs sync write tests. We have tried to fix it a lot of time, but never fully got rid of it.

## Unscriptable pages

Some pages are not scriptable. Like the `chrome://` etc pages, and the extension gallery. For these pages we should have a permanent banner informing that scripting doesn't work. Hopefully there is a reliable way to detect this condition.