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

Inject `extension/bundled/epupp/*.cljs` files as Scittle script tags during REPL connect, similar to how userscripts are injected. The TDD target is the failing test:

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
5. Userscript code is injected via `inject-userscript` message (creates `<script type="application/x-scittle">` tag)
6. `trigger-scittle.js` is injected to evaluate all pending Scittle scripts

### How REPL Connect Currently Works

1. `connect-tab!` orchestrates the connection
2. `ensure-bridge!` injects content bridge
3. `ensure-scittle!` loads Scittle core
4. `ensure-scittle-nrepl!` loads scittle.nrepl
5. `inject-epupp-namespace!` evaluates `epupp-namespace-code` string via `eval_scittle_fn`

### Current Files

| File | Role |
|------|------|
| `extension/bundled/epupp/repl.cljs` | Extracted `epupp.repl` namespace (Scittle source) |
| `extension/bundled/epupp/fs.cljs` | Extracted `epupp.fs` namespace (Scittle source) |
| `src/background.cljs` | Contains `inject-epupp-namespace!` that needs modification |
| `src/content_bridge.cljs` | Handles `inject-userscript` message for Scittle code injection |

## Implementation Plan

### Phase 0: Convert Bundled Files to Plain Clojure ;pez: the notebook format is lie, they were previous defined as a plain string.

**Goal**: Strip VS Code Notebook XML wrappers so Scittle can parse the files.

**Problem**: The extracted files use VS Code's notebook format with XML cell wrappers:
```xml
<VSCode.Cell id="#VSC-..." language="clojure">
(ns epupp.repl ...)
</VSCode.Cell>
```

Scittle expects plain Clojure source.

**Steps**:
1. Convert `extension/bundled/epupp/repl.cljs` to plain Clojure ;pez: this is done
2. Convert `extension/bundled/epupp/fs.cljs` to plain Clojure   :pez: this is done
3. Verify files are syntactically correct ;pez: this is done

**Verification**: Files should be parseable by any Clojure reader. ;pez: this is done

### Phase 1: Make Bundled Files Web-Accessible

**Goal**: Ensure the bundled epupp files can be served by the extension.

**Steps**:
1. Update `extension/manifest.json` to include `bundled/epupp/*.cljs` in `web_accessible_resources`

**Verification**: Manual check that files are accessible at `chrome-extension://<id>/bundled/epupp/repl.cljs`

### Phase 2: Add Bundled File Loading to Content Bridge

**Goal**: Content bridge can inject Scittle code from extension URLs (not just vendor scripts).

**Analysis**: The existing `inject-script` message injects `<script src="...">` tags, which is for JavaScript. For Scittle code files, we need `inject-scittle-src` that creates `<script type="application/x-scittle" src="...">` tags.

**Steps**:
1. Add new message handler `inject-scittle-src` to `content_bridge.cljs`
2. Handler creates `<script type="application/x-scittle" src="url">` and waits for load
3. Include idempotency tracking (similar to `inject-script-tag!`)

**Unit test**: Not applicable (Chrome API dependent)

**E2E verification**: Will be verified by Phase 4 test ;pez: if there is any way we can unit test parts of it  that should be done, and same for e2e, we could at least verify that we are injecting things as we expect.

### Phase 3: Implement Epupp API Injection

**Goal**: Replace `inject-epupp-namespace!` to inject bundled files instead of eval'ing strings.

**Steps**:
1. Create `inject-epupp-api!` function in `background.cljs`
2. Function sends `inject-scittle-src` for each bundled epupp file
3. Triggers Scittle evaluation after injection
4. Replace call to `inject-epupp-namespace!` with `inject-epupp-api!` in `connect-tab!`
5. Remove dead code: `epupp-namespace-code`, `inject-epupp-namespace!`

**Order of injection**:
1. `bundled/epupp/repl.cljs` - REPL utilities (manifest!)
2. `bundled/epupp/fs.cljs` - File system operations

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
1. Remove the placeholder `epupp-namespace-code` def
2. Rename `*_xtest.cljs` files back to `*_test.cljs` for FS tests (if they pass)
3. Update documentation if needed

## File Changes Summary

| File | Change |
|------|--------|
| `extension/manifest.json` | Add `bundled/epupp/*.cljs` to web_accessible_resources |
| `src/content_bridge.cljs` | Add `inject-scittle-src` message handler |
| `src/background.cljs` | Replace `inject-epupp-namespace!` with `inject-epupp-api!` |

## Risk Mitigation

1. **Scittle evaluation timing**: The `trigger-scittle.js` approach is proven for userscripts - we reuse the same pattern
2. **Idempotency**: Track injected URLs to prevent duplicate injection on reconnect
3. **Load order**: Inject `repl.cljs` before `fs.cljs` (fs may depend on repl utilities in future)
4. **VS Code Notebook format**: Files must be plain Clojure - strip XML cell wrappers before use

## Execution Sequence

1. [x] Phase 0: Convert bundled files from strings to plain Clojure
2. [ ] Phase 1: Update manifest.json
3. [ ] Phase 2: Add `inject-scittle-src` handler to content bridge
4. [ ] Phase 3: Implement `inject-epupp-api!` in background.cljs
5. [ ] Phase 4: Run TDD target test
6. [ ] Phase 5: Full test suite
7. [ ] Phase 6: Cleanup

## Notes

- **IMPORTANT**: The bundled `.cljs` files currently use VS Code notebook format (XML cells). This needs to be converted to plain Clojure before Scittle can parse them. Add Phase 0 to strip XML wrappers.
- Both `repl.cljs` and `fs.cljs` define their own `send-and-receive` helper - this duplication is acceptable for now (isolation), could be extracted to a shared namespace later

## Alternative Considered: Inline Injection via eval_string

Keep the current `eval_scittle_fn` approach but read files at build time. Rejected because:
- Still requires string escaping in the build pipeline
- Doesn't leverage Scittle's native script tag loading
- More complex build setup
