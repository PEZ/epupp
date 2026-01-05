/// <reference types="vitest" />
import { defineConfig } from 'vite';

export default defineConfig({
  test: {
    include: ["build/test/**/*_test.mjs"],
    // Watch mode settings
    watch: true,
    // Reporter for clear output
    reporters: ['verbose'],
  },
});
