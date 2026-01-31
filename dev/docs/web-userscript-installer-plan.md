# Web Userscript Installer Refactor Plan

Redesign the Gist Installer to become a general-purpose "Web Userscript Installer" that:
1. Extracts code directly from DOM elements (no URL fetching)
2. Uses configurable auto-run-match via the Origins setting
3. Tracks script provenance via new `:script/source` metadata

## Background

### Current Model
```
Gist page (hardcoded match) → Extract manifest → Fetch code from raw URL → Validate URL origin → Save
```

**Problem:** The "Allowed Userscript-install Base URLs" setting is confusing because:
- It controls which URLs can be *fetched from*, not where the installer runs
- The installer's auto-run-match is hardcoded in its manifest
- Users can't easily extend installer to work on other code-hosting sites

### Proposed Model
```
Any matched page → Extract code from DOM → Send message with code + page URL → Storage saves with source
```

**Benefits:**
- **WYSIWYG security** - Code you see is code you install (no TOCTOU via URL fetch)
- **Simpler mental model** - Setting controls where installer activates
- **User extensible** - Add any site that displays code blocks
- **Less code** - No URL fetching, no origin validation for URLs
- **Consolidated logic** - Storage is single source of truth for all save operations

### No backward compatibility

The userscripts feature is not released yet. Adding any backward compatibility to the code will just be a burden. We're all about forward compatability at this stage.

## Design Decisions

### Origins Setting Repurposed

| Current | Proposed |
|---------|----------|
| "Allowed Userscript-install Base URLs" | "Web Installer Sites" |
| Controls *fetch* origins | Controls *auto-run-match* additions |
| Format: URL prefix (e.g., `https://gist.github.com/`) | Format: URL glob pattern (e.g., `https://gist.github.com/*`) |

The installer script's final auto-run-match = `manifest patterns ∪ user-added patterns`

### Provenance Tracking

New optional field `:script/source` to track where a script came from:

| Source | Value | Example |
|--------|-------|---------|
| DevTools panel | `:source/panel` | User wrote in panel, clicked Save |
| REPL FS API | `:source/repl` | `(epupp.fs/save! ...)` |
| Built-in scripts | `:source/built-in` | Bundled with extension |
| Web installer | URL string | `"https://gist.github.com/PEZ/abc123"` |

**Why this design:**
- Keywords for internal sources (simple, no extra data needed)
- URL string for web installs (retains provenance, useful for updates)
- Optional field - existing scripts without `:script/source` are fine

**Note on `:source/built-in`:** There's overlap with existing built-in detection (`builtin-script?` checks ID prefix). Having both is intentional - `:script/source` provides consistent provenance tracking across all scripts, while the ID-based check remains for quick filtering in existing code paths.

**Alternative considered:** Structured map `{:type :web :url "..."}` - rejected as over-engineering for current needs. Can migrate later if needed.

### Storage as Single Source of Truth

**Critical design principle:** `storage/save-script!` is the ONLY place that persists scripts. All save paths must flow through it.

**Current flow (installer):**
```
Installer (page) → postMessage "install-userscript" {manifest, scriptUrl}
  → content-bridge → chrome.runtime.sendMessage
  → background action → install-userscript! (fetches URL) → storage/save-script!
```

**New flow (installer):**
```
Installer (page) → postMessage "save-script" {code, source: pageUrl}
  → content-bridge → chrome.runtime.sendMessage
  → background action → storage/save-script! (with :script/source)
```

**Source responsibility by caller:**

| Caller | Passes to storage | Storage preserves |
|--------|-------------------|-------------------|
| Panel save | `{:script/source :source/panel}` | `:script/source` |
| REPL FS save | `{:script/source :source/repl}` | `:script/source` |
| Web installer | `{:script/source "https://..."}` | `:script/source` |
| Built-in sync | `{:script/source :source/built-in}` | `:script/source` |

Storage does NOT infer or set source - callers provide it explicitly. This keeps storage simple and responsibility clear.

### Message Protocol Change

The installer script runs in **page context** (Scittle), not extension context. It cannot call `epupp.fs/save!` directly - that namespace is available in the REPL context only.

Instead, the installer sends a message through the content bridge:

**Current message:** `{type: "install-userscript", manifest: {...}, scriptUrl: "..."}`
**New message:** `{type: "save-script", code: "...", source: "https://gist.github.com/..."}`

The background handler for `save-script` routes directly to `storage/save-script!` - no intermediate `install-userscript!` function needed.

### Script Rename

| Current | Proposed |
|---------|----------|
| `epupp/gist_installer.cljs` | `epupp/web_userscript_installer.cljs` |
| Hardcoded gist detection | Generic code block detection |
| Gist-specific DOM selectors | Configurable or heuristic selectors |

---

## Standard

- All edits delegated to **Clojure-editor subagent**
- Before each batch: delegate to **epupp-testrunner subagent** for baseline
- After each batch: delegate to **epupp-testrunner subagent** for verification
- Unit tests preferred where practical
- E2E tests delegated to **epupp-e2e-expert subagent**
- Tick checkboxes without inserting commentary blocks

---

## Required Reading

### Architecture Docs
- [dev/docs/architecture.md](architecture.md) - Overall system architecture
- [dev/docs/userscripts-architecture.md](userscripts-architecture.md) - Userscript system design

### Source Files
- [src/storage.cljs](../../src/storage.cljs) - Script storage schema and operations (CENTRAL)
- [src/registration.cljs](../../src/registration.cljs) - Content script registration for early-timing scripts
- [src/background.cljs](../../src/background.cljs) - `install-userscript!` function (to be removed)
- [src/content_bridge.cljs](../../src/content_bridge.cljs) - Message bridge between page and background
- [src/background_actions/repl_fs_actions.cljs](../../src/background_actions/repl_fs_actions.cljs) - FS save action
- [src/panel_actions.cljs](../../src/panel_actions.cljs) - Panel save action
- [src/popup.cljs](../../src/popup.cljs) - Origins UI (lines 735-755)
- [src/popup_utils.cljs](../../src/popup_utils.cljs) - Origin validation (lines 55-80)
- [config/prod.edn](../../config/prod.edn) - Default allowed origins

### Userscript Source
- [extension/userscripts/epupp/gist_installer.cljs](../../extension/userscripts/epupp/gist_installer.cljs) - Current installer script

### Test Files
- [e2e/userscript_test.cljs](../../e2e/userscript_test.cljs) - Gist installer E2E tests
- [test-data/pages/mock-gist.html](../../test-data/pages/mock-gist.html) - Mock page for E2E

---

## Checklist

### Phase 1: Storage Schema Extension

#### 1.1 Add :script/source to storage schema
Location: `src/storage.cljs`

Add optional `:script/source` field support to `save-script!`. Field should be preserved if provided, ignored if not.

- [ ] addressed in code
- [ ] verified by tests

#### 1.2 Unit tests for source metadata
Location: `test/storage_test.cljs`

Test that:
- Scripts can be saved with `:script/source`
- Source is preserved on script updates
- Source is omitted when not provided (no crash)

- [ ] addressed in code
- [ ] verified by tests

#### 1.3 Update callers to pass source
Location: Multiple files

| Caller | File | Change |
|--------|------|--------|
| Panel save | `src/panel_actions.cljs` | Add `:script/source :source/panel` |
| REPL FS save | `src/background_actions/repl_fs_actions.cljs` | Add `:script/source :source/repl` |
| Built-in sync | `src/storage.cljs` (sync-builtins!) | Add `:script/source :source/built-in` |

- [ ] addressed in code
- [ ] verified by tests

---

### Phase 2: Installer Script Refactor

#### 2.1 Rename script file and update manifest
Location: `extension/userscripts/epupp/gist_installer.cljs` → `web_userscript_installer.cljs`

- Rename file
- Update `:epupp/script-name` to `"epupp/web_userscript_installer.cljs"`
- Update namespace to `epupp.web-userscript-installer`
- Keep existing auto-run-match (will be extended by user settings)

- [ ] addressed in code
- [ ] verified by tests

#### 2.2 Update bundled builtins reference
Location: `src/storage.cljs`

Update `bundled-builtins` to reference new filename and path.

- [ ] addressed in code
- [ ] verified by tests

#### 2.3 Refactor installer to send code via message
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Replace URL-fetch approach with direct code extraction:
1. Extract code content from DOM element (already works via `get-gist-file-text`)
2. Get page URL as source: `js/window.location.href`
3. Send message: `{type: "save-script", code: codeText, source: pageUrl}`

The background receives this and routes to `storage/save-script!`.

- [ ] addressed in code
- [ ] verified by tests

#### 2.4 Add save-script message handler
Location: `src/content_bridge.cljs`, `src/background.cljs`

Add handling for the new `save-script` message type:
- Content bridge: forward to background (similar to `install-userscript`)
- Background: extract code/source, call `storage/save-script!` with `:script/source`

- [ ] addressed in code
- [ ] verified by tests

#### 2.5 Remove fetch-based install infrastructure
Location: `src/background.cljs`

Remove:
- `install-userscript!` function
- `url-origin-allowed?` check
- `allowed-script-origins` function
- `fetch-text!` for script installation (if only used here)
- `:msg/ax.install-userscript` action handler
- `:userscript/fx.install` effect
- `install-userscript` message handling in content-bridge

- [ ] addressed in code
- [ ] verified by tests

---

### Phase 3: Origins Setting Repurpose

#### 3.1 Rename and repurpose UI labels
Location: `src/popup.cljs`

Change:
- "Allowed Userscript-install Base URLs" → "Web Installer Sites"
- Description text to explain the new purpose
- Format hint to show glob pattern format

- [x] addressed in code
- [x] verified by tests

#### 3.2 Change validation to glob patterns
Location: `src/popup_utils.cljs`

Update `valid-origin?` to validate glob patterns instead of URL prefixes:
- Must start with `http://` or `https://`
- Must contain `*` (it's a glob pattern)
- Or be a complete URL (for exact match)

- [x] addressed in code
- [x] verified by tests

#### 3.3 Update installer registration on pattern change
Location: `src/popup.cljs` or `src/background.cljs`

When user adds/removes a pattern:
1. Merge user patterns with installer's manifest patterns
2. Update installer script's `:script/match` in storage
3. Trigger `registration/update-early-registrations!`

This reuses existing registration infrastructure - no special injection mechanism needed.

- [x] addressed in code
- [x] verified by tests

#### 3.4 Update config defaults
Location: `config/*.edn`

Rename `allowedScriptOrigins` to `installerSitePatterns` and update values to glob patterns:
```edn
:installerSitePatterns ["https://gist.github.com/*"
                        "https://gitlab.com/*"
                        "https://codeberg.org/*"]
```

Remove raw.githubusercontent.com (no longer fetching, just where installer runs).

Also update all references to `allowedScriptOrigins` in:
- `src/popup.cljs`
- `src/storage.cljs`
- Any tests

- [x] addressed in code
- [x] verified by tests

---

### Phase 4: E2E Test Updates

#### 4.1 Update mock gist page
Location: `test-data/pages/mock-gist.html`

Ensure mock page works with new DOM-based extraction.

- [x] addressed in code
- [x] verified by tests

#### 4.2 Update existing E2E tests
Location: `e2e/userscript_test.cljs`

Update tests to:
- Reference new script name
- Test DOM-based installation
- Remove URL-fetch-related assertions

- [x] addressed in code
- [ ] verified by tests

#### 4.3 Add E2E test for user pattern extension
Location: `e2e/settings_test.cljs` or `e2e/userscript_test.cljs`

Test that adding a custom pattern makes installer run on a new page.

- [x] addressed in code
- [ ] verified by tests

---

### Phase 5: Documentation

#### 5.1 Update user guide
Location: `docs/user-guide.md`

Update:
- Settings section to explain "Web Installer Sites"
- Gist Installer references → Web Userscript Installer
- Any URL-fetching references removed

- [x] addressed in code
- [x] verified by tests

#### 5.2 Update architecture docs
Location: `dev/docs/` as appropriate

Document the new `:script/source` field and its semantics.

- [x] addressed in code
- [x] verified by tests

---

## Batch Execution Order

**Batch A: Schema Extension + Source Tracking**
1. Run testrunner baseline
2. Add `:script/source` support to storage
3. Add unit tests for source metadata
4. Update all callers to pass source (panel, REPL, built-in)
5. Run testrunner verification

**Batch B: Script Refactor**
1. Run testrunner baseline
2. Rename script file and update references
3. Refactor installer to extract DOM content and send `save-script` message
4. Add `save-script` message handler in content-bridge and background
5. Remove fetch-based install infrastructure
6. Run testrunner verification

**Batch C: Origins Repurpose**
1. Run testrunner baseline
2. Rename config key and update UI labels
3. Update validation to glob patterns
4. Implement pattern merge + re-registration on change
5. Update config defaults
6. Run testrunner verification

**Batch D: E2E Updates**
1. Run testrunner baseline
2. Update mock page and existing tests
3. Add new E2E for pattern extension
4. Run testrunner verification

**Batch E: Documentation**
1. Update user guide
2. Update architecture docs
3. Final review

---

## Open Questions

### Q1: How to inject user patterns into installer script?

**Context:** The installer script runs via content script registration. Its `auto-run-match` is set when the script is registered. User-added patterns need to be merged into this registration.

**Current registration flow:**
```
storage/sync-builtins! → registration/update-early-registrations! → registerContentScripts
```

**Solution:** When user adds/removes patterns in Settings, trigger re-registration of the installer with merged patterns:
- Manifest patterns + user patterns = final `auto-run-match`
- Store merged patterns on the installer script's `:script/match`
- Registration system picks up the updated match

This approach:
- Uses existing registration infrastructure
- No global variable injection needed
- User pattern changes take effect after page refresh (acceptable UX)

**Implementation:** Add a handler for pattern changes that updates the installer script's match field and triggers re-registration.

Options for old Q1 (now resolved):
- ~~A) Global variable injection~~ - Not needed
- ~~B) Message-based query~~ - Not needed
- **C) Content script registration with dynamic patterns** - YES, this is the path

### Q2: Should we support multiple code block formats?

Current: Gist-specific selectors (`.js-file-line`)

Options:
- Keep tight coupling to known sites (gist, gitlab, codeberg specific selectors)
- Generic `<pre><code>` detection with manifest sniffing
- Configurable selectors per-site

**Recommendation:** Start with known sites. Add generic detection as enhancement later.

### Q3: Backward compatibility for existing saved origins?

**Not needed.** Userscripts feature not yet released - we have a clean slate. No migration code required.

### Q4: Should config key be renamed?

Current: `allowedScriptOrigins`
Semantics change: controls where installer runs, not fetch origins

Options:
- Keep `allowedScriptOrigins` (minimal change, slightly misleading name)
- Rename to `installerSitePatterns` (clearer, more churn)

**Recommendation:** Rename to `installerSitePatterns` - clearer intent, and no backward compat needed.

---

## Success Criteria

- [ ] Web Userscript Installer runs on gist.github.com (existing behavior)
- [ ] User can add custom site patterns via Settings
- [ ] Installer uses DOM content, not URL fetch
- [ ] Scripts installed via web installer have `:script/source` set to page URL
- [ ] Scripts saved from panel have `:script/source` set to `:source/panel`
- [ ] Scripts saved from REPL have `:script/source` set to `:source/repl`
- [ ] Built-in scripts have `:script/source` set to `:source/built-in`
- [ ] All unit tests pass
- [ ] All E2E tests pass
- [ ] Zero lint warnings
- [ ] Documentation updated

---

## Original Plan-producing Prompt

So I did have a hunch that it was about the gist installer. I guess the real fix for my confusion had been that the UI clearly referenced that. But before we go there... The reason I got curious is that the gist installer script already restricts where it is active, and the nature of the script currently makes that the ultimate origin check. Further, I am actually considering expanding the auto-run-match for the script. And changing how it works a bit. Something like:

0. Renaming the script: "Web Userscript Installer" (filename `epupp/web_userscript_installer.cljs`)
1. The origins setting rather is about auto-run-match for this script (since it is read-only for the user). The final auto-run-match for this script would be whatever is in its manifest, plus the users additions.
2. The script uses epupp.fs to install the content of the element that has the identified script, rather than downloading from a url.

**Refinement 1:** For provenance tracking, expand storage metadata with `:script/source` - either `:source/panel`, `:source/repl`, `:source/built-in`, or a URL for web-installed scripts.

**Refinement 2:** No backward compatibility needed - userscripts feature not released yet.

**Refinement 3:** Storage is single source of truth. Callers pass `:script/source` to storage, storage preserves it. All save paths flow through `storage/save-script!`.

**Refinement 4:** Installer can't call `epupp.fs/save!` directly (runs in page context). Uses message to background which routes to storage.

**Refinement 5:** Rename config key `allowedScriptOrigins` → `installerSitePatterns` for clarity.
