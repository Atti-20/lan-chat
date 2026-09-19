// Serve the actual Vue checkout (or its protected validation snapshot).
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const root = path.resolve(process.env.PROBE_WEB_ROOT || '../../apps/web')
const apiOrigin = new URL(process.env.PROBE_API_ORIGIN || 'http://127.0.0.1:18385')
if (apiOrigin.protocol !== 'http:' || apiOrigin.hostname !== '127.0.0.1'
    || apiOrigin.username || apiOrigin.password || apiOrigin.pathname !== '/'
    || apiOrigin.search || apiOrigin.hash) {
  throw new Error('Mobile fixture proxy requires a local HTTP origin')
}
const wsOrigin = new URL(apiOrigin)
wsOrigin.protocol = 'ws:'
const { createServer } = await import(pathToFileURL(path.join(root, 'node_modules/vite/dist/node/index.js')))
const server = await createServer({
  root,
  optimizeDeps: { force: true },
  server: {
    host: '127.0.0.1', port: 5194, strictPort: true, watch: null, hmr: false,
    proxy: {
      '/api': { target: apiOrigin.origin, changeOrigin: true },
      '/ws': { target: wsOrigin.origin, ws: true, changeOrigin: true },
    },
  },
})
await server.listen()
server.printUrls()
