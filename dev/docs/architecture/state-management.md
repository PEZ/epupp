## Epupp State Management

State is local to each runtime context because browser extension contexts are
isolated. The architecture coordinates through message passing.

## State Domains

- Background worker: connection lifecycle, toolbar icon state,
  auto-connect tracking for tabs, and per-tab FS sync state
  (`:fs/sync-tab-id` - ephemeral, not persisted).
- Content bridge: ephemeral relay state and keepalive behavior.
- Popup: UI state, connection status, settings, and script list derived from
  storage.
- Panel: editor state, evaluation results, and per-hostname persistence.
- Storage: source of truth for userscripts, user-managed origins, and
  sponsor status (`:sponsor/status`, `:sponsor/checked-at` with 90-day expiry).

## Data Ownership and Sync

- `chrome.storage.local` is the durable source of truth for userscripts and
  settings.
- FS sync state (`:fs/sync-tab-id`) is ephemeral in-memory state in the
  background worker. It is not persisted to storage and resets on service
  worker restart. Only one tab can have FS sync at a time.
- UI contexts mirror storage in memory and react to `storage.onChanged` for
  updates.
- Background is the only place that orchestrates injection, so storage changes
  are normalized there for policy decisions.

## Uniflow as the Local Decision Engine

Background, popup and panel use Uniflow to separate pure decisions from side effects. This
keeps UI and write validation deterministic while allowing side effects to be
minimal.

See [uniflow.md](uniflow.md) for the event system. The background uses the same
pattern for FS write mutations, WS lifecycle, icon state, and navigation
decisions via `background_actions.cljs` and its sub-modules.

## Design Rules

- Keep state flat and namespaced by domain for clarity.
- Treat storage as the durable source and rehydrate on open.
- Keep decision logic pure and push side effects to effect handlers.
- Only the event loop (`dispatch!`) may `deref` or `reset!` the state atom - see
  [The Single Access Point Rule](uniflow.md#the-single-access-point-rule).
- Effects receive data from actions via `:uf/fxs` parameters - they never read
  `@!state` directly (nor transitively through helpers).
- Guard/utility functions are pure - they receive data as parameters, not atom
  access.
- Message handlers and event listeners dispatch actions rather than reading
  state. The action extracts what it needs and declares effects with that data.
- No `swap!` or `reset!` on the state atom outside the event loop during
  runtime. The sole exception is pre-dispatch initialization, documented as such.
