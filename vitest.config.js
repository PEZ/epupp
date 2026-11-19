/// <reference types="vitest" />
import { defineConfig } from 'vite';

export default defineConfig({
  define: {
    // Provide EXTENSION_CONFIG for modules that import test-logger
    'EXTENSION_CONFIG': JSON.stringify({ test: false }),
  },
  // Other checkouts (bbg worktrees) live in worktrees/ and are not ours to watch
  server: {
    watch: {
      ignored: ['**/worktrees/**'],
    },
  },
  test: {
    include: ["build/test/**/*_test.mjs"],
    // Provide browser global stubs for modules that reference window/chrome
    setupFiles: ['./test/setup.js'],
    // Watch mode settings
    watch: true,
    // Reporter for clear output
    reporters: ['verbose'],
  },
});
