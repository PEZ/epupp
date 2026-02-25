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

## Firefox

* [ ] Script import doesn't work. File dialog opens, but nothing happens when opening a file.

## Unscriptable pages

Some pages are not scriptable. Like the `chrome://` etc pages, and the extension gallery. For these pages we should have a permanent banner informing that scripting doesn't work. Hopefully there is a reliable way to detect this condition.