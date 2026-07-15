import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/app/',
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static/app',
    // Keep prior hashed files available while a running Spring server still
    // serves a cached index.html from the previous frontend build.
    emptyOutDir: false,
    sourcemap: false,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
})
