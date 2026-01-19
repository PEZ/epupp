# Known Issues

## Safari: Limited Extension Functionality

**Status:** Not investigated

**Symptoms:**
- **DevTools Panel:** No scripts work from panel - always returns "tab not found" error
- **Userscripts:** Auto-injection doesn't trigger on any site
- **Popup "Run":** Works on some sites (calva.io) but fails on CSP-strict sites (GitHub, YouTube)

**Notes:** The panel issue appears to be a general Safari DevTools API limitation, not specific to any feature. Needs investigation to determine if these are fixable or platform constraints.

---

## Undo Not Working in DevTools Panel Editor (Brave-specific)

**Status:** Parked - needs further investigation

**Symptom:** In Brave browser, typing in the DevTools panel code editor and pressing Cmd+Z does not undo the typed text. The undo stack appears to be cleared.

**Affected browsers:** Brave only (Chrome, Firefox, Safari, and Playwright/Chromium all work correctly)

### Investigation Findings

1. **E2E tests pass:** Created a Playwright E2E test for undo functionality - it passes consistently, even in headless Chromium.

2. **Controlled input pattern:** The editor uses Reagami with a controlled textarea pattern:
   ```clojure
   [:textarea {:value code :on-input #(dispatch! [[:editor/ax.set-code ...]])}]
   ```
   This causes Reagami to set `element.value` on every render.

3. **Bizarre observation:** Adding an unmanaged textarea (without `:value` binding) anywhere in the popup or panel causes undo to start working for the controlled editor textarea in Brave. Removing the unmanaged textarea breaks undo again.

4. **Attempted fix:** Changed to uncontrolled pattern with `defaultValue` + `:on-render` sync:
   ```clojure
   [:textarea {:defaultValue code
               :on-render (fn [el _phase _data]
                            (let [state-code (:panel/code @!state)]
                              (when (not= (.-value el) state-code)
                                (set! (.-value el) state-code))))}]
   ```
   This did not resolve the issue in Brave.

### Possible Causes

- **Reagami rendering behavior:** Reagami may be setting the value property in a way that clears undo in Brave but not other browsers. The bizarre "unmanaged textarea enables undo" behavior suggests something about Brave's internal undo tracking.

- **Brave-specific behavior:** Brave may handle programmatic value assignment differently than standard Chromium for undo stack management.

- **Not conclusively a Reagami bug:** While Reagami is involved, we cannot conclude it's a Reagami issue without more investigation. The behavior is Brave-specific and other browsers work correctly with the same code.

### Workarounds Considered

1. Using `defaultValue` instead of `value` - did not help
2. Only syncing value when DOM differs from state - did not help
3. The "add unmanaged textarea" hack works but is not a real solution

### Next Steps (when revisiting)

1. Investigate how Reagami sets the value property during rendering
2. Check if Brave has documented differences in undo stack handling
3. Test with a minimal reproduction outside of the extension context
4. Consider whether a different approach to state synchronization could preserve undo

### References

- E2E test: [e2e/panel_test.cljs](../../e2e/panel_test.cljs) (search for "undo")
- Panel editor: [src/panel.cljs](../../src/panel.cljs)
- Reagami: `node_modules/reagami/lib/reagami/core.mjs`

---

## Epupp Namespace as Separate File

**Status:** Future enhancement

**Current state:** The `epupp` namespace code (providing `manifest!`, `ls`, `cat`, `save!`, `mv!`, `rm!`) is embedded as a string literal in `background.cljs`. This works but makes the code harder to edit - no syntax highlighting, no paredit, no REPL support.

**Desired state:** Move the epupp namespace to a separate `.cljs` file that Scittle loads at runtime. This would provide full editor support for maintaining the REPL API.

**Connection to library support:** This relates to a broader feature - supporting non-script (library) files in Epupp. The epupp namespace could become a built-in library that is auto-prepended to all scripts, similar to how userscript managers provide GM_* functions.

**Design considerations:**
- How to distinguish library files from userscripts (no site-match, not shown in script list?)
- Auto-injection order: libraries before userscripts
- Built-in vs user-defined libraries
- Namespace collision handling

**Why not macros:** Attempted using Squint `defmacro` to stringify Clojure forms at compile time, but Squint macros don't receive forms as data in the REPL context (form argument comes in as `nil`). This approach won't work for this use case.

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
