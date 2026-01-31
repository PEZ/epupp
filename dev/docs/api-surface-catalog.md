# Epupp API Surface Catalog

**Created:** January 24, 2026
**Purpose:** Complete catalog of all API surfaces for MVP release planning

This document identifies every "API" in Epupp - both obvious and subtle - that external code, users, or future versions might depend on. Each API is documented with its stability characteristics and potential versioning needs.

---

## 1. Userscript API

### 1.1 REPL File System API (`epupp.fs`)

**Primary API for managing userscripts from connected REPL.**

**Location:** [extension/bundled/epupp/fs.cljs](../../extension/bundled/epupp/fs.cljs)
**Injected:** On REPL connection
**Consumers:** nREPL clients (Calva, CIDER, AI agents, manual REPL)

| Function | Signature | Purpose | Gated By Setting |
|----------|-----------|---------|------------------|
| `ls` | `([] [opts])` | List scripts with metadata | No (read) |
| `show` | `(name-or-names)` | Get script code by name(s) | No (read) |
| `save!` | `(code-or-codes [opts])` | Save script from code with manifest | Yes (write) |
| `mv!` | `(from-name to-name [opts])` | Rename script | Yes (write) |
| `rm!` | `(name-or-names)` | Delete script(s) | Yes (write) |

**Return shape (namespaced keywords):**
- `ls`: Vector of `{:fs/name :fs/auto-run-match :fs/enabled? :fs/modified ...}`
- `show`: String (single) or map (bulk)
- `save!`: `{:fs/success :fs/name :fs/error}`
- `mv!`: `{:fs/success :fs/from-name :fs/to-name :fs/error}`
- `rm!`: `{:fs/success :fs/name :fs/existed? :fs/error}`

**Options API:**
- `:fs/ls-hidden?` (bool) - include built-in scripts
- `:fs/force?` (bool) - overwrite behavior (Unix `-f` flag semantics)
- `:fs/enabled?` (bool) - script enabled state on save

**Stability:** HIGH - This is a core feature, breaking changes would impact workflows
**Versioning indicators:** None currently
**Volatile aspects:** Error message strings, internal bulk-operation tracking

---

### 1.2 REPL Session API (`epupp.repl`)

**Library loading for REPL sessions.**

**Location:** [extension/bundled/epupp/repl.cljs](../../extension/bundled/epupp/repl.cljs)
**Injected:** On REPL connection
**Consumers:** nREPL clients

| Function | Signature | Purpose |
|----------|-----------|---------|
| `manifest!` | `(m)` | Load Scittle libraries on demand |

**Manifest shape:**
- `:epupp/inject` - vector of `scittle://` URLs

**Example:**
```clojure
(epupp.repl/manifest! {:epupp/inject ["scittle://reagent.js"]})
```

**Stability:** MEDIUM - Core functionality but API shape might evolve
**Versioning indicators:** None
**Volatile aspects:** Manifest key names could change (e.g., `:epupp/require` vs `:epupp/inject`)

---

### 1.3 Scittle Library Catalog

**Available bundled libraries via `scittle://` URLs.**

**Location:** [src/scittle_libs.cljs](../../src/scittle_libs.cljs)
**Defined in:** `library-catalog` map
**Consumers:** Userscripts, REPL sessions via `:epupp/inject`

| URL | Provides | Dependencies |
|-----|----------|--------------|
| `scittle://pprint.js` | `cljs.pprint` | core |
| `scittle://promesa.js` | `promesa.core` | core |
| `scittle://replicant.js` | Replicant UI library | core |
| `scittle://js-interop.js` | `applied-science.js-interop` | core |
| `scittle://reagent.js` | Reagent + React | core, react |
| `scittle://re-frame.js` | Re-frame | core, reagent |
| `scittle://cljs-ajax.js` | `cljs-http.client` | core |

**Internal (not directly requestable):**
- `scittle://core` → `scittle.js`
- `scittle://react` → React + ReactDOM

**Stability:** HIGH - Library catalog is user-facing
**Versioning:** Currently none, but library versions could be exposed
**Volatile aspects:** Library versions, dependency resolution, new libraries added

---

### 1.4 Userscript Installation API

**Page-initiated userscript installation from allowed origins.**

**Location:** [src/background.cljs](../../src/background.cljs) - `install-userscript` handler
**Triggered by:** Page sending `save-script` message via content bridge
**Consumers:** Web Userscript Installer (extracts code from DOM)

**Message shape:**
```javascript
{
  type: "save-script",
  code: "...",  // Script code (from DOM element)
  source: "https://gist.github.com/..."  // Page URL for provenance
}
```

**Site patterns:**
- Installer auto-runs on sites matching `installerSitePatterns` (config) OR user-added patterns
- Uses glob patterns (e.g., `https://example.com/*`) or exact URLs
- Dev config: GitHub, GitLab, Codeberg, localhost
- Prod config: GitHub, GitLab, Codeberg only

**Stability:** MEDIUM - Installation flow is established but validation might tighten
**Versioning indicators:** None
**Volatile aspects:** Allowed origin list, manifest validation rules

---

## 2. Storage Schema

**chrome.storage.local persistence format.**

**Location:** [src/storage.cljs](../../src/storage.cljs)
**Authority:** Background worker (single writer pattern)
**Consumers:** Background, popup, panel (via `storage.onChanged` sync)

### 2.1 Scripts Array

**Storage key:** `"scripts"`

**Schema per script:**
```javascript
{
  id: "script-TIMESTAMP",    // Immutable identifier
  code: "(ns ...)",           // ClojureScript source
  enabled: true,              // Auto-run flag
  created: "2026-01-24T...",  // ISO timestamp
  modified: "2026-01-24T...", // ISO timestamp
  builtin: true               // Built-in flag (optional)
}
```

**Derived on load (from manifest in `code`):**
- `name` - `:epupp/script-name`
- `match` - `:epupp/auto-run-match`
- `description` - `:epupp/description`
- `runAt` - `:epupp/run-at`
- `inject` - `:epupp/inject`

**Field constraints:**
- `id` - Immutable after creation
- `code` - Source of truth for manifest-derived fields
- `enabled` - Auto-run flag, only meaningful when `match` is present
- `builtin` - Only set for built-in scripts

**Stability:** HIGH - Core data model
**Versioning indicators:** `schemaVersion` (current: 1)
**Volatile aspects:** Derived fields may expand, schema migrations may add keys

---

### 2.2 Settings Keys

**Auto-connect settings:**
- `"autoConnectRepl"` (bool) - Auto-connect REPL to all pages (default: false)
- `"autoReconnectRepl"` (bool) - Auto-reconnect to previously connected tabs (default: true)
- `"fsReplSyncEnabled"` (bool) - Allow REPL to write scripts (default: false)

**Schema versioning:**
- `"schemaVersion"` (number) - Storage schema version (current: 1)

**Script origin management:**
- `"grantedOrigins"` (array) - Granted origin patterns (currently unused in permission flow)
- `"userAllowedOrigins"` (array) - User-added allowed origins for userscript installation

**Panel state (per-hostname):**
- `"panelState.HOSTNAME"` - Persisted editor content keyed by hostname

**Stability:** HIGH for auto-connect/sync settings, MEDIUM for others
**Versioning indicators:** `schemaVersion` (current: 1)
**Volatile aspects:** Storage keys may be extended, migration steps may be added

---

## 3. Message Protocol

**All cross-context communication shapes.**

**Authority:** [dev/docs/architecture/message-protocol.md](architecture/message-protocol.md)
**Contexts:** Page ↔ Content Bridge ↔ Background ↔ Popup/Panel

### 3.1 Page ↔ Content Bridge (window.postMessage)

**Source identifiers:**
- `"epupp-page"` - Messages from page to bridge
- `"epupp-userscript"` - Installation requests
- `"epupp-bridge"` - Messages from bridge to page

**Critical message types (24 total):**

| Direction | Type | Payload | Purpose |
|-----------|------|---------|---------|
| Page → Bridge | `ws-connect` | `{port}` | Request WebSocket |
| Page → Bridge | `ws-send` | `{data}` | Send through WS |
| Page → Bridge | `load-manifest` | `{manifest}` | Inject libraries |
| Page → Bridge | `list-scripts` | `{lsHidden, requestId}` | FS read |
| Page → Bridge | `get-script` | `{name, requestId}` | FS read |
| Page → Bridge | `save-script` | `{code, enabled, force, requestId, bulk-*}` | FS write |
| Page → Bridge | `rename-script` | `{from, to, force, requestId}` | FS write |
| Page → Bridge | `delete-script` | `{name, force, requestId, bulk-*}` | FS write |
| Bridge → Page | `bridge-ready` | - | Bridge loaded |
| Bridge → Page | `ws-open` | - | WS connected |
| Bridge → Page | `ws-message` | `{data}` | WS data |
| Bridge → Page | `ws-close` | - | WS closed |
| Bridge → Page | `*-response` | `{success, requestId, ...}` | Operation result |

**Bulk operation tracking:**
- `bulk-id`, `bulk-index`, `bulk-count` - Coordinate UI updates for multi-script operations

**Stability:** HIGH - Core communication protocol
**Versioning:** Should add protocol version field
**Volatile aspects:** Error message formats, new message types

---

### 3.2 Content Bridge ↔ Background (chrome.runtime)

**Similar to page protocol but different transport.**

Key differences:
- Uses `chrome.runtime.sendMessage` / `chrome.tabs.sendMessage`
- Includes `inject-script` and `inject-userscript` commands (background → bridge)
- `ping` keepalive every 5s

---

### 3.3 Popup/Panel ↔ Background

**Extension UI communication.**

| Type | Payload | Response | Purpose |
|------|---------|----------|---------|
| `get-connections` | - | `{connections}` | List active REPLs |
| `disconnect-tab` | `{tabId}` | - | Disconnect REPL |
| `check-status` | `{tabId}` | `{status}` | Bridge/Scittle check |
| `ensure-scittle` | `{tabId}` | `{success}` | Inject Scittle |
| `inject-libs` | `{tabId, libs}` | `{success}` | Load libraries |
| `evaluate-script` | `{tabId, scriptId, code, inject}` | `{success}` | Run script |
| `panel-save-script` | `{script}` | `{success, isUpdate, id}` | Save from panel |
| `panel-rename-script` | `{from, to}` | `{success}` | Rename from panel |

**Broadcasts:**
- `connections-changed` - Connection list updates
- `system-banner` - System notifications (FS operations, errors)

**Stability:** MEDIUM - UI protocol evolves with features
**Versioning indicators:** None

---

## 4. Extension Manifest API

**What Chrome/Firefox extension APIs declare.**

**Location:** [extension/manifest.json](../../extension/manifest.json)

### 4.1 Permissions

```json
{
  "permissions": ["scripting", "activeTab", "storage", "webNavigation"],
  "host_permissions": ["<all_urls>"]
}
```

**Implications:**
- Extension can inject scripts into any page via `chrome.scripting.executeScript`
- User sees "Read and change all your data on all websites" warning
- Required for userscript auto-injection

**Stability:** CRITICAL - Changing permissions requires re-review and user consent
**Versioning:** Manifest v3 (no v2 support planned)

---

### 4.2 Web Accessible Resources

**Bundled files exposed to pages:**

```json
{
  "resources": [
    "vendor/scittle.js",
    "vendor/scittle.nrepl.js",
    "vendor/scittle.*.js",
    "vendor/react*.js",
    "ws-bridge.js",
    "trigger-scittle.js"
  ],
  "matches": ["<all_urls>"]
}
```

**Stability:** HIGH - URLs are part of injection flows
**Versioning:** Library versions embedded in vendor files

---

### 4.3 Content Scripts (Dynamic Registration)

**Early-timing scripts use `chrome.scripting.registerContentScripts`.**

**Registered at runtime:**
- `userscript-loader.js` (ISOLATED world)
- Pattern matching from `:script/match` of `document-start` / `document-end` scripts

**Persistence:** Survives browser restart until unregistered

**Stability:** MEDIUM - Registration API is stable but script selection logic evolves
**Versioning indicators:** None

---

### 4.4 Extension Version

**Format:** `0.0.7.511` (semantic-ish + build number)

**Location:** `manifest.json` `version` field
**Updated:** Via `bb bump-version` task

**Stability:** N/A - This IS the version mechanism
**Consideration:** Should document versioning scheme for public API

---

## 5. URL Matching API

**Pattern syntax for userscript targeting.**

**Location:** [src/script_utils.cljs](../../src/script_utils.cljs) - `url-matches-pattern?`
**Consumers:** Background auto-injection, popup script list
**Documented:** [dev/docs/userscripts-architecture.md](userscripts-architecture.md)

### 5.1 Pattern Syntax

| Pattern | Matches | Example |
|---------|---------|---------|
| `*://example.com/*` | Any scheme (http/https) | `https://example.com/path` |
| `https://*.example.com/*` | Any subdomain | `https://foo.example.com/` |
| `https://example.com/path/*` | Path prefix | `https://example.com/path/sub` |
| `<all_urls>` | All URLs | Everything |

**Regex conversion:** `pattern->regex` escapes special chars, then `*` → `.*`

**Stability:** HIGH - Pattern syntax is TamperMonkey-compatible
**Versioning:** None
**Volatile aspects:** Edge cases in escaping, new pattern types

---

### 5.2 Helper Functions

| Function | Purpose | consumers |
|----------|---------|-----------|
| `url-matches-pattern?` | Single pattern match | Everywhere |
| `url-matches-any-pattern?` | Match any in list | Auto-injection |
| `url-to-match-pattern` | URL → pattern string | Panel "↵" button |

**Stability:** MEDIUM - Pure functions, unlikely to break but signatures could expand

---

## 6. Script Manifest Format

**Metadata embedded in userscript code.**

**Location:** [src/manifest_parser.cljs](../../src/manifest_parser.cljs)
**Format:** EDN map in first top-level form
**Consumers:** Panel save, background install, storage

### 6.1 Manifest Keys

```clojure
{:epupp/script-name "my_script.cljs"     ; Display name
 :epupp/auto-run-match "https://example.com/*"  ; URL pattern(s)
 :epupp/description "Does a thing"       ; Optional description
 :epupp/run-at "document-idle"           ; Timing: start/end/idle
 :epupp/inject ["scittle://reagent.js"]} ; Library dependencies
```

**Parsing:**
- Uses `edn-data/parseEDNString` with `{:mapAs "object" :keywordAs "string"}`
- Returns string keys: `"script-name"`, `"auto-run-match"`, etc.
- Validates `run-at` against `valid-run-at-values`

**Normalization:**
- `script-name` → lowercase, underscores, `.cljs` suffix
- `auto-run-match` → preserved as string or vector (not normalized to array here)
- `run-at` → defaults to `"document-idle"` if invalid
- `inject` → normalized to vector (string → `[string]`, nil → `[]`)

**Return shape:**
```javascript
{
  "script-name": "normalized_name.cljs",
  "raw-script-name": "Original Name",
  "name-normalized?": true,
  "auto-run-match": "...",
  "description": "...",
  "run-at": "document-idle",
  "raw-run-at": "invalid-value",
  "run-at-invalid?": true,
  "inject": ["scittle://..."],
  "found-keys": ["epupp/script-name", ...],
  "unknown-keys": ["epupp/typo"]
}
```

**Stability:** MEDIUM-HIGH - Core UX, but manifest keys could evolve
**Versioning indicators:** None (should add manifest version)
**Volatile aspects:** Key names (especially `:epupp/require` vs `:epupp/inject` inconsistency noted in codebase)

---

## 7. Build Configuration API

**Build-time settings injected globally.**

**Location:** [config/dev.edn](../../config/dev.edn), [config/prod.edn](../../config/prod.edn)
**Injection:** esbuild `--define:EXTENSION_CONFIG=...`
**Access:** All modules via `js/EXTENSION_CONFIG`

### 7.1 Config Shape

```clojure
{:dev true/false
 :depsString "{:deps {...}}"  ; Babashka deps string for browser-nrepl
 :installerSitePatterns ["https://gist.github.com/*" ...]
}
```

**Installer site patterns (prod):**
- `https://gist.github.com/*`
- `https://gitlab.com/*`
- `https://codeberg.org/*`

**Dev adds:**
- `http://localhost:*`
- `http://127.0.0.1:*`

**Stability:** MEDIUM - Pattern list will grow
**Versioning:** None
**Volatile aspects:** New patterns will be added without warning

---

## 8. UI Contracts

**User-facing behaviors that become implicit APIs.**

### 8.1 Popup Settings

**Location:** [src/popup.cljs](../../src/popup.cljs)

| Setting | Storage Key | Default | Impact |
|---------|-------------|---------|--------|
| Auto-reconnect | `autoReconnectRepl` | true | Reconnect previously connected tabs |
| Auto-connect all | `autoConnectRepl` | false | Connect REPL on every page load |
| FS REPL Sync | `fsReplSyncEnabled` | false | Allow REPL to write scripts |

**User-added origins:** List of origin prefixes for userscript installation

**Stability:** HIGH - Changing defaults or removing settings impacts users
**Versioning:** None
**Volatile:** Validation rules, new settings

---

### 8.2 Panel Persistence

**Per-hostname editor state saved to `chrome.storage.local`.**

**Key pattern:** `"panelState.HOSTNAME"`
**Contents:** Last editor code and Scittle check state

**Stability:** MEDIUM - Nice-to-have feature
**Volatile:** Key format, what is persisted

---

### 8.3 Script Naming Rules

**Name normalization (API contract for panel save flow).**

**Rules:**
- Lowercase
- Spaces → underscores
- Dashes → underscores
- Dots → underscores (except final `.cljs`)
- Preserve `/` for namespacing
- Append `.cljs` if missing

**Function:** `normalize-script-name` in [src/script_utils.cljs](../../src/script_utils.cljs)

**Example:** `"My GitHub Script"` → `"my_github_script.cljs"`

**Stability:** MEDIUM - Users depend on consistent naming
**Versioning:** None
**Volatile:** Normalization rules might add more transformations

---

### 8.4 Built-in Scripts

**Immutable, pre-installed userscripts.**

**Current:**
- **GitHub Gist Installer** (`epupp-builtin-gist-installer`)
  - Match: `https://gist.github.com/*`, `http://localhost:18080/mock-gist.html`
  - Code: [extension/userscripts/epupp/gist_installer.cljs](../../extension/userscripts/epupp/gist_installer.cljs)
  - Always enabled, cannot be deleted

**Identification:**
- `:script/builtin?` metadata
- ID prefix: `epupp-builtin-*`

**Stability:** HIGH - Built-ins are user-facing
**Versioning:** Code updated via storage sync, but no version tracking
**Volatile:** New built-ins may be added

---

## 9. REPL Protocol Interface

**nREPL connection flow and requirements.**

**Consumer:** Babashka `browser-nrepl` relay server
**Documented:** [dev/docs/architecture/connected-repl.md](architecture/connected-repl.md)

### 9.1 Connection Requirements

**Pre-connection (user setup):**
1. Run `bb browser-nrepl --nrepl-port PORT --websocket-port WS_PORT`
2. Open popup, click "Connect" with matching `WS_PORT`

**Connection flow guarantees:**
1. Content bridge injected (ISOLATED world)
2. WS bridge injected (MAIN world)
3. Scittle core loaded
4. Scittle nREPL loaded
5. Epupp API namespaces injected (`epupp.fs`, `epupp.repl`)
6. WebSocket virtual connection established

**Keepalive:** Content bridge pings every 5s to maintain connection

**Stability:** HIGH - Core feature
**Versioning:** Babashka deps version in config
**Volatile:** Injection sequence, keepalive timing

---

### 9.2 Scittle nREPL WebSocket

**Virtual WebSocket implementation.**

**URL pattern:** `ws://localhost:PORT/_nrepl`

**Intercepted by:** `ws-bridge.js` in page MAIN world

**Message transport:**
- Page → Bridge: `ws-send` postMessage
- Bridge → Background: `ws-send` chrome.runtime message
- Background → Relay: Actual WebSocket send
- (Reverse for responses)

**Stability:** HIGH - Critical path
**Versioning:** None (should track Scittle nREPL version)
**Volatile:** WebSocket interceptor could fail with future Scittle updates

---

### 9.3 Epupp API Injection

**Files injected at connect time:**
- `bundled/epupp/repl.cljs` → `epupp.repl` namespace
- `bundled/epupp/fs.cljs` → `epupp.fs` namespace

**Injection method:** Same as userscripts (inline `<script type="application/x-scittle">` tags)

**Stability:** HIGH - REPL workflows depend on this
**Versioning:** None
**Volatile:** File paths, injection timing

---

## 10. Internal Contracts (Future Versioning Candidates)

### 10.1 Debug Globals

**Exposed for console testing (undocumented but discoverable).**

| Global | Module | Purpose |
|--------|--------|---------|
| `globalThis.storage` | storage.cljs | Access storage functions |
| `globalThis.scriptUtils` | script_utils.cljs | Utility functions |
| `globalThis.urlMatching` | url_matching.cljs | Pattern matching |

**Stability:** NONE - Internal/debug only
**Versioning:** Not guaranteed
**Volatile:** May be removed or renamed anytime

---

### 10.2 Window Globals (Page Context)

**Set by injected scripts.**

| Global | Set By | Purpose |
|--------|--------|---------|
| `window.__browserJackInWSBridge` | ws_bridge.cljs | WS bridge loaded flag |
| `window.__browserJackInContentBridge` | content_bridge.cljs | Content bridge loaded flag |
| `window.__epuppInjectedScripts` | content_bridge.cljs | Track injected URLs (idempotency) |
| `window.SCITTLE_NREPL_WEBSOCKET_HOST` | background.cljs | Scittle config |
| `window.SCITTLE_NREPL_WEBSOCKET_PORT` | background.cljs | Scittle config |
| `window.ws_nrepl` | ws_bridge.cljs | Virtual WebSocket instance |

**Stability:** LOW - Implementation details
**Versioning:** None
**Volatile:** Names could change, properties may be added/removed

---

## 11. Summary: Stability Assessment

### Critical (Breaking Changes Impact Core Workflows)

1. **REPL FS API** (`epupp.fs`) - Read/write operations
2. **Storage schema** - Scripts array structure
3. **Extension permissions** - Manifest host_permissions
4. **URL matching syntax** - TamperMonkey compatibility
5. **Scittle library catalog** - Available `scittle://` URLs

### High (User-Facing, Documented Behavior)

1. **Message protocol** - Cross-context communication shapes
2. **Script manifest format** - `:epupp/*` keys
3. **Settings API** - Auto-connect, FS sync gate
4. **Built-in scripts** - Gist installer
5. **REPL connection flow** - Injection sequence

### Medium (May Evolve, But Used)

1. **Build config** - `installerSitePatterns`
2. **Panel persistence** - Per-hostname state
3. **Name normalization** - Script naming rules
4. **Library loading** - `epupp.repl/manifest!`
5. **Userscript installation** - Page-initiated install

### Low (Internal, Undocumented)

1. **Debug globals** - `globalThis.*`
2. **Window flags** - `__browserJackIn*`
3. **Message bulk-tracking** - `bulk-id`, `bulk-index`, `bulk-count`

---

## 12. Versioning Recommendations

### Should Add Version Fields

1. **Storage schema version** - Detect migrations needed
2. **Message protocol version** - Backward-compatible comm
3. **Manifest format version** - Support old userscripts
4. **API version** - Track `epupp.fs` breaking changes

### Should Document Formally

1. **URL pattern syntax** - Grammar spec
2. **Scittle library versions** - What's in each vendor file
3. **Script lifecycle** - Creation → Injection → Deletion
4. **Error codes** - Standardize error strings

### Should Stabilize Before 1.0

1. **Manifest key naming** - `:epupp/inject` vs `:epupp/require` inconsistency
2. **Storage key casing** - `autoConnectRepl` vs `auto-connect-repl`
3. **Response shape consistency** - Always include `success` field
4. **Built-in script update mechanism** - Currently overwrites on code change

---

## Related Documents

- [architecture.md](architecture.md) - System overview
- [message-protocol.md](architecture/message-protocol.md) - Message reference
- [userscripts-architecture.md](userscripts-architecture.md) - Userscript design
- [repl-fs-sync.md](architecture/repl-fs-sync.md) - FS API architecture
- [connected-repl.md](architecture/connected-repl.md) - REPL connection details
