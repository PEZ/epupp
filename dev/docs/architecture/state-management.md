## Epupp State Management

State is local to each runtime context because browser extension contexts are
isolated. The architecture coordinates through message passing.

## State Domains

- Background worker: connection lifecycle, approvals, toolbar icon state, and
  auto-connect tracking for tabs.
- Content bridge: ephemeral relay state and keepalive behavior.
- Popup: UI state, connection status, settings, and script list derived from
  storage.
- Panel: editor state, evaluation results, and per-hostname persistence.
- Storage: source of truth for userscripts and user-managed origins.

## Data Ownership and Sync

- `chrome.storage.local` is the durable source of truth for userscripts and
  settings.
- UI contexts mirror storage in memory and react to `storage.onChanged` for
  updates.
- Background is the only place that orchestrates injection and approvals, so
  storage changes are normalized there for policy decisions.

## Uniflow as the Local Decision Engine

Popup and panel use Uniflow to separate pure decisions from side effects. The
background uses the same pattern for increasingly more of its decisions. This
keeps UI and write validation deterministic while allowing side effects to be
minimal.

See [uniflow.md](uniflow.md) for the event system and
[background-uniflow-implementation.md](background-uniflow-implementation.md)
for the background write pipeline.

## Design Rules

- Keep state flat and namespaced by domain for clarity.
- Treat storage as the durable source and rehydrate on open.
- Keep decision logic pure and push side effects to effect handlers.
