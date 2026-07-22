import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'com.atti20.lanchat',
  appName: 'MeshX',
  webDir: '../../frontend/dist-mobile',
  backgroundColor: '#f4f7fb',
  loggingBehavior: 'debug',
  server: {
    hostname: 'localhost',
    androidScheme: 'https',
    // Android release builds remain HTTPS/WSS-only. The LAN debug flavor is
    // generated separately and is the only place where cleartext is enabled.
    cleartext: false,
  },
  android: {
    minWebViewVersion: 60,
    loggingBehavior: 'debug',
  },
}

export default config
