import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: 'repl.spec.ts',
  timeout: 60000,
  use: {
    channel: 'chromium',
  },
  // Run tests sequentially - extension context needs isolation
  workers: 1,
});
