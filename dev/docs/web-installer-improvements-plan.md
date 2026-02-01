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

**Reference Implementation:**
- [web_userscript_installer.cljs](../../extension/userscripts/epupp/web_userscript_installer.cljs)
- [Archived plan](archive/web-userscript-installer-plan.md)
- [GitHub and GitLab page research](github-gitlab-page-research.md) - DOM structures, detection patterns, and button placement strategies

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

**Solution:** Source the icon from the extension assets. The content bridge can provide the icon URL via `chrome.runtime.getURL()`.

- [ ] Add message to fetch extension icon URL
- [ ] Update button component to use real icon
- [ ] Verify icon displays correctly

---

## Enhancements

### 4. Support GitHub Repo Code (Not Just Gists)

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

### 5. Support GitLab Repo Code (Not Just Snippets)

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

### 6. Support Textarea Elements

**Context:** Some pages display code in `<textarea>` elements (e.g., GitHub raw file views).

Example: https://github.com/PEZ/browser-jack-in/blob/userscripts/test-data/tampers/repl_manifest.cljs

- [ ] Add `detect-textarea-elements` function
- [ ] Extract text from textarea
- [ ] Place button before textarea (generic placement)
- [ ] Add mock block to test page

### 7. Generic Pre/Textarea Fallback Placement

**Context:** For pages that don't have GitHub/GitLab structure, we need generic button placement.

**Current:** Insert `<div>` before `<pre>` element
**Issue:** May disrupt layouts on some pages

**Options:**
- Insert before (current)
- Append after
- Float overlay
- Sticky corner button

- [ ] Test current placement on various sites
- [ ] Decide if changes needed
- [ ] Document supported placement strategies

---

## Implementation Batches

### Batch A: Bug Fixes (Critical)
1. Fix installed status detection (#1)
2. Fix GitLab button placement (#2)
3. Fix Epupp icon (#3)

### Batch B: GitHub/GitLab Full Support
4. Support GitHub repo code (#4)
5. Support GitLab repo code (#5)
6. Skip excerpts/summaries (#7)

### Batch C: Expanded Format Support
7. Support textarea elements (#6)

---

## Test Pages

**Manual testing:**
- GitLab snippet: https://gitlab.com/-/snippets/4922251
- GitHub gist: https://gist.github.com/PEZ/9d2a9eec14998de59dde93979453247e
- GitHub repo file: (need example with manifest)
- GitLab repo file: (need example with manifest)

**E2E mock page:** `test-data/pages/mock-gist.html`

---

## Original Plan-Producing Prompt

Create a follow-up plan for Web Userscript Installer improvements after the initial refactor. Include:

1. Known bugs:
   - Installed status not detected on initial scan (always shows "Install")
   - GitLab button placement disrupts layout (needs `.file-actions` placement)
   - Epupp icon shows generic "E" instead of real icon

2. Format support expansion:
   - GitHub code in repos (not just gists)
   - GitLab code in repos (not just snippets)
   - Textarea elements

3. Generic fallback placement for pre/textarea on unknown page structures

Archive the previous plan first; this is follow-up work.
