import { createApp } from 'vue'
import App from './App.vue'
import './assets/main.css'
import { nativeBridge } from './platform/nativeBridge'

void nativeBridge.runtimeInfo()
  .then((info) => console.info('[MeshX] runtime', info))
  .catch((error) => console.warn('[MeshX] native bridge unavailable', error))

createApp(App).mount('#app')
