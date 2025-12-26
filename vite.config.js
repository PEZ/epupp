import { defineConfig } from 'vite';

export default defineConfig({
  root: 'src',
  base: './',  // Use relative paths for extension compatibility
  build: {
    outDir: '../dist-vite',
    emptyOutDir: true,
    // For browser extension: inline everything, no code splitting
    rollupOptions: {
      input: {
        popup: 'src/popup.html'
      },
      output: {
        entryFileNames: '[name].js',
        assetFileNames: '[name][extname]',
        inlineDynamicImports: true
      }
    }
  }
});
