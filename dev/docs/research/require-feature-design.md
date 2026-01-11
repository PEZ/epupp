# @require Feature Design

**Created**: January 11, 2026
**Status**: Design proposal

## Overview

This document captures research and design decisions for implementing `@require` functionality in Epupp, allowing userscripts to load external JavaScript libraries before execution.

## TamperMonkey Reference

TamperMonkey's `@require` header loads external scripts before the userscript runs:

```javascript
// @require https://code.jquery.com/jquery-2.1.4.min.js
// @require https://cdn.jsdelivr.net/npm/lodash@4.17.21/lodash.min.js#sha256=...
// @require tampermonkey://vendor/jquery.js
```

Key characteristics:
- Scripts load and execute **before** the userscript
- Supports **Subresource Integrity (SRI)** via URL hash fragments
- Has built-in vendor libraries (`tampermonkey://vendor/...`)
- Multiple `@require` tags allowed
- No npm support - only raw URLs to JS files

## Security Implications

### Supply Chain Attack Vectors

External scripts introduce supply chain risks:

| Risk | Description |
|------|-------------|
| **CDN compromise** | Attacker modifies hosted file; all users affected |
| **URL content change** | Same URL serves different content over time |
| **MITM on HTTP** | Unencrypted URLs can be intercepted |
| **Transitive trust** | User approves script A, unaware it loads library B |

### Scope of Damage

Epupp scripts run in **MAIN world** with full page access. A malicious `@require` could:
- Steal session cookies and tokens
- Keylog form inputs
- Exfiltrate sensitive page data
- Modify page behavior silently
- Persist across page navigations (via service worker abuse)

### Trust Model Considerations

Epupp's per-pattern approval gives users control over *which sites* a script runs on. However:
- Users approve `my-script.cljs` for `github.com/*`
- They may not realize `my-script.cljs` loads `https://sketchy-cdn.io/lib.js`
- The approval implicitly trusts all `@require` dependencies

## Proposed Security Model

### Design Principles

1. **Secure by default**: Empty allowlist ships with the extension
2. **User responsibility**: Professional users consciously opt-in to CDN trust
3. **Defense in depth**: Origin allowlist + SRI verification
4. **Transparent**: UI clearly shows what libraries a script requires

### Layered Defense

```
┌─────────────────────────────────────────────────────┐
│  1. Origin Allowlist                                │
│     - Bundled origins (read-only)                   │
│     - User-added origins (editable)                 │
│     - Separate from script installer origins        │
├─────────────────────────────────────────────────────┤
│  2. SRI Hash Verification                           │
│     - SHA-512 only (start simple)                   │
│     - Warn in UI if missing hash                    │
│     - Block if hash mismatch                        │
├─────────────────────────────────────────────────────┤
│  3. Caching                                         │
│     - Cache fetched libraries in extension storage  │
│     - Reduce network requests                       │
│     - SRI hash serves as cache key                  │
└─────────────────────────────────────────────────────┘
```

### Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Allowlist scope** | Separate from installer origins | Different trust implications - installing a script vs running arbitrary JS |
| **Hash algorithms** | SHA-512 only | Start simple; cdnjs uses SHA-512. Expand to SHA-256/384 if user demand arises |
| **Caching** | Yes, in extension storage | Performance; SRI hash as cache key ensures integrity |
| **Default allowlist** | Bundled origins only | User must explicitly add external CDNs |

### Bundled Allowed Origins

The following origins are allowed by default (read-only, cannot be removed):

| Origin Pattern | Purpose |
|----------------|---------|
| `epupp://*` | Extension-bundled libraries |
| `scittle://*` | Scittle-specific libraries we ship |
| `https://cdn.jsdelivr.net/npm/scittle@*` | Official Scittle CDN distributions |
| `https://unpkg.com/scittle@*` | Alternative Scittle CDN |

**Note**: The exact CDN patterns should match what Scittle officially recommends for loading additional namespaces (reagent, promesa, etc.).

### User-Added Origins

Users can add origins in Settings, similar to the existing script installer origins:

- User adds: `https://cdn.jsdelivr.net`
- User adds: `https://cdnjs.cloudflare.com`
- User removes origins they've added (cannot remove bundled)

## Manifest Syntax

### In Epupp Scripts

```clojure
{:epupp/script-name "My Script"
 :epupp/site-match "https://example.com/*"
 :epupp/require ["https://cdn.jsdelivr.net/npm/lodash@4.17.21/lodash.min.js#sha256=..."
                 "scittle://reagent.js"]}

(ns my-script)
;; lodash and reagent available here
```

### Supported URL Schemes

| Scheme | Example | Description |
|--------|---------|-------------|
| `https://` | `https://cdn.jsdelivr.net/...` | External HTTPS URL (origin must be allowed) |
| `epupp://` | `epupp://vendor/lodash.js` | Bundled with extension |
| `scittle://` | `scittle://reagent.js` | Scittle ecosystem library |

**HTTP not supported**: Only HTTPS external URLs are allowed.

### SRI Hash Format

```
url#sha512=base64encodedHash
```

Only SHA-512 is supported initially. cdnjs (and likely other major CDNs) provides SHA-512 hashes. If users encounter CDNs that only provide SHA-256/384, we can expand support.

## UI/UX Requirements

### Panel Manifest Summary

The panel's property table should show `@require` status:

**When all requirements are satisfied:**
```
┌─────────────────────────────────────────┐
│ Name:        my_script.cljs             │
│ URL Pattern: https://example.com/*      │
│ Requires:    2 libraries ✓              │
│ Run At:      document-idle (default)    │
└─────────────────────────────────────────┘
```

**When origin is not allowed:**
```
┌─────────────────────────────────────────┐
│ Name:        my_script.cljs             │
│ URL Pattern: https://example.com/*      │
│ Requires:    ⚠️ 1 blocked (origin not   │
│              allowed: sketchy-cdn.io)   │
│ Run At:      document-idle (default)    │
└─────────────────────────────────────────┘
```

**When SRI hash is missing:**
```
┌─────────────────────────────────────────┐
│ Name:        my_script.cljs             │
│ URL Pattern: https://example.com/*      │
│ Requires:    2 libraries ⚠️ (1 without  │
│              integrity hash)            │
│ Run At:      document-idle (default)    │
└─────────────────────────────────────────┘
```

### Settings Panel

New section in popup Settings view:

```
Allowed Library Origins
─────────────────────────────────────────
Bundled (cannot remove):
  • epupp://*
  • scittle://*
  • https://cdn.jsdelivr.net/npm/scittle@*
  • https://unpkg.com/scittle@*

User-added:
  • https://cdn.jsdelivr.net      [×]
  • https://cdnjs.cloudflare.com  [×]

[Add origin: _______________] [Add]
```

### Save Button Behavior

The Save Script button should be **disabled** if:
- Any `@require` URL uses a disallowed origin
- Any `@require` URL has an SRI hash that doesn't validate

Show clear error message explaining what's wrong.

## Implementation Considerations

### Storage Schema

```clojure
;; New storage keys
:storage/require-allowed-origins []  ; User-added origins

;; Script schema addition
{:script/id "..."
 :script/require [{:url "https://..."
                   :origin "cdn.jsdelivr.net"
                   :hash "sha256=..."
                   :cached? true}]
 ;; ... existing fields
 }
```

### Cache Storage

Cached libraries stored with SRI hash as key:

```clojure
;; In chrome.storage.local
{"require-cache:sha256=abc123..." {:content "..."
                                   :fetched-at 1736600000000
                                   :url "https://..."}}
```

Cache invalidation: Libraries with SRI hashes are immutable by definition. Cache entries without hashes should have TTL or be re-fetched on each use.

### Injection Order

1. Inject content bridge (if not present)
2. Inject Scittle core
3. **Inject @require libraries in declaration order**
4. Inject userscript
5. Trigger Scittle evaluation

## Effort Estimate

| Component | Effort | Notes |
|-----------|--------|-------|
| Manifest parser updates | S | Add `:epupp/require` extraction |
| Origin validation logic | S | Reuse patterns from installer |
| SRI verification | M | Hash parsing, fetch + verify |
| Cache management | M | Storage, invalidation logic |
| Settings UI | S | Similar to existing origins UI |
| Panel manifest display | S | Add requires row with status |
| Injection flow changes | M | Modify background.cljs injection |
| **Total** | **M-L** | 3-4 weeks |

## SRI User Experience

### How Users Obtain SRI Hashes

Major CDNs provide SRI hashes - we don't need to build tooling for this:

| CDN | How to Get SRI |
|-----|----------------|
| **cdnjs.com** | Library pages have "copy" buttons with integrity attribute included. API provides `sri` field: `https://api.cdnjs.com/libraries/lodash.js?fields=sri` |
| **jsdelivr.com** | Library pages show copy-able `<script>` tags with integrity |
| **unpkg.com** | No built-in SRI, but files are immutable per version |

### Design Decision: Link, Don't Build

We chose to **link users to CDN pages** rather than auto-fetching and computing hashes:

| Option | Complexity | Security |
|--------|------------|----------|
| ~~Auto-fetch hash~~ | High - fetch, compute, UI for review | Trusts first fetch blindly |
| **Link to CDNs** ✓ | None - just docs | User sees official CDN hash |

**Rationale**: CDNs already solve this problem well. Adding auto-hash tooling increases our attack surface and maintenance burden for marginal UX gain.

### User Workflow

1. User visits cdnjs.com or jsdelivr.com
2. Finds library, selects version
3. Copies URL from CDN (many provide "copy with SRI" buttons)
4. Pastes into `:epupp/require` with hash fragment:
   ```clojure
   {:epupp/require ["https://cdn.jsdelivr.net/npm/lodash@4.17.21/lodash.min.js#sha256=..."]}
   ```

### Version Pinning Requirement

SRI hashes are content-based. Users **must** pin versions in URLs:

```clojure
;; ✓ Good - version pinned, hash will match
"https://cdn.jsdelivr.net/npm/lodash@4.17.21/lodash.min.js#sha256=..."

;; ✗ Bad - @latest can change, breaking hash verification
"https://cdn.jsdelivr.net/npm/lodash@latest/lodash.min.js#sha256=..."
```

The panel should warn if a URL contains `@latest` or similar version specifiers.

### Documentation Responsibility

We should document:
1. Where to get URLs with SRI (cdnjs, jsdelivr)
2. Why version pinning matters
3. What the warning indicators mean in the panel

## Open Questions

1. **Cache size limits**: Should we limit total cache size? Eviction policy?
2. **Offline support**: Should scripts work offline if libraries are cached?
3. ~~**Version pinning**: Should we encourage or require version-pinned URLs?~~ → Warn, don't block
4. **Error UX**: What happens at runtime if a library fails to load?

## References

### TamperMonkey Documentation
- [@require header](https://www.tampermonkey.net/documentation.php#meta:require)
- [Subresource Integrity](https://www.tampermonkey.net/documentation.php#api:Subresource_Integrity)

### Web Standards
- [MDN: Subresource Integrity](https://developer.mozilla.org/en-US/docs/Web/Security/Subresource_Integrity)
- [W3C SRI Spec](https://www.w3.org/TR/SRI/)

### Related Epupp Documents
- [TamperMonkey Gap Analysis](tampermonkey-gap-analysis.md)
- [Userscripts Architecture](../userscripts-architecture.md)
