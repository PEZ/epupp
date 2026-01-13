# Architecture Documentation Restructure Plan

**Purpose:** Split the monolithic `architecture.md` into focused, domain-centered documents while creating a navigable overview.

## Current State

The existing `architecture.md` (~450 lines) serves as an authoritative reference but mixes several concerns:
- High-level system design
- Component relationships
- Message protocols (detailed tables)
- State schemas (code blocks for each module)
- Injection flows (multiple scenarios)
- Security model
- Build pipeline

Related documents already exist:
- `userscripts-architecture.md` - Design rationale for userscripts
- `dev.md` - Development setup and build commands
- `testing.md` (+ `testing-unit.md`, `testing-e2e.md`) - Already split!
- `ui.md` - End-user UI guide

## Proposed Structure

```
dev/docs/architecture/
├── overview.md              # System overview with navigation
├── components.md            # Source files, module dependencies
├── message-protocol.md      # All message types and flows
├── state-management.md      # Atoms, schemas, Uniflow actions/effects
├── injection-flows.md       # REPL connection, userscripts, panel eval
├── security.md              # CSP bypass, message isolation, approvals
└── build-pipeline.md        # Compilation, bundling, configuration
```

## Document Specifications

### 1. overview.md

**Purpose:** Bird's-eye view enabling intelligent navigation.

**Content to include:**
- Opening paragraph from current "Overview" section
- Main mermaid diagram (Component Architecture)
- Three use cases list (REPL, Userscripts, Panel)
- **Navigation table** pointing to detailed docs
- Brief mention of key architectural decisions

**Lines from architecture.md:** 1-50 (Overview + Component Architecture diagram)

**Length target:** ~80 lines

**Cross-references:**
- Link each use case to relevant detailed doc
- Link to `userscripts-architecture.md` for design rationale

---

### 2. components.md

**Purpose:** Map of what lives where - the "Source Files" reference.

**Content to include:**
- Source Files table (all 14 entries)
- Module Dependencies mermaid diagram
- Brief explanation of standalone vs. connected modules
- File naming conventions (underscore files, hyphen namespaces)

**Lines from architecture.md:** 51-70 (Source Files), 295-315 (Module Dependencies)

**Length target:** ~60 lines

**Notes for writer:**
- This is primarily a reference table - keep prose minimal
- The diagram speaks for itself

---

### 3. message-protocol.md

**Purpose:** Complete message type reference for developers extending the system.

**Content to include:**
- Intro explaining message-based architecture
- Page ↔ Content Bridge messages (both directions)
- Content Bridge ↔ Background messages (both directions)
- Popup/Panel → Background messages
- Source identifiers explanation (`epupp-page`, `epupp-bridge`)

**Lines from architecture.md:** 72-120 (entire Message Protocol section)

**Length target:** ~80 lines

**Notes for writer:**
- Tables are the primary content - keep explanatory text brief
- Consider adding "When to use" guidance for each message type

---

### 4. state-management.md

**Purpose:** Reference for all stateful atoms and the Uniflow event system.

**Content to include:**
- Introduction to atom-per-component pattern
- State schemas for each component:
  - Background Worker (`!init-promise`, `!state`)
  - Content Bridge (`!state`)
  - WebSocket Bridge (`!state`)
  - Popup (`!state`)
  - Panel (`!state`)
  - Storage (`!db`)
- Uniflow Event System section
- Action tables (Popup, Panel, Generic)
- Effect tables (Generic)

**Lines from architecture.md:** 122-225 (State Management + Uniflow)

**Length target:** ~130 lines

**Cross-references:**
- Link to `.github/uniflow.instructions.md` for framework docs
- Mention `event_handler.cljs` as the implementation

**Notes for writer:**
- Code blocks are essential - preserve them exactly
- Group related state keys with comments

---

### 5. injection-flows.md

**Purpose:** Step-by-step walkthroughs of the three main injection scenarios.

**Content to include:**
- REPL Connection flow (from Popup)
- Userscript Auto-Injection flow (on Navigation)
- Panel Evaluation flow (from DevTools)
- Content Script Registration section:
  - Timing options table
  - Registration Architecture diagram
  - Userscript Loader Flow diagram
  - Dual Injection Path Summary table

**Lines from architecture.md:** 227-293 (Injection Flows + Content Script Registration)

**Length target:** ~100 lines

**Cross-references:**
- Link to `userscripts-architecture.md` for design rationale
- Reference `message-protocol.md` for message types used

**Notes for writer:**
- Preserve both mermaid diagrams
- Numbered steps are crucial - don't convert to prose

---

### 6. security.md

**Purpose:** Security model and trust boundaries for the extension.

**Content to include:**
- Message Origin Isolation section + table
- Content Bridge as Security Boundary explanation + table
- CSP Bypass Strategy (3-point solution)
- Per-Pattern Approval system (4-point explanation)
- Injection Guards table

**Lines from architecture.md:** 317-375 (entire Security Model section)

**Length target:** ~80 lines

**Notes for writer:**
- The "What if any page script could call this?" question is key
- Security docs should be precise - don't paraphrase

---

### 7. build-pipeline.md

**Purpose:** Build system reference including configuration injection.

**Content to include:**
- Build flow mermaid diagram
- Build-Time Configuration explanation:
  - Config files list
  - Build script flow
  - esbuild injection
  - Access pattern
- Config shape code block

**Lines from architecture.md:** 377-405 (Build Pipeline section)

**Length target:** ~50 lines

**Cross-references:**
- Link to `dev.md` for build commands
- Reference `config/` directory files

---

## Migration Steps

### Phase 1: Create Directory and Skeleton Files

1. Create `dev/docs/architecture/` directory
2. Create each `.md` file with title and "TODO" placeholder
3. Verify structure matches plan

### Phase 2: Extract Content

For each document (in this order):
1. `overview.md` - Start here to establish navigation
2. `components.md` - Simple extraction
3. `message-protocol.md` - Table-heavy, straightforward
4. `build-pipeline.md` - Small and self-contained
5. `security.md` - Standalone section
6. `injection-flows.md` - Has diagrams, needs care
7. `state-management.md` - Largest, do last

**For each extraction:**
- Copy relevant sections from `architecture.md`
- Add document introduction (1-2 sentences)
- Add cross-reference links
- Verify mermaid diagrams render

### Phase 3: Update Cross-References

1. Update `overview.md` navigation table with final paths
2. Update `dev.md` to reference new structure
3. Update `.github/copilot-instructions.md` references
4. Search for "architecture.md" in all `.md` files

### Phase 4: Cleanup

1. Archive old `architecture.md` to `archive/architecture-monolith.md`
2. Create redirect or deprecation notice if needed
3. Verify no broken links

## Validation Checklist

After restructuring, verify:

- [ ] All content from original `architecture.md` is preserved
- [ ] No duplicate content between new documents
- [ ] All mermaid diagrams render correctly
- [ ] Cross-references are bidirectional where appropriate
- [ ] `overview.md` provides clear navigation to each domain
- [ ] Each document can be read standalone (has necessary context)
- [ ] Developer can find any topic in ≤2 clicks from overview

## Content That Stays Elsewhere

**Do NOT move to architecture folder:**

| Content | Current Location | Reason |
|---------|-----------------|--------|
| Userscript design rationale | `userscripts-architecture.md` | Design decisions, not technical reference |
| Development workflow | `dev.md` | How-to guide, not architecture |
| Testing strategy | `testing.md` | Separate concern |
| UI user guide | `ui.md` | End-user documentation |

## Notes for the Writer

1. **Preserve technical precision** - Architecture docs are reference material. Don't "simplify" code blocks or table entries.

2. **Keep cross-references light** - One or two relevant links per document. Don't create a web of "see also" links.

3. **Diagrams are primary** - In architecture docs, a diagram often says more than prose. Let them speak.

4. **Audience is developers** - Assume familiarity with browser extensions, ClojureScript, and message passing. Don't over-explain basics.

5. **Test your changes** - After extraction, search the codebase for broken links to `architecture.md`.
