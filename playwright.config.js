import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './build/e2e',
  testMatch: '**/*_{test,spec}.mjs',
  testIgnore: '**/repl_ui_spec.mjs',  // REPL tests need infrastructure, run via test:repl-e2e
  timeout: 30000,
  use: {
    // Use Chrome channel for extension support
    channel: 'chromium',
  },
  // Run tests sequentially - extension context needs isolation
  workers: 1,
  // Output for CI
  reporter: [['html', { outputFolder: 'playwright-report' }], ['list']],
  outputDir: 'test-results',
});
