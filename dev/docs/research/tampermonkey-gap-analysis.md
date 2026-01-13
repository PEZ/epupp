# TamperMonkey Gap Analysis

**Generated**: January 8, 2026
**Status**: Complete

## Executive Summary

- **Epupp covers basic userscript functionality** (URL matching, enable/disable, per-pattern approval) but lacks most TamperMonkey convenience features
- **`@run-at` injection timing is the highest-priority gap** - essential for REPL-driven development where scripts need to intercept page initialization
- **GM_* APIs are largely out of scope** - Epupp's Scittle runtime already provides DOM access; cross-origin networking conflicts with the REPL-first model
- **Script metadata headers (`==UserScript==`) would enable import/export** and compatibility with existing userscript ecosystems
- **UI management features (tags, cloud sync, dashboard)** are intentionally minimal per Epupp's design philosophy

**Strategic recommendation**: Focus on injection timing (`@run-at`) and metadata header parsing for script portability. Avoid replicating TamperMonkey's management complexity - the REPL connection is Epupp's value proposition, not script library management.

## Methodology

### Sources Consulted
- TamperMonkey documentation: https://www.tampermonkey.net/documentation.php (January 8, 2026)
- TamperMonkey FAQ: https://www.tampermonkey.net/faq.php (January 8, 2026)
- Epupp source code: `src/*.cljs`, `extension/manifest.json`
- Epupp documentation: `dev/docs/architecture/overview.md`, `dev/docs/userscripts-architecture.md`

### Categorization Framework

> **Note**: The "Strategic Fit" and "User Impact" assessments in this document are **AI-generated suggestions** based on Epupp's stated design philosophy. These require human review and decision-making before being treated as priorities.

| Dimension | Values | Criteria |
|-----------|--------|----------|
| **Strategic Fit (AI assessed)** | Core / Adjacent / Out-of-Scope | Core = enhances REPL workflow; Adjacent = useful but not REPL-specific; Out-of-Scope = TM-specific, doesn't fit Epupp model |
| **User Impact (AI assessed)** | High / Medium / Low | How much this affects typical Epupp users |
| **Effort** | S / M / L | S = days, M = weeks, L = months |

## Feature Comparison Matrix

### Metadata Headers

| Feature | TamperMonkey | Epupp | Gap? | Strategic Fit (AI assessed) | Impact (AI assessed) | Effort |
|---------|--------------|-------|------|---------------|--------|--------|
| `@name` | Yes | Yes (`:script/name`) | No | - | - | - |
| `@match` patterns | Yes | Yes (`:script/match`) | No | - | - | - |
| `@description` | Yes | Yes (`:script/description`) | No | - | - | - |
| `@version` | Yes | No | Yes | Adjacent | Low | S |
| `@namespace` | Yes | No | Yes | Out-of-Scope | Low | S |
| `@author` | Yes | No | Yes | Adjacent | Low | S |
| `@run-at` timing | Yes | No | **Yes** | **Core** | **High** | **M** |
| `@grant` capabilities | Yes | No | Yes | Out-of-Scope | Medium | L |
| `@require` external scripts | Yes | No | Yes | Adjacent | Medium | M |
| `@resource` preloading | Yes | No | Yes | Out-of-Scope | Low | M |
| `@include` (glob patterns) | Yes | Partial | Partial | Adjacent | Low | S |
| `@exclude` patterns | Yes | No | Yes | Adjacent | Medium | S |
| `@connect` domains | Yes | No | Yes | Out-of-Scope | Low | S |
| `@noframes` | Yes | Partial (main frame only) | No | - | - | - |
| `@updateURL` / `@downloadURL` | Yes | No | Yes | Adjacent | Low | M |
| `@icon` | Yes | No | Yes | Adjacent | Low | S |
| `@sandbox` | Yes | No | Yes | Adjacent | Low | M |
| `@run-in` (incognito/containers) | Yes | No | Yes | Out-of-Scope | Low | M |
| `@antifeature` | Yes | No | Yes | Out-of-Scope | Low | S |
| `@webRequest` | Yes (experimental, no MV3) | No | Yes | Out-of-Scope | Low | - |
| Metadata header parsing | Yes | No | **Yes** | **Core** | **High** | **M** |
| Internationalization (`@name:de`) | Yes | No | Yes | Out-of-Scope | Low | S |

### GM_* APIs

| Feature | TamperMonkey | Epupp | Gap? | Strategic Fit (AI assessed) | Impact (AI assessed) | Effort |
|---------|--------------|-------|------|---------------|--------|--------|
| `GM_setValue` / `GM_getValue` | Yes | No (use Scittle + localStorage) | Yes | Adjacent | Medium | M |
| `GM_addValueChangeListener` | Yes | No | Yes | Adjacent | Low | M |
| `GM_xmlhttpRequest` | Yes | No (use REPL for network) | Yes | Out-of-Scope | Low | L |
| `GM_download` | Yes | No | Yes | Out-of-Scope | Low | M |
| `GM_notification` | Yes | No | Yes | Out-of-Scope | Low | M |
| `GM_openInTab` | Yes | No (use `js/window.open`) | Yes | Out-of-Scope | Low | S |
| `GM_setClipboard` | Yes | No (use `navigator.clipboard`) | Yes | Out-of-Scope | Low | S |
| `GM_addStyle` | Yes | No (use DOM APIs) | Yes | Adjacent | Low | S |
| `GM_addElement` | Yes | No (use DOM APIs) | Yes | Adjacent | Low | S |
| `GM_registerMenuCommand` | Yes | No | Yes | Out-of-Scope | Low | M |
| `GM_getResourceURL` / `GM_getResourceText` | Yes | No | Yes | Out-of-Scope | Low | M |
| `GM_cookie` | Yes | No | Yes | Out-of-Scope | Low | M |
| `GM_webRequest` | Yes (no MV3) | No | Yes | Out-of-Scope | Low | - |
| `GM_info` | Yes | No | Yes | Adjacent | Low | S |
| `GM_log` | Yes | No (use `println`) | Yes | Out-of-Scope | Low | S |
| `unsafeWindow` | Yes | No (already in MAIN world) | No | - | - | - |
| Promise-based `GM.*` variants | Yes | No | Yes | Out-of-Scope | Low | M |

### Script Management

| Feature | TamperMonkey | Epupp | Gap? | Strategic Fit (AI assessed) | Impact (AI assessed) | Effort |
|---------|--------------|-------|------|---------------|--------|--------|
| Enable/disable scripts | Yes | Yes | No | - | - | - |
| Delete scripts | Yes | Yes | No | - | - | - |
| Per-pattern approval | No | Yes | No (Epupp has more) | - | - | - |
| Script tags/categories | Yes | No | Yes | Out-of-Scope | Low | M |
| Script ordering/priority | Yes | No | Yes | Adjacent | Low | S |
| User include/exclude overrides | Yes | No | Yes | Adjacent | Medium | M |
| Script storage inspector | Yes | No | Yes | Out-of-Scope | Low | M |
| Import scripts (zip/JSON) | Yes | No | Yes | Adjacent | Medium | M |
| Export scripts (zip/JSON) | Yes | No | Yes | Adjacent | Medium | M |
| Cloud sync (Google Drive, etc.) | Yes | No | Yes | Out-of-Scope | Low | L |
| Script update checking | Yes | No | Yes | Adjacent | Low | M |
| Multiple scripts on same page | Yes | Yes | No | - | - | - |
| Script run counter/stats | Yes | No | Yes | Out-of-Scope | Low | S |

### UI Features

| Feature | TamperMonkey | Epupp | Gap? | Strategic Fit (AI assessed) | Impact (AI assessed) | Effort |
|---------|--------------|-------|------|---------------|--------|--------|
| Built-in code editor | Yes (CodeMirror) | Yes (textarea) | Partial | Out-of-Scope | Low | - |
| Syntax highlighting | Yes | No | Yes | Out-of-Scope | Low | M |
| ESLint integration | Yes | No | Yes | Out-of-Scope | Low | L |
| Dashboard tab | Yes | No (intentional) | Yes | Out-of-Scope | Low | - |
| Popup with script status | Yes | Yes | No | - | - | - |
| Badge with active script count | Yes (running scripts) | Yes (pending approvals) | No | - | - | - |
| DevTools panel | No | Yes | No (Epupp has more) | - | - | - |
| REPL connection | No | Yes | No (Epupp has more) | - | - | - |
| Configuration modes (Novice/etc.) | Yes | No | Yes | Out-of-Scope | Low | M |
| External editor integration | Yes (TamperDAV, VS Code ext) | Yes (nREPL) | No | - | - | - |

### Script Installation

| Feature | TamperMonkey | Epupp | Gap? | Strategic Fit (AI assessed) | Impact (AI assessed) | Effort |
|---------|--------------|-------|------|---------------|--------|--------|
| One-click install from GreasyFork | Yes | No | Yes | Out-of-Scope | Low | L |
| Install from GitHub/Gist raw URLs | Yes | Yes (with origin approval) | No | - | - | - |
| `.user.js` file drag-drop | Yes | No | Yes | Adjacent | Low | M |
| Manual script creation | Yes | Yes | No | - | - | - |
| Built-in scripts | No | Yes | No (Epupp has more) | - | - | - |

### Security

| Feature | TamperMonkey | Epupp | Gap? | Strategic Fit (AI assessed) | Impact (AI assessed) | Effort |
|---------|--------------|-------|------|---------------|--------|--------|
| Permission grants (`@grant`) | Yes | No | Yes | Out-of-Scope | Medium | L |
| Blacklist system | Yes | No | Yes | Out-of-Scope | Low | M |
| Download file type whitelist | Yes | No | Yes | Out-of-Scope | Low | S |
| Sandbox modes | Yes | No | Yes | Out-of-Scope | Low | M |
| CSP bypass for scripts | Yes | Yes | No | - | - | - |
| Per-pattern user approval | No | Yes | No (Epupp has more) | - | - | - |
| Origin validation for installs | No | Yes | No (Epupp has more) | - | - | - |

## Detailed Gap Analysis

#### @run-at Injection Timing

See dedicated document: [run-at-injection-timing.md](run-at-injection-timing.md)

**Summary**: TamperMonkey provides five timing modes (`document-start`, `document-body`, `document-end`, `document-idle`, `context-menu`). Epupp currently only supports `document-idle` equivalent. Earlier injection is important for REPL-driven development to intercept page initialization, block scripts, or modify globals before page code runs.

#### Metadata Header Parsing (==UserScript==)

See [TamperMonkey Userscript Header documentation](https://www.tampermonkey.net/documentation.php#meta:name).

- **What TamperMonkey provides**: Standard header block parsed from script source, enabling portable scripts across userscript managers.

- **Epupp current state**: Metadata stored separately from code in `:script/*` keys. No header parsing from code.

- **Why it matters for Epupp**:
  1. **Import compatibility**: Users can't paste existing userscripts and have them work
  2. **Export portability**: Scripts created in Epupp can't run elsewhere
  3. **Single source of truth**: Headers inline with code means script files are self-contained
  4. **REPL workflow**: When developing scripts via REPL, headers can be edited alongside code

- **Implementation approach**:
  1. Add header parser in `script_utils.cljs` (regex for `// ==UserScript==` block)
  2. On script save, extract headers and populate `:script/*` fields
  3. On script display/edit, render headers from `:script/*` fields back into code
  4. Support key headers first: `@name`, `@match`, `@description`, `@run-at`
  5. Add import/export that preserves headers

- **Effort estimate**: M (2-3 weeks) - parser implementation, bidirectional sync, import/export UI

#### @exclude Patterns

See [TamperMonkey @exclude documentation](https://www.tampermonkey.net/documentation.php#meta:exclude).

- **What TamperMonkey provides**: Exclude specific URLs from otherwise matching patterns.
- **Epupp current state**: No exclude support; must create separate patterns or disable script entirely.
- **Why it matters**: Common need to exclude specific pages (e.g., run on `github.com/*` except `github.com/settings/*`).
- **Implementation approach**: Add `:script/exclude` vector, update `url-matches-pattern?` to check excludes after matches.
- **Effort estimate**: S (days)

#### Import/Export Scripts

See [TamperMonkey FAQ - Import/Export](https://www.tampermonkey.net/faq.php#Q209).

- **What TamperMonkey provides**: Multiple formats (ZIP, JSON, textarea, cloud), bulk operations.
- **Epupp current state**: No import/export. Scripts trapped in chrome.storage.local.
- **Why it matters**: Backup, sharing, migrating between browsers.
- **Implementation approach**:
  - Export: Serialize scripts to JSON or `.user.cljs` files with metadata headers
  - Import: File picker, parse headers, add to storage
- **Effort estimate**: M (1-2 weeks)

#### @require External Scripts

See [TamperMonkey @require documentation](https://www.tampermonkey.net/documentation.php#meta:require).

- **What TamperMonkey provides**: Load external JS libraries before script execution (jQuery, lodash, etc.).
- **Epupp current state**: Scripts must load dependencies manually or rely on page's existing libraries.
- **Why it matters**: Common pattern for userscripts to require utility libraries.
- **Implementation approach**:
  - Add `:script/require` vector of URLs
  - Inject `<script>` tags for each URL before userscript tag
  - Consider caching/integrity verification
- **Effort estimate**: M (1-2 weeks)

#### User Include/Exclude Overrides

See [TamperMonkey FAQ - Script Settings](https://www.tampermonkey.net/faq.php#Q302).

- **What TamperMonkey provides**: Modify script's URL patterns without editing source code.
- **Epupp current state**: Must edit script's `:script/match` directly.
- **Why it matters**: Quickly test scripts on different sites, or exclude problematic pages.
- **Implementation approach**: Add `:script/user-includes` and `:script/user-excludes` vectors, apply in addition to script's own patterns.
- **Effort estimate**: S-M (days to a week)

#### GM_setValue / GM_getValue Equivalents

See [TamperMonkey GM_setValue documentation](https://www.tampermonkey.net/documentation.php#api:GM_setValue) and [GM_getValue documentation](https://www.tampermonkey.net/documentation.php#api:GM_getValue).

- **What TamperMonkey provides**: Per-script persistent storage with cross-tab change listeners.
- **Epupp current state**: Scripts can use `localStorage` or `sessionStorage` directly, but no isolation per script.
- **Why it matters**: Scripts that persist user preferences or state need reliable storage.
- **Implementation approach**:
  - Create Scittle library with `epupp.storage/set-value!` and `get-value` functions
  - Use namespaced keys in localStorage: `epupp:script-id:key`
  - Or use `chrome.storage.local` via message passing (more complex)
- **Effort estimate**: M (1-2 weeks)

#### @grant Permission System

See [TamperMonkey @grant documentation](https://www.tampermonkey.net/documentation.php#meta:grant).

- **What TamperMonkey provides**: A permission system that whitelists which GM_* APIs a script can use. Scripts declare their needs (e.g., `@grant GM_xmlhttpRequest`, `@grant GM_setValue`), and TamperMonkey enforces these boundaries. Special values include:
  - `@grant none` - Run without sandbox, no GM_* functions except `GM_info`
  - `@grant unsafeWindow` - Access page's window object
  - `@grant window.close` / `window.focus` / `window.onurlchange` - Window control APIs

- **Epupp current state**: No grant system. Scripts run in MAIN world with full page access and standard browser APIs.

- **Epupp rationale**: The grant system exists to sandbox scripts and limit their capabilities for security. Epupp scripts already run in MAIN world (equivalent to TamperMonkey's `@grant none` or `@sandbox raw`), and we don't implement most GM_* APIs that would need grants. The per-pattern approval system provides user control instead.

- **Recommendation**: Won't implement. Without GM_* APIs, there's nothing to grant. If we later add storage APIs (`epupp.storage`), they would use a simpler opt-in model.

#### GM_xmlhttpRequest

See [TamperMonkey GM_xmlhttpRequest documentation](https://www.tampermonkey.net/documentation.php#api:GM_xmlhttpRequest).

- **What TamperMonkey provides**: Cross-origin HTTP requests bypassing CORS, with cookies and headers.
- **Epupp rationale**: This conflicts with Epupp's model. For network requests during development, users can:
  1. Use the REPL from their editor (which can make requests from Node.js/JVM)
  2. Use `fetch()` for same-origin requests
  3. Set up a proxy server for cross-origin development needs
- **Recommendation**: Won't implement. Document workarounds for network-heavy development.

#### Script Tags/Categories

See [TamperMonkey @tag documentation](https://www.tampermonkey.net/documentation.php#meta:tag).

- **What TamperMonkey provides**: Tag system for organizing large script collections.
- **Epupp rationale**: Epupp targets developers with few scripts under active development, not collectors with dozens of scripts. The popup's simple list is sufficient.
- **Recommendation**: Won't implement unless user feedback shows demand.

#### Cloud Sync

See [TamperMonkey FAQ - Script Sync](https://www.tampermonkey.net/faq.php#Q105).

- **What TamperMonkey provides**: Google Drive, Dropbox, WebDAV, browser sync.
- **Epupp rationale**: Scripts developed in Epupp likely live in version control (git), not cloud sync. The REPL-first workflow implies professional development practices.
- **Recommendation**: Won't implement. Users can export scripts and commit to git.

#### Dashboard Tab

- **What TamperMonkey provides**: Full-page management interface.
- **Epupp rationale**: Intentionally omitted per design philosophy. DevTools panel + popup provides contextual management. A dashboard adds complexity without benefiting REPL workflow.
- **Recommendation**: Won't implement. This is a conscious design decision.

#### Built-in Code Editor Enhancements

- **What TamperMonkey provides**: CodeMirror with syntax highlighting, ESLint.
- **Epupp rationale**: The DevTools textarea is an on-ramp; serious development uses external editors via nREPL. Investing in editor features competes with the core value prop.
- **Recommendation**: Won't implement beyond current state. Consider recommending VS Code + Calva for best experience.

#### GreasyFork Integration

- **What TamperMonkey provides**: One-click install from script repositories.
- **Epupp rationale**: GreasyFork scripts are JavaScript, not ClojureScript. While header parsing enables import, active repository integration doesn't fit Epupp's developer audience.
- **Recommendation**: Won't implement. Header parsing (when implemented) will enable manual import of adapted scripts.

#### GM_registerMenuCommand

See [TamperMonkey GM_registerMenuCommand documentation](https://www.tampermonkey.net/documentation.php#api:GM_registerMenuCommand).

- **What TamperMonkey provides**: Add script-specific entries to browser context menu.
- **Epupp rationale**: Low value for REPL-driven development. Users can add UI elements to pages directly via REPL evaluation.
- **Recommendation**: Won't implement.

#### GM_notification

See [TamperMonkey GM_notification documentation](https://www.tampermonkey.net/documentation.php#api:GM_notification).

- **What TamperMonkey provides**: Desktop notifications from userscripts.
- **Epupp rationale**: Scripts can use the standard `Notification` API directly. No special wrapper needed.
- **Recommendation**: Won't implement. Document use of native `Notification` API.

## Recommendations (AI assessed)

> **Note**: These recommendations are AI-generated suggestions based on the gap analysis. They require human review and prioritization.

### Must-Have (Roadmap candidates)

1. **@run-at injection timing** - Highest impact for REPL-driven development. Enables intercepting page initialization.
2. **==UserScript== header parsing** - Enables import/export, script portability, and single-source-of-truth workflow.

### Should-Have (If resources allow)

3. **@exclude patterns** - Common need, low effort.
4. **Import/export (JSON or .user.cljs)** - Enables backup, sharing, migration.
5. **@require external scripts** - Common pattern for utility library loading.
6. **Script version tracking** - Supports future update checking.

### Won't-Have (Intentional scope exclusions)

7. **GM_xmlhttpRequest** - Conflicts with REPL-first model. Network requests should use editor-side tooling.
8. **Dashboard tab** - Intentional design decision. Popup + DevTools panel is sufficient.
9. **Cloud sync** - Professional users should use git. Export/import covers backup needs.
10. **CodeMirror/syntax highlighting** - Editor investment competes with REPL value prop. Recommend external editors.
11. **GreasyFork integration** - Scripts are JavaScript, not ClojureScript. Manual import via header parsing is sufficient.
12. **Script tags/categories** - Targets collectors, not active developers. List UI is sufficient.
13. **GM_registerMenuCommand** - Low value, users can add UI via REPL.
14. **GM_notification** - Native `Notification` API works directly.

## Appendix

### A. Complete TamperMonkey Header Reference

See [TamperMonkey Documentation - Userscript Header](https://www.tampermonkey.net/documentation.php#meta:name) for full details.

| Header | Description | Required? |
|--------|-------------|-----------|
| `@name` | Script display name | Yes |
| `@namespace` | Script namespace (for uniqueness) | No |
| `@version` | Version string for updates | For updates |
| `@description` | Short description | No |
| `@author` | Author name | No |
| `@match` | URL patterns (Chrome-style) | No* |
| `@include` | URL patterns (glob/regex) | No* |
| `@exclude` | URLs to exclude | No |
| `@run-at` | Injection timing | No (default: document-idle) |
| `@grant` | Required API permissions | No |
| `@require` | External script URLs | No |
| `@resource` | Preloaded resources | No |
| `@connect` | Allowed domains for GM_xmlhttpRequest | No |
| `@noframes` | Main frame only | No |
| `@sandbox` | Execution context | No |
| `@run-in` | Browser context type | No |
| `@icon` | Script icon URL | No |
| `@updateURL` | Update metadata URL | No |
| `@downloadURL` | Script download URL | No |
| `@supportURL` | Issue reporting URL | No |
| `@homepage` | Author's homepage | No |
| `@copyright` | Copyright statement | No |
| `@antifeature` | Monetization disclosure | No |
| `@tag` | Script categories | No |
| `@unwrap` | Remove sandbox wrapper | No |
| `@webRequest` | Web request rules (experimental) | No |

*At least one of `@match` or `@include` typically needed for script to run.

### B. Complete GM_* API Reference

See [TamperMonkey Documentation - API](https://www.tampermonkey.net/documentation.php#api:GM_setValue) for full details.

**Storage APIs**: `GM_setValue`, `GM_getValue`, `GM_deleteValue`, `GM_listValues`, `GM_setValues`, `GM_getValues`, `GM_deleteValues`, `GM_addValueChangeListener`, `GM_removeValueChangeListener`

**Network APIs**: `GM_xmlhttpRequest`, `GM_webRequest`, `GM_download`

**Cookie APIs**: `GM_cookie.list`, `GM_cookie.set`, `GM_cookie.delete`

**DOM/UI APIs**: `GM_addElement`, `GM_addStyle`

**User Interface APIs**: `GM_notification`, `GM_openInTab`, `GM_registerMenuCommand`, `GM_unregisterMenuCommand`, `GM_setClipboard`

**Tab Management APIs**: `GM_getTab`, `GM_saveTab`, `GM_getTabs`

**Resource APIs**: `GM_getResourceText`, `GM_getResourceURL`

**Audio APIs**: `GM_audio.setMute`, `GM_audio.getState`, `GM_audio.addStateChangeListener`, `GM_audio.removeStateChangeListener`

**Utility APIs**: `GM_info`, `GM_log`

**Window Access**: `unsafeWindow`, `window.close`, `window.focus`, `window.onurlchange`

### C. Epupp Architecture Notes

**Script storage schema**:
```clojure
{:script/id "..."
 :script/name "..."
 :script/match ["https://.../*"]
 :script/code "..."
 :script/enabled true
 :script/created "ISO-timestamp"
 :script/modified "ISO-timestamp"
 :script/approved-patterns ["..."]
 :script/description "..."}
```

**Injection flow** (current):
1. `webNavigation.onCompleted` fires (main frame only)
2. Match enabled scripts against URL
3. Check pattern approval status
4. If approved: inject content bridge → inject Scittle → inject userscript tags → trigger evaluation

**Security model**:
- MAIN world execution (no isolation from page)
- Content bridge filters messages from page (whitelist: `ws-connect`, `ws-send`, `install-userscript`)
- Per-pattern approval provides user control beyond Chrome's permissions
- Origin validation for remote script installation
