# Web Userscript Installer Improvements Plan

Follow-up improvements to the Web Userscript Installer after the initial refactor.

## Current State (2026-02-01)

The Web Userscript Installer has been refactored from the original "Gist Installer":

**Completed:**
- Renamed to `epupp/web_userscript_installer.cljs`
- DOM-based code extraction (no URL fetching - WYSIWYG security)
- Provenance tracking via `:script/source` metadata
- Message-based installation through content bridge
- Removed origins infrastructure (no Settings UI for patterns)
- Multi-format detection: GitHub tables and generic `<pre>` elements
- E2E test coverage for both formats

**Reference:**
- [web_userscript_installer.cljs](../../extension/userscripts/epupp/web_userscript_installer.cljs)
- [Archived plan](archive/web-userscript-installer-plan.md)
- [GitHub and GitLab page research](github-gitlab-page-research.md) - DOM structures, detection patterns, and button placement strategies

**Key functions:**
| Function | Purpose |
|----------|---------|
| `detect-all-code-blocks` | Finds code blocks using multiple detection strategies |
| `detect-github-tables` | GitHub gist table-based code blocks |
| `detect-pre-elements` | Generic `<pre>` element detection |
| `attach-button-to-block!` | Inserts button into DOM |
| `render-install-button` | Replicant hiccup for button |
| `render-modal` | Confirmation dialog |
| `process-code-block!+` | Orchestrates manifest detection and button creation |

**State atom:** `!state` with `:blocks` vector, `:modal` map, `:installed-scripts` map

---

## Standard

- All edits delegated to **Clojure-editor subagent**
- Before each batch: delegate to **epupp-testrunner subagent** for baseline
- After each batch: delegate to **epupp-testrunner subagent** for verification
- E2E tests delegated to **epupp-e2e-expert subagent**
- Tick checkboxes without inserting commentary blocks

---

## Known Issues

### 1. Installed Status Not Detected on Initial Scan ✅

**Problem:** Button always renders as "Install" on page load, even when script is already installed. The `fetch-installed-scripts!+` call happens async after the first scan, but the re-scan doesn't update existing button states properly.

**Expected:** Button should show "Installed" or "Update" based on comparison with stored script code.

**Solution:** Added `update-existing-blocks-with-installed-scripts!+` function that updates block states directly instead of re-scanning (which filtered out already-processed blocks). Called from `init!` after installed scripts are fetched.

- [x] Investigate state update flow
- [x] Fix re-render after installed scripts arrive
- [x] Add E2E test verifying correct initial state

### 2. GitLab Button Placement Disrupts Layout ✅

**Problem:** On GitLab snippets, inserting button before `<pre>` messes with the file layout. GitLab has a `.file-actions` container we could use.

**Research:** See [github-gitlab-page-research.md](github-gitlab-page-research.md#gitlab-snippet)

**Solution:** Detect GitLab-specific structure and place button in proper container like we do for GitHub. ✅ IMPLEMENTED

**Implementation:**
- `detect-gitlab-snippets` finds `.file-holder` elements ✅
- `get-gitlab-snippet-text` extracts code from nested pre ✅
- Generic pre detection excludes elements inside `.file-holder` ✅
- Button placed in `.file-actions` container with GitLab classes (`btn btn-default btn-sm`) ✅
- E2E test verifies correct placement and installation ✅

- [x] Add `:gitlab-snippet` format detection
- [x] Implement Vue mount detection
- [x] Place GitLab buttons in `.file-title-flex-parent` or `.file-actions`
- [x] Add E2E test with GitLab-style mock block

### 3. Epupp Icon Shows Generic "E" ✅

**Problem:** The button displays a generic "E" icon instead of the actual Epupp icon.

**Solution:** Inline SVG extracted from `extension/icons/icon.svg`. Self-contained in the script, no network requests needed.

- [x] Choose icon source approach
- [x] Update button component to use real icon
- [x] Verify icon displays correctly

---

## Enhancements

### 4. Button State Display ✅

**Context:** Button should reflect whether script is installed, needs update, or is fresh.

| State | Button Text | Enabled? | Style | Tooltip |
|-------|-------------|----------|-------|--------|
| `:install` | "Install" | Yes | Primary (green) | "Install to Epupp" |
| `:update` | "Update" | Yes | Warning (amber) | "Update existing Epupp script" |
| `:installed` | "Installed" | No | Muted (gray) | "Already installed in Epupp (identical)" |
| `:installing` | "Installing..." | No | Primary | - |
| `:error` | "Failed" | No | Error (red) | Error message |

**Code comparison:** Simple string equality. If user reformats, they probably want to update anyway.

**Solution:** Added `title` attribute to `render-install-button` with state-specific tooltips. Error state includes the actual error message in the tooltip.

- [x] Verify current state rendering matches table
- [x] Add tooltips via `title` attribute
- [x] Test all states visually

### 5. Dialog Improvements ✅

**Current:** Dialog always shows "Install Userscript"

**Improvements:**
- Title: "Install Userscript" vs "Update Userscript" based on state
- Confirm button: "Install" vs "Update"
- For updates: show "This will update the existing script"
- Error dialog: show actual error message from manifest parsing or storage layer

**Solution:** Already implemented - `modal-title`, `modal-description`, and confirm button text all vary based on `:update?` state. Error handling shows inline error messages in the modal.

- [x] Update modal title for install vs update
- [x] Update confirm button text
- [x] Add error dialog for failed installs
- [x] Parse and display helpful error messages

### 6. Support GitHub Repo Code (Not Just Gists)

**Context:** GitHub displays code differently in repos vs gists. Repo files use React-based rendering.

**Research:** See [github-gitlab-page-research.md](github-gitlab-page-research.md#github-repo-file)

| Context | DOM Structure | Detection | Notes |
|---------|---------------|-----------|-------|
| Gist | `.blob-wrapper.data` in traditional DOM | `meta[name="hostname"]="gist.github.com"` | Current implementation |
| Repo blob | React-rendered `[data-ssr-id="react-code-view"]` | `meta[name="route-pattern"]` contains `/blob/` | Needs React hydration wait |
| Repo search results | Different, possibly excerpts | Skip | Not in scope |

**Implementation notes:**
- Need to wait for React to hydrate (MutationObserver or polling)
- Look for `[data-ssr-id="react-code-view"]` container
- Button placement may be in Primer `Button-group` or toolbar
- File type detection: check file extension in URL or component props

- [ ] Implement React hydration detection
- [ ] Add `detect-github-repo-file` function
- [ ] Handle button placement in React component structure
- [ ] Add E2E test with GitHub repo file mock

### 7. Support GitLab Repo Code (Not Just Snippets)

**Context:** GitLab displays code differently in repos vs snippets. Both use Vue-based rendering with GraphQL data.

**Research:** See [github-gitlab-page-research.md](github-gitlab-page-research.md#gitlab-repo-file)

| Context | DOM Structure | Detection | Notes |
|---------|---------------|-----------|-------|
| Snippet | Vue-rendered with GraphQL | `body[data-page="snippets:show"]` | Current implementation |
| Repo blob | Vue + Monaco editor | URL contains `/blob/`, likely `data-page="projects:blob:show"` | Needs verification |

**Example to check:** https://gitlab.com/gitlab-org/gitlab/-/blob/master/README.md
**Example to test with userscript:** https://gitlab.com/pappapez/userscripts-test/-/blob/main/pez/gitlab_repo_test_us.cljs

**Implementation notes:**
- Need to wait for Vue to mount
- May use Monaco editor (`.monaco-editor`) for editable views
- Look for `.file-holder`, `.blob-viewer` containers
- Button placement likely in `.file-title-flex-parent` or `.file-actions`

- [ ] Verify DOM structure with actual GitLab repo file
- [ ] Implement Vue mount detection for repo files
- [ ] Add `detect-gitlab-repo-file` function
- [ ] Handle button placement in Vue component structure
- [ ] Add E2E test with GitLab repo file mock

### 8. Improve Generic Pre/Textarea Detection ✅

**Context:** Before adding site-specific repo support, improve generic detection to handle more cases.

**Pre elements with child elements:**
- Current `detect-pre-elements` uses `textContent` which handles nested elements
- Verified it works with `<pre><code>...</code></pre>` and `<pre><span>...</span></pre>` patterns
- Syntax-highlighted code where each token is wrapped in spans works automatically via `textContent`

**Textarea elements:**
- Some pages display code in `<textarea>` elements (e.g., raw file views, code editors)
- Extract text from textarea `.value` property
- Place button before textarea (generic placement)

Example raw view: https://github.com/PEZ/browser-jack-in/blob/userscripts/test-data/tampers/repl_manifest.cljs

- [x] Verify `detect-pre-elements` handles nested child elements correctly
- [x] Add `detect-textarea-elements` function
- [x] Extract text from textarea value
- [x] Place button before textarea (generic placement)
- [x] Add mock blocks to test page (pre with children, textarea)

### 9. Epupp-Branded Button Styling

**Context:** Button should match Epupp UI styling.

Reference: `build/components.css` button styles

- [ ] Match border radius, font, padding from design tokens
- [ ] Match color palette
- [ ] Test visual consistency with extension popup

---

## Implementation Batches

### Batch A: Bug Fixes (Critical) ✅
1. ✅ Run testrunner baseline
2. ✅ Fix installed status detection (#1)
3. ✅ Fix GitLab button placement (#2)
4. ✅ Fix Epupp icon (#3)
5. ✅ Run testrunner verification

### Batch B: UX Improvements ✅
1. ✅ Run testrunner baseline
2. ✅ Verify button state display (#4)
3. ✅ Dialog improvements (#5)
4. Epupp-branded styling (#9) - deferred, current styling acceptable
5. ✅ Run testrunner verification

### Batch C: Generic Format Detection (Foundation) ✅
1. ✅ Run testrunner baseline
2. ✅ Improve generic pre/textarea detection (#8)
   - Verify pre with nested children works
   - Add textarea element support
3. ✅ Run testrunner verification

**Rationale:** Solid generic detection provides fallback for any page. Site-specific detection (Batch D) can then override with better button placement.

### Batch D: Site-Specific Repo Support
1. Run testrunner baseline
2. Support GitHub repo code (#6)
3. Support GitLab repo code (#7)
4. Run testrunner verification

**Rationale:** After generic detection works, add site-specific handling for better UX on GitHub/GitLab repos (proper button placement in toolbars, framework timing).

---

## Test Pages

**Manual testing:**
- GitLab snippet: https://gitlab.com/-/snippets/4922251
- GitHub gist: https://gist.github.com/PEZ/9d2a9eec14998de59dde93979453247e
- GitHub repo file: (need example with manifest)
- GitLab repo file: https://gitlab.com/pappapez/userscripts-test/-/blob/main/pez/gitlab_repo_test_us.cljs

**E2E mock page:** `test-data/pages/mock-gist.html`

---

## Success Criteria

- [ ] Installer finds manifests in any `<pre>` or `<pre><code>` block
- [ ] Button appears above the code block it pertains to
- [ ] Button shows correct state: Install/Update/Installed
- [ ] Installed state shows disabled button
- [ ] Running installer twice doesn't create duplicate buttons
- [ ] Button has Epupp icon and matches extension styling
- [ ] Tooltips explain the action
- [ ] Dialog title reflects install vs update
- [ ] Dialog confirm button says "Install" or "Update" appropriately
- [ ] Error dialog shows when install fails with helpful error message
- [ ] GitLab buttons placed in proper container (not disrupting layout)
- [ ] All unit tests pass
- [ ] All E2E tests pass
- [ ] Zero lint warnings

---

## Original Plan-Producing Prompt

Create a consolidated plan for Web Userscript Installer improvements. Include:

1. Known bugs:
   - Installed status not detected on initial scan (always shows "Install")
   - GitLab button placement disrupts layout (needs `.file-actions` placement)
   - Epupp icon shows generic "E" instead of real icon

2. UX improvements:
   - Button state display (Install/Update/Installed) with tooltips
   - Dialog aware of install vs update
   - Error dialog for failed installs
   - Epupp-branded styling

3. Format support expansion:
   - GitHub code in repos (not just gists)
   - GitLab code in repos (not just snippets)
   - Textarea elements

Include success criteria and batch execution order with testrunner delegation.
