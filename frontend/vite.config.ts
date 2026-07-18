import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const desktop = mode === 'desktop'

  return {
    base: desktop ? './' : '/app/',
    plugins: [vue()],
    build: {
      outDir: desktop ? 'dist-desktop' : '../src/main/resources/static/app',
      // Web 构建保留旧哈希资源，避免运行中的 Spring Boot 仍引用旧 index.html。
      // Desktop 构建则每次清空独立目录，确保打包内容可重复。
      emptyOutDir: desktop,
      sourcemap: false,
    },
    server: {
      port: desktop ? 1420 : 5173,
      strictPort: desktop,
      watch: {
        ignored: ['**/apps/desktop/src-tauri/**'],
      },
      proxy: {
        '/api': 'http://localhost:8080',
        '/ws': {
          target: 'ws://localhost:8080',
          ws: true,
        },
      },
    },
  }
})
