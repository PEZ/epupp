# GitHub Copilot Coding Agent Configuration Research

**Date:** January 5, 2026
**Project:** Scittle Tamper (Squint/ClojureScript + Babashka)
**Purpose:** Configure repository for GitHub Copilot coding agent

## Executive Summary

GitHub Copilot coding agent can be configured for repository-specific guidance through **custom instructions files**, primarily `.github/copilot-instructions.md`. There is **no** separate build/test configuration file like `.github/copilot-setup-steps.yml`. All build, test, and environment setup instructions go into the markdown instructions file.

## Key Findings

### 1. Configuration File Location and Format

**Primary configuration file:**
- **Path:** `.github/copilot-instructions.md`
- **Format:** Markdown with natural language instructions
- **Purpose:** Repository-wide custom instructions for Copilot coding agent
- **Scope:** Read by Copilot coding agent when working in the repository

**Alternative/Additional files:**
- **Path-specific instructions:** `.github/instructions/*.instructions.md` with frontmatter
- **Agent instructions:** `AGENTS.md`, `CLAUDE.md`, or `GEMINI.md` (nearest in directory tree takes precedence)

### 2. No Separate Setup YAML File

**Critical:** There is **no** `.github/copilot-setup-steps.yml` or similar YAML configuration file for build/test steps. All setup, build, and test instructions are written in **natural language** within `.github/copilot-instructions.md`.

GitHub Copilot coding agent:
- Works autonomously in a **GitHub Actions-powered ephemeral development environment**
- Has **read-only access** to the repository
- Can **run commands** based on instructions in the custom instructions file
- Creates and pushes to branches starting with `copilot/`

### 3. Content Structure for `.github/copilot-instructions.md`

Based on official GitHub documentation, the file should include:

#### High-Level Details
- Repository summary (what it does)
- Languages, frameworks, target runtimes
- Project size and type

#### Build Instructions
For each of bootstrap, build, test, run, lint:
- **Sequence of steps** to run successfully
- **Versions** of runtime/build tools
- **Preconditions and postconditions**
- **Command validation** - each command should be verified as working
- **Error documentation** - document observed errors and workarounds
- **Timing** - document commands that may timeout
- **Environment setup** - even "optional" steps that are actually required

**Example for this project:**
```markdown
## Build and Test Commands

### Prerequisites
- Babashka 1.x (tested with 1.3+)
- Node.js 18+ for npm dependencies

### Setup (first time)
```bash
npm install
```

### Development Workflow
1. Start watchers: `bb watch` (Squint compilation) and `bb test:watch` (unit tests)
2. Start Squint nREPL: `bb squint-nrepl` (port 1337)
3. Make changes to `.cljs` files in `src/`
4. Watchers auto-compile `src/*.cljs` → `extension/*.mjs` → `build/*.js`

### Testing
- **Unit tests:** `bb test` (run once) or `bb test:watch` (continuous)
- **E2E tests:** `bb test:e2e` (popup UI) or `bb test:repl-e2e` (full REPL pipeline)
- Tests use Vitest (unit) and Playwright (E2E)

### Build
- **Development:** `bb build:dev` (bumps dev version)
- **Production:** `bb build` (creates release builds for Chrome/Firefox/Safari)

### Critical Notes
- Never edit `.mjs` files - they are Squint-compiled output
- Always run `bb bundle-scittle` after updating Scittle version (CSP patch)
- Test on CSP-strict sites (GitHub, YouTube) to verify Scittle patch
```

#### Project Layout
- Major architectural elements with relative paths
- Main project files locations
- Configuration files (linting, compilation, testing)
- CI/CD checks and validation pipelines
- Dependencies not obvious from file structure
- File listings prioritizing structural importance

**Example for this project:**
```markdown
## Project Layout

### Source Code (Squint ClojureScript)
- `src/*.cljs` - Source files (compiled to `.mjs` by Squint)
- `extension/*.mjs` - Squint-compiled output (never edit directly)
- `build/*.js` - esbuild bundled output (IIFE format)

### Testing
- `test/*.cljs` - Unit tests (Vitest)
- `e2e/*.cljs` - E2E tests (Playwright)

### Configuration
- `squint.edn` - Squint compiler config
- `bb.edn` - Babashka task definitions
- `package.json` - npm dependencies
- `vitest.config.js` - Unit test config
- `playwright.config.js` - E2E test config

### Documentation
- `README.md` - User-facing overview
- `dev/docs/dev.md` - Development setup
- `dev/docs/architecture.md` - Technical reference
- `.github/copilot-instructions.md` - Agent instructions (this file)
```

### 4. Path-Specific Instructions

For more granular control, use `.github/instructions/*.instructions.md` files with frontmatter:

```markdown
---
applyTo: "**/*.cljs"
excludeAgent: "code-review"  # Optional: only for coding agent, not code review
description: "Squint ClojureScript patterns"
---

# Squint-Specific Guidelines

## Critical Differences from ClojureScript
- Keywords ARE strings (no `name` function needed)
- Data structures are mutable JavaScript objects
- No persistent data structures
...
```

**Glob patterns:**
- `*.cljs` - All `.cljs` files in current directory
- `**/*.cljs` - All `.cljs` files recursively
- `src/**/*.cljs` - All `.cljs` files under `src/`
- `**/test/**/*.cljs` - All test files

### 5. REPL Configuration

**No specific REPL configuration format exists.** Document REPL setup in the build instructions:

```markdown
## REPL Setup

### Squint nREPL for Testing Code
1. Start: `bb squint-nrepl` (listens on port 1337)
2. Connect from editor (e.g., Calva: "Connect to running REPL" → "squint")
3. Use for testing pure functions before editing files
4. Squint nREPL runs in Node.js, not browser (no browser APIs available)

### Testing in REPL
```clojure
;; Test pure functions
(require '[url-matching :refer [url-matches-pattern?]])
(url-matches-pattern? "https://github.com/foo" "*://github.com/*")
;; => true
```

**What works:** Pure functions, data transformations
**What doesn't work:** Browser APIs, extension messaging, DOM
```

### 6. Babashka Project Considerations

Babashka projects require clear task documentation since `bb.edn` defines tasks but not their purpose:

```markdown
## Babashka Tasks

Common tasks defined in `bb.edn`:

| Task | Purpose |
|------|---------|
| `bb watch` | Watch mode - auto-recompile Squint on file changes |
| `bb test` | Run unit tests once (Vitest) |
| `bb test:watch` | Unit test watch mode |
| `bb test:e2e` | Run Playwright E2E tests (popup UI) |
| `bb test:repl-e2e` | Run full REPL integration tests |
| `bb squint-nrepl` | Start Squint nREPL on port 1337 |
| `bb build` | Production build (all browsers) |
| `bb build:dev` | Development build (bumps version) |
| `bb bundle-scittle` | Bundle and patch Scittle for CSP |
| `bb publish` | Release workflow (version, tag, push) |

Run `bb tasks` to see all available tasks.
```

### 7. Agent Workflow Environment

**Execution environment:**
- GitHub Actions-powered ephemeral containers
- Read-only access to repository
- Can run shell commands based on instructions
- Internet access controlled by firewall (customizable)
- Creates branches with `copilot/` prefix
- Cannot push to `main` or `master`

**Security model:**
- Only users with **write access** can assign tasks to agent
- Pull requests created as **drafts** requiring approval
- CodeQL, secret scanning, dependency checks run automatically
- User who requested PR cannot approve it (enforces review)

### 8. Best Practices

#### For Custom Instructions

1. **Be explicit about command order**
   - Document which commands must run first
   - Note dependencies between steps

2. **Document errors and workarounds**
   - Include common failure modes
   - Provide tested solutions

3. **Keep under 2 pages**
   - Limit prevents context overload
   - Focus on essential information

4. **Validate all commands**
   - Test each command before documenting
   - Verify in clean environment

5. **Use imperative language**
   - "Always run npm install before building"
   - "Never edit `.mjs` files directly"

#### For This Project

1. **Emphasize Squint gotchas**
   - `.mjs` files are compiled output (never edit)
   - Keywords are strings
   - Mutable data structures

2. **Document CSP patch requirement**
   - Must run `bb bundle-scittle` after Scittle updates
   - Critical for extension to work on strict CSP sites

3. **Clarify test hierarchy**
   - Unit tests → E2E popup → E2E REPL (increasing scope)
   - When to run which tests

4. **REPL limitations**
   - Squint nREPL: pure functions only (Node.js)
   - Browser environment: via browser-nrepl relay

## Implementation Checklist

- [ ] Create `.github/copilot-instructions.md`
- [ ] Document build commands with exact steps
- [ ] Document test commands (unit, E2E)
- [ ] Document REPL setup (Squint nREPL on port 1337)
- [ ] Document Babashka tasks with descriptions
- [ ] Include project layout with file paths
- [ ] Document Squint-specific gotchas
- [ ] Document CSP patch requirement
- [ ] Add architecture overview from existing docs
- [ ] Reference existing dev documentation
- [ ] Test by asking Copilot agent to make a simple change
- [ ] Iterate based on agent's questions/struggles

## References

- **Official Docs:** https://docs.github.com/en/copilot/how-tos/configure-custom-instructions/add-repository-instructions
- **Coding Agent Overview:** https://docs.github.com/en/copilot/concepts/agents/coding-agent/about-coding-agent
- **Custom Instructions Template (from GitHub):** See "Asking Copilot coding agent to generate a copilot-instructions.md file" prompt in official docs
- **Existing Project Docs:**
  - `README.md` - User overview
  - `dev/docs/dev.md` - Development setup
  - `dev/docs/architecture.md` - Technical reference
  - `.github/copilot-instructions.md` - Already exists with comprehensive content

## Gotchas and Considerations

### 1. No YAML Configuration
Don't create `.github/copilot-setup-steps.yml` or similar - it doesn't exist. Everything goes in the markdown instructions file.

### 2. Natural Language Over Structure
Instructions are processed by LLM, not parsed. Write clearly but naturally. The agent interprets intent, not syntax.

### 3. Avoid Conflicting Instructions
If you have personal Copilot instructions or organization-level instructions, ensure they don't conflict with repository instructions. Repository instructions are applied **after** organization instructions and **before** personal instructions in priority.

### 4. Agent Can't See Everything
The agent gets:
- Repository files (read-only)
- Custom instructions
- Issue/PR context
- Previous conversation history

The agent **cannot** access:
- Other repositories (unless via MCP configuration)
- Private environment variables (unless exposed in instructions)
- External documentation sites (unless fetched via tools)

### 5. Testing Instructions with Agent
After creating/updating instructions, test by:
1. Create a simple issue (e.g., "Add a comment to explain function X")
2. Assign to `@copilot`
3. Review the PR Copilot creates
4. Check the session log to see if agent found instructions helpful
5. Iterate based on what the agent had to search for

### 6. MCP Integration
For advanced use cases, the coding agent can use **Model Context Protocol (MCP) servers** to:
- Access external data sources
- Use specialized tools
- Integrate with third-party services

This requires additional configuration but is beyond basic repository setup.

## Conclusion

GitHub Copilot coding agent configuration for this Squint/ClojureScript + Babashka project requires:

1. **One primary file:** `.github/copilot-instructions.md` (already exists)
2. **Content:** Natural language instructions covering build, test, REPL, architecture
3. **No YAML config:** All instructions are markdown
4. **Babashka clarity:** Document `bb` tasks with purpose, not just command
5. **Squint specifics:** Highlight compiled output, CSP patch, REPL limitations
6. **Testing strategy:** Unit → E2E hierarchy

The existing `.github/copilot-instructions.md` already provides comprehensive coverage. Review and enhance sections on:
- Exact build/test command sequences
- REPL setup for Squint (port 1337)
- Babashka task descriptions
- Common error scenarios and fixes
