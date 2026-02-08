# Sponsor Heart: Security Fix & Remaining Work

**Created:** February 8, 2026

## Feature Summary

The sponsor heart feature adds a clickable heart icon to the popup and panel headers. Clicking it opens `github.com/sponsors/PEZ`, where a builtin userscript detects sponsor status via DOM signals and sends the result back to the extension. The heart fills when sponsor status is confirmed, with a 3-month expiry for re-verification.

Feature description: [architecture/sponsor-heart.md](architecture/sponsor-heart.md)

**Current state:** 423 unit tests and 127 E2E tests pass. A dev build (v0.0.7.742) exists. PEZ has not yet manually verified. The code is pretty messy with abandoned development paths not reverted.

## Security Vulnerability

The `sponsor-status` message flow has no verification. Any code running in a page context can gain sponsor status by posting the right message.

### Attack surface

The message travels:

```
window.postMessage({source: "epupp-userscript", type: "sponsor-status"})
  -> content bridge (isolated world, forwards blindly)
  -> chrome.runtime.sendMessage({type: "sponsor-status"})
  -> background: handle-sponsor-status (blindly trusts, persists)
```

**D1 - Message spoofing:** Any page-level code (console, userscript, malicious JS) can post `{source: "epupp-userscript", type: "sponsor-status"}` and the background will persist `{:sponsor/status true}`. The content bridge forwards without verification.

**D2 - Wrong page detection:** The sponsor script can be run manually (play button in popup) on any GitHub sponsors page. If the user happens to sponsor that person, `detect-and-act!` detects "Sponsoring as" and sends `sponsor-status: true` - even though it says nothing about sponsoring PEZ (or the current sponsored person, as set in the dev-only UI).

### Why this matters

Sponsor status is a soft signal (cosmetic heart icon, no gated features), but the mechanism should still be trustworthy. A spoofable signal trains bad habits and undermines the extension's integrity.

## Fix Design

### Principle: verify at the trust boundary

The background service worker is the trust boundary. Chrome's `onMessage` API provides `sender.tab` with the verified URL of the sending tab. This cannot be spoofed by page-level code.

### Changes

#### 1. Background URL verification (security)

**File:** `src/background.cljs`

`handle-sponsor-status` currently takes `[_message send-response]` (line 581). The dispatch at line 669 has access to `sender` but does not pass it:

```clojure
;; Current (line 669):
"sponsor-status" (handle-sponsor-status message send-response)
```

Fix:

- Pass `sender` to `handle-sponsor-status`
- Extract `sender.tab.url`
- Load expected username: read `sponsor/sponsored-username` from `chrome.storage.local`, default to `"PEZ"`
- Verify URL matches `https://github.com/sponsors/{username}*`
- If mismatch: respond `{:success false :error "URL mismatch"}`, do NOT persist
- If match: persist as before

```clojure
(defn- ^:async handle-sponsor-status [_message sender send-response]
  (let [tab-url (when (.-tab sender) (.. sender -tab -url))
        storage (js-await (js/chrome.storage.local.get "sponsor/sponsored-username"))
        username (or (.-sponsor/sponsored-username storage) "PEZ")
        expected-prefix (str "https://github.com/sponsors/" username)]
    (if (and tab-url (.startsWith tab-url expected-prefix))
      (do (swap! storage/!db assoc
                 :sponsor/status true
                 :sponsor/checked-at (js/Date.now))
          (js-await (storage/persist!))
          (send-response #js {:success true}))
      (send-response #js {:success false
                          :error "URL mismatch"})))
  true)
```

Dispatch becomes:

```clojure
"sponsor-status" (handle-sponsor-status message sender send-response)
```

**Permission check:** `sender.tab.url` requires `tabs` permission or host permissions. Verify `manifest.json` includes what's needed. The extension likely already has broad host permissions for content script injection.

#### 2. Script-side URL guard (DX and correctness)

**File:** `extension/userscripts/epupp/sponsor.cljs`

Before running `detect-and-act!`, check `window.location.pathname` starts with `/sponsors/{expected-username}`. This ensures the script only detects and reports sponsor status for the configured username - both in auto-run and manual-run scenarios.

The script requests the expected username from the extension via a message, following the same pattern as `get-icon-url`. A new `get-sponsored-username` message type:

- Script posts `{source: "epupp-userscript", type: "get-sponsored-username", requestId: ...}`
- Content bridge handles it by sending `{type: "get-sponsored-username"}` to the background
- Background reads `sponsor/sponsored-username` from storage (default `"PEZ"`) and responds
- Content bridge relays the response back to the page
- Script stores the result in a `defonce` atom (survives re-injections, same as `!icon-url`)

This shares the same source of truth as the background verification.

This guard is essential for the dev override to work end-to-end. Without it, changing `sponsor/sponsored-username` only affects auto-run matching but manual execution still detects sponsorship of the wrong person. The entire dev tools UI for sponsor testing depends on this guard working correctly.

### Message flow after fix

```
Page JS (attacker-controlled)
  -> window.postMessage({type: "sponsor-status"})
  -> content bridge (forwards blindly - this is fine)
  -> background: handle-sponsor-status
     1. Read sender.tab.url (Chrome API, not spoofable)
     2. Load expected username (dev override or "PEZ")
     3. Verify URL starts with https://github.com/sponsors/{username}
     4. Match: persist sponsor status, respond success
     5. No match: respond error, do NOT persist
```

### What does NOT need changing

- **Content bridge** - Its job is to forward messages. The security boundary is in the background. Defense-in-depth here adds complexity with no meaningful benefit.
- **`send-sponsor-status!` deduplication** - Functionally harmless, deferred.

## Checklist

- [ ] Pass `sender` to `handle-sponsor-status` in dispatch (`src/background.cljs` line 669)
- [ ] Add URL verification logic to `handle-sponsor-status` (`src/background.cljs` line 581)
- [ ] Add expected-username lookup (`sponsor/sponsored-username` from storage, default `"PEZ"`)
- [ ] Add script-side URL guard via `get-sponsored-username` message (`sponsor.cljs`, content bridge, background)
- [ ] Unit test: `handle-sponsor-status` rejects when tab URL is wrong
- [ ] Unit test: `handle-sponsor-status` accepts when tab URL matches
- [ ] E2E test: sponsor status not granted from non-matching page
- [ ] Verified by PEZ
- [ ] Code cleanup pass across all sponsor-heart files:
  - [ ] Review and remove dead code, abandoned implementation paths, and unused functions
  - [ ] Remove stale comments referencing old approaches (e.g., references to removed `insert-banner!`, old detection strategies)
  - [ ] Verify naming consistency (`sponsor/` namespace across storage keys, functions, and state)
  - [ ] Check for orphaned requires/imports
  - [ ] Ensure docstrings reflect current behavior, not implementation history
  - [ ] Files to review: `src/background.cljs`, `src/storage.cljs`, `src/popup.cljs`, `src/popup_actions.cljs`, `src/panel.cljs`, `src/content_bridge.cljs`, `src/components.cljs`, `extension/userscripts/epupp/sponsor.cljs`
  - [ ] Confirm all tests still pass after cleanup
  - [ ] Verified by PEZ

## Other remaining work

- [ ] PEZ manual verification of the full feature (heart icon, banners, sponsor detection, light/dark themes)
- [ ] Final documentation review after security fix
