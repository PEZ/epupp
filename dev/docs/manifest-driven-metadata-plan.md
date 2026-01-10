# Manifest-Driven Script Metadata - Implementation Plan

**Created:** January 10, 2026
**Status:** Implemented

## Overview

This document outlines the plan to make manifest annotations the single source of truth for script metadata in the DevTools panel. Currently, there are two ways to specify metadata:

1. **Manifest annotations** in the code itself (e.g., `{:epupp/script-name "..."}`)
2. **UI input fields** for name and match pattern

This dual-source approach is confusing. The change will make manifest annotations authoritative, with UI fields becoming read-only displays of parsed/coerced values.

## Current Behavior Analysis

### Manifest Parser (`manifest_parser.cljs`)

Currently parses:
- `:epupp/script-name` - Script name
- `:epupp/site-match` - URL pattern
- `:epupp/description` - Description text
- `:epupp/run-at` - Injection timing (validated against allowed values)

Returns a map with string keys: `"script-name"`, `"site-match"`, `"description"`, `"run-at"`.

**Behavior:**
- Returns `nil` if no `:epupp/*` keys found
- Throws on invalid `run-at` values
- Defaults `run-at` to `"document-idle"` if omitted

### Panel Actions (`panel_actions.cljs`)

The `:editor/ax.set-code` action currently:
1. Parses manifest from code (catches errors silently)
2. Stores parsed manifest in `:panel/detected-manifest`
3. **Auto-fills empty UI fields** from manifest values

This auto-fill behavior only works when fields are empty, leading to the confusion: if you load a script for editing, then change the manifest in code, the UI fields don't update.

### Panel State (`panel.cljs`)

Current state tracks both sources:
```clojure
{:panel/code ""
 :panel/script-name ""           ; UI input value
 :panel/script-match ""          ; UI input value
 :panel/script-description ""    ; UI input value
 :panel/detected-manifest nil}   ; Parsed from code
```

### Save Logic

`:editor/ax.save-script` reads from UI state (`script-name`, `script-match`, `script-description`), not from `detected-manifest`.

## Scope of Changes

### What Changes

1. **Manifest parser** - Extended to:
   - Parse `:epupp/site-match` as string or vector of strings
   - Return all `:epupp/*` keys found
   - Identify unknown `:epupp/*` keys for warnings
   - Apply coercion (normalize name, validate match patterns, validate run-at)
   - Return both raw and coerced values for hint display

2. **Panel actions** - Updated:
   - `:editor/ax.set-code` parses manifest on every change, then dispatches:
     - `:editor/ax.set-script-name` with coerced name
     - `:editor/ax.set-script-match` with validated pattern
     - `:editor/ax.set-script-description` with description
   - Add `:editor/ax.set-manifest-hints` for normalization/warning messages
   - Keep existing `set-*` actions (reused, just triggered differently)

3. **Panel state** - Extended:
   - Keep `script-name`, `script-match`, `script-description` (now set by manifest parsing)
   - Add `manifest-hints` for normalization/warning messages
   - Add `manifest-raw` for displaying "Normalized from: X" hints

4. **Panel UI** - Updated:
   - Name, match, description, run-at fields become read-only displays
   - Always show description field (encourage usage)
   - Show hints for: missing required fields, normalization applied, unknown keys
   - Enable/disable Save button based on manifest completeness

### What Stays the Same

- **Save/Create/Rename button logic** - Same triggers, just fed from parsed manifest
- **Popup behavior** - No changes to inspect-script or any popup functionality
- **Panel evaluation/results** - Unchanged
- **Storage format** - Scripts stored the same way
- **E2E test assertions** - Tests verify same outcomes, may need different setup

## Manifest Schema

### Required Keys

| Key | Type | Coercion |
|-----|------|----------|
| `:epupp/script-name` | string | `normalize-script-name` applied |
| `:epupp/site-match` | string or `[string ...]` | Validated against URL pattern format |

### Optional Keys

| Key | Type | Coercion |
|-----|------|----------|
| `:epupp/description` | string | None |
| `:epupp/run-at` | string | Default to `"document-idle"` if missing/invalid |

### Unknown Keys

Any key starting with `:epupp/` not in the above list triggers a warning hint:
> "Unknown manifest key: :epupp/foo"

Non-`:epupp/` keys are ignored (open maps philosophy).

## Hints System

The UI will show contextual hints:

| Condition | Hint Text | Location |
|-----------|-----------|----------|
| Name missing | "Add :epupp/script-name to manifest" | Name field |
| Match missing | "Add :epupp/site-match to manifest" | Match field |
| Name normalized | "Normalized from: Original Name" | Name field |
| Unknown `:epupp/*` key | "Unknown manifest key: :epupp/foo" | Below manifest info |
| Parse error | "Manifest parse error: ..." | Below manifest info |
| Invalid run-at | "Invalid run-at, using default" | Run-at display |

## Implementation Plan (TDD)

### Phase 1: Enhance Manifest Parser

**Tests first:**
1. Parse `:epupp/site-match` as string
2. Parse `:epupp/site-match` as vector of strings
3. Collect all `:epupp/*` keys found
4. Identify unknown `:epupp/*` keys
5. Apply name normalization and return both raw and normalized
6. Validate match pattern format

**Implementation:**
- Add new parsing logic to `manifest_parser.cljs`
- Return richer structure with raw, coerced, and warnings

### Phase 2: Update Panel Actions

**Tests first:**
1. `:editor/ax.set-code` parses manifest and dispatches `set-*` actions with coerced values
2. Parse errors stored in hints state (not thrown)
3. Normalization hints tracked (raw vs coerced)
4. Unknown key warnings tracked
5. Existing `set-*` action tests still pass

**Implementation:**
- Update `:editor/ax.set-code` to dispatch `set-*` actions from parsed manifest
- Add `:editor/ax.set-manifest-hints` action
- Add `manifest-raw` and `manifest-hints` to state

### Phase 3: Update Panel UI

**No new unit tests** (UI rendering is not unit tested)

**E2E test updates:**
1. Tests that fill name/match inputs need to write manifest in code instead
2. Verify read-only fields display correct values
3. Verify hints appear for normalization
4. Verify Save button enable/disable logic

**Implementation:**
- Change input fields to read-only displays
- Add hint rendering
- Update Save button logic

### Phase 4: Migration & Cleanup

1. Remove unused code paths (if any)
2. Update any remaining tests
3. Update documentation

## Test Files Affected

### Unit Tests

| File | Changes Needed |
|------|----------------|
| `manifest_parser_test.cljs` | Add tests for new parsing features |
| `panel_test.cljs` | Update to use manifest-based approach, remove UI input tests |

### E2E Tests

| File | Changes Needed |
|------|----------------|
| `panel_test.cljs` | Update script creation to use manifest in code |
| `integration_test.cljs` | Update script lifecycle test setup |

## Example Manifest

```clojure
{:epupp/script-name "GitHub Tweaks"
 :epupp/site-match ["https://github.com/*" "https://gist.github.com/*"]
 :epupp/description "Enhance GitHub UX with custom shortcuts"
 :epupp/run-at "document-idle"}

(ns github-tweaks)

(defn init []
  (println "GitHub Tweaks loaded!"))

(init)
```

## UI Mockup

```
┌─────────────────────────────────────────────────┐
│ Edit Userscript                                 │
├─────────────────────────────────────────────────┤
│ Name         [github_tweaks.cljs      ] (readonly)
│              ℹ️ Normalized from: GitHub Tweaks
│
│ Match        [https://github.com/*    ] (readonly)
│              [https://gist.github.com/*]
│
│ Description  [Enhance GitHub UX...    ] (readonly)
│
│ Run-at       🚀 document-start
│
│ ⚠️ Unknown manifest key: :epupp/author
│
│ [Save Script] [Rename]
│ ✓ Saved "github_tweaks.cljs"
└─────────────────────────────────────────────────┘
```

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Breaking existing scripts without manifest | Scripts saved via UI already work; just can't edit metadata without manifest |
| E2E test brittleness during migration | Run tests after each phase; update incrementally |
| Users confused by read-only fields | Clear hints explaining how to edit metadata |

## Success Criteria

1. All 211 unit tests pass (may need updates)
2. All E2E tests pass (may need setup changes)
3. No new lint warnings
4. Manifest is single source of truth for metadata
5. UI hints guide users effectively

## Related Documents

- [architecture.md](architecture.md) - State management patterns
- [testing.md](testing.md) - Testing strategy and patterns
- [ui.md](ui.md) - Current UI behavior documentation
- [userscripts-architecture.md](userscripts-architecture.md) - Script lifecycle
