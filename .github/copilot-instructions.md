# Epupp - AI Coding Agent Instructions

## Quick Start for AI Agents

**Essential facts:**
- **Language:** Squint (ClojureScript-like, compiles to JS) - source in `src/*.cljs`
- **Never edit:** `extension/*.mjs` or `build/*.js` (compiled artifacts)
- **Testing:** Run `bb test:e2e` (headless by default, includes REPL integration)
- **Commands:** PREFER `bb <task>` - over direct `npx`/`npm` commands
- **Watchers:** Usually already running - check task output before building

**Start work setup:**
1. Check `clojure_list_sessions` to verify REPL availability (look for both `squint` and `scittle-dev-repl` sessions)
2. Consult the **Documentation Index** below based on your task

<principles>

  <epistemology>
    Assumptions are the enemy. Never guess numerical values - benchmark instead of estimating.
    When uncertain, measure. Say "this needs to be measured" rather than inventing statistics.
  </epistemology>

  <scaling>
    Validate at small scale before scaling up. Run a sub-minute version first to verify the
    full pipeline works. When scaling, only the scale parameter should change.
  </scaling>

  <ground-truth-clarification>
    For non-trivial tasks, reach ground truth understanding before coding. Simple tasks execute
    immediately. Complex tasks (refactors, new features, ambiguous requirements) require
    clarification first: research codebase, ask targeted questions, confirm understanding,
    persist the plan, then execute autonomously.
  </ground-truth-clarification>

  <delegation>
    You use subagents intelligently.
  </delegation>

  <style>No emojis. No em dashes - use hyphens or colons instead.</style>

  <bb-tasks>
    Always prefer `bb <task>` over direct `npx`/`npm` commands. The bb tasks in `bb.edn` encode
    project-specific configurations, output paths, and workflow decisions. Running tools directly
    bypasses these and often produces incorrect results (wrong output directories, missing flags).
    Check `bb tasks` for available commands before resorting to direct tool invocation.

    **E2E tests and Docker**: Use `bb test:e2e` exclusively. Never use direct `docker build` or
    `docker run` commands. The Dockerfile uses `COPY . .` which invalidates layers when source
    files change - Docker caching is NOT a problem. If tests fail after code changes, the issue
    is in your code, not in Docker caching. Do not waste time with `--no-cache` rebuilds.
  </bb-tasks>

  <babashka-utilities>
    Prefer Babashka built-in utilities over Python, shell scripts, or other external tools when
    functionality overlaps. The project uses Babashka extensively and has dependencies loaded.

    Common replacements:
    - HTTP server: Use `bb test:server` or `babashka.http-server` instead of `python -m http.server`
    - File operations: Use `babashka.fs` instead of shell commands (cp, mv, rm, find, etc.)
    - Process execution: Use `babashka.process` instead of raw shell scripts
    - HTTP requests: Use `babashka.http-client` instead of curl or wget

    Check bb.edn dependencies and existing tasks before reaching for external tools.
  </babashka-utilities>
</principles>

## Documentation Index

**Workflow**: Select docs based on task. Return here when your understanding evolves and you need different context.

### Critical (read for any code change)
| Doc | When to Read |
|-----|--------------|
| `.github/squint.instructions.md` | Editing ANY `.cljs` file - Squint gotchas will bite you |

### By Task Type

| Task | Read These |
|------|-----------|
| **Understanding the system** | `dev/docs/architecture/overview.md` - has navigation to detailed docs |
| **Writing tests** | `dev/docs/testing.md` |
| **UI work (popup/panel)** | `.github/reagami.instructions.md`, `dev/docs/ui.md` |
| **State/events** | `dev/docs/architecture/uniflow.md`, `dev/docs/architecture/state-management.md` |
| **Messaging between contexts** | `dev/docs/architecture/message-protocol.md` |
| **Injection/REPL flow** | `dev/docs/architecture/injection-flows.md` |
| **Userscript features** | `dev/docs/userscripts-architecture.md` |
| **Build/release** | `dev/docs/dev.md` |
| **Finding source files** | `dev/docs/architecture/components.md` |

### Reference (consult as needed)
- `README.md` - User-facing overview
- `dev/docs/architecture/security.md` - Trust boundaries, CSP
- `dev/docs/architecture/build-pipeline.md` - Build config injection

## Subagents

There are currently three subagents:

* commit: Give the commit subagent a summary of the task (the bigger picture) that has been carried out
* research: Give the research subagent context of what you are working with and need to know and instruct it how you want it to structure its report.
* edit: Give the edit subagent a complete task with files, linenumbers, code and what to do with it. It should be very much the same as you would have given to the edit tools if you used them yourself.

## Source Code: Squint ClojureScript

This is a **Squint** project. All application logic lives in `src/*.cljs` files.

**Source of truth:** `src/*.cljs` (ClojureScript)

**Ignore when reading code** (compiled artifacts, not source):
- `extension/*.mjs` - Squint compiler output
- `build/*.js` - esbuild bundled output

Only read `.mjs` or `build/*.js` files when debugging compilation issues. Never edit them.

## Project Overview

**Epupp** is a browser extension that injects a [Scittle](https://github.com/babashka/scittle) REPL server into web pages, enabling ClojureScript evaluation directly in the browser DOM via nREPL. This bridges your Clojure editor (or AI agent) with the browser's execution environment through a **Babashka relay server**.

**Architecture in brief:**
`Editor/AI nREPL client` ↔ `Babashka browser-nrepl (ports 12345/12346)` ↔ `Extension background worker` ↔ `Content bridge script` ↔ `Page WebSocket bridge` ↔ `Scittle REPL` ↔ `DOM`

## Critical Build System Understanding

### Squint Compilation Model

This project uses **[Squint](https://github.com/squint-cljs/squint)** to compile ClojureScript to modern ESM JavaScript. Key points:

- **Source:** `src/*.cljs` files are compiled to `extension/*.mjs` (ES modules)
- **Bundling:** esbuild bundles `extension/*.mjs` → `build/*.js` (IIFE format)
- **Config:** `squint.edn` specifies paths and `.mjs` extension

**Build flow:**
```bash
npx squint compile  # src/*.cljs → extension/*.mjs
npx esbuild ...     # extension/*.mjs → build/*.js (IIFE bundles)
```

**CRITICAL: Treat `.mjs` files as compiled artifacts (like binaries).** Never edit them directly - they are auto-generated from `.cljs` source. Only read `.mjs` files when investigating Squint compilation issues. Always edit `.cljs` source in `src/`.

### Scittle CSP Patching

Scittle is patched to remove `eval()` for CSP compatibility. See `dev/docs/dev.md` (section: Patching Scittle for CSP Compatibility) for details. **Always run `bb bundle-scittle` after updating Scittle version.**

## Key Developer Workflows

See `dev/docs/dev.md` for build commands, loading extension locally, and release process. Note that the instructions there for setting up for local development are meant for the human.

The Squint REPL is useful for testing code and pure functions interactively before editing files. See `.github/squint.instructions.md` (section: Testing Code in Squint nREPL).

### Quick Command Reference

**For AI Agents (Prefer These):**

| Command | Purpose |
|---------|--------|
| `bb test` | Unit tests (fast, always run after changes) |
| `bb test:e2e` | E2E tests (headless in Docker, includes REPL integration, always builds) without version bump |
| `bb build:dev` | Dev build, when handing off to human for manual testing |

**Filtering tests:** Pass `--grep "pattern"` to any test command:
```bash
bb test:e2e --grep "popup"   # Run only popup tests
```

E2E tasks accept all Playwright options, so you should not need to resort to using Playwright directly.

**Critical:** After building or code changes, wait for user confirmation before committing.

### AI Development Workflow (Local - VS Code with Human)

**Before starting work:**
1. **Verify watchers are running** - check watcher task output for compilation/test status
2. **Check problem report** - review any existing lint errors
3. **Verify REPLs** - use `clojure_list_sessions` to confirm available sessions:
   - `squint` - Squint nREPL (port 1337) for testing pure functions in Node.js
   - `scittle-dev-repl` - Scittle Dev REPL (port 31337) for testing Scittle code in browser-like environment

**CRITICAL: Watcher verification is mandatory.** Use `get_task_output` with these task IDs:
- `shell: Squint Watch` - compilation status
- `shell: Unit Test Watch` - test status
- `shell: Scittle Dev REPL` - browser-nrepl relay status

If `get_task_output` returns "Terminal not found", **STOP and tell the user**:
> "I cannot find the watcher task outputs. This happens when VS Code's terminal tracking gets out of sync. Please restart the default build task (Cmd/Ctrl+Shift+B) to restore the watchers, then ask me to continue."

Do NOT proceed without watcher feedback - it's essential for verifying compilation and test results.

**While working:**
- Use the **Squint REPL** (`squint` session) to test pure functions in Node.js
- Use the **Scittle Dev REPL** (`scittle-dev-repl` session) to test Scittle-specific code in a browser-like environment
- Develop solutions incrementally in the appropriate REPL

**After editing files:**
1. **Check watcher output** - verify compilation succeeded and tests pass
2. **Check problem report** - fix any new lint errors
3. Address any issues before proceeding

## Common Pitfalls

1. **Treat `.mjs` as binary** - These are Squint-compiled output. Never edit, and only read when debugging compilation. Edit `.cljs` source in `src/`.
2. **Squint != ClojureScript** - see `.github/squint.instructions.md` for gotchas (keywords are strings, missing `name` function, mutable data).
3. **Run `bb bundle-scittle`** after Scittle version updates - the CSP patch is critical.
4. **Test on CSP-strict sites** (GitHub, YouTube) to verify Scittle patch works.
5. **WebSocket readyState management** - set to `3` (CLOSED) in `ws-close` handler (`src/ws_bridge.cljs`) to prevent reconnection loops.
6. **Firefox CSP** - `content_security_policy` in manifest must allow `ws://localhost:*` for local connections.

## Use Subagents to protect your contect window and ensure quality

The following subagents are available to you:

- `research` subagent, for gathering information, from the codebase as well as external sources.
- `edit` subagent, editing can be finicky and waste context, let the edit subagent handle the details.
- `commit` subagent, for an expert git agent. Give it good and succinct context of the work to be committed.
