# Sponsor Heart

A clickable heart icon in popup and panel headers that reflects GitHub sponsor
status. Unfilled by default, filled when the user is detected as a sponsor.

## UX Flow

1. Heart icon appears in the header status area (right side), rendered as a button
2. Clicking it opens `https://github.com/sponsors/{username}` in a new tab
   (username defaults to `"PEZ"`, overridable via dev tools)
3. A builtin userscript runs on that page and inspects DOM for sponsor signals
4. The script renders a branded Epupp banner at the top of the page with a
   context-dependent message (thank-you, encouragement, login prompt, etc.)
5. If sponsoring: the script sends `sponsor-status` through the message protocol
6. Background persists to storage; UI re-renders with filled heart

Tooltips: "Click to update sponsor status" (unfilled) / "Thank you for
sponsoring!" (filled).

## Message Flow

```
Heart click (popup/panel)
  -> chrome.tabs.create sponsors URL (using sponsor/sponsored-username or "PEZ")
  -> builtin userscript auto-runs on page
  -> DOM inspection for login + sponsor state
  -> branded banner rendered at top of <main>
  -> if sponsor: window.postMessage (source: "epupp-userscript", type: "sponsor-status")
  -> content bridge whitelists and forwards via chrome.runtime.sendMessage
  -> background handler persists to chrome.storage.local
  -> storage.onChanged fires in popup/panel
  -> UI re-renders filled heart
```

## Storage

| Key | Type | Purpose |
|-----|------|---------|
| `:sponsor/status` | boolean | Whether the user is a detected sponsor |
| `:sponsor/checked-at` | number (ms timestamp) | When sponsor status was last confirmed |
| `sponsor/sponsored-username` | string | GitHub username to check sponsorship for (default `"PEZ"`, editable in dev tools) |

Sponsor status expires after 90 days. The pure function `storage/sponsor-active?`
derives effective status from both keys. When expired, the heart reverts to
unfilled and the user can re-check by clicking.

## One-Way Signal Principle

The userscript only ever sends `true` to Epupp - it never sends `false`. If the
user visits the sponsors page and is not sponsoring, the script does nothing.
This means a one-time sponsorship gives 90 days of sponsor status (from the
timestamp), after which it naturally expires.

## Builtin Userscript

Located at `extension/userscripts/epupp/sponsor.cljs`. Registered in the
`builtin-scripts` catalog in `src/storage.cljs` and auto-installed on extension
init via `sync-builtin-scripts!`.

The script is always enabled, not deletable by users, and visible in the popup
script list for transparency.

### Detection States

| State | DOM Signal | Action |
|-------|-----------|--------|
| Forever sponsor | `meta[name='user-login']` matches hardcoded map | Personalized banner + send `true` |
| Just sponsored | URL has `?success=true` | Thank-you banner + send `true` |
| Not logged in | `meta[name='user-login']` content is empty | Info banner |
| Not sponsoring | `h1.f2` contains "Become a sponsor" | Encouragement banner |
| Sponsoring (recurring) | "Sponsoring as" text present | Thank-you banner + send `true` |
| Unknown | None of the above | Do nothing (graceful degradation) |

### Branded Banner

All detection states (except unknown) render a branded Epupp banner at the top
of `<main>`, using GitHub's `flash flash-warn flash-full` classes. The banner
has a left side (Epupp icon + name + tagline) and a right side (context-dependent
message).

Idempotency is handled via a `data-epupp-sponsor-banner` marker attribute.
`remove-existing-banner!` clears any previous banner before rendering a new one.

The Epupp icon URL is fetched from the extension via the content bridge
(`get-icon-url` message) and cached in a `defonce !icon-url` atom that survives
re-injections.

### Forever Sponsors

A hardcoded map of GitHub usernames to personalized thank-you messages. These
users are always recognized regardless of actual GitHub sponsor status.

To add or remove forever sponsors, edit the `forever-sponsors` map in
`extension/userscripts/epupp/sponsor.cljs`.

### SPA Navigation

Uses the `window.navigation` API (same pattern as the web installer builtin) to
re-run detection when the user navigates within the sponsors page. A `defonce
!nav-registered` atom prevents listener stacking across re-injections.

## Dev Tools Override

In dev/test builds, the popup shows a "Dev Tools" section with:

- **Sponsor Username** input: persists to `sponsor/sponsored-username` in
  `chrome.storage.local`. Changes trigger `update-sponsor-script-match!` in the
  background, which rewrites the sponsor script's `:epupp/auto-run-match` URL
  pattern in the code itself (source of truth). This is cleared on extension
  install/update, defaulting back to `"PEZ"`.

- **Reset Sponsor Status** button: clears `sponsorStatus` and `sponsorCheckedAt`
  from storage, reverting the heart to unfilled.

Both popup and panel read `sponsor/sponsored-username` to construct the sponsors
tab URL when the heart is clicked.

## Robustness

- `meta[name='user-login']` is the most stable login signal - used across all
  of GitHub, not specific to the sponsors page layout
- Text-based detection (`re-find`) is more resilient than CSS class selectors,
  since GitHub frequently changes class names but rarely changes user-facing text
- Graceful degradation: unknown states do nothing, no harm done
- Banner idempotency: `data-epupp-sponsor-banner` marker + removal before
  re-rendering prevents duplication
- SPA listener guard: `defonce !nav-registered` prevents listener stacking

## Security

**Status: NOT YET FIXED.** See `dev/docs/sponsor-heart-security.md` for the
full analysis and fix design.

The `sponsor-status` message can currently be spoofed by any code in the page
context. The planned fix adds background-side URL verification using
`sender.tab.url` (Chrome API, not spoofable) and a script-side URL guard.

## Key Files

| File | Role |
|------|------|
| `src/icons.cljc` | Heart icon with `:filled?` option |
| `src/storage.cljs` | Storage keys, `sponsor-active?`, builtin catalog |
| `src/view_elements.cljs` | `app-header` with sponsor heart button |
| `src/popup.cljs` | Popup wiring, sponsor status loading, dev tools UI |
| `src/popup_actions.cljs` | Popup actions for sponsor check, dev username |
| `src/panel.cljs` | Panel wiring (same pattern as popup) |
| `src/panel_actions.cljs` | Panel actions for sponsor check |
| `src/content_bridge.cljs` | Whitelists `sponsor-status` message |
| `src/background.cljs` | `handle-sponsor-status` handler, `update-sponsor-script-match!` |
| `extension/userscripts/epupp/sponsor.cljs` | Builtin userscript with detection logic and branded banner |
| `extension/components.css` | `.sponsor-heart` button styles |
| `dev/docs/sponsor-heart-security.md` | Security vulnerability analysis and fix plan |
