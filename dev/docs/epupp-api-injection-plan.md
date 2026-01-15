# Plan: Inject Epupp API from Scittle Source Files

## Problem Statement

The `epupp.repl/manifest!` and `epupp.fs/*` functions were defined as inline strings in `background.cljs` and evaluated via `scittle.core.eval_string`. This approach makes the code:
- Hard to edit (strings within strings, escaping nightmare)
- Impossible to lint or syntax-check
- Difficult to test in isolation

The functions have been extracted to proper Scittle source files at `extension/bundled/epupp/`:
- `repl.cljs` - provides `epupp.repl/manifest!`
- `fs.cljs` - provides `epupp.fs/cat`, `epupp.fs/ls`, `epupp.fs/save!`, `epupp.fs/mv!`, `epupp.fs/rm!`

## Goal

Inject `extension/bundled/epupp/*.cljs` files as inline Scittle script tags during REPL connect, similar to how userscripts are injected. The TDD target is the failing test:

```
epupp.repl/manifest! loads Replicant for REPL evaluation
```

Success criteria:
1. The REPL manifest test passes (without modifying the test)
2. Both `epupp.fs` and `epupp.repl` namespaces are available after REPL connect
3. Clean separation: Scittle code in `.cljs` files, injection logic in background.cljs

## Current Architecture

### How Userscripts Are Injected (reference pattern)

1. `execute-scripts!` in `background.cljs` orchestrates injection
2. Content bridge is injected into page (`inject-content-script`)
3. Bridge readiness is confirmed via ping (`wait-for-bridge-ready`)
4. Required Scittle libraries are injected via `inject-script` message
5. Userscript code is injected via `inject-userscript` message (creates inline `<script type="application/x-scittle">` tags)
6. `trigger-scittle.js` is injected to evaluate all pending Scittle scripts

### How REPL Connect Currently Works (before this change)

1. `connect-tab!` orchestrates the connection
2. `ensure-bridge!` injects content bridge
3. `ensure-scittle!` loads Scittle core
4. `ensure-scittle-nrepl!` loads scittle.nrepl
5. `inject-epupp-namespace!` evaluates `epupp-namespace-code` string via `eval_scittle_fn` ← **this is what we're replacing**

### Current Files

| File | Role |
|------|------|
| `extension/bundled/epupp/repl.cljs` | Extracted `epupp.repl` namespace (Scittle source) |
| `extension/bundled/epupp/fs.cljs` | Extracted `epupp.fs` namespace (Scittle source) |
| `src/background.cljs` | Contains `inject-epupp-namespace!` that needs modification |
| `src/content_bridge.cljs` | Handles `inject-userscript` message for Scittle code injection |

## Implementation Plan

### Phase 0: Extract Bundled Files to Plain Clojure - DONE

**Goal**: Create proper Scittle source files from the inline string definitions.

**Status**: Complete. Files are now plain Clojure at:
- `extension/bundled/epupp/repl.cljs`
- `extension/bundled/epupp/fs.cljs`

### Phase 1: Verify Bundled Files Are Accessible

**Goal**: Ensure the bundled epupp files can be fetched by the background script.

**Analysis**: The background script will fetch file content using `fetch(chrome.runtime.getURL(...))`. Background scripts have full access to extension resources, so files in `extension/bundled/` should be accessible without adding them to `web_accessible_resources`.

**Steps**:
1. Verify files are included in the build output (check `dist/chrome/bundled/epupp/` or the active build output directory)
2. If not, update the build script to copy bundled files

**Note**: `web_accessible_resources` is only needed if web pages need direct access to these URLs. Since we fetch in background and pass content via message, this may not be required.

**Verification**: Confirm `fetch(chrome.runtime.getURL('bundled/epupp/repl.cljs'))` works in background context (can be validated in a temporary REPL evaluation or a small test helper).

### Phase 2: Design - Fetch and Inject Pattern

**Goal**: Determine how to load Scittle code from bundled files.

**Analysis**: The current Scittle pipeline in this extension evaluates inline script content in `<script type="application/x-scittle">` tags. It does not include a `src`-based loading path. The existing `inject-userscript` message already handles inline Scittle injection. We have two options:

**Option A - Fetch in background, inject inline**: Background fetches file content via `chrome.runtime.getURL` + fetch, sends code to bridge via existing `inject-userscript` message.

**Option B - New message to fetch and inject**: Add `inject-scittle-url` handler to content bridge that fetches the URL and creates inline script tag.

**Chosen: Option A** - Simpler, reuses existing `inject-userscript` infrastructure, keeps fetch logic in background where we have full access to extension URLs.

**Result**: No code changes in this phase - just design decision documented above.

**Unit test**: Not applicable - this is a design phase.

**E2E verification**: Covered in Phase 3 implementation.

### Phase 3: Implement Epupp API Injection

**Goal**: Replace `inject-epupp-namespace!` to inject bundled files instead of eval'ing strings.

**Steps**:
1. Create `inject-epupp-api!` function in `background.cljs`
2. Function fetches each bundled file via `chrome.runtime.getURL` + `fetch`
3. Sends fetched code to content bridge via `inject-userscript` message with stable ids (e.g., `epupp-repl`, `epupp-fs`)
4. Triggers Scittle evaluation with `trigger-scittle.js` after all files injected (same mechanism used for userscripts)
5. Replace call to `inject-epupp-namespace!` with `inject-epupp-api!` in `connect-tab!`
6. Remove dead code: `epupp-namespace-code`, `inject-epupp-namespace!`

**Order of injection**:
1. `bundled/epupp/repl.cljs` - REPL utilities (manifest!)
2. `bundled/epupp/fs.cljs` - File system operations

**E2E verification**:
- Add test event logging via `test-logger/log-event!` for `EPUPP_API_INJECTED` with file list
- Verify in existing manifest test that files were injected via logged events
- Could add dedicated injection test: connect, check events for both repl.cljs and fs.cljs injection

### Phase 4: Run TDD Target Test

**Goal**: Verify the failing test now passes.

```bash
bb test:e2e --serial -- --grep "manifest"
```

Expected: `epupp.repl/manifest! loads Replicant for REPL evaluation` passes.

### Phase 5: Verify Full Test Suite

**Goal**: Ensure no regressions.

```bash
bb test
bb test:e2e
```

### Phase 6: Cleanup

**Steps**:
1. Rename `*_xtest.cljs` files back to `*_test.cljs` for FS tests (if they pass)
2. Update documentation if needed
3. Move this plan document to archive when complete

## File Changes Summary

| File | Change |
|------|--------|
| `src/background.cljs` | Replace `inject-epupp-namespace!` with `inject-epupp-api!` (fetch + inject pattern) |
| Build config (if needed) | Ensure `bundled/epupp/*.cljs` files are copied to dist |

Note: No changes needed to `content_bridge.cljs` - we reuse the existing `inject-userscript` message handler.
Note: `manifest.json` changes are likely unnecessary since background fetches files directly, and the page never fetches these URLs.

## Risk Mitigation

1. **Scittle evaluation timing**: After injecting script tags, we trigger evaluation with `trigger-scittle.js` - same proven pattern as userscripts
2. **Idempotency**: Namespace redefinition in Scittle should be safe for these namespaces (overwrites definitions). For performance, could track injected files, but not critical for correctness
	- Note: `inject-userscript` does not dedupe, so use stable ids if you want to reason about duplicates
3. **Load order**: Inject `repl.cljs` before `fs.cljs` (fs may depend on repl utilities in future)
4. **Fetch failures**: Extension resources should always be available, but add error handling for robustness

## Execution Sequence

1. [x] Phase 0: Extract bundled files to plain Clojure - DONE
2. [ ] Phase 1: Verify bundled files are accessible from background + build includes them
3. [ ] Phase 2: Design fetch + inject pattern (no content bridge changes needed)
4. [ ] Phase 3: Implement `inject-epupp-api!` in background.cljs
5. [ ] Phase 4: Run TDD target test
6. [ ] Phase 5: Full test suite
7. [ ] Phase 6: Cleanup

## Notes

- Both `repl.cljs` and `fs.cljs` define their own `send-and-receive` helper - this duplication is acceptable for now (isolation), could be extracted to a shared namespace later

## Alternative Considered: Inline at Build Time

Read file contents at build time and embed as strings in compiled JS. Rejected because:
- Requires build pipeline changes
- Strings still need escaping in the bundled output
- Files wouldn't be editable/inspectable in installed extension
- Our approach (runtime fetch) is simpler and keeps files as-is
