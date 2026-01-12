# E2E Test TODO

Tracking gaps in E2E test coverage and planned additions.

## Gist Installer

**Current coverage:** ~30% (1 test covering happy path)

### Existing Test
- `log_powered_test.cljs`: "gist installer shows Install button and installs script"
  - Install button appears on installable gist ✅
  - Confirmation modal appears on click ✅
  - Confirm → button changes to "Installed" ✅
  - Script appears in popup after install ✅

### Missing Tests

**Negative Cases (high priority):**
- [ ] No Install button on files without manifest
- [ ] Cancel button in modal → no installation occurs
- [ ] Network error fetching raw gist URL → error state

**UI Validation:**
- [ ] Name normalization shown in modal (e.g., "My Script" → "my_script.cljs")
- [ ] Description displays in confirmation modal
- [ ] Run-at timing displays correctly
- [ ] Invalid run-at value shows warning + uses default

**Edge Cases:**
- [ ] Multiple installable files on same gist page
- [ ] Reinstalling already-installed script (update behavior)
- [ ] Vector `:epupp/site-match` (vs string)
- [ ] Script with `:epupp/require` installs correctly

**Functional Verification:**
- [ ] Installed script actually runs on matching page

### Mock Page Updates Needed

Current `test-data/pages/mock-gist.html` has:
- One installable file (with manifest)
- One non-installable file (no manifest)

May need additional mock files for edge case testing.

---

## Phase 3B: Require Injection

**Current coverage:** 0% (auto-injection tested, manual flows not)

### Missing Tests

- [ ] Panel eval with `:epupp/require` injects libraries
- [ ] Popup "Run" button with script that has requires injects libraries
- [ ] Requires injected even when Scittle already loaded (libraries might not be)

See [scittle-dependencies-implementation.md](scittle-dependencies-implementation.md) for test specifications.
