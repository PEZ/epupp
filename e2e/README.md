# E2E Tests for Browser Jack-in

This directory contains end-to-end tests for the Browser Jack-in extension, with a focus on testing the DevTools panel functionality.

## Overview

The tests use [Playwright](https://playwright.dev/) with the Chrome DevTools Protocol (CDP) to:
1. Load the extension in a headed Chrome browser
2. Verify the DevTools panel is created correctly
3. Test panel UI components and functionality
4. Validate connection status updates

## Running Tests

### Prerequisites

```bash
npm install
npx playwright install chromium
```

### Build and Run Tests

```bash
# Build extension for testing
bb build:test

# Compile ClojureScript tests to JavaScript
npx squint compile --paths e2e --output-dir build/e2e

# Run all tests
npx playwright test

# Run specific test suites
npx playwright test -g "Panel UI Tests"
npx playwright test -g "Panel Functionality Tests"

# Run with UI mode (helpful for debugging)
npx playwright test --ui
```

## Test Structure

### `panel_test.cljs`

The main test file contains three test suites:

1. **Browser Jack-in DevTools Panel - Basic Loading**
   - Tests that the extension loads correctly
   - Verifies the background service worker is running
   - Uses CDP to inspect extension targets

2. **Panel UI Tests**
   - Verifies panel HTML and JavaScript files exist
   - Checks manifest configuration for devtools_page
   - Validates file content includes expected components

3. **Panel Functionality Tests**
   - Tests that panel code contains expected functionality
   - Verifies connection status handling
   - Checks for tab ID and state management code

## Testing Strategy

### Why Not Direct DevTools Automation?

Testing Chrome DevTools panels programmatically is challenging because:
- DevTools runs in a separate window/process
- Direct manipulation of DevTools UI requires special CDP commands
- Most DevTools interactions are not exposed through standard automation APIs

### Our Approach

We use a multi-layered testing strategy:

1. **File-based verification**: Ensure all required files exist and contain expected content
2. **Extension loading tests**: Verify the extension loads and background worker starts
3. **Code inspection**: Check that compiled JavaScript includes expected functionality
4. **Manual verification**: Use the extension in a real browser during development

### Testing the DevTools Panel

For comprehensive DevTools panel testing, we recommend:

1. **Automated tests** (current implementation):
   - File existence and structure validation
   - Manifest configuration checks
   - Code compilation verification

2. **Manual testing** (documented below):
   - Visual inspection of panel UI
   - Connection status updates
   - Message handling

## Manual Testing Guide

To manually test the DevTools panel:

1. **Build the extension:**
   ```bash
   bb build:test
   ```

2. **Load extension in Chrome:**
   - Open `chrome://extensions`
   - Enable "Developer mode"
   - Click "Load unpacked"
   - Select `dist/chrome` directory

3. **Open DevTools:**
   - Navigate to any webpage
   - Press F12 or right-click → "Inspect"
   - Look for "Browser Jack-in" tab in DevTools

4. **Test panel functionality:**
   - Verify tab ID is displayed
   - Check initial status shows "Not connected"
   - Use extension popup to connect REPL
   - Verify status changes to "Connected"
   - Monitor message count updates

## Extending Tests

To add new tests:

1. **Create test in ClojureScript** (`e2e/panel_test.cljs`):
   ```clojure
   (pw/test "my new test"
     (fn []
       ;; Test implementation
       ))
   ```

2. **Compile to JavaScript**:
   ```bash
   npx squint compile --paths e2e --output-dir build/e2e
   ```

3. **Run tests**:
   ```bash
   npx playwright test
   ```

## Limitations and Future Work

### Current Limitations

- Cannot programmatically open DevTools and interact with panel UI
- Connection status changes require manual verification
- Message flow testing is limited to code inspection

### Future Improvements

- Implement CDP-based DevTools automation for deeper testing
- Add screenshot-based visual regression testing
- Create integration tests with actual REPL connections
- Add performance testing for message handling

## Troubleshooting

### Tests fail with "Extension not loaded"

- Ensure `bb build:test` was run successfully
- Check that `dist/chrome` directory exists and contains all files
- Verify manifest.json includes `devtools_page` field

### Tests fail with module import errors

- Ensure all npm dependencies are installed: `npm install`
- Check that Playwright is installed: `npx playwright install chromium`
- Verify test files are compiled: `npx squint compile --paths e2e --output-dir build/e2e`

### Browser doesn't start

- Extensions require headed mode (headless: false)
- Ensure display is available (may require Xvfb in CI)
- Check Playwright browser binaries are installed

## CI/CD Integration

The GitHub Actions workflow (`.github/workflows/copilot-setup-steps.yml`) includes test setup:

```yaml
- name: Install Playwright browsers
  run: npx playwright install chromium

- name: Build extension for development
  run: bb build:test

- name: Compile test files
  run: npx squint compile --paths e2e --output-dir build/e2e
```

Note: Extension tests in CI require a headed browser, which may need virtual display setup (Xvfb).
