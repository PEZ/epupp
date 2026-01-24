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
| **Settings API** | Auto-connect, FS sync defaults and behavior | |
| **Extension Permissions** | Manifest permissions (user consent required) | |

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

### 1. Naming Inconsistency: `:epupp/inject` vs `:epupp/require`

**Status:** Both terms appear in code/docs for script manifest
**Impact:** CRITICAL - users write this in their userscript code
**Decision needed:** Pick one canonical name before 1.0
**Recommendation:**

**Discussion:**


---

### 2. Script Manifest Keys Stability

**Current manifest keys:**
- `:epupp/script-name`
- `:epupp/site-match`
- `:epupp/description`
- `:epupp/run-at`
- `:epupp/inject` (or `:epupp/require`?)

**Decision needed:** Commit to these names? Add version field?
**Recommendation:**

**Discussion:**


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

### 4. Built-in Script Updates

**Current:** Code overwrites on version change, no tracking
**Risk:** User modifications lost silently (though built-ins aren't editable in UI)
**Decision needed:** Add version tracking or accept current behavior?
**Recommendation:**

**Discussion:**


---

### 5. Panel Persistence Over-Caching

**Current:** Saves `{code, scriptName, scriptMatch, scriptDescription, originalName}`
**Problem:** Metadata can get out of sync with manifest in code
**Recommendation:** Only persist `{code}`, parse manifest on restore
**Benefits:**
- Code is source of truth
- No sync issues
- Simpler migration (one field)
- Follows "parse, don't validate"

**Discussion:**


---

## Principles for API Stability

| Pattern | Epupp Application |
|---------|-------------------|
| **Namespaced keys** | `epupp.fs` returns `:fs/name`, `:fs/success` - prevents collision |
| **Additive evolution** | New optional fields can be added without breaking consumers |
| **Clear error semantics** | `{:fs/success false :fs/error "message"}` - check `:fs/success` first |
| **Idempotent operations** | `save!` with `:fs/force? true` - same input, same outcome |
| **Documented contracts** | TamperMonkey-compatible URL matching - users know the syntax |

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



