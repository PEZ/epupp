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

**Solution:** Detect GitLab-specific structure and place button last in `.file-actions` like we do for GitHub.

- [ ] Add `:gitlab-pre` format detection
- [ ] Place GitLab buttons in `.file-actions`
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

**Context:** GitHub displays code differently in repos vs gists. Need to handle both.

| Context | DOM Structure | Detection |
|---------|---------------|-----------|
| Gist | `table.js-file-line-container` | Current |
| Repo blob | `table.js-file-line-container` | Same? TBD |
| Repo search results | Different, possibly excerpts | Skip |

- [ ] Research GitHub repo code view DOM
- [ ] Add detection if different from gists

### 5. Support GitLab Repo Code (Not Just Snippets)

**Context:** GitLab displays code differently in repos vs snippets. Need to handle both.

Example: https://gitlab.com/intem-oss/combine-taglib/-/blob/develop/src/main/java/se/intem/web/taglib/combined/RequestPath.java?ref_type=heads

- [ ] Research GitLab repo code view DOM
- [ ] Add detection for GitLab repos

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
