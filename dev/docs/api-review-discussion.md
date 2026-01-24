# Epupp API Review - MVP Release Discussion

**Date:** January 24, 2026
**Purpose:** Scratchpad for API stability review before 1.0 release
**Reference:** [api-surface-catalog.md](api-surface-catalog.md) - Complete catalog

---

## External API (Breaking Changes Hurt Users)

These are exposed to userscripts, REPL code, or users. Must be stable.

| API Surface | Example | Commit to 1.0? |
|-------------|---------|----------------|
| **`epupp.fs` Functions** | `ls`, `save!`, `mv!`, `rm!`, `show` signatures and return shapes | |
| **Script Manifest Format** | `:epupp/script-name`, `:epupp/inject`, `:epupp/run-at` keys | |
| **URL Matching Syntax** | TamperMonkey-compatible patterns (`*://example.com/*`) | |
| **Scittle Library Catalog** | `scittle://reagent.js` URLs | |

### Subtle External APIs

Things that become API by accident:

1. **Name normalization** - `"My Script"` → `"my_script.cljs"` is a user-visible contract
2. **Error message strings** - REPL code might parse these

---

## Migration Concerns (Internal, Needs Versioning)

These are internal but need version fields for smooth upgrades.

| Schema | Why Version Needed | Priority |
|--------|-------------------|----------|
| **Storage schema** | Scripts array structure, settings keys - needs migration on breaking changes | |
| **Panel persistence** | Per-hostname state format | |

---

## External API Concerns

### 1. Naming Consistency: `:epupp/inject` (Decided)

**Status:** Code uses `:epupp/inject`, README.md fixed to match
**Impact:** CRITICAL - users write this in their userscript code
**Decision:** Use `:epupp/inject` as the canonical name

**Discussion:** ✅ Decided - `:epupp/inject` is the standard manifest key for library dependencies


---

### 2. Script Manifest Keys Stability

**Current manifest keys:**
- `:epupp/script-name` - Display name
- `:epupp/site-match` - URL pattern(s) for auto-injection
- `:epupp/description` - Human-readable description
- `:epupp/run-at` - Timing: document-start/end/idle
- `:epupp/inject` - Library dependencies

**Decision: Required vs Optional - Context Matters**

**Epupp Script Recognition:**
- First form is a map with at least one `:epupp/` namespaced key
- Minimal valid manifest: `{:epupp/inject []}` or even `{:epupp/script-name "foo.cljs"}`

**REPL Session (`epupp.repl/manifest!`):**
- Ephemeral, no persistence
- Any `:epupp/` key(s) valid: `{:epupp/inject ["scittle://reagent.js"]}`
- No `:epupp/script-name` required

**Storage Gate (`save-script!`):**
- Persistence requires identity
- **REQUIRED: `:epupp/script-name`** - cannot save without it
- Error if missing: "Cannot save script without :epupp/script-name in manifest"

**Optional Keys (all contexts, sensible defaults):**
- `:epupp/description` → `""` (empty)
- `:epupp/run-at` → `"document-idle"`
- `:epupp/inject` → `[]` (no libraries)
- `:epupp/site-match` → `[]` (no auto-injection, manual only)

**Benefits:**
- Flexible REPL exploration
- Strict storage validation
- Clear error messages
- Code is portable (name travels with it)

**Discussion:** ✅ Decided - `:epupp/script-name` required at storage gate, optional elsewhere


---

### 3. `epupp.fs` Return Shape Stability

**Current return shapes use namespaced keywords:**
- `ls`: `{:fs/name :fs/enabled :fs/match :fs/modified ...}`
- `save!`: `{:fs/success :fs/name :fs/error}`
- etc.

**Decision needed:** Commit to these shapes? What's additive vs breaking?
**Recommendation:**

**Discussion:**


---

### 4. Settings Defaults

**Current defaults:**
- `autoConnectRepl`: `false`
- `autoReconnectRepl`: `true`
- `fsReplSyncEnabled`: `false`

**Decision needed:** Lock these defaults for 1.0?
**Recommendation:**

**Discussion:**


---

### 5. Reserved Script Namespace

**Decision:** Reserve `epupp/` prefix for system scripts before 1.0

**Contract:**
- Users cannot create scripts with names starting `epupp/`
- Built-ins use pattern: `epupp/built-in/<name>.cljs`
- Future system scripts: `epupp/examples/...`, `epupp/tools/...`, etc.
- Error message: "Script names cannot start with 'epupp/' - reserved for system scripts"

**Validation:**
- Enforce at storage level (`save-script!` in `storage.cljs`)
- Single chokepoint covers all entry points

**Impact:** User-facing restriction - this is API

**Discussion:** ✅ Decided - `epupp/` namespace reserved


---

### 6. Reserved URI Schemes

**Decision:** Reserve URI schemes for system use

**Current schemes:**
- `scittle://` - Scittle library loading (in use)
  - Example: `scittle://reagent.js`, `scittle://re-frame.js`
- `epupp://` - Epupp-specific resources (reserved, future use)

**Potential `epupp://` uses:**
- `epupp://api/...` - Internal API endpoints
- `epupp://docs/...` - Bundled documentation
- `epupp://examples/...` - Bundled example scripts
- `epupp://tools/...` - Extension tools/utilities

**Why reserve now:**
- Can't unreserve later without breaking users
- Zero cost (just validation)
- Provides namespace for future features
- Consistent with `:epupp/` and `epupp/` reservations

**Discussion:** ✅ Decided - `epupp://` scheme reserved


---

## Migration Concerns

### 1. Storage Schema Versioning

**Current:** No version field in storage
**Risk:** Can't detect when migration needed
**Decision needed:** Add `"schemaVersion"` field now?
**Recommendation:**

**Discussion:**


---

### 2. Storage Key Naming Inconsistency

**Current:** `"autoConnectRepl"` (camelCase) vs `"granted-origins"` (kebab-case)
**Impact:** Internal only, but migration harder if inconsistent
**Decision needed:** Standardize now or accept as-is?
**Recommendation:**

**Discussion:**


---

### 3. Unused Fields

**`approvedPatterns`** in storage schema - legacy, unused
**Decision needed:** Remove before 1.0 or keep for future?
**Recommendation:**

**Discussion:**


---

### 4. Built-in Script Clean Reinstall

**Current:** Code overwrites on version change, no tracking

**Decision:** Remove and reinstall all built-ins on extension update

**Strategy:**
- On extension startup, remove all scripts with IDs matching `epupp-builtin-*`
- Reinstall from fresh source code
- Guarantees users always have latest built-in code
- No version tracking needed - just nuke and pave

**Discussion:** ✅ Decided - clean reinstall on update


---

### 5. Panel Persistence Over-Caching

**Current:** Saves `{code, scriptName, scriptMatch, scriptDescription, originalName}`
**Problem:** Metadata can get out of sync with manifest in code
**Decision:** Only persist `{code}`, parse manifest on restore
**Benefits:**
- Code is source of truth
- No sync issues
- Simpler migration (one field)
- Follows "parse, don't validate"

**Discussion:** ✅ Decided - simplify to code-only persistence


---

### 6. Storage Schema Redundancy

**Current storage per script:**
```clojure
{:script/id "..."              ; Can't derive
 :script/code "..."            ; Source of truth
 :script/name "..."            ; Derivable from manifest
 :script/match [...]           ; Derivable from manifest
 :script/run-at "..."          ; Derivable from manifest
 :script/inject [...]          ; Derivable from manifest
 :script/description "..."     ; Derivable from manifest
 :script/enabled true          ; User preference (not in manifest)
 :script/created "..."         ; Metadata
 :script/modified "..."        ; Metadata
 :script/approved-patterns []} ; UNUSED, legacy
```

**Problem:** Manifest-derived fields cached in storage can get out of sync with code

**Decision:** Store only non-derivable fields, derive rest on startup/save:

**Store:**
```clojure
{:script/id "..."           ; Can't derive
 :script/code "..."         ; Source of truth
 :script/enabled true       ; User preference
 :script/created "..."      ; Metadata
 :script/modified "..."}    ; Metadata
```

**Derive on load (from manifest in code):**
- `:script/name` - from `:epupp/script-name`
- `:script/match` - from `:epupp/site-match`
- `:script/run-at` - from `:epupp/run-at`
- `:script/inject` - from `:epupp/inject`
- `:script/description` - from `:epupp/description`

**Performance:** Parse manifests once at extension startup and cache in memory. No impact on page navigation (reads from memory cache, not storage).

**Benefits:**
- Code is source of truth
- No sync issues between storage and manifest
- Simpler migration (fewer fields)
- Removes `:script/approved-patterns` (unused legacy field)

**Discussion:** ✅ Decided - parse-on-load strategy


---

## Principles for API Stability

| Pattern | Epupp Application |
|---------|-------------------|
| **Namespaced keys** | `epupp.fs` returns `:fs/name`, `:fs/success` - prevents collision |
| **Additive evolution** | New optional fields can be added without breaking consumers |
| **Clear error semantics** | `{:fs/success false :fs/error "message"}` - check `:fs/success` first |
| **Idempotent operations** | `save!` with `:fs/force? true` - same input, same outcome |
| **Documented contracts** | TamperMonkey-compatible URL matching - users know the syntax |
| **Start conservative** | Permissive → restrictive breaks users. Restrictive → permissive is safe. When uncertain, restrict first, relax later. |

---

## Anti-Patterns That Break APIs

| Anti-Pattern | Current Risk in Epupp |
|-------------|----------------------|
| **Renaming keys** | `:epupp/inject` vs `:epupp/require` inconsistency |
| **Changing defaults** | `autoConnectRepl` default change would surprise users |
| **Removing fields** | Removing return fields from `epupp.fs` functions |
| **Changing normalization** | Script name rules affect existing references |

---

## Action Items

_Track decisions and implementation tasks:_

1.
2.
3.

---

## Notes

_Free-form discussion notes:_



