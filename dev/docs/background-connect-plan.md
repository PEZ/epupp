# Background-managed Connect Flow (Popup as UI only)

## Purpose

Move the “connect and inject” orchestration from the popup to the background worker.

This makes the popup a pure UI surface and makes the background worker the single authoritative place for:

- Tab selection and tab targeting
- Injecting bridge + Scittle + scittle.nrepl
- Owning the per-tab WebSocket connections

This change is motivated by both architecture and testing:

- MV3 popups are ephemeral. The background worker is the stable owner of “real work”.
- Playwright tests currently open `popup.html` as a normal tab, which breaks `activeTab` expectations and causes the connect flow to target the wrong tab.

## Goals

- Keep the existing user-facing UI and behavior.
- Preserve the current model: one WebSocket connection per browser tab (keyed by tab id).
- Make the connect flow target an explicit tab id (no reliance on `tabs.query` from popup at connect-time).
- Unblock Playwright integration tests by allowing tests to request “connect tab X” without rendering the popup as a tab.

## Non-goals

- Introducing optional host permissions or additional permission prompts.
- Changing the userscript auto-injection system.
- Supporting “connect from popup.html opened as a tab” as a product feature.

## Current Behavior (brief)

The popup currently:

1. Finds a target tab using `chrome.tabs.query({active:true,currentWindow:true})`.
2. Injects bridge + Scittle into that tab using `chrome.scripting.executeScript`.
3. Configures `SCITTLE_NREPL_WEBSOCKET_PORT` in the page.
4. Injects `vendor/scittle.nrepl.js`.

The background worker currently:

- Owns the WebSocket connection(s) to `ws://localhost:PORT/_nrepl`.
- Stores them as `tab-id -> WebSocket`.
- Relays messages between the page (via content bridge) and the WebSocket.

## Why Playwright fails today

In Playwright, we navigate to `chrome-extension://<id>/popup.html` in a normal browser tab.

When the popup code calls `tabs.query({active:true,...})`, the active tab is the popup tab itself, not the intended `http(s)://...` page tab.

Then the popup tries to inject into the popup tab, which triggers Chrome errors of the form:

- “Cannot access contents of the page. Extension manifest must request permission to access the respective host.”

This is a tab-targeting problem. Fixing tab targeting fixes the error.

## Proposed Architecture

### Key idea

- The background worker owns the connect flow.
- The popup only requests “connect tab X using ws-port Y”.

### Message protocol

Add a new runtime message handled by the background worker:

- Type: `connect-tab`
- Payload: `{tabId, wsPort}`
- Response: `{success, error?}`

Optional convenience message:

- Type: `connect-active-tab`
- Payload: `{wsPort}`
- Background selects a suitable target tab and then runs the same connect logic.

For Playwright, the best contract is the explicit one: `connect-tab`.

### Background connect flow

Create a background function that performs the whole “ensure injected then connect” sequence:

1. Ensure content bridge is present in the target tab.
2. Ensure WS bridge is present in the target tab.
3. Ensure Scittle is present in the target tab.
4. Set `SCITTLE_NREPL_WEBSOCKET_HOST` and `SCITTLE_NREPL_WEBSOCKET_PORT` in the target tab.
5. Ensure scittle.nrepl is loaded and connected.

Important: steps 1-5 are for a specific `tab-id`.

### Preserving “one socket per tab”

Keep the background worker’s existing state:

- `:ws/connections {tab-id -> WebSocket}`

The connect flow should be idempotent and should replace the existing socket for that tab id.

## Implementation Plan

### Step 1: Add background connect handler

In `src/background.cljs`:

- Add a new handler for runtime messages:
  - `"connect-tab"` with payload `tabId` and `wsPort`
- Implement `connect-tab!` (async) that:
  - calls the injection steps for `tabId`
  - then ensures the background has opened the per-tab WebSocket connection to `wsPort`

Notes:

- The injection helpers already exist in background for userscripts and panel injection (`execute-in-page`, `inject-content-script`, `ensure-scittle!`). Reuse or extend them.
- If popup already has some page-context JS helper fns (like setting config, reconnecting), either duplicate them in background or move them to a shared namespace used by both.

### Step 2: Make popup call background instead of injecting

In `src/popup.cljs`:

- Replace the current `:popup/fx.connect` behavior.
- Instead of calling `connect-to-tab!` from the popup, send a runtime message to background:
  - Determine the target tab id (for production UX) and pass it explicitly.
  - Message: `{type: "connect-tab", tabId, wsPort}`
  - Use callback response to set `:ui/status`.

This keeps the popup as UI-only, while still using the same “current active tab” UX.

### Step 3: Make target tab selection explicit and robust

In production, you can keep “use the current active tab” UX, but ensure it refers to a non-extension page:

- Option A: popup picks tab id using the existing `get-active-tab` and sends it.
- Option B: background maintains “last active non-extension tab id” via `tabs.onActivated` and `windows.onFocusChanged` and uses that for `connect-active-tab`.

Option A is smaller and is enough for product behavior. Option B is nicer long-term.

### Step 4: Add a test-only hook for Playwright

To support integration tests without opening the popup as a tab, add a test-only message behind a build-time dev flag.

Example message:

- Type: `e2e/find-tab-id`
- Payload: `{urlSubstring}` or `{urlEquals}`
- Response: `{tabId}`

This lets Playwright:

1. Open the target page.
2. Ask the background worker for its Chrome `tabId`.
3. Ask the background worker to `connect-tab`.

This avoids reliance on popup tab selection entirely.

Implementation sketch:

- Guard the handler behind config `:dev` (already injected as `EXTENSION_CONFIG`).
- Use `chrome.tabs.query` to find matching tabs.

### Step 5: Update the Phase 3 integration test

Update the REPL integration harness to:

- Create a browser tab for the test page.
- Use `e2e/find-tab-id` to get `tabId`.
- Call `connect-tab` with that `tabId`.
- Proceed with nREPL eval assertions.

This removes “open popup and click Connect” from the integration test.

## Acceptance Criteria

- Manual:
  - Open any normal page tab.
  - Open the extension popup.
  - Click Connect.
  - Scittle is injected and the page connects to the relay.
  - Repeat in a second tab. Both tabs can connect to different ws ports (or the same) and maintain independent background WebSockets.

- Automated:
  - Existing Playwright popup smoke tests continue to pass.
  - The REPL integration test can connect a page tab without opening the popup as a tab.

## Rollout Notes

- Keep the message protocol stable: background is the owner of `connect-tab`.
- The popup should not need `scripting.executeScript` knowledge after this refactor.
- This refactor reduces the chance of future “popup lifecycle” and “tab targeting” bugs.
