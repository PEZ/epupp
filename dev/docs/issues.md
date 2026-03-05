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

**Host permissions are optional in Firefox.** Unlike Chrome, Firefox treats `host_permissions` as revocable. Epupp checks for permission before injection and shows a "Grant Permission" banner in the popup when `<all_urls>` is not granted. Without it, auto-run scripts and manual injection will silently skip tabs where permission is missing.

* [ ] Script import doesn't work. File dialog opens, but nothing happens when opening a file.

## Unscriptable pages

Some pages are not scriptable. Like the `chrome://` etc pages, and the extension gallery. For these pages we should have a permanent banner informing that scripting doesn't work. Hopefully there is a reliable way to detect this condition.