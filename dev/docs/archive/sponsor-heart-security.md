# Sponsor Heart: Security Fix

**Created:** February 8, 2026
**Completed:** February 8, 2026

## Feature Summary

The sponsor heart feature adds a clickable heart icon to the popup and panel headers. Clicking it opens `github.com/sponsors/PEZ`, where a builtin userscript detects sponsor status via DOM signals and sends the result back to the extension. The heart fills when sponsor status is confirmed, with a 3-month expiry for re-verification.

Feature description: [../architecture/sponsor-heart.md](../architecture/sponsor-heart.md)

## Security Vulnerability (fixed)

The `sponsor-status` message flow had no verification. Any code running in a page context could gain sponsor status by posting the right message.

### Attack surface

The message traveled:

```
window.postMessage({source: "epupp-userscript", type: "sponsor-status"})
  -> content bridge (isolated world, forwards blindly)
  -> chrome.runtime.sendMessage({type: "sponsor-status"})
  -> background: handle-sponsor-status (blindly trusts, persists)
```

**D1 - Message spoofing:** Any page-level code (console, userscript, malicious JS) could post `{source: "epupp-userscript", type: "sponsor-status"}` and the background would persist `{:sponsor/status true}`. The content bridge forwarded without verification.

**D2 - Wrong page detection:** The sponsor script could be run manually (play button in popup) on any GitHub sponsors page. If the user happened to sponsor that person, `detect-and-act!` detected "Sponsoring as" and sent `sponsor-status: true` - even though it said nothing about sponsoring PEZ.

**D3 - Manual REPL bypass:** Even with URL verification, `(send-sponsor-status!)` could be called directly from the REPL on the correct page, bypassing the detection logic entirely.

## Fix Applied

### Principle: verify at the trust boundary

The background service worker is the trust boundary. Two layers of verification:

1. **Pending check tracking** - The background sets a time-limited pending flag before executing the sponsor script. Only auto-run (navigation) and popup play button paths set this flag. Manual REPL eval does not.

2. **URL verification** - `sender.tab.url` (Chrome API, not spoofable) is verified against the expected sponsor page URL.

Both must pass for sponsor status to be persisted.

### Changes applied

#### 1. Background pending check + URL verification

- Added `set-sponsor-pending!` / `consume-sponsor-pending!` to background state (one-shot, 30s timeout)
- `process-navigation!` sets pending flag when sponsor script is among matched scripts
- `:script/fx.evaluate` effect sets pending flag when sponsor script is played via popup
- `handle-sponsor-status` requires valid pending check AND URL match
- Added `sponsor-script-id` constant to `background_utils.cljs`

#### 2. Script-side URL guard

- `detect-and-act!` checks `window.location.pathname` against expected username before detection
- Username fetched from extension via `get-sponsored-username` message (same source of truth as background)

#### 3. Async handler fix

- `handle-sponsor-status` was `^:async`, returning a Promise instead of synchronous `true`. Chrome's `onMessage` API requires synchronous `return true` to keep the message channel open. Fixed to use async IIFE pattern.

#### 4. State key mismatch fix

- `dev-tools-section` destructured `{:dev/keys [sponsor-username]}` but state used `:sponsor/sponsored-username`. Fixed to `{:sponsor/keys [sponsored-username]}`.

#### 5. Sponsor script DRY refactor

- Extracted `send-and-receive` helper in sponsor.cljs, reducing duplicated message plumbing in `fetch-icon-url!+` and `fetch-sponsored-username!+`.

### Message flow after fix

```
Page JS (attacker-controlled)
  -> window.postMessage({type: "sponsor-status"})
  -> content bridge (forwards blindly - this is fine)
  -> background: handle-sponsor-status
     1. Extract sender.tab.id (Chrome API, not spoofable)
     2. Consume pending check for tab-id (one-shot, 30s timeout)
     3. Read sender.tab.url
     4. Load expected username (dev override or "PEZ")
     5. Verify both pending check AND URL match
     6. Both pass: persist sponsor status, respond success
     7. Either fails: respond error, do NOT persist
```

## Checklist

- [x] Pass `sender` to `handle-sponsor-status` in dispatch
- [x] Add URL verification logic to `handle-sponsor-status`
- [x] Add expected-username lookup (`sponsor/sponsored-username` from storage, default `"PEZ"`)
- [x] Add pending check tracking (background-initiated-only verification)
- [x] Add script-side URL guard via `get-sponsored-username` message
- [x] Fix async handler (Promise vs synchronous `true`)
- [x] Fix state key mismatch in dev tools view
- [x] E2E test: sponsor status not granted from non-sponsor context
- [x] All 429 unit tests passing
- [x] All 128 E2E tests passing

## Remaining work

- [ ] PEZ manual verification of the full feature
- [ ] Code cleanup pass across sponsor-heart files (dead code, stale comments, orphaned requires)
- [ ] Final documentation review
