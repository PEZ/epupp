import { defineConfig } from '@playwright/test';
import path from 'path';

export default defineConfig({
  testDir: './build/e2e',
  testMatch: '**/*_test.mjs',
  timeout: 30000,
  use: {
    // Use Chrome channel for extension support
    channel: 'chromium',
  },
  // Run tests sequentially - extension context needs isolation
  workers: 1,
});
