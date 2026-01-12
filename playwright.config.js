import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './build/e2e',
  testMatch: '**/*_{test,spec}.mjs',
  // REPL integration tests now run with main suite - both need browser-nrepl
  timeout: 60000,  // Increased for REPL tests which need more setup time
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
