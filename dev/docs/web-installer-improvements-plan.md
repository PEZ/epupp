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

### 1. Installed Status Not Detected on Initial Scan

**Problem:** Button always renders as "Install" on page load, even when script is already installed. The `fetch-installed-scripts!+` call happens async after the first scan, but the re-scan doesn't update existing button states properly.

**Expected:** Button should show "Installed" or "Update" based on comparison with stored script code.

- [ ] Investigate state update flow
- [ ] Fix re-render after installed scripts arrive
- [ ] Add E2E test verifying correct initial state

### 2. GitLab Button Placement Disrupts Layout

**Problem:** On GitLab snippets, inserting button before `<pre>` messes with the file layout. GitLab has a `.file-actions` container we could use.

**Research:** See [github-gitlab-page-research.md](github-gitlab-page-research.md#gitlab-snippet)

**Current behavior:** Generic `<pre>` detection inserts button before the element
**GitLab structure:** Vue-rendered with `.file-title-flex-parent` or `.file-actions` containers

**Solution:** Detect GitLab-specific structure and place button in proper container like we do for GitHub.

**Implementation approach:**
- Wait for Vue to mount (check for `.js-snippets-note-edit-form-holder`)
- Look for `.file-title-flex-parent` or `.file-actions`
- Insert button using GitLab button classes: `btn`, `btn-default`

- [ ] Add `:gitlab-snippet` format detection
- [ ] Implement Vue mount detection
- [ ] Place GitLab buttons in `.file-title-flex-parent` or `.file-actions`
- [ ] Add E2E test with GitLab-style mock block

### 3. Epupp Icon Shows Generic "E"

**Problem:** The button displays a generic "E" icon instead of the actual Epupp icon.

**Solution Options:**
- Inline SVG in the script (self-contained)
- Base64 encoded data URI
- Fetch from extension via content bridge (`chrome.runtime.getURL()`)

**Recommendation:** Inline SVG for self-contained script.

- [ ] Choose icon source approach
- [ ] Update button component to use real icon
- [ ] Verify icon displays correctly

---

## Enhancements

### 4. Button State Display

**Context:** Button should reflect whether script is installed, needs update, or is fresh.

| State | Button Text | Enabled? | Style | Tooltip |
|-------|-------------|----------|-------|---------|
| `:install` | "Install" | Yes | Primary (green) | "Install to Epupp" |
| `:update` | "Update" | Yes | Warning (amber) | "Update existing Epupp script" |
| `:installed` | "Installed" | No | Muted (gray) | "Already installed in Epupp (identical)" |
| `:installing` | "Installing..." | No | Primary | - |
| `:error` | "Failed" | No | Error (red) | Error message |

**Code comparison:** Simple string equality. If user reformats, they probably want to update anyway.

- [ ] Verify current state rendering matches table
- [ ] Add tooltips via `title` attribute
- [ ] Test all states visually

### 5. Dialog Improvements

**Current:** Dialog always shows "Install Userscript"

**Improvements:**
- Title: "Install Userscript" vs "Update Userscript" based on state
- Confirm button: "Install" vs "Update"
- For updates: show "This will update the existing script"
- Error dialog: show actual error message from manifest parsing or storage layer

- [ ] Update modal title for install vs update
- [ ] Update confirm button text
- [ ] Add error dialog for failed installs
- [ ] Parse and display helpful error messages

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

**Example to test:** https://gitlab.com/gitlab-org/gitlab/-/blob/master/README.md

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

### 8. Support Textarea Elements

**Context:** Some pages display code in `<textarea>` elements (e.g., GitHub raw file views).

Example: https://github.com/PEZ/browser-jack-in/blob/userscripts/test-data/tampers/repl_manifest.cljs

- [ ] Add `detect-textarea-elements` function
- [ ] Extract text from textarea value
- [ ] Place button before textarea (generic placement)
- [ ] Add mock block to test page

### 9. Epupp-Branded Button Styling

**Context:** Button should match Epupp UI styling.

Reference: `build/components.css` button styles

- [ ] Match border radius, font, padding from design tokens
- [ ] Match color palette
- [ ] Test visual consistency with extension popup

---

## Implementation Batches

### Batch A: Bug Fixes (Critical)
1. Run testrunner baseline
2. Fix installed status detection (#1)
3. Fix GitLab button placement (#2)
4. Fix Epupp icon (#3)
5. Run testrunner verification

### Batch B: UX Improvements
1. Run testrunner baseline
2. Verify button state display (#4)
3. Dialog improvements (#5)
4. Epupp-branded styling (#9)
5. Run testrunner verification

### Batch C: GitHub/GitLab Repo Support
1. Run testrunner baseline
2. Support GitHub repo code (#6)
3. Support GitLab repo code (#7)
4. Run testrunner verification

### Batch D: Expanded Format Support
1. Run testrunner baseline
2. Support textarea elements (#8)
3. Run testrunner verification

---

## Test Pages

**Manual testing:**
- GitLab snippet: https://gitlab.com/-/snippets/4922251
- GitHub gist: https://gist.github.com/PEZ/9d2a9eec14998de59dde93979453247e
- GitHub repo file: (need example with manifest)
- GitLab repo file: (need example with manifest)

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
