import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './build/e2e',
  testMatch: '**/*_{test,spec}.mjs',
  // REPL integration tests now run with main suite - both need browser-nrepl
  // Fast locally (10s), patient in CI (30s) - CI runners are slower
  timeout: process.env.CI ? 30000 : 10000,
  use: {
    // Use Chrome channel for extension support
    channel: 'chromium',
  },
  // Run tests sequentially - extension context needs isolation
  workers: 1,
  // Output for CI - don't auto-open report in browser
  reporter: [['html', { outputFolder: 'playwright-report', open: 'never' }], ['list']],
  outputDir: 'test-results',
});
