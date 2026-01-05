import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '../build/e2e',
  testMatch: 'repl_ui_spec.mjs',
  timeout: 60000,
  use: {
    channel: 'chromium',
  },
  // Run tests sequentially - extension context needs isolation
  workers: 1,
});
