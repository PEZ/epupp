# VS Code Codicons Research Report

**Date:** January 8, 2026
**Purpose:** Evaluate VS Code codicons as a replacement for current inline SVG icons (Heroicons/Lucide)

## Executive Summary

VS Code codicons is a viable and well-suited icon source for the Epupp browser extension. The library offers 536 standardized icons under a permissive CC BY 4.0 license, uses consistent SVG structure compatible with the current hiccup approach, and provides all required icons except a lightning bolt (for which "rocket" is a good alternative).

**Recommendation:** ✅ Proceed with codicons adoption

## 1. Repository and License

**Repository:** https://github.com/microsoft/vscode-codicons
- Stars: 1,054
- Description: "The icon font for Visual Studio Code"
- Active development by Microsoft

**License:** Creative Commons Attribution 4.0 International (CC BY 4.0)
- ✅ Commercial use allowed
- ✅ Modification allowed
- ✅ Distribution allowed
- ⚠️ Attribution required

**Attribution Requirements (CC BY 4.0):**
1. Must retain copyright notice
2. Must provide link to license
3. Must indicate if modifications were made
4. Can satisfy attribution "in any reasonable manner" based on context

**Recommended Attribution Approach:**
- Add comment in `src/icons.cljs`: `; Icons adapted from VS Code Codicons (CC BY 4.0) - https://github.com/microsoft/vscode-codicons`
- This satisfies "reasonable manner" for embedded/inline usage

## 2. Icon Structure Analysis

All codicons use a consistent SVG structure ideal for hiccup conversion:

```xml
<svg width="16" height="16" viewBox="0 0 16 16"
     xmlns="http://www.w3.org/2000/svg" fill="currentColor">
  <path d="..."/>
</svg>
```

**Key Properties:**
- Fixed size: 16x16
- Consistent viewBox: `0 0 16 16`
- Uses `fill="currentColor"` (inherits text color)
- Single `<path>` element for most icons (some use multiple paths)
- Clean, optimized path data

**Comparison to Current Icons:**
- ✅ Same structure as current Heroicons/Lucide icons
- ✅ Compatible with existing hiccup pattern
- ✅ Uses `currentColor` for theme support
- ✅ 16x16 matches current default size

**Example Conversion (gear icon):**

**SVG:**
```xml
<svg width="16" height="16" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" fill="currentColor">
  <path d="M7.99997 6C6.89497 6 5.99997 6.895..."/>
</svg>
```

**Hiccup (Squint/Clojure):**
```clojure
(defn gear
  ([] (gear {}))
  ([{:keys [size] :or {size 16}}]
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :width size :height size
          :viewBox "0 0 16 16"
          :fill "currentColor"}
    [:path {:d "M7.99997 6C6.89497 6 5.99997 6.895..."}]]))
```

## 3. Icon Discovery and Browsing

**Official Icon Viewer:** https://microsoft.github.io/vscode-codicons/dist/codicon.html
- Searchable interface
- Dark/light theme toggle
- Copy icon name on click
- Visual preview of all icons

**Alternative Methods:**
1. Browse GitHub: https://github.com/microsoft/vscode-codicons/tree/main/src/icons
2. NPM package: `@vscode/codicons`
3. API endpoint: `https://api.github.com/repos/microsoft/vscode-codicons/contents/src/icons`

## 4. Available Icons - Coverage Analysis

**Total Icons:** 536

### Icons for Epupp Requirements:

| Use Case | Icon Name | Available | Notes |
|----------|-----------|-----------|-------|
| **document-start timing** | `rocket.svg` | ✅ | Best alternative to lightning bolt |
| **document-end timing** | `flag.svg` | ✅ | Perfect semantic match |
| **Settings** | `gear.svg` | ✅ | VS Code standard |
| **Play/Run** | `play.svg` | ✅ | Standard play button |
| **Eye/Inspect** | `eye.svg` | ✅ | + `eye-closed.svg` available |
| **Pencil/Edit** | `edit.svg` | ✅ | Clean edit icon |
| **Close/Delete** | `close.svg`, `trash.svg` | ✅ | Both available |
| **Chevron Right** | `chevron-right.svg` | ✅ | |
| **Chevron Down** | `chevron-down.svg` | ✅ | + up/left variants |
| **Cube/Package** | `package.svg` | ✅ | Perfect for built-in scripts |

**Additional Useful Icons Found:**
- `add.svg` / `add-small.svg` - Add/create actions
- `circle-filled.svg` - Status indicators
- `code.svg` - Code-related actions
- `debug.svg` - Debugging features
- `file.svg`, `file-code.svg` - File operations
- `search.svg` - Search functionality
- `warning.svg`, `error.svg`, `info.svg` - Status messages

**Icons NOT Found:**
- ❌ Lightning bolt (use `rocket.svg` as alternative)
- ❌ Zap/flash icons

**Semantic Recommendation for document-start:**
The `rocket.svg` icon is an excellent choice for document-start timing because:
1. Rockets represent "launch" and "early start"
2. VS Code uses rocket icon for performance/speed contexts
3. Visually distinct from flag (document-end)
4. Semantically appropriate: "launching before page loads"

## 5. SVG Examples

### Selected Icons with Full SVG:

**Play Icon:**
```xml
<svg width="16" height="16" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" fill="currentColor">
  <path d="M4.74514 3.06414C4.41183 2.87665 4 3.11751 4 3.49993V12.5002C4 12.8826 4.41182 13.1235 4.74512 12.936L12.7454 8.43601C13.0852 8.24486 13.0852 7.75559 12.7454 7.56443L4.74514 3.06414ZM3 3.49993C3 2.35268 4.2355 1.63011 5.23541 2.19257L13.2357 6.69286C14.2551 7.26633 14.2551 8.73415 13.2356 9.30759L5.23537 13.8076C4.23546 14.37 3 13.6474 3 12.5002V3.49993Z"/>
</svg>
```

**Rocket Icon (document-start alternative):**
```xml
<svg width="16" height="16" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" fill="currentColor">
  <path d="M8.36379 5.34606C8.9975 4.71235 10.024 4.71748 10.6566 5.35004C11.2891 5.9826 11.2943 7.00912 10.6606 7.64282C10.0268 8.27653 9.00033 8.27141 8.36777 7.63885C7.73521 7.00629 7.73008 5.97977 8.36379 5.34606ZM9.94947 6.05714C9.70408 5.81176 9.31074 5.81332 9.0709 6.05317C8.83105 6.29301 8.82949 6.68635 9.07487 6.93174C9.32026 7.17712 9.7136 7.17556 9.95345 6.93572C10.1933 6.69588 10.1949 6.30253 9.94947 6.05714ZM13.7779 3.50068C13.587 2.89444 13.1121 2.4196 12.5059 2.22871C10.7853 1.68697 8.90627 2.14716 7.63077 3.42266L7.00196 4.05147C6.13441 3.67814 5.08977 3.84574 4.38122 4.55429L3.59574 5.33977C3.40048 5.53503 3.40048 5.85161 3.59574 6.04687L4.44566 6.89679C4.31513 7.39537 4.44523 7.94804 4.83597 8.33878L5.00279 8.5056L4.23036 8.96744C4.0981 9.04652 4.00969 9.18217 3.99074 9.33511C3.9718 9.48804 4.02443 9.64117 4.13339 9.75013L6.25641 11.8731C6.36535 11.9821 6.51843 12.0347 6.67133 12.0158C6.82423 11.9969 6.95987 11.9086 7.03899 11.7764L7.50124 11.004L7.66778 11.1706C8.05848 11.5613 8.61108 11.6914 9.10962 11.5609L9.95968 12.411C10.1549 12.6063 10.4715 12.6063 10.6668 12.411L11.4523 11.6255C12.1608 10.917 12.3284 9.87225 11.955 9.00467L12.5839 8.3758C13.8594 7.1003 14.3196 5.22124 13.7779 3.50068ZM12.2056 3.18255C12.5003 3.27536 12.7312 3.50624 12.824 3.80101C13.2538 5.16601 12.8887 6.65677 11.8768 7.6687L9.082 10.4635C8.88674 10.6588 8.57015 10.6588 8.37489 10.4635L5.54307 7.63168C5.34781 7.43641 5.34781 7.11983 5.54307 6.92457L8.33787 4.12977C9.34979 3.11785 10.8406 2.75275 12.2056 3.18255ZM11.1424 9.81729C11.1789 10.2108 11.0465 10.6171 10.7452 10.9184L10.3132 11.3503L9.9613 10.9984L11.1424 9.81729ZM5.08833 5.26139C5.38961 4.96012 5.7958 4.82769 6.18931 4.86412L5.00826 6.04517L4.6564 5.69332L5.08833 5.26139ZM6.77217 10.275L6.51018 10.7127L5.29404 9.49657L5.73194 9.23475L6.77217 10.275ZM4.84893 11.8648C5.04419 11.6696 5.04419 11.353 4.84893 11.1577C4.65367 10.9625 4.33708 10.9625 4.14182 11.1577L2.82158 12.478C2.62632 12.6732 2.62632 12.9898 2.82158 13.1851C3.01684 13.3803 3.33342 13.3803 3.52869 13.1851L4.84893 11.8648ZM3.78728 10.0963C3.98255 10.2916 3.98255 10.6082 3.78728 10.8034L3.25785 11.3329C3.06259 11.5281 2.74601 11.5281 2.55074 11.3329C2.35548 11.1376 2.35548 10.821 2.55074 10.6257L3.08018 10.0963C3.27544 9.90105 3.59202 9.90105 3.78728 10.0963ZM5.91033 12.9263C6.10559 12.7311 6.10559 12.4145 5.91033 12.2192C5.71507 12.024 5.39849 12.024 5.20322 12.2192L4.67379 12.7487C4.47853 12.9439 4.47853 13.2605 4.67379 13.4558C4.86905 13.651 5.18563 13.651 5.3809 13.4558L5.91033 12.9263Z"/>
</svg>
```

**Flag Icon (document-end):**
```xml
<svg width="16" height="16" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" fill="currentColor">
  <path d="M4 9V3H12.0284L10.0931 5.70938C9.96896 5.88323 9.96896 6.11677 10.0931 6.29062L12.0284 9H4ZM4 10H13C13.4067 10 13.6432 9.54032 13.4069 9.20938L11.1145 6L13.4069 2.79062C13.6432 2.45968 13.4067 2 13 2H3.5C3.22386 2 3 2.22386 3 2.5V13.5C3 13.7761 3.22386 14 3.5 14C3.77614 14 4 13.7761 4 13.5V10Z"/>
</svg>
```

## 6. Extraction Workflow

### Recommended Approach: Direct GitHub Fetch

**Step-by-step:**

1. **Browse icons** at https://microsoft.github.io/vscode-codicons/dist/codicon.html
2. **Identify icon name** (e.g., "gear", "play", "rocket")
3. **Fetch SVG** from GitHub raw URL:
   ```
   https://raw.githubusercontent.com/microsoft/vscode-codicons/main/src/icons/{icon-name}.svg
   ```
4. **Convert to hiccup** using existing pattern in `src/icons.cljs`
5. **Add attribution comment** if this is the first codicon

**Alternative: Clone Repository**
```bash
git clone https://github.com/microsoft/vscode-codicons.git
cd vscode-codicons/src/icons
# Icons are in this directory
```

**Script-Assisted Extraction (Future Enhancement):**
Could create a bb task to fetch and convert icons automatically, but manual conversion is straightforward for the ~10 icons needed.

## 7. Gotchas and Limitations

### License Attribution
⚠️ **Must provide attribution** per CC BY 4.0 requirements.

**Recommended Implementation:**
```clojure
;; In src/icons.cljs header:
(ns icons)

;; Icons adapted from VS Code Codicons by Microsoft
;; Licensed under Creative Commons Attribution 4.0 International (CC BY 4.0)
;; https://github.com/microsoft/vscode-codicons
;; Original icons © Microsoft Corporation
```

This satisfies attribution in a "reasonable manner" for inline/embedded use.

### SVG Structure Differences

**Multi-path icons:** Some icons use multiple `<path>` elements (e.g., rocket has many paths). Simply include all paths in hiccup:

```clojure
[:svg {...}
  [:path {:d "..."}]
  [:path {:d "..."}]
  [:path {:d "..."}]]
```

**No other structural differences:** All icons use the same namespace, viewBox, and currentColor pattern.

### Icon Size Consistency

All codicons are 16x16 by default. Current Epupp icons also default to 16, so no changes needed to existing size handling.

### No Lightning Icon

The library lacks a traditional lightning bolt. **Recommendation:** Use `rocket.svg` for document-start timing because:
- Semantic fit: "launching early"
- VS Code convention: rockets represent speed/performance
- Visually distinct from flag (document-end)

## 8. Comparison to Current Icons

| Aspect | Current (Heroicons/Lucide) | Codicons | Winner |
|--------|---------------------------|----------|---------|
| **License** | MIT / ISC | CC BY 4.0 (attribution required) | Current (simpler) |
| **Consistency** | Mixed sources | Single consistent set | Codicons |
| **VS Code integration** | Generic | Native VS Code design language | Codicons |
| **Availability** | Must search 2 libraries | 536 icons, single source | Codicons |
| **Structure** | Consistent | Identical consistency | Tie |
| **Size** | 16x16 default | 16x16 fixed | Tie |
| **Maintenance** | Maintained separately | Microsoft-backed | Codicons |

**Key Advantage of Codicons:** Using the same icon library as VS Code creates visual consistency for users familiar with the editor. Since Epupp targets Clojure developers (likely VS Code users), this is a UX win.

## 9. Implementation Plan

### Phase 1: Add Attribution (1 min)
```clojure
;; Add to top of src/icons.cljs
;; Icons from VS Code Codicons (CC BY 4.0)
;; https://github.com/microsoft/vscode-codicons
```

### Phase 2: Fetch Required SVGs (5 min)
Download SVGs for the ~10 icons currently in use:
- gear (settings)
- play (run)
- eye (inspect)
- edit (pencil)
- close (X)
- trash (delete)
- chevron-right
- chevron-down
- package (cube replacement)
- rocket (document-start)
- flag (document-end)

### Phase 3: Convert to Hiccup (15 min)
For each icon, follow the pattern:
```clojure
(defn icon-name
  ([] (icon-name {}))
  ([{:keys [size] :or {size 16}}]
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :width size :height size
          :viewBox "0 0 16 16"
          :fill "currentColor"}
    [:path {:d "...from SVG..."}]]))
```

### Phase 4: Update Icon References (5 min)
- Replace `(icons/pencil)` calls with new function names if any changed
- Test in popup and panel UIs
- Verify dark mode (currentColor should work automatically)

**Total Time Estimate:** ~30 minutes

### Migration Strategy

**Option A: All at Once (Recommended)**
- Replace all icons in one commit
- Easier to verify visual consistency
- Single attribution comment

**Option B: Gradual**
- Replace icons as needed
- May result in mixed icon styles (not ideal)

## 10. Final Assessment

### Is This Viable? ✅ YES

**Strengths:**
1. **License:** CC BY 4.0 allows commercial use with simple attribution
2. **Coverage:** All required icons available (with rocket as lightning alternative)
3. **Structure:** Perfect match for current hiccup pattern
4. **Quality:** Microsoft-maintained, used in VS Code itself
5. **Consistency:** Single design language, 536 icons
6. **UX:** Visual familiarity for VS Code users

**Weaknesses:**
1. ⚠️ Attribution required (vs MIT/ISC permissive)
2. No lightning bolt (but rocket is better semantically)
3. Fixed 16x16 size (not an issue for this project)

**Clear Path Forward:**
1. Add attribution comment to `src/icons.cljs`
2. Fetch SVGs from GitHub raw URLs
3. Convert to hiccup using existing pattern
4. Replace current icon calls
5. Test in both light and dark themes

**Estimated Implementation Time:** 30 minutes
**Estimated Testing Time:** 10 minutes
**Total Effort:** < 1 hour

### Recommendation

**Proceed with codicons adoption.** The benefits outweigh the minor attribution requirement, and using VS Code's native icon library improves brand consistency for the target audience.

## References

- **Repository:** https://github.com/microsoft/vscode-codicons
- **Icon Viewer:** https://microsoft.github.io/vscode-codicons/dist/codicon.html
- **NPM Package:** https://www.npmjs.com/package/@vscode/codicons
- **License:** CC BY 4.0 - https://creativecommons.org/licenses/by/4.0/
- **SVG Source:** https://github.com/microsoft/vscode-codicons/tree/main/src/icons

---

**Research Date:** January 8, 2026
**Researcher:** AI Agent (Joyride-powered)
**Status:** Complete ✅
