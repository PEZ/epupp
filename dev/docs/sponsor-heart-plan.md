# Implementation Plan: Sponsor Status Heart

**Created:** February 7, 2026
**Status:** In Progress

## Overview

Add a clickable sponsor-status heart icon to the popup and panel headers. The heart appears unfilled by default and becomes filled when the user is detected as a GitHub sponsor of PEZ. Clicking the heart opens the GitHub Sponsors page and a builtin userscript detects sponsor status, updating the extension via the existing message protocol.

## UX Flow

1. **Header heart icon** - Popup and panel headers show a heart icon to the right of the title:
   - Unfilled heart: default/non-sponsor state. Tooltip: "Click to update sponsor status"
   - Filled heart: sponsor detected. Tooltip: "Thank you for sponsoring!"
2. **User clicks the heart** - A new tab opens to `https://github.com/sponsors/PEZ`
3. **Userscript runs on the sponsors page** and checks login status:
   - **Not logged in** - The page already shows a "You must be logged in to sponsor PEZ" banner. The userscript inserts an additional message near it: "Log in to update your Epupp sponsor status"
   - **Logged in, not a sponsor** - The userscript inserts a banner: "Sponsor PEZ to get your Epupp sponsor heart!"
   - **Logged in and sponsoring** - The userscript shows a "Thanks for sponsoring me!" banner and sends a `"sponsor-status"` message to Epupp, which persists the status and updates all UI contexts
   - **Hardcoded forever sponsors** - Certain users (borkdude, richhickey, swannodette, thheller) are recognized by username and shown a personalized thank-you banner. They also get `true` sent to Epupp.

## Architecture

### Message Flow

```
User clicks heart in popup/panel
  -> popup/panel opens tab to github.com/sponsors/PEZ via chrome.tabs.create
  -> builtin userscript auto-runs on that page
  -> userscript inspects DOM for login + sponsor status
  -> if sponsor: window.postMessage({source: "epupp-userscript", type: "sponsor-status", ...})
  -> content bridge whitelists and forwards to background
  -> background persists to chrome.storage.local
  -> storage.onChanged fires in popup/panel
  -> UI re-renders with filled heart
```

### State

```
chrome.storage.local:
  {:sponsor/status true          ; or absent/false
   :sponsor/checked-at 1738880000000}  ; js/Date.now timestamp, reset after 3 months

popup !state / panel !state:
  {:sponsor/status true}   ; derived: true only if stored true AND checked-at < 3 months ago
```

The 3-month expiry ensures sponsor status is re-verified periodically. When expired, the heart reverts to unfilled, prompting the user to click and re-check.

**One-way signal principle:** The userscript only ever sends `true` (sponsor detected) to Epupp - it never sends `false`. If the user visits the sponsors page and is not currently sponsoring, the script simply does nothing. This means a one-time sponsorship gives 3 months of sponsor status (from the timestamp), after which it naturally expires. This is fair and avoids prematurely resetting status for one-time sponsors who would otherwise show as "not sponsoring" on revisit.

### GitHub Sponsors Page DOM Signals

Verified via REPL on live GitHub Sponsors pages:

| State | DOM Signal |
|-------|------------|
| Not logged in | `meta[name='user-login']` content is empty; flash banner "You must be logged in to sponsor" |
| Logged in, not sponsoring | `h1.f2` heading contains "Become a sponsor to [Name]" |
| Logged in, sponsoring (recurring) | "Sponsoring as" H4 text present; no `h1.f2` heading |
| Just sponsored (one-time) | URL has `?success=true` query parameter; no `h1.f2` or "Sponsoring as" |
| Forever sponsor | `meta[name='user-login']` matches hardcoded username map |

## Implementation Chunks

### Chunk 1: Heart Icon

**File:** `src/icons.cljc`

- [x] Check what the current `heart` icon looks like and decide approach (`:filled?` prop or separate `heart-filled` component)
- [x] Add filled heart SVG path(s) to `src/icons.cljc` ;PEZ: I think we already have the heart icon. Maybe make it take a `:filled?` option?
- [ ] Verified by unit tests
- [ ] Verified by e2e tests
- [ ] Verified by PEZ

**Implementation notes:** Existing `heart` function modified to accept `:filled?` option. When true, renders solid heart path; when false/absent, renders original outline. No breaking changes.

### Chunk 2: Storage Keys for Sponsor Status

**File:** `src/storage.cljs`

- [x] Add `:sponsor/status` key (boolean) to storage schema
- [x] Add `:sponsor/checked-at` key (timestamp ms) to storage schema
- [x] Add helper function to derive effective sponsor status: `true` only when `:sponsor/status` is `true` AND `:sponsor/checked-at` is less than 3 months ago
- [x] Storage module already handles `chrome.storage.onChanged` and syncs to `!db` atom - verify these keys flow through
- [ ] Verified by unit tests
- [ ] Verified by e2e tests
- [ ] Verified by PEZ

**Implementation notes:** Pure function `sponsor-active?` takes db map (and optional `now` for testability). 7 unit tests added covering active, expired, missing, and boundary cases. 90-day expiry (strict `<`).

### Chunk 3: App Header - Sponsor Heart Rendering

**File:** `src/view_elements.cljs`

- [x] Add `:elements/sponsor-status` option (boolean) to `app-header`
- [x] Add `:elements/on-sponsor-click` option (click handler) to `app-header`
- [x] ~~Render heart icon to the right of the title text, inside `.app-header-title`~~ Render heart in the header status position (right side), replacing the regular status text
- [x] Remove `:elements/status` option - the heart replaces the old status area
- [x] Apply appropriate tooltip based on status ("Thank you for sponsoring!" / "Click to update sponsor status")
- [ ] Verified by unit tests
- [ ] Verified by e2e tests
- [ ] Verified by PEZ

**Implementation notes:** Heart button is a direct child of `.app-header`, positioned to the right via `justify-content: space-between`. The `:elements/status` option (used by panel for "Ready" text) has been removed - the heart is the status now.

```clojure
[:div {:class (str "app-header " header-class)}
 [:div.app-header-title
  (or icon [:img {:src "icons/icon-32.png" :alt ""}])
  [:span.app-header-title-text
   "Epupp"
   [:span.tagline "Live Tamper your Web"]]]
 [:button.sponsor-heart
  {:on-click on-sponsor-click
   :title (if sponsor-status
            "Thank you for sponsoring!"
            "Click to update sponsor status")}
  [icons/heart {:size 14
                :filled? sponsor-status
                :class (str "sponsor-heart-icon"
                            (when sponsor-status " sponsor-heart-filled"))}]]]
```

### Chunk 4: CSS for Sponsor Heart

**Files:** `extension/base.css` or `extension/components.css`

- [x] Add `.sponsor-heart` button styles (background, border, cursor, alignment)
- [x] Add `.sponsor-heart-icon` default color and transition
- [x] Add `.sponsor-heart-filled` filled color (#e91e63)
- [x] Add `.sponsor-heart:hover` effect
- [ ] Verified by unit tests (not applicable - CSS only)
- [ ] Verified by e2e tests
- [ ] Verified by PEZ

**Implementation notes:** Added to `extension/components.css`. Used `var(--transition-duration)` instead of hardcoded `0.2s` to stay consistent with the design system.

```css
.sponsor-heart {
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px;
  display: flex;
  align-items: center;
  /* Positioned to right by header's justify-content: space-between */
}

.sponsor-heart-icon {
  color: var(--color-text-muted);
  transition: color 0.2s;
}

.sponsor-heart-filled {
  color: #e91e63; /* pink/red heart */
}

.sponsor-heart:hover .sponsor-heart-icon {
  color: #e91e63;
}
```

### Chunk 5: Popup - Wire Up Sponsor Heart

**File:** `src/popup.cljs`, `src/popup_actions.cljs`

- [x] Read `:sponsor/status` from storage state in popup
- [x] Add action `:popup/ax.check-sponsor` that triggers the sponsor tab effect
- [x] Add effect `:popup/fx.check-sponsor` that opens `https://github.com/sponsors/PEZ` via `chrome.tabs.create`
- [x] Pass sponsor status and click handler to `app-header`
- [ ] Verified by unit tests
- [ ] Verified by e2e tests
- [ ] Verified by PEZ

**Implementation notes:** Plan didn't detail how sponsor status loads into popup's own state. Added `:popup/ax.load-sponsor-status` / `:popup/fx.load-sponsor-status` action/effect pair following existing settings-loading pattern, plus a `chrome.storage.onChanged` listener for reactive updates.

Action handler:
```clojure
:popup/ax.check-sponsor
{:uf/fxs [[:popup/fx.check-sponsor]]}
```

Effect:
```clojure
:popup/fx.check-sponsor
(js/chrome.tabs.create #js {:url "https://github.com/sponsors/PEZ" :active true})
```

### Chunk 6: Panel - Wire Up Sponsor Heart

**File:** `src/panel.cljs`

Same pattern as popup:

- [x] Read `:sponsor/status` from storage state in panel
- [x] Add action/effect for opening sponsors tab
- [x] Pass sponsor status and click handler to `app-header`
- [ ] Verified by unit tests
- [ ] Verified by e2e tests
- [ ] Verified by PEZ

**Implementation notes:** Same pattern as popup, adapted to `editor/*` namespace convention. Added `:editor/ax.check-sponsor`, `:editor/ax.load-sponsor-status` actions and corresponding effects, plus `chrome.storage.onChanged` listener.

### Chunk 7: Content Bridge - Whitelist Sponsor Status Message

**File:** `src/content_bridge.cljs`

- [x] Add `"sponsor-status"` to the userscript message whitelist
- [x] Content bridge forwards it to background via `chrome.runtime.sendMessage`
- [ ] Verified by unit tests
- [ ] Verified by e2e tests
- [ ] Verified by PEZ

**Implementation notes:** Used `send-message-safe!` (fire-and-forget) since the userscript doesn't need a response relayed back.

### Chunk 8: Background - Handle Sponsor Status Message

**File:** `src/background.cljs`

- [x] Add `"sponsor-status"` case to `onMessage` dispatch table
- [x] Implement `handle-sponsor-status` handler that persists to storage
- [x] Handler only receives `true` (the userscript never sends `false`)
- [ ] Verified by unit tests
- [ ] Verified by e2e tests
- [ ] Verified by PEZ

**Implementation notes:** Handler updates `storage/!db` directly and calls `storage/persist!`, then responds with `{:success true}`.

Dispatch entry:
```clojure
"sponsor-status"
(handle-sponsor-status message dispatch! send-response)
```

Handler:
```clojure
(defn- handle-sponsor-status [_message _dispatch! send-response]
  (storage/persist! {:sponsor/status true
                     :sponsor/checked-at (js/Date.now)})
  (send-response #js {:success true}))
```

### Chunk 9: Builtin Userscript - Sponsor Detection

**Location:** `extension/userscripts/epupp/sponsor.cljs` (installed on extension load)

- [x] Create userscript file with manifest, detection logic, and SPA navigation handler
- [x] Implement `forever-sponsors` map with personalized messages
- [x] Implement `detect-and-act!` function with all 5 detection states
- [x] Implement `insert-banner!` helper for DOM banner insertion
- [x] Implement `send-sponsor-status!` helper (one-way `true` signal)
- [x] Add SPA navigation listener using `window.navigation` API pattern
- [ ] Verified by unit tests (not applicable - userscript runs in page context)
- [ ] Verified by e2e tests (all 116 pass with new builtin)
- [ ] Verified by PEZ

**Implementation notes:** Initial file creation had a garbled manifest (missing opening `{`, duplicate ns form) - fixed manually. File now matches the plan. E2E test script count assertions across 5 test files needed incrementing by 1 to account for the new builtin (was counting 1 builtin, now 2).

The userscript:

```clojure
{:epupp/script-name "epupp/sponsor.cljs"
 :epupp/auto-run-match "https://github.com/sponsors/PEZ*"
 :epupp/description "Detects GitHub sponsor status for Epupp"
 :epupp/run-at "document-idle"}

(ns epupp.sponsor)

;; Privacy: This script only sends a single boolean (sponsor/not-sponsor)
;; back to Epupp. No GitHub username or other personal data is collected.
;; The source code is visible in the Epupp popup script list for anyone
;; to verify.

(def forever-sponsors
  {"PEZ" "Thanks for Epupp and Calva! You have status in my heart as a forever sponsor."
   "borkdude" "Thanks for SCI, Squint, Babashka, Scittle, Joyride, and all the things! You have status in my heart as a forever sponsor of Epupp and Calva."
   "richhickey" "Thanks for Clojure! You have status in my heart as a forever sponsor of Epupp and Calva."
   "swannodette" "Thanks for stewarding ClojureScript for all these years! You have status in my heart as a forever sponsor of Epupp and Calva."
   "thheller" "Thanks for shadow-cljs! You have status in my heart as a forever sponsor of Epupp and Calva."})

(defn send-sponsor-status! []
  (js/window.postMessage
   #js {:source "epupp-userscript"
        :type "sponsor-status"
        :sponsor true}
   "*"))

(defn insert-banner! [text style]
  (let [container (js/document.querySelector ".container-lg.p-responsive")
        msg (js/document.createElement "div")]
    (set! (.-innerHTML msg)
          (str "<div style='" style "'>" text "</div>"))
    (when container
      (.insertBefore container msg (.-firstChild container)))))

(def sponsor-banner-style
  "padding: 12px 16px; margin: 8px 0; background: #e8f5e9; border-left: 3px solid #4caf50; border-radius: 4px;")

(def encourage-banner-style
  "padding: 12px 16px; margin: 8px 0; background: #e3f2fd; border-left: 3px solid #5881d8; border-radius: 4px;")

(defn detect-and-act! []
  (let [params (js/URLSearchParams. (.-search js/window.location))
        just-sponsored? (= "true" (.get params "success"))
        user-login (.-content (js/document.querySelector "meta[name='user-login']"))
        logged-in? (and (string? user-login) (not (empty? user-login)))
        forever-message (when logged-in? (get forever-sponsors user-login))
        h1 (js/document.querySelector "h1.f2")
        h1-text (when h1 (.trim (.-textContent h1)))
        body-text (.-textContent js/document.body)
        has-sponsoring-as? (re-find #"Sponsoring as" body-text)
        login-flash (->> (array-seq (js/document.querySelectorAll ".flash.flash-warn"))
                         (filter #(re-find #"must be logged in" (.-textContent %)))
                         first)]
    (cond
      ;; Forever sponsor - personalized thank-you, always send true
      forever-message
      (do
        (insert-banner! (str forever-message " <span style=\"color: #e91e63;\">&#9829;</span>")
                        sponsor-banner-style)
        (send-sponsor-status!))

      ;; Just completed a sponsorship (one-time or recurring confirmation)
      just-sponsored?
      (do
        (insert-banner! "Thanks for sponsoring me! <span style=\"color: #e91e63;\">&#9829;</span>"
                        sponsor-banner-style)
        (send-sponsor-status!))

      ;; Not logged in - add message near the login banner
      (not logged-in?)
      (when login-flash
        (let [msg (js/document.createElement "div")]
          (set! (.-textContent msg) "Log in to GitHub to update your Epupp sponsor status.")
          (set! (.. msg -style -cssText)
                "padding: 8px 16px; color: #856404; background: #fff3cd; text-align: center;")
          (.insertAdjacentElement login-flash "afterend" msg)))

      ;; Logged in, "Become a sponsor" heading present - not sponsoring
      (and h1-text (re-find #"Become a sponsor" h1-text))
      (insert-banner! (str "Sponsor PEZ to light up your Epupp sponsor heart! "
                           "<span style=\"color: #e91e63;\">&#9829;</span>")
                      encourage-banner-style)

      ;; Logged in, "Sponsoring as" present - confirmed recurring sponsor
      has-sponsoring-as?
      (do
        (insert-banner! "Thanks for sponsoring me! <span style=\"color: #e91e63;\">&#9829;</span>"
                        sponsor-banner-style)
        (send-sponsor-status!))

      ;; Unknown state - do nothing (graceful degradation)
      :else nil)))

;; Run detection
(detect-and-act!)

;; SPA navigation: re-detect if user navigates within sponsors page
(when js/window.navigation
  (let [!nav-timeout (atom nil)
        !last-url (atom js/window.location.href)]
    (.addEventListener js/window.navigation "navigate"
                       (fn [evt]
                         (let [new-url (.-url (.-destination evt))]
                           (when (not= new-url @!last-url)
                             (reset! !last-url new-url)
                             (when-let [tid @!nav-timeout]
                               (js/clearTimeout tid))
                             (reset! !nav-timeout
                                     (js/setTimeout detect-and-act! 300))))))))
```

**Design notes:**
- The `auto-run-match` uses a wildcard (`*`) to also match `?success=true` URLs.
- The detection function is extracted as `detect-and-act!` so it can be called both initially and on SPA navigation events.
- **One-way signal:** The script only ever sends `true` to Epupp. Non-sponsor and unknown states do nothing (no `false` message). This means a one-time sponsor gets 3 months of status from the timestamp, then it naturally expires.

### Chunk 10: Builtin Script Installation

Register the sponsor check script as a builtin userscript, following the pattern established by the web installer builtin.

- [x] Add entry to `builtin-scripts` catalog in `src/storage.cljs`
- [x] Place userscript file at `extension/userscripts/epupp/sponsor.cljs`
- [ ] Verify `sync-builtin-scripts!` picks it up on extension init (e2e)
- [ ] The `epupp/sponsors.cljs` script must not have an enable/disable toggle in the UI
- [ ] The `epupp/sponsors.cljs` script must be force-enabled on every `sync-builtin-scripts!` call
- [x] Verify `epupp/sponsors.cljs` is not deletable by the user (e2e)
- [x] Verify `epupp/sponsors.cljs` is visible in popup script list (transparency) (e2e)
- [ ] Verified by unit tests
- [ ] Verified by e2e tests (sponsor_builtin_test.cljs verifies: visible, no delete, no toggle checkbox, always enabled in storage)
- [ ] Verified by PEZ

**Builtin registration pattern (from `src/storage.cljs`):**

1. Add entry to the `builtin-scripts` catalog (around line 32-35):
   ```clojure
   (def builtin-scripts
     [{:id "web-installer"
       :path "userscripts/epupp/web_userscript_installer.cljs"
       :name "epupp/web_userscript_installer.cljs"}
      {:id "sponsor-check"
       :path "userscripts/epupp/sponsor.cljs"
       :name "epupp/sponsor.cljs"}])
   ```

2. Place the userscript file at `extension/userscripts/epupp/sponsor.cljs`

3. The existing `sync-builtin-scripts!` function (in `src/storage.cljs`, around lines 392-456) handles:
   - Loading bundled code via `chrome.runtime.getURL`
   - Building the script map with manifest data
   - Saving to storage
   - Re-syncing on storage changes if a builtin is missing or stale

4. Extension initialization triggers `storage/init!` which calls `sync-builtin-scripts!` at startup.

The script must be:
- **Always enabled** - no UI toggle for disabling; force-enabled on every sync
- **Not deletable** by the user
- Visible in the popup script list (so users can read the source and verify privacy claims)

### Chunk 11: Documentation

**Location:** `dev/docs/`

- [x] Create `dev/docs/architecture/sponsor-heart.md` describing the feature for future maintainers:
  - Feature overview and UX flow
  - Architecture: message flow, storage keys, expiry mechanism
  - Builtin userscript: detection logic, forever-sponsors map, one-way signal principle
  - DOM signals and robustness considerations
  - How to add/remove forever sponsors
- [x] Link the new doc from `dev/docs/architecture.md` (components/features list)
- [x] Link from `dev/docs/architecture/components.md` (if it lists features by file)
- [x] Link from `dev/docs/architecture/message-protocol.md` (new `sponsor-status` message type)
- [x] Link from `dev/docs/architecture/state-management.md` (new storage keys)
- [ ] Verified by unit tests (not applicable - documentation only)
- [ ] Verified by e2e tests (not applicable - documentation only)
- [ ] Verified by PEZ

### Chunk 12: Dev Tools UI for Sponsor Testing

**Files:** `src/storage.cljs`, `src/background.cljs`, `src/popup.cljs`, `src/popup_actions.cljs`, `src/panel.cljs`, `extension/popup.css`

Replaces the previous `!dev-mode?` / `:dev-match` mechanism (which never actually worked
because `save-script!` re-parses the manifest and overwrites the match from
`build-bundled-script`).

- [x] Remove `!dev-mode?` atom from `src/storage.cljs`
- [x] Remove `:dev-match` field from `bundled-builtins` catalog
- [x] Simplify `init!` to take no args (removed `:dev?` parameter)
- [x] Simplify `build-bundled-script` - remove dev-match logic
- [x] Update `src/background.cljs` - `storage/init!` called with no args
- [x] Add `apply-dev-sponsor-override!` to `storage.cljs` - reads dev username from
      storage and overrides sponsor script match after `sync-builtin-scripts!`
- [x] `sync-builtin-scripts!` calls `apply-dev-sponsor-override!` at the end
- [x] Clear `dev/sponsor-username` from storage in `onInstalled` handler
- [x] Add Dev Tools collapsible section in popup (dev/test builds only)
- [x] Add sponsor username text input (default: PEZ)
- [x] Add Reset Sponsor Status button
- [x] Move Dump Dev Log button into Dev Tools section
- [x] Popup heart click uses dev username from state
- [x] Panel heart click reads dev username from storage
- [x] Add popup actions: `set-dev-sponsor-username`, `reset-sponsor-status`, `load-dev-sponsor-username`
- [x] Add popup effects for all three actions
- [x] Add CSS for dev-tools-content, input, and buttons layout
- [x] Verified by unit tests (305 passing)
- [x] Verified by e2e tests (121 passing)
- [ ] Fix: stop trying to override script match in storage (it gets overwritten
      by manifest-is-source-of-truth on every re-parse). Instead, use display-only
      approach: popup/panel show the dev username in the UI but never mutate the
      stored match. See "Revised design" below.
- [ ] Remove `apply-dev-sponsor-override!` from storage.cljs (dead code now)
- [ ] Remove `sync-builtin-scripts!` call to `apply-dev-sponsor-override!`
- [ ] Remove `handle-apply-dev-sponsor-override` from background.cljs
- [ ] Remove `"apply-dev-sponsor-override"` message case from background.cljs
- [ ] Simplify popup effect `set-dev-sponsor-username` to only persist to
      chrome.storage.local (no message to background)
- [ ] E2E tests for Dev Tools UI (new test file)
- [ ] Check plan doc checkbox items
- [ ] Verified by PEZ

**Design:**

A "Dev Tools" collapsible section in the popup (gated on `config.dev`), containing:
1. Sponsor Username text input (persisted to `chrome.storage.local` as `dev/sponsor-username`)
2. Reset Sponsor Status button (clears `sponsorStatus` and `sponsorCheckedAt`)
3. Dump Dev Log button (moved from standalone rendering)

**Revised design - display-only username override:**

The sponsor script's `:script/match` in storage always reflects the manifest
(`"https://github.com/sponsors/PEZ*"`). Attempting to override it in storage fails
because `parse-scripts` (via `derive-script-fields`) re-extracts match from the
manifest code on every storage change event, wiping the override.

Instead of fighting the manifest-is-source-of-truth design, the dev username is
used at the **consumption points only**:

1. **Heart button URL** - popup reads `:dev/sponsor-username` from `!state`, panel
   reads `dev/sponsor-username` from `chrome.storage.local`. Both construct the
   sponsor URL dynamically. Already implemented.
2. **Popup script list display** - the auto-run-match shown in the popup for the
   sponsor script stays as `PEZ*` (matching what the manifest says). This is correct
   - it shows the actual match pattern. The dev username only controls where the
   heart button navigates.
3. **Sponsor script execution** - when the user clicks the heart (which now points
   to the correct dev username's page), the sponsor script runs on that page and
   correctly detects sponsor status. The script's auto-run-match doesn't need to
   change because manual execution works on any sponsor page.

**Username flow (simplified):**
1. User changes username in Dev Tools input
2. Popup action updates `!state` with new username
3. Popup effect persists `dev/sponsor-username` to chrome.storage.local
4. Heart click opens `https://github.com/sponsors/{dev-username}`
5. No background messaging, no storage match override, no re-parse conflicts

**Reset on install/update (not every wake):**
- `onInstalled` fires on extension install, update, and Chrome update
- Reloading via chrome://extensions counts as "update"
- Service worker hibernation/wake does NOT clear the dev username
- This means the username persists across popup reopens and SW hibernation,
  but resets when a new build is loaded

**Lessons learned:**
1. The manifest is the source of truth for script match patterns. Any attempt to
   override match in storage gets wiped by `derive-script-fields` on the next
   `parse-scripts` call (which happens on every `onChanged` event).
2. The previous `apply-dev-sponsor-override!` approach (overriding after
   `sync-builtin-scripts!`) appeared to work momentarily but was immediately
   undone by the `onChanged` listener in `init!` which re-parses scripts.
3. When a value is derived from source code, override at the consumption point,
   not the storage layer.

**E2E test plan:**

New test file `e2e/dev_tools_test.cljs` covering:
- Dev Tools section visible only in dev builds
- Sponsor username input persists value to chrome.storage.local
- Heart button URL reflects dev username (not hardcoded PEZ)
- Reset Sponsor Status clears sponsorStatus and sponsorCheckedAt from storage
- Dev username survives popup close/reopen (persisted)

### Chunk 13: Userscript Idempotency & Sponsor Page Guard

**File:** `extension/userscripts/epupp/sponsor.cljs`

**Investigation (REPL on live github.com/sponsors/PEZ, logged-out state):**

Three confirmed idempotency issues, ranked by severity:

#### Issue A: Banner duplication (HIGH)

`detect-and-act!` inserts a new DOM banner every time it runs, without checking
whether one already exists. Observed: 5 identical "Log in to GitHub to update your
Epupp sponsor status." messages stacked on the page.

Two separate insertion paths are affected:
1. `insert-banner!` - used by forever-sponsor, just-sponsored, not-sponsoring, and
   sponsoring branches. Inserts via `insertBefore` on `.container-lg.p-responsive`.
2. Not-logged-in branch - uses `insertAdjacentElement` on `login-flash`, completely
   independent of `insert-banner!`. Any fix that only guards `insert-banner!` will
   miss this path.

#### Issue B: SPA navigation listener stacking (HIGH)

The `window.navigation` listener registration at the bottom of the file has NO guard
against re-registration. The web installer solves this with `defonce !state` +
`(when-not (:nav-registered? state))` - the sponsor script has no equivalent.

Each full page load (via `webNavigation.onCompleted`) re-injects the script via
`process-navigation!` -> `bg-inject/execute-scripts!`, adding another listener.
Result: N page loads = N listeners = N calls to `detect-and-act!` per SPA navigation =
exponential banner accumulation.

`window.navigation` API confirmed present on the GitHub sponsors page.

#### Issue C: Redundant storage writes (LOW)

`send-sponsor-status!` triggers `postMessage` -> content bridge -> background ->
`storage/persist!` with no deduplication. Each call writes `{:sponsor/status true,
:sponsor/checked-at <now>}` and triggers `storage.onChanged` events, which fire UI
re-renders in popup/panel. Functionally harmless (writing `true` over `true`), but
wasteful.

#### Issue D: No sponsor page guard (MEDIUM)

The script can be run manually (via the play button in the popup) on ANY GitHub
sponsors page, not just the configured username's page. When run on another user's
sponsor page (e.g., `github.com/sponsors/someoneelse`) where the logged-in user
happens to be sponsoring that person, `detect-and-act!` detects the "Sponsoring as"
signal and sends `sponsor-status: true` back to Epupp - even though this says
nothing about sponsoring the Epupp author.

The script should verify that the current page URL matches the expected sponsor
username before executing. The expected username is encoded in the script's
`:epupp/auto-run-match` pattern (e.g., `https://github.com/sponsors/PEZ*`).
The script should extract the username from the URL path and compare it against
the match pattern's username. If they don't match, the script should do nothing.

Note: For auto-run execution this is redundant (the match pattern already filters),
but for manual execution it's a necessary guard.

#### Not issues

- **DOM mutation interference:** Our banner texts don't contain "Sponsoring as" or
  "Become a sponsor", so detection logic won't be confused by our own insertions.
- **Timing/race conditions:** `detect-and-act!` takes ~0.2ms. The 300ms debounce
  in the SPA nav listener is adequate.

**Fix plan:**

- [ ] Add `data-epupp-sponsor-banner` attribute to ALL inserted elements (both
      `insert-banner!` and the not-logged-in branch)
- [ ] At the start of `detect-and-act!`, remove any existing elements with that marker
      before proceeding
- [ ] Guard SPA navigation listener with `defonce` pattern (use `defonce !nav-registered`
      atom, check before adding listener) - following the web installer's pattern
- [ ] Add sponsor page guard: extract expected username from the script's match
      pattern (or hardcode "PEZ" and let Dev Tools override handle it), compare
      against URL path (`/sponsors/{username}`). Skip `detect-and-act!` if mismatch.
- [ ] Verified by e2e tests
- [ ] Verified by PEZ

**Design notes:**
- A single `data-epupp-sponsor-banner` attribute on all inserted elements is sufficient.
  `detect-and-act!` starts by calling `(.querySelectorAll js/document "[data-epupp-sponsor-banner]")`
  and removing any matches before proceeding.
- The `defonce` guards the SPA listener from stacking across re-injections. Scittle
  supports `defonce`, so this works without additional machinery.
- `send-sponsor-status!` deduplication is deferred - functionally harmless and the
  added complexity isn't warranted at this stage.

### Chunk 14: Branded Epupp Banner on Sponsors Page

**File:** `extension/userscripts/epupp/sponsor.cljs`

Replace the current ad-hoc banner insertion with a single branded Epupp banner rendered
at the top of the page, immediately after GitHub's main navigation bar.

**Layout:**

```
+----------------------------------------------------------------------+
| [Epupp logo SVG] Epupp - Live Tamper your Web    [sponsor message]   |
+----------------------------------------------------------------------+
```

- Left side: Epupp logo (inline SVG from `icons.cljc`), name "Epupp", tagline
  "Live Tamper your Web"
- Right side: the context-dependent sponsor message (thank-you, encouragement,
  log-in prompt, or forever-sponsor message)
- Single banner element replaces all current insertion points (`insert-banner!` and
  the not-logged-in `insertAdjacentElement` path)

**Placement:**

Insert as first child of `<main>`, before any GitHub flash banners. Verified DOM
structure via REPL:

```
<header.HeaderMktg> (GitHub top nav bar)
<div.stale-session-flash> (hidden, between header and main)
<main>
  [EPUPP BANNER HERE]
  <div.flash.flash-warn.flash-full> ("must be logged in" - logged out only)
  <div.container-lg.p-responsive> (main content)
```

**Styling:**

Use GitHub's `flash flash-warn flash-full` classes for the outer container to match
the visual weight and background of the existing login message banner (white background,
yellow border `rgba(212, 167, 44, 0.4)`, `padding: 20px 16px`). Add flexbox layout
inside for left/right alignment:

```css
/* Inherits from GitHub's .flash.flash-warn.flash-full:
   background: white, border: 1px solid rgba(212,167,44,0.4), padding: 20px 16px */

.epupp-sponsor-banner-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.epupp-sponsor-brand {
  display: flex;
  align-items: center;
  gap: 8px;
}

.epupp-sponsor-brand-name {
  font-weight: 600;
  color: #24292f;
}

.epupp-sponsor-brand-tagline {
  font-size: 12px;
  color: #656d76;
  margin-left: 4px;
}

.epupp-sponsor-message {
  text-align: right;
}
```

**Icon approach:**

Fetch the Epupp icon URL from the extension via the content bridge, following the same
pattern as the web installer script. The content bridge already handles `"get-icon-url"`
messages (resolves `chrome.runtime.getURL "icons/icon.svg"` directly, no background
round-trip). The sponsor script needs a lightweight `send-and-receive` helper for this
one async call.

Pattern from the web installer (`send-and-receive` in
`extension/userscripts/epupp/web_userscript_installer.cljs`, line 271):
- Posts `{:source "epupp-page" :type "get-icon-url" :requestId id}` to `window`
- Listens for `{:source "epupp-bridge" :type "get-icon-url-response" :requestId id}`
- Content bridge handles it at `src/content_bridge.cljs` line 240

The sponsor script can use a simplified version (single-use, no general-purpose
infrastructure needed). The icon URL is fetched once at script init and stored in
a `defonce` atom so it survives re-injections.

```clojure
(defonce !icon-url (atom nil))

(defn fetch-icon-url!+ []
  (js/Promise.
   (fn [resolve reject]
     (let [req-id (str "sponsor-" (js/Date.now))
           timeout-id (atom nil)
           handler (fn handler [e]
                     (when (= (.-source e) js/window)
                       (let [msg (.-data e)]
                         (when (and msg
                                    (= "epupp-bridge" (.-source msg))
                                    (= "get-icon-url-response" (.-type msg))
                                    (= req-id (.-requestId msg)))
                           (when-let [tid @timeout-id]
                             (js/clearTimeout tid))
                           (.removeEventListener js/window "message" handler)
                           (resolve (.-url msg))))))]
       (.addEventListener js/window "message" handler)
       (reset! timeout-id
               (js/setTimeout
                (fn []
                  (.removeEventListener js/window "message" handler)
                  (resolve nil)) ;; graceful fallback: no icon
                2000))
       (.postMessage js/window
                     #js {:source "epupp-page"
                          :type "get-icon-url"
                          :requestId req-id}
                     "*")))))
```

The banner renders with `<img>` when the URL is available, or without the icon on
timeout (graceful degradation).

**Relationship to Chunk 13:**

This chunk SUPERSEDES the `insert-banner!` and not-logged-in insertion paths entirely.
Instead of fixing two separate insertion mechanisms with marker attributes, there is now
one unified banner element. The `data-epupp-sponsor-banner` marker from Chunk 13 applies
to this single banner. The SPA listener guard (`defonce`) from Chunk 13 is still needed.

Implementation order: Chunk 14 should be done TOGETHER with Chunk 13, since both modify
the same functions and the banner rendering replaces the code that Chunk 13 would patch.

- [ ] Add `fetch-icon-url!+` helper (simplified `send-and-receive` for icon URL)
- [ ] Store icon URL in `defonce !icon-url` atom (survives re-injections)
- [ ] Create `render-banner!` function that builds the complete branded banner DOM element
- [ ] Replace `insert-banner!` and not-logged-in branch with calls to `render-banner!`
- [ ] Add `data-epupp-sponsor-banner` attribute to the banner (from Chunk 13)
- [ ] Render `<img>` with fetched icon URL (graceful fallback: omit if unavailable)
- [ ] Apply `flash flash-warn flash-full` classes to outer element
- [ ] Insert as first child of `<main>`
- [ ] Pass message text from each detection branch to `render-banner!`
- [ ] Verified by e2e tests
- [ ] Verified by PEZ

**Design notes:**
- The forever-sponsor branch passes the personalized message as the right-side text
- The just-sponsored and sponsoring branches pass "Thanks for sponsoring me! [heart]"
- The not-sponsoring branch passes the encouragement message
- The not-logged-in branch passes "Log in to GitHub to update your Epupp sponsor status"
- The unknown branch still does nothing (no banner rendered)
- Since there is now only ONE insertion point and ONE banner element, the idempotency
  fix from Chunk 13 (remove existing banner before inserting) becomes trivially simple

## Implementation Order

1. **Chunk 1** - Heart icon (foundation)
2. **Chunk 2** - Storage key (foundation)
3. **Chunk 4** - CSS (can be parallel with 3)
4. **Chunk 3** - App header rendering (depends on 1, 4)
5. **Chunk 5** - Popup wiring (depends on 2, 3)
6. **Chunk 6** - Panel wiring (depends on 2, 3)
7. **Chunk 7** - Content bridge whitelist (foundation for messaging)
8. **Chunk 8** - Background handler (depends on 7)
9. **Chunk 10** - Builtin script registration (depends on understanding builtin pattern)
10. **Chunk 9** - Userscript logic (depends on 7, 8, 10)
11. **Chunk 11** - Documentation (after all implementation is complete)
12. **Chunk 12** - Dev-mode match override (after builtin registration works)
13. **Chunk 13 + 14** - Userscript idempotency + branded banner (together, same functions)

## Execution Workflow

0. Load todo list with: initial test run, all 11 chunks
1. Delegate to `epupp-testrunner` to verify green unit and e2e test slate
2. For each chunk (in implementation order):
   - a. Understand the work and delegate to `epupp-doer` with instructions:
     - Tests are green when starting (no need to verify)
     - Before handing off completed work, delegate to `epupp-testrunner` to verify leaving the slate as green as entered
     - Hand off work with a brief summary of what was done and any deviations from the plan
   - b. On handoff: check off relevant checkboxes in this plan document
   - c. Note any deviations or problems in the chunk's section of this document
   - d. Succinctly summarize the current state of the work to PEZ
   - e. Do not wait for PEZ to verify - continue with next chunk
3. Summarize the completed work to PEZ

## Testing Strategy

### Unit Tests

- Storage: `:sponsor/status` round-trips through persist/load
- Background handler: `handle-sponsor-status` persists correct value
- View elements: `app-header` renders heart with correct tooltip based on status

### E2E Tests

- Popup header shows unfilled heart by default
- Panel header shows unfilled heart by default
- Clicking heart opens a new tab (verify tab URL)
- When storage has `:sponsor/status true`, heart appears filled
- Heart tooltip changes based on status

### Manual Verification Required

- GitHub Sponsors page DOM detection signals (logged in/out, sponsor/non-sponsor)
- Userscript banner rendering on the sponsors page
- Visual appearance of filled/unfilled heart in light and dark themes

#### Dev-mode sponsor page checking

In dev builds (`config/dev.edn` with `:dev true`), the sponsor userscript automatically
matches ALL GitHub sponsor pages (`https://github.com/sponsors/*`) instead of just PEZ's.
This is implemented via the `:dev-match` override in Chunk 12.

To verify regular (non-forever) sponsorship detection manually:

- **Recurring sponsor:** PEZ sponsors `jeaye` - visit `https://github.com/sponsors/jeaye`
  to verify the "Sponsoring as" detection and thank-you banner
- **One-time sponsor:** Visit `https://github.com/sponsors/borkdude?success=true` to
  verify the just-sponsored detection

No temporary file edits needed - the dev build handles it automatically.

## Verification Checklist

- [ ] Heart icon renders in popup header (unfilled)
- [ ] Heart icon renders in panel header (unfilled)
- [ ] Clicking heart opens `github.com/sponsors/PEZ` in new tab
- [ ] Userscript runs on sponsors page
- [ ] Not-logged-in state: informational message appears
- [ ] Logged-in non-sponsor: encouragement banner appears
- [ ] Logged-in sponsor: thank-you banner appears + message sent to extension
- [ ] Just-sponsored (`?success=true`): thank-you banner appears + message sent
- [ ] Hardcoded forever sponsor: personalized banner appears + message sent
- [ ] Heart becomes filled after sponsor detection
- [ ] Heart stays filled across popup/panel reopens (persisted)
- [ ] Tooltips are correct for both states
- [ ] Light and dark theme appearance
- [ ] Unit tests pass (`bb test`) - 417 passing
- [ ] E2E tests pass (`bb test:e2e`) - 116 passing, 13 shards

## Resolved Questions

1. **DOM selectors** - Investigated via the `epupp-github` REPL connected to the live GitHub Sponsors page. See [DOM Detection Strategy](#dom-detection-strategy) section below for findings.

2. **Builtin script pattern** - Use `"https://github.com/sponsors/PEZ*"` as the match pattern (wildcard covers `?success=true` URLs). Follow existing builtin registration in `src/storage.cljs`.

3. **Reset mechanism** - Store a `:sponsor/checked-at` timestamp alongside `:sponsor/status`. Sponsor status expires after 3 months, reverting the heart to unfilled. Re-visiting the sponsors page refreshes both the status and timestamp.

4. **Privacy** - The script sends only a boolean (sponsor/not-sponsor). No username or personal data collected. A privacy comment is included at the top of the script source. The script is visible in the popup script list for transparency.

5. **Always enabled** - The sponsor check script is always enabled. The UI does not show an enable/disable toggle for the sponsor script. `toggle-script!` rejects attempts to toggle the sponsor script. `sync-builtin-scripts!` force-enables the sponsor script on every sync (startup and storage changes). Note: this behavior is specific to the sponsor script, not all builtins - the web installer builtin retains its normal toggle.

## DOM Detection Strategy

Investigated via `epupp-github` REPL connected to the live GitHub Sponsors page (`https://github.com/sponsors/PEZ/`).

### Primary Signal: `meta[name='user-login']`

GitHub includes a meta tag on every page:

```html
<meta name="user-login" content="">      <!-- logged out -->
<meta name="user-login" content="username"> <!-- logged in -->
```

This is the most reliable login detection signal - it's a GitHub-wide convention, not specific to the sponsors page DOM structure.

### State Detection Logic

```
0. Check meta[name='user-login'] against forever-sponsors map
   - Match -> FOREVER SPONSOR (send true + personalized banner)
   - No match -> proceed to step 1

1. Check URL for ?success=true query parameter
   - Present -> JUST SPONSORED (send true + thank-you banner)
   - Absent -> proceed to step 2

2. Check meta[name='user-login'].content
   - Empty string -> NOT LOGGED IN
   - Non-empty string -> LOGGED IN, proceed to step 3

3. Check H1.f2 heading text
   - Contains "Become a sponsor" -> NOT SPONSORING (encouragement banner)
   - H1.f2 absent -> proceed to step 4

4. Check for "Sponsoring as" text on page
   - Present -> SPONSORING (send true + thank-you banner)
   - Absent -> UNKNOWN (graceful degradation: do nothing)
```

Note: The forever-sponsors check comes first because it's a simple username lookup and should override all other states. The `?success=true` check comes next because GitHub redirects there after completing a sponsorship (including one-time payments), where neither "Sponsoring as" nor "Become a sponsor" is present.

### Verified DOM Elements (Logged-Out State)

| Element | Selector | Content |
|---------|----------|---------|
| Login banner | `div.flash.flash-warn.flash-full` (child of `main`) | "You must be logged in to sponsor PEZ" |
| Main heading | `h1.f2` | "Become a sponsor to Peter Stromberg" |
| Tier select buttons | `a` with text "Select" | 8 links, each redirecting to `/login?return_to=...` |
| Sponsorship form | `form[action$='/sponsorships']` | Custom amount input + tier selection |
| Content container | `div.container-lg.p-responsive` | Main content area below the flash banner |

### Detection Strategy for Userscript

```clojure
(let [params (js/URLSearchParams. (.-search js/window.location))
      just-sponsored? (= "true" (.get params "success"))
      user-login (.-content (js/document.querySelector "meta[name='user-login']"))
      logged-in? (and (string? user-login) (not (empty? user-login)))
      forever-message (when logged-in? (get forever-sponsors user-login))
      h1 (js/document.querySelector "h1.f2")
      h1-text (when h1 (.trim (.-textContent h1)))
      body-text (.-textContent js/document.body)
      has-sponsoring-as? (re-find #"Sponsoring as" body-text)]
  (cond
    ;; Forever sponsor - personalized thank-you, always send true
    forever-message
    :forever-sponsor

    ;; URL has ?success=true -> just completed a sponsorship (one-time or recurring)
    just-sponsored?
    :just-sponsored

    ;; Not logged in
    (not logged-in?)
    :not-logged-in

    ;; Logged in, page shows "Become a sponsor" -> not sponsoring
    (and h1-text (re-find #"Become a sponsor" h1-text))
    :not-sponsoring

    ;; Logged in, "Sponsoring as" present -> confirmed recurring sponsor
    has-sponsoring-as?
    :sponsoring

    ;; Logged in, but can't determine status -> unknown
    :else
    :unknown))
```

### Verified: Sponsor State (Logged In, Sponsoring)

Verified on `github.com/sponsors/jeaye` while logged in as PEZ (an active sponsor):

| Signal | Value | Notes |
|--------|-------|-------|
| `meta[name='user-login']` | `"PEZ"` | Non-empty = logged in |
| `h1.f2` heading | **absent** | No H1 with class `f2` on sponsor page |
| "Become a sponsor" | **absent** | Not present in page text |
| "Sponsoring as" | **present** | H4 with text "Sponsoring as" + account switcher |
| "You're sponsoring" | absent | Not used by GitHub |
| "Manage sponsorship" | absent | Not used by GitHub |
| Login flash banner | hidden stale-session flash only | No "must be logged in" banner |

**Key difference between sponsor and non-sponsor pages:**
- Non-sponsor: Has `h1.f2` with "Become a sponsor to [Name]" + tier "Select" links
- Sponsor: No `h1.f2`, has "Sponsoring as" H4 + one-time payment options

### Verified: One-Time Sponsorship Confirmation

Verified on `github.com/sponsors/borkdude?success=true` after completing a one-time sponsorship:

| Signal | Value | Notes |
|--------|-------|-------|
| URL | `?success=true` query parameter | Very clean, reliable signal |
| `meta[name='user-login']` | `"PEZ"` | Logged in |
| `h1.f2` heading | **absent** | No H1 on confirmation page |
| "Sponsoring as" | **absent** | Not present on one-time confirmation |
| "Become a sponsor" | **absent** | Not present |
| Page content | "Share your sponsorships" confirmation dialog | Confirmation UI |

**Key insight:** After a one-time sponsorship, GitHub redirects to `?success=true`. This page has neither the "Become a sponsor" heading nor "Sponsoring as", so without the URL check it would fall through to `:unknown`. The `?success=true` parameter is the cleanest detection signal for just-completed sponsorships.

### SPA Navigation: `window.navigation` API

GitHub uses SPA-style navigation on some pages. The web installer userscript (`epupp/web_userscript_installer.cljs`) already handles this with the `window.navigation` API:

```clojure
;; Pattern from the web installer (lines 985-999)
(when js/window.navigation
  (let [!nav-timeout (atom nil)
        !last-url (atom js/window.location.href)]
    (.addEventListener js/window.navigation "navigate"
                       (fn [evt]
                         (let [new-url (.-url (.-destination evt))]
                           (when (not= new-url @!last-url)
                             (reset! !last-url new-url)
                             (when-let [tid @!nav-timeout]
                               (js/clearTimeout tid))
                             (reset! !nav-timeout
                                     (js/setTimeout re-check-fn 300))))))))
```

The sponsor check script should use this same pattern to re-trigger detection if the user navigates within the sponsors page (e.g., from `?success=true` back to the main page). Guard with `(when js/window.navigation ...)` since the API is not available in all browsers.

### Robustness Considerations

1. **`meta[name='user-login']` is the most stable signal** - it's used across all of GitHub for login detection, not specific to the sponsors page layout.

2. **Text-based detection (`re-find`) is more resilient** than CSS class selectors, since GitHub frequently changes class names but rarely changes user-facing text.

3. **"Become a sponsor" heading is a strong negative signal** - its presence reliably means "not sponsoring".

4. **"Sponsoring as" is a verified positive signal** - confirmed present on sponsor pages, absent on non-sponsor pages.

5. **Graceful degradation** - If none of the signals match (`:unknown` state), no message is sent and the heart stays unfilled. No harm done.

6. **URL-based detection for one-time sponsors** - The `?success=true` query parameter is set by GitHub's redirect after completing a sponsorship. This is more reliable than DOM text for just-completed one-time sponsorships, where neither "Sponsoring as" nor "Become a sponsor" is present.

---

## Original Plan-producing Prompt

Create an implementation plan for a sponsor-status heart feature in the Epupp browser extension:

1. Add a clickable heart icon to popup and panel headers (filled = sponsor, unfilled = default). Tooltip says "Thank you for sponsoring!" for sponsors, "Click to update sponsor status" for non-sponsors.

2. Clicking the heart opens a new tab to `https://github.com/sponsors/PEZ`.

3. A builtin userscript auto-runs on that page and checks:
   - Not logged in (page shows "You must be logged in" banner): insert a message near the banner saying "Log in to update your Epupp sponsor status"
   - Logged in but not sponsoring: insert a banner encouraging sponsorship
   - Logged in and sponsoring: send a `sponsor-status` message through the content bridge to the background, which persists it to storage

4. The popup and panel headers react to the stored sponsor status and render the heart filled.

5. The plan should follow the project's message protocol (content bridge whitelist, background onMessage dispatch), storage patterns (chrome.storage.local with in-memory mirror), and Uniflow state management. Structure inspired by recent archived plans in `dev/docs/archive/`.

6. Sponsor status expires after 3 months (stored timestamp). Re-visiting the sponsors page refreshes the check.

7. The builtin userscript is always enabled with no UI for disabling. It includes a privacy comment explaining only a boolean is sent.

8. The script source is visible in the popup script list for transparency.

9. DOM detection handles four states: not logged in (`meta[name='user-login']` empty), not sponsoring (`h1.f2` "Become a sponsor"), sponsoring ("Sponsoring as" text), and just-sponsored (`?success=true` URL parameter for one-time sponsorship confirmation). The script only ever sends `true` to Epupp - non-sponsor states do nothing, so a one-time sponsor gets 3 months of status before natural expiry.

10. Use the `window.navigation` API pattern (from the web installer builtin) for SPA navigation detection, so the script re-checks when the user navigates within the sponsors page.

11. Register as a builtin userscript following the existing pattern in `src/storage.cljs` (catalog entry + file at `extension/userscripts/epupp/`). Match pattern `"https://github.com/sponsors/PEZ*"` with wildcard to cover `?success=true` URLs.

12. A hardcoded map of "forever sponsors" maps GitHub usernames to personalized thank-you messages. These users always get `true` sent to Epupp and see their personalized banner. Initial map: borkdude, richhickey, swannodette, thheller.

13. Regular detected sponsors (via DOM signals or `?success=true`) also see a "Thanks for sponsoring me!" banner on the page.
