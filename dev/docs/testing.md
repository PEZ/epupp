# Testing

This documentation has been split for easier navigation:

- **[Unit Testing](testing-unit.md)** - Vitest, pure functions, Squint gotchas
- **[E2E Testing](testing-e2e.md)** - Playwright, browser extension workflows, REPL integration

## Meaningful Tests

A great test covers behavior users depend on. It tests a feature that, if broken, would frustrate or block users.

It validates real workflows - not implementation details. It catches regressions before users do.

Do NOT write tests just to increase coverage. Use coverage as a guide to find UNTESTED USER-FACING BEHAVIOR.

If uncovered code is not worth testing (boilerplate, unreachable error branches, internal plumbing), then don't add tests for it.

## Test-Driven Development

Lead new work with tests to lock in intent and expose regressions early.


## Test Layers

| Layer | Tool | Speed | What to Test |
|-------|------|-------|--------------|
| Unit | Vitest | Fast (~1s) | Pure functions, state transitions, data transformations |
| E2E | Playwright + browser-nrepl | Medium (~40s) | Extension UI, REPL integration, full nREPL pipeline |

## Quick Commands

**Default (AI Agents and Automated Testing):**
- `bb test` - Unit tests (fast, always run after changes)
- `bb test:e2e` - All E2E tests (headless in Docker), includes REPL integration

**Human Developers (Visible Browser):**
- `bb test:watch` - Unit test watcher
- `bb test:e2e:headed` - E2E tests (browsers visible)
- `bb test:e2e:ui:headed` - Playwright UI (interactive debugging)

**CI/CD Only:**
- `bb test:e2e:ci` - E2E without rebuild (GitHub Actions)

**Filter tests:** Pass `--grep "pattern"` to any Playwright test:
```bash
bb test:e2e --grep "popup"
bb test:e2e --grep "approval"
```

## What Goes Where?

### Unit Tests (`test/*.cljs`)

- Uniflow action handlers (pure state transitions)
- URL pattern matching (`url-matches-pattern?`)
- Script utilities (ID generation, validation)
- Data transformations
- Manifest parsing

**Rule of thumb**: If it's a pure function that takes input and returns output without side effects, unit test it.

### E2E Tests (`e2e/*_test.cljs`, `e2e/*_spec.cljs`)

- Extension loading and initialization
- Popup UI workflows (REPL setup, script management, approvals)
- Panel evaluation and save workflows
- Cross-component storage flows
- Internal behavior via log-powered tests
- Full nREPL evaluation pipeline (REPL integration)
- Connection establishment and tracking
- Code evaluation in page context
- DOM access from REPL

**Rule of thumb**: If it involves multiple Epupp components, requires a browser, DOM, Chrome APIs, or the REPL relay server, E2E test it.

## Key Principles

1. **TDD encouraged** - Write failing tests first, then implement. Use `bb test:watch` for fast unit TDD cycles
2. **Extract pure functions** - Most business logic should be unit-testable
3. **No sleep patterns** - Use Playwright's auto-waiting, not arbitrary timeouts
4. **Short timeouts for TDD** - When doing E2E TDD, use short wait timeouts (~500ms) to fail fast
5. **Consolidated journeys** - E2E tests cover complete user workflows, not isolated clicks
6. **Build mode matters** - E2E tests require `bb build:test` (dev config without version bump)

## Infrastructure Summary

| Component | Port | Purpose |
|-----------|------|---------|
| HTTP test server | 18080 | Serves `test-data/pages/` for E2E |
| browser-nrepl | 12345/12346 | REPL relay (nREPL/WebSocket) |

Servers are started automatically by `bb test:e2e` tasks.

