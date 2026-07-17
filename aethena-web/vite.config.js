import { defineConfig } from 'vite';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  base: '/NemoClaw/',
  resolve: {
    alias: {
      '@wllama/wllama/esm/wasm-from-cdn.js': fileURLToPath(new URL('./src/wasm-from-cdn.js', import.meta.url)),
    },
  },
  build: {
    target: 'es2022',
    sourcemap: true,
  },
});
