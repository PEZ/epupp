# Sponsor Heart

A clickable heart icon in popup and panel headers that reflects GitHub sponsor
status. Unfilled by default, filled when the user is detected as a sponsor.

## UX Flow

1. Heart icon appears in the header, right of the title text
2. Clicking it opens `https://github.com/sponsors/PEZ` in a new tab
3. A builtin userscript runs on that page and inspects DOM for sponsor signals
4. If sponsoring: the script sends `sponsor-status` through the message protocol
5. Background persists to storage; UI re-renders with filled heart

Tooltips: "Click to update sponsor status" (unfilled) / "Thank you for
sponsoring!" (filled).

## Message Flow

```
Heart click (popup/panel)
  -> chrome.tabs.create sponsors URL
  -> builtin userscript auto-runs on page
  -> DOM inspection for login + sponsor state
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
| Not logged in | `meta[name='user-login']` content is empty | Info message near login banner |
| Not sponsoring | `h1.f2` contains "Become a sponsor" | Encouragement banner |
| Sponsoring (recurring) | "Sponsoring as" text present | Thank-you banner + send `true` |
| Unknown | None of the above | Do nothing (graceful degradation) |

### Forever Sponsors

A hardcoded map of GitHub usernames to personalized thank-you messages. These
users are always recognized regardless of actual GitHub sponsor status.

To add or remove forever sponsors, edit the `forever-sponsors` map in
`extension/userscripts/epupp/sponsor.cljs`.

### SPA Navigation

Uses the `window.navigation` API (same pattern as the web installer builtin) to
re-run detection when the user navigates within the sponsors page.

## Robustness

- `meta[name='user-login']` is the most stable login signal - used across all
  of GitHub, not specific to the sponsors page layout
- Text-based detection (`re-find`) is more resilient than CSS class selectors,
  since GitHub frequently changes class names but rarely changes user-facing text
- Graceful degradation: unknown states do nothing, no harm done

## Key Files

| File | Role |
|------|------|
| `src/icons.cljc` | Heart icon with `:filled?` option |
| `src/storage.cljs` | Storage keys, `sponsor-active?`, builtin catalog |
| `src/view_elements.cljs` | `app-header` with sponsor heart rendering |
| `src/popup.cljs` | Popup wiring, sponsor status loading, storage listener |
| `src/popup_actions.cljs` | Popup actions for sponsor check |
| `src/panel.cljs` | Panel wiring (same pattern as popup) |
| `src/panel_actions.cljs` | Panel actions for sponsor check |
| `src/content_bridge.cljs` | Whitelists `sponsor-status` message |
| `src/background.cljs` | `handle-sponsor-status` handler, persists to storage |
| `extension/userscripts/epupp/sponsor.cljs` | Builtin userscript with detection logic |
| `extension/components.css` | `.sponsor-heart` styles |
