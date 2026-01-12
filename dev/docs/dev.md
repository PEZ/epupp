## Development

### Prerequisites

- [Babashka](https://babashka.org/) (bb)
- Node.js (for npm dependencies)

### Setup

```bash
npm install
```

### Local Development Workflow

Start the development environment:

If you are using VS Code:

1. **Run the default build task** (Cmd/Ctrl+Shift+B) - starts Squint watch + unit test watcher
2. **Start Squint nREPL**: `bb squint-nrepl` (or run the "Squint nREPL" VS Code task)
3. **Connect your editor** to the Squint nREPL (port 1337)
   - Calva: "Connect to a running REPL" -> select "squint"
**VS Code tasks** (defined in `.vscode/tasks.json`):
- `Start Dev Environment` (default build) - runs watch + test:watch in parallel
- `Squint nREPL` - starts the nREPL server on port 1337

**Manual alternative** (separate terminals):
```bash
bb watch        # Terminal 1: Auto-compile on save
bb test:watch   # Terminal 2: Unit test watcher
bb squint-nrepl # Terminal 3: Squint nREPL
```

The Squint REPL lets you evaluate code in a Node.js environment, useful for testing pure functions that do not need the browser execution environment. See [squint.instructions.md](../../.github/squint.instructions.md#testing-code-in-squint-nrepl) for details.

### Testing

#### Unit Tests

Run unit tests once:
```bash
bb test
```

Start unit test watchers (Squint + Vitest in parallel):
```bash
bb test:watch
```

Unit tests use [Vitest](https://vitest.dev/) and live in `test/*.cljs`. Squint compiles them to `build/test/*.mjs` which Vitest watches and runs.

#### E2E Tests

**Default (AI Agents and Automated Testing):**
```bash
bb test:e2e  # All E2E tests in Docker (headless), includes REPL integration
```

**For humans** (headed tests with visible browser):
```bash
bb test:e2e:headed     # Run tests (builds extension first)
bb test:e2e:ui:headed  # Interactive Playwright UI
```

**Filter tests:** Pass Playwright options to any e2e task:
```bash
bb test:e2e --grep "popup"     # Run only popup tests
bb test:e2e:headed --debug     # Debug mode with inspector (headed)
```

**See [testing.md](testing.md) for:** complete testing strategy, fixtures, utilities, and troubleshooting.

### CI/CD Pipeline

GitHub Actions runs on every push and PR:

```
┌───────────────┐   ┌─────────────┐
│ build-release │   │ build-test  │  <- Parallel builds
└───────────────┘   └──────┬──────┘
                           │
                   ┌───────┴───────┐
                   ▼               ▼
            ┌────────────┐  ┌────────────┐
            │ unit-tests │  │ e2e-tests  │  <- Parallel tests
            └────────────┘  └────────────┘
                   │               │
                   └───────┬───────┘
                           ▼
                     ┌─────────┐
                     │ release │  <- On version tags only
                     └─────────┘
```

See [.github/workflows/build.yml](../../.github/workflows/build.yml) for details.

### Patching Scittle for CSP Compatibility

The vendored `scittle.js` contains a dynamic import polyfill that uses `eval()`:

```javascript
globalThis["import"]=eval("(x) => import(x)");
```

This breaks on sites with strict Content Security Policy (like YouTube, GitHub, etc.). The `bb bundle-scittle` task automatically patches this to use a regular function instead:

```javascript
globalThis["import"]=function(x){return import(x);};
```

The patch is applied automatically when running `bb bundle-scittle`.

### Build

Build for all browsers (production):
```bash
bb build
```

Build for development (bumps dev version for testing):
```bash
bb build:dev
```

Build for e2e tests (dev config without version bump):
```bash
bb build:test
```

The `build:test` task uses dev config (needed for test-only message handlers like `e2e/find-tab-id`) but doesn't bump the manifest version. This prevents version drift when running e2e tests repeatedly.

Build for specific browser:
```bash
bb build:chrome
bb build:firefox
bb build:safari
```

Dev versions use 4-part format (`0.0.7.0`, `0.0.7.1`). The 4th number is the build number, bumped by `bb build:dev`.

### Load Extension Locally

**Chrome:**

0. Unpack `epupp-chrome.zip` (will unpack a `chrome` folder)
1. Go to `chrome://extensions`
2. Enable "Developer mode"
3. Click "Load unpacked"
4. Select the `chrome` folder

**Firefox:**

1. Go to `about:debugging#/runtime/this-firefox`
2. Click "Load Temporary Add-on"
3. Select any file in `epupp-firefox.zip` file

**Safari:**

1. Safari → Settings → Developer → Click "Add Temporary Extension"
2. Select the `epupp-safari.zip` file
3. Ensure the extension is enabled in Safari → Settings → Extensions

## Distribution

### Release Process

Use the automated publish workflow:

```bash
bb publish
```

This will:
1. Verify git is clean and on master branch
2. Check CHANGELOG.md has unreleased content
3. Strip build number from version (e.g., `0.0.7.8` -> `0.0.7`)
4. Update CHANGELOG with release date
5. Commit, tag `vN.N.N`, and push
6. Bump to next dev version (e.g., `0.0.8.0`)

GitHub Actions will build all three browser versions and create a draft release.

### Store Submission

- **Chrome:** Upload `epupp-chrome.zip` to [Chrome Web Store Developer Dashboard](https://chrome.google.com/webstore/devconsole)
- **Firefox:** Upload `epupp-firefox.zip` to [Firefox Add-on Developer Hub](https://addons.mozilla.org/developers/)
- **Safari:** Submit via Xcode to App Store Connect

## Related Documentation

- [testing.md](testing.md) - Test strategy and utilities
- [architecture.md](architecture.md) - Message protocol, state management, injection flows
- [userscripts-architecture.md](userscripts-architecture.md) - Userscript design decisions
- [ui.md](ui.md) - Script editor UX and ID behavior
