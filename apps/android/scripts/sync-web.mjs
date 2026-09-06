import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const androidRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const webSource = resolve(androidRoot, '../../frontend/dist-mobile')
const assetsRoot = resolve(androidRoot, 'app/src/main/assets')
const publicTarget = resolve(assetsRoot, 'public')
const config = JSON.parse(
  await readFile(resolve(androidRoot, 'capacitor.config.json'), 'utf8'),
)

await rm(publicTarget, { recursive: true, force: true })
await mkdir(assetsRoot, { recursive: true })
await cp(webSource, publicTarget, { recursive: true })
await writeFile(
  resolve(assetsRoot, 'capacitor.config.json'),
  `${JSON.stringify(config)}\n`,
)

const plugins = [
  {
    pkg: '@capacitor/app',
    classpath: 'com.capacitorjs.plugins.app.AppPlugin',
  },
  {
    pkg: '@capacitor/local-notifications',
    classpath: 'com.capacitorjs.plugins.localnotifications.LocalNotificationsPlugin',
  },
]

await writeFile(
  resolve(assetsRoot, 'capacitor.plugins.json'),
  `${JSON.stringify(plugins)}\n`,
)

console.log(`Synced Vue UI from ${webSource} to ${publicTarget}`)
