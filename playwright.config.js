import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './build/e2e',
  timeout: 30000,
  use: {
    headless: false, // Extensions require headed mode
    viewport: { width: 1280, height: 720 },
  },
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report' }]
  ],
});
