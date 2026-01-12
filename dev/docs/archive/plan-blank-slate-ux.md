# Blank Slate UX Improvements Plan

**Created:** January 12, 2026
**Status:** ✅ Completed (January 12, 2026)

## Implementation Summary

Implemented contextual blank slate hints for empty UI states:

- **Connected Tabs** (popup): "Start the server (Step 1), then click Connect (Step 2)."
- **Matching Scripts** (popup): Differentiates "No userscripts yet!" (with DevTools guidance) vs "No scripts match" (with hostname-based URL pattern example)
- **Other Scripts** (popup): "Scripts that don't match this page appear here."
- **Panel Results**: Keyboard shortcut hint (Ctrl+Enter)

E2E tests added to verify hint content, not just visibility.

---

## Overview

Empty states ("blank slates") are golden opportunities to guide users, reduce confusion, and encourage engagement. Currently, Epupp has functional but minimal empty states. This plan proposes contextual, helpful hints that turn empty sections into onboarding moments.

## Design Philosophy

Good blank slates:
1. **Explain what belongs here** - not just "nothing here"
2. **Guide to next action** - link to docs, show examples
3. **Reduce anxiety** - reassure this is normal, here's how to fix it
4. **Match context** - the hint should be relevant to what the user is doing

## Current Empty States Audit

### Popup UI

| Section | Current Text | Issues |
|---------|--------------|--------|
| Connected Tabs | "No REPL connections active" | No guidance on how to connect |
| Matching Scripts | "No scripts match this page." | No hint about what patterns look like |
| Other Scripts | "No other scripts." | When both sections empty, no guidance to create scripts |
| Custom Origins | "No custom origins added yet." | OK - has input form visible |

### Panel UI

| Section | Current Text | Issues |
|---------|--------------|--------|
| Results Area | "Evaluate ClojureScript code above" | Good, but could link to docs |
| No Manifest | Shows example manifest | Good - already helpful |

## Workflow

**ALWAYS act informed.** You start by investigating the testing docs and the existing tests to understand patterns and available fixture.

**ALWAYS use `bb <task>` over direct shell commands.** The bb tasks encode project-specific configurations. Check `bb tasks` for available commands.

**ALWAYS check lint/problem reports after edits.** Use `get_errors` tool to verify no syntax or bracket errors before running tests.

**ALWAYS use the `edit` subagent for file modifications.** The edit subagent specializes in Clojure/Squint structural editing and avoids bracket balance issues. Provide it with complete context: file paths, line numbers, and the exact changes needed.

- `bb test` - Compile and run unit tests
- `bb test:e2e` - Compile and run E2E tests (Docker)

## Proposed Improvements

### 1. Connected Tabs Section (Popup)

**Current:**
```
No REPL connections active
```

**Proposed:**
```
No REPL connections active

To connect: Start the browser-nrepl server (Step 1),
then click Connect (Step 2).

→ Learn more in the README
```

**Implementation notes:**
- Link to `#repl-usage` section in README
- Keep it concise - steps 1-2 are already visible above
- Could include a small arrow icon pointing up to the steps

### 2. Matching Scripts Section (Popup)

**Current:**
```
No scripts match this page.
```

**Proposed (when scripts exist but none match):**
```
No scripts match this page.

Scripts match using URL patterns like:
  *://example.com/*

→ Open DevTools → Epupp panel to create scripts
```

**Proposed (when NO scripts exist at all):**
```
No userscripts yet!

Create your first script in DevTools → Epupp panel,
or install example scripts from our README.

→ Getting started guide
```

**Implementation notes:**
- Detect `(empty? scripts/list)` vs "scripts exist but none match"
- Show current tab's hostname as pattern example: `*://${hostname}/*`
- Link to README#userscripts-usage
- Could link to example gists (future: when gist installer is polished)

### 3. Other Scripts Section (Popup)

**Current:**
```
No other scripts.
```

**Proposed:**
```
No other scripts saved.

Scripts created in DevTools → Epupp panel
will appear here when they don't match
the current page.
```

**Implementation notes:**
- This is less critical since "Matching Scripts" handles the "no scripts at all" case
- Keep it simple and informative

### 4. Results Area (Panel)

**Current:**
```
Evaluate ClojureScript code above
```

**Proposed:**
```
Evaluate ClojureScript code above
Ctrl+Enter to run

Powered by Scittle • Full DOM access
→ See example scripts in the README
```

**Implementation notes:**
- Add links to Scittle and README
- Keep keyboard shortcut prominent
- Could show Scittle logo (already have CSS for `.empty-results-logos`)

### 5. No Manifest Message (Panel)

**Current:** Already good - shows example manifest format

**Proposed enhancement:**
Add a "Use template" button that inserts a starter template:

```clojure
{:epupp/script-name "My Script"
 :epupp/site-match "*://example.com/*"
 :epupp/description "What this script does"}

(ns my-script)

;; Your code here
(js/console.log "Hello from Epupp!")
```

**Implementation notes:**
- Button fills in code textarea with template
- Could pre-fill site-match with current page's hostname
- This is a nice-to-have, not critical

## README Additions Needed

To support the links, we need anchor targets in README.md:

1. Add `## Example Scripts` section with links to installable gist examples
2. Ensure `#repl-usage` anchor exists (already does)
3. Ensure `#userscripts-usage` anchor exists (need to verify/add)

### Example Scripts Section (to add to README)

```markdown
## Example Scripts

Get started with these example userscripts. Click "Install" on any gist page
(requires the Gist Installer userscript to be enabled):

- [GitHub Dark Mode Toggle](https://gist.github.com/PEZ/...)
- [YouTube Keyboard Enhancer](https://gist.github.com/PEZ/...)
- [Generic Page Timer](https://gist.github.com/PEZ/...)

Or create your own - see [Userscripts Usage](#userscripts-usage) above.
```

## CSS Additions

New styles needed:

```css
/* Blank slate hints */
.no-scripts-hint,
.no-connections-hint {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 8px;
  line-height: 1.5;
}

.no-scripts-hint code {
  font-family: ui-monospace, SFMono-Regular, monospace;
  background: var(--code-bg);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 10px;
}

.blank-slate-link {
  color: var(--clojure-blue);
  text-decoration: none;
  font-weight: 500;
}

.blank-slate-link:hover {
  text-decoration: underline;
}
```

## Implementation Order

1. **Phase 1 - Quick wins (low effort, high impact)**
   - Connected Tabs hint with link to README
   - Matching Scripts contextual hint (show hostname pattern)

2. **Phase 2 - Script guidance**
   - Detect empty scripts list vs no-match scenarios
   - Add DevTools guidance
   - Add README link for example scripts

3. **Phase 3 - Panel enhancements**
   - Results area with Scittle link
   - "Use template" button for no-manifest state

4. **Phase 4 - README content**
   - Add Example Scripts section
   - Create actual example gists
   - Verify all anchors work

## Testing Considerations

- E2E tests currently check for `.no-connections`, `.no-scripts`, etc.
- New hints should use additional nested elements, not replace the class markers
- Example: `[:div.no-scripts "..." [:div.no-scripts-hint "..."]]`

## Open Questions

1. **Should hints be dismissable?** Probably not - they're small enough not to be annoying
2. **Should we track "first run" state?** Could show more elaborate onboarding on first use
3. **Icon usage?** Could add small icons (book, arrow-right) but may be overkill

## Summary

The key improvements focus on:
- **Connected Tabs**: Guide users to the connect workflow
- **Scripts sections**: Show URL pattern format with current hostname
- **Panel results**: Add documentation links

These changes are non-intrusive, contextually helpful, and guide users toward successful use of Epupp without overwhelming them.
