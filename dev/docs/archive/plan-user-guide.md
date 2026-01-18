# User Guide Documentation Plan

**Created:** January 13, 2026
**Status:** Completed
**Archived:** January 13, 2026

This document outlines the plan for creating `docs/user-guide.md` - a comprehensive end-user guide for Epupp.

## Outcome

Created `docs/user-guide.md` with all planned sections:
- Quick Start (installation, first eval, gist examples placeholder, save as userscript)
- Using the DevTools Panel (evaluation, selection, results, New button, shortcuts)
- Using the REPL (setup, evaluating, libraries, multi-tab, connection tracking, auto-reconnect)
- Creating Userscripts (manifest format, patterns, saving, renaming)
- Managing Scripts (popup sections, actions, approval workflow)
- Script Timing (document-idle/start/end, Safari limitation)
- Using Scittle Libraries (table, in userscripts, from REPL)
- Settings (origins, auto-connect)
- Examples (migrated from docs/examples.md)
- Troubleshooting
- Reference tables

**Remaining work (not part of this plan):**
- Create actual example gists for Quick Start
- Slim down README.md to link to user guide

## Goals

1. **Single entry point** for users learning Epupp
2. **Task-oriented** structure (what users want to do, not how the system works)
3. **Progressive disclosure** from simple to advanced
4. **Consolidate** scattered user-facing content from README.md, ui.md, and examples.md

## Current Documentation Landscape

### User-Facing (to consolidate)
| Source | Content | Status |
|--------|---------|--------|
| `README.md` | Installation, REPL usage, userscripts basics, library reference | Keep as project landing page, link to user guide |
| `docs/examples.md` | Copy-paste script examples | Move to user guide |
| `dev/docs/ui.md` | UI behavior reference | Extract user-relevant parts |

### Developer-Facing (separate)
| Source | Content | Status |
|--------|---------|--------|
| `dev/docs/architecture.md` | Technical internals | Keep for developers |
| `dev/docs/userscripts-architecture.md` | Design decisions | Keep for developers |
| `dev/docs/dev.md` | Build/development setup | Keep for developers |

## E2E Tests as Documentation Source

The e2e tests document real user workflows. Key scenarios to cover:

### From popup_test.cljs
- Port configuration and command copying
- Script list with enable/disable checkboxes
- Approval workflow (Allow/Deny buttons)
- Settings: custom origins management
- Auto-connect REPL setting
- Connection tracking and reveal buttons

### From panel_test.cljs
- Code evaluation (Ctrl+Enter, button)
- Selection evaluation (select code, Ctrl+Enter)
- Manifest-driven metadata (script-name, site-match, description, run-at)
- Save workflow (first save = Created, subsequent = Saved)
- Create vs Save vs Rename behavior
- New button to clear editor
- Undo in code editor
- Blank slate hints

### From userscript_test.cljs
- Script injection on matching URLs
- document-start timing (runs before page scripts)
- Gist installer workflow (Install button, confirmation)

### From integration_test.cljs
- Full script lifecycle: save -> view -> toggle -> edit -> delete
- Run-at badges in popup

### From require_test.cljs
- Using `:epupp/require` for Scittle libraries
- Library loading (Reagent, pprint)

### From repl_ui_spec.cljs
- REPL connection and evaluation
- DOM access from REPL
- `epupp/manifest!` for dynamic library loading
- Multi-tab REPL (different servers)

## Proposed User Guide Structure

```
docs/user-guide.md

# Epupp User Guide

## Quick Start
- Install extension (Chrome/Firefox/Safari links)
- Your first evaluation (DevTools panel)
- Install example scripts from gists (via built-in Gist Installer)
- Save as userscript

### Example Gists (TBD)
Create and publish these gists for users to install:
- [ ] Hello World badge - simple floating indicator
- [ ] GitHub PR enhancer - practical example
- [ ] YouTube keyboard shortcuts - popular use case
- [ ] Generic page timer - utility script

## Using the REPL
- Prerequisites (Babashka, editor)
- Starting the relay server
- Connecting from popup
- Evaluating code
- DOM access examples
- Loading libraries with epupp/manifest! (safe to call multiple times)
- Multi-tab connections (different servers)
- Troubleshooting connection issues

## Using the DevTools Panel
- Opening the panel
- Evaluating code
  - Full script (button)
  - Selection (Ctrl+Enter)
- Results display
- Keyboard shortcuts

## Creating Userscripts
- The manifest format
  - Required keys (script-name, site-match)
  - Optional keys (description, run-at, require)
- Saving scripts
- Name normalization
- Editing existing scripts
- Renaming scripts

## Managing Scripts (Popup)
- Script list sections (Matching vs Other)
- Enable/disable
- Approval workflow
- Running scripts manually
- Deleting scripts
- Built-in scripts (Gist Installer)

## Script Timing
- document-idle (default) - when to use
- document-start - intercepting globals
- document-end - early DOM access
- Safari limitations

## Using Scittle Libraries
- Available libraries table
- The :epupp/require manifest key
- Dependency resolution
- Examples with Reagent, pprint

## Settings
- Custom origins for Gist Installer
- Auto-connect REPL (and warning)

## Examples
(Migrate content from docs/examples.md)
- Hello World
- Floating Badge
- Reagent Counter
- Fetch Interceptor (document-start example)
- Pretty Printing

## Troubleshooting
- No Epupp panel?
- Extension Gallery restrictions
- CSP-strict sites
- Connection failures

## Reference
- Manifest keys table
- Available libraries table
- Keyboard shortcuts table
```

## Content Migration Plan

### Phase 1: Core Structure
1. Create `docs/user-guide.md` with outline
2. Write Quick Start section (new content)
3. Write DevTools Panel section (from ui.md + e2e observations)
4. Create example gists for Gist Installer onboarding (TBD list above)

### Phase 2: REPL Content
1. Migrate REPL usage from README
2. Add epupp/manifest! and multi-tab (from repl_ui_spec.cljs)
3. Add troubleshooting (from README + ui.md)

### Phase 3: Userscripts Content
1. Migrate userscripts basics from README
2. Expand manifest format documentation
3. Document approval workflow (from popup_test.cljs patterns)
4. Document timing options (from userscript_test.cljs patterns)

### Phase 4: Libraries and Advanced
1. Migrate library table from README
2. Add :epupp/require documentation (from require_test.cljs)
3. Note idempotency of library loading (safe to call multiple times)

### Phase 5: Examples
1. Migrate examples.md content
2. Add script coordination example (interceptor + dashboard)
3. Ensure all examples have valid manifests

### Phase 6: Polish
1. Cross-reference with README (remove duplication, add links)
2. Add screenshots where helpful
3. Review against e2e tests for completeness
4. Update README to link to user guide

## README.md Changes

After user guide is complete, README should become:
- Project description and value proposition
- Installation links (stores + manual)
- Demo video link
- Link to User Guide for detailed usage
- Development section (link to dev.md)
- Privacy, License, Sponsorship

Remove from README (moved to user guide):
- Detailed REPL usage instructions
- Userscripts usage details
- Advanced timing section
- Library reference table

## Key Decisions

### Writing Style
**Decision:** Natural, direct prose. No AI-ish patterns.
**Guidelines:**
- No em-dashes - use colons, commas, or separate sentences
- No rhetorical questions ("Want to customize your browsing? Here's how!")
- No forced tempo or breathless excitement
- No "Let's", "Simply", "Just", "Easily" filler words
- State facts. Show examples. Move on.
- Technical accuracy over marketing polish

### Single vs Multiple Files
**Decision:** Single user-guide.md file with clear sections.
**Rationale:** Users can Ctrl+F to find content. GitHub renders markdown with TOC. Simpler to maintain.

### Examples Location
**Decision:** Examples at end of user guide, not separate file.
**Rationale:** Keeps context together. Users reading about features can scroll to examples.

### Screenshots
**Decision:** Minimal screenshots, focus on text.
**Rationale:** Screenshots go stale. Text is searchable. Current README has 2 screenshots which is appropriate.

### Code Example Format
**Decision:** All examples include full manifest.
**Rationale:** Copy-paste ready. Shows best practice. Consistent with docs/examples.md pattern.

## Success Criteria

1. New user can install and run first evaluation within 5 minutes
2. All features documented in e2e tests are covered
3. README becomes focused landing page (< 200 lines)
4. No user-facing content duplicated between README and user guide
5. All code examples are copy-paste ready with valid manifests

## Related Documents

- [README.md](../../README.md) - Current user-facing content
- [docs/examples.md](../../docs/examples.md) - Script examples
- [ui.md](ui.md) - UI behavior reference
- [architecture.md](../architecture.md) - Technical reference
- [userscripts-architecture.md](userscripts-architecture.md) - Design decisions

