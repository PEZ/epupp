## Development

### Prerequisites

- [Babashka](https://babashka.org/) (bb)
- Node.js (for npm dependencies)

### Setup

```bash
npm install
```

### Testing

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

Run Playwright E2E tests for popup UI:
```bash
bb test:e2e          # Run tests
bb test:e2e:ui       # Interactive Playwright UI
```

Run REPL integration tests (full pipeline with browser-nrepl):
```bash
bb test:repl-e2e     # Run tests
bb test:repl-e2e:ui  # Interactive Playwright UI
```

E2E tests live in `e2e/*.cljs` and are compiled to `build/e2e/*.mjs`. See [e2e-testing-research.md](e2e-testing-research.md) for architecture details.

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

0. Unpack `scittle-tamper-chrome.zip` (will unpack a `chrome` folder)
1. Go to `chrome://extensions`
2. Enable "Developer mode"
3. Click "Load unpacked"
4. Select the `chrome` folder

**Firefox:**

1. Go to `about:debugging#/runtime/this-firefox`
2. Click "Load Temporary Add-on"
3. Select any file in `scittle-tamper-firefox.zip` file

**Safari:**

1. Safari → Settings → Developer → Click "Add Temporary Extension"
2. Select the `scittle-tamper-safari.zip` file
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

- **Chrome:** Upload `scittle-tamper-chrome.zip` to [Chrome Web Store Developer Dashboard](https://chrome.google.com/webstore/devconsole)
- **Firefox:** Upload `scittle-tamper-firefox.zip` to [Firefox Add-on Developer Hub](https://addons.mozilla.org/developers/)
- **Safari:** Submit via Xcode to App Store Connect
