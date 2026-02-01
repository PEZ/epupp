# Web Userscript Installer Refactor Plan

Redesign the Gist Installer to become a general-purpose "Web Userscript Installer" that:
1. Extracts code directly from DOM elements (no URL fetching)
2. Works on ANY page via manual execution (play button)
3. Optionally auto-runs on all pages when user enables it
4. Tracks script provenance via new `:script/source` metadata

## Background

### Current Model
```
Gist page (hardcoded match) → Extract manifest → Fetch code from raw URL → Validate URL origin → Save
```

**Problems:**
- URL-fetch approach has TOCTOU security concerns
- "Allowed Userscript-install Base URLs" setting is confusing
- Complex origin validation logic
- Limited to pre-configured sites

### Proposed Model (Simplified)
```
Any page (manual run or auto-run when enabled) → Extract code from DOM → Send message → Storage saves with source
```

**Benefits:**
- **WYSIWYG security** - Code you see is code you install (no TOCTOU via URL fetch)
- **Works everywhere** - Manual play button works on any page
- **Simple opt-in** - User enables script to auto-run on all pages
- **Much less code** - No URL fetching, no origin validation, no Settings UI for patterns
- **Consolidated logic** - Storage is single source of truth for all save operations

### No backward compatibility

The userscripts feature is not released yet. Adding any backward compatibility to the code will just be a burden. We're all about forward compatibility at this stage.

## Design Decisions

### Simplified Auto-Run Strategy

| Aspect | Design |
|--------|--------|
| Auto-run match | `"*"` (all URLs) |
| Default state | Disabled |
| Manual execution | Always available via popup play button |
| Auto-run behavior | User enables script to auto-run everywhere |

**No Settings UI for patterns.** Users control behavior entirely via the script's enable/disable toggle:
- **Disabled (default):** Run manually via play button on any page
- **Enabled:** Auto-runs on every page navigation

This eliminates the need for origin pattern management, merging logic, and related UI.

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
- [src/background.cljs](../../src/background.cljs) - `install-userscript!` function (to be removed)
- [src/content_bridge.cljs](../../src/content_bridge.cljs) - Message bridge between page and background
- [src/background_actions/repl_fs_actions.cljs](../../src/background_actions/repl_fs_actions.cljs) - FS save action
- [src/panel_actions.cljs](../../src/panel_actions.cljs) - Panel save action
- [src/popup.cljs](../../src/popup.cljs) - Origins UI (TO BE REMOVED)
- [src/popup_actions.cljs](../../src/popup_actions.cljs) - Origin actions (TO BE REMOVED)

### Userscript Source
- [extension/userscripts/epupp/web_userscript_installer.cljs](../../extension/userscripts/epupp/web_userscript_installer.cljs) - Installer script

### Test Files
- [e2e/userscript_test.cljs](../../e2e/userscript_test.cljs) - Installer E2E tests
- [test-data/pages/mock-gist.html](../../test-data/pages/mock-gist.html) - Mock page for E2E

---

## Checklist

### Phase 1: Storage Schema Extension

#### 1.1 Add :script/source to storage schema
Location: `src/storage.cljs`

Add optional `:script/source` field support to `save-script!`. Field should be preserved if provided, ignored if not.

- [x] addressed in code
- [x] verified by tests

#### 1.2 Unit tests for source metadata
Location: `test/storage_test.cljs`

Test that:
- Scripts can be saved with `:script/source`
- Source is preserved on script updates
- Source is omitted when not provided (no crash)

- [x] addressed in code
- [x] verified by tests

#### 1.3 Update callers to pass source
Location: Multiple files

| Caller | File | Change |
|--------|------|--------|
| Panel save | `src/panel_actions.cljs` | Add `:script/source :source/panel` |
| REPL FS save | `src/background_actions/repl_fs_actions.cljs` | Add `:script/source :source/repl` |
| Built-in sync | `src/storage.cljs` (sync-builtins!) | Add `:script/source :source/built-in` |

- [x] addressed in code
- [x] verified by tests

---

### Phase 2: Installer Script Refactor

#### 2.1 Update installer manifest for universal match
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Change manifest:
- `:epupp/auto-run-match` → `"*"` (matches all URLs)
- Script remains `document-idle` timing (default)
- Script is disabled by default (storage handles this)

- [x] addressed in code
- [x] verified by tests

#### 2.2 Refactor installer to send code via message
Location: `extension/userscripts/epupp/web_userscript_installer.cljs`

Replace URL-fetch approach with direct code extraction:
1. Extract code content from DOM element (already works via `get-gist-file-text`)
2. Get page URL as source: `js/window.location.href`
3. Send message: `{type: "save-script", code: codeText, source: pageUrl}`

The background receives this and routes to `storage/save-script!`.

- [x] addressed in code
- [x] verified by tests

#### 2.3 Add save-script message handler
Location: `src/content_bridge.cljs`, `src/background.cljs`

Add handling for the new `save-script` message type:
- Content bridge: forward to background (similar to `install-userscript`)
- Background: extract code/source, call `storage/save-script!` with `:script/source`

- [x] addressed in code
- [x] verified by tests

#### 2.4 Remove fetch-based install infrastructure
Location: `src/background.cljs`

Remove:
- `install-userscript!` function
- `url-origin-allowed?` check
- `allowed-script-origins` function
- `fetch-text!` for script installation (if only used here)
- `:msg/ax.install-userscript` action handler
- `:userscript/fx.install` effect
- `install-userscript` message handling in content-bridge

- [x] addressed in code
- [x] verified by tests

---

### Phase 3: Remove Origins Infrastructure (NEW - Simplification)

#### 3.1 Remove Settings UI for user origins
Location: `src/popup.cljs`

Remove:
- "Web Installer Sites" section from Settings
- Add/remove origin form components
- Related render functions

- [x] addressed in code
- [x] verified by tests

#### 3.2 Remove origin-related state and actions
Location: `src/popup.cljs`, `src/popup_actions.cljs`

Remove:
- `:settings/user-origins` state key
- `:settings/new-origin` state key
- `:popup/ax.add-origin` action
- `:popup/ax.remove-origin` action
- `:popup/fx.add-user-origin` effect
- `:popup/fx.remove-user-origin` effect
- `:popup/fx.update-installer-patterns` effect
- `:popup/fx.load-user-origins` effect

- [x] addressed in code
- [x] verified by tests

#### 3.3 Remove origin storage
Location: `src/storage.cljs`, `src/background.cljs`

Remove:
- `userAllowedOrigins` from storage schema
- `handle-update-installer-patterns` message handler
- Any origin-related storage watchers

- [x] addressed in code
- [x] verified by tests

#### 3.4 Remove origin validation utilities
Location: `src/popup_utils.cljs`

Remove:
- `valid-origin?` function (or repurpose if used elsewhere)
- Pattern validation logic

- [x] addressed in code
- [x] verified by tests

#### 3.5 Remove config defaults for origins
Location: `config/*.edn`

Remove:
- `allowedScriptOrigins` / `installerSitePatterns` config key
- Any related configuration

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
- Remove pattern-extension test (no longer applicable)

- [x] addressed in code
- [x] verified by tests

#### 4.3 Remove/update pattern extension tests
Location: `e2e/userscript_test.cljs`, `e2e/settings_test.cljs`

Remove tests for:
- Adding custom patterns via Settings
- Pattern merging behavior
- Installer running on user-added patterns

- [x] addressed in code
- [x] verified by tests

---

### Phase 5: Documentation

#### 5.1 Update user guide
Location: `docs/user-guide.md`

Update:
- Remove "Web Installer Sites" settings documentation
- Explain manual execution via play button
- Explain optional auto-run when enabled
- Gist Installer references → Web Userscript Installer

- [x] addressed in code
- [x] verified by tests

#### 5.2 Update architecture docs
Location: `dev/docs/` as appropriate

Document:
- `:script/source` field and its semantics
- Simplified installer model (manual-first, optional auto-run)

- [x] addressed in code
- [x] verified by tests

---

## Batch Execution Order

**Batch A: Schema Extension + Source Tracking** (DONE)
1. Run testrunner baseline
2. Add `:script/source` support to storage
3. Add unit tests for source metadata
4. Update all callers to pass source (panel, REPL, built-in)
5. Run testrunner verification

**Batch B: Script Refactor** (MOSTLY DONE)
1. Run testrunner baseline
2. Refactor installer to extract DOM content and send `save-script` message
3. Add `save-script` message handler in content-bridge and background
4. Remove fetch-based install infrastructure
5. Update manifest to `"*"` auto-run-match
6. Run testrunner verification

**Batch C: Remove Origins Infrastructure** (NEW)
1. Run testrunner baseline
2. Remove Settings UI for user origins
3. Remove origin-related actions and effects
4. Remove origin storage and handlers
5. Remove config defaults
6. Run testrunner verification

**Batch D: E2E Test Cleanup**
1. Run testrunner baseline
2. Remove pattern extension tests
3. Update remaining E2E tests
4. Run testrunner verification

**Batch E: Documentation**
1. Update user guide
2. Update architecture docs
3. Final review

---

## Open Questions

### Q1: Code block detection strategy

Current: Gist-specific selectors (`.js-file-line`)

Options:
- Keep tight coupling to known sites (gist, gitlab, codeberg specific selectors)
- Generic `<pre><code>` detection with manifest sniffing
- Site-specific detection with fallback to generic

**Status:** Current implementation detects gist-like structures. Works well enough for manual execution - user navigates to a page with code blocks and runs the installer.

### Q2: Auto-run performance on all pages

With `"*"` auto-run-match and script enabled, installer runs on every page.

**Mitigations:**
- Script is disabled by default
- DOM scanning is fast (querySelector-based)
- No network requests in scanning phase
- Install buttons only added when manifests found

**Status:** Acceptable. Users who enable it want this behavior.

---

## Success Criteria

- [ ] Web Userscript Installer has `"*"` auto-run-match
- [ ] Installer is disabled by default
- [ ] User can run installer manually via play button on any page
- [ ] User can enable installer for auto-run on all pages
- [ ] Installer uses DOM content, not URL fetch
- [ ] Scripts installed via web installer have `:script/source` set to page URL
- [ ] Scripts saved from panel have `:script/source` set to `:source/panel`
- [ ] Scripts saved from REPL have `:script/source` set to `:source/repl`
- [ ] Built-in scripts have `:script/source` set to `:source/built-in`
- [ ] No "Web Installer Sites" Settings UI (removed)
- [ ] No origin storage or pattern merging code
- [ ] All unit tests pass
- [ ] All E2E tests pass
- [ ] Zero lint warnings
- [ ] Documentation updated

---

## Original Plan-producing Prompt

Redesign the Gist Installer to become a general-purpose "Web Userscript Installer" that extracts code directly from DOM elements (no URL fetching) and tracks script provenance via `:script/source` metadata.

**Key design decisions:**

1. **Rename script:** `epupp/web_userscript_installer.cljs`
2. **DOM-based extraction:** WYSIWYG security - code you see is code you install
3. **Provenance tracking:** `:script/source` field tracks origin (`:source/panel`, `:source/repl`, `:source/built-in`, or URL string)
4. **Storage as single source of truth:** All save paths flow through `storage/save-script!`
5. **Message-based install:** Installer sends `save-script` message through content bridge

**Simplification refinement:**

Remove the "user origins" complexity entirely:
- Set auto-run-match to `"*"` (all URLs)
- Disable by default
- User runs manually via play button (works on any page)
- OR user enables it for auto-run everywhere

This eliminates:
- Settings UI for managing origin patterns
- `userAllowedOrigins` storage
- Pattern merging logic
- Related effects and actions
- Configuration for default origins

**No backward compatibility needed** - userscripts feature not yet released.
