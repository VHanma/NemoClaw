import { defineConfig } from 'vite';

export default defineConfig({
  base: '/NemoClaw/',
  build: {
    target: 'es2022',
    sourcemap: true,
  },
});
