import { createApp } from 'vue'
// Theme initialization reads generated canvas data while App modules evaluate.
import './assets/main.css'
import App from './App.vue'
import { nativeBridge } from './platform/nativeBridge'

void nativeBridge.runtimeInfo()
  .then((info) => console.info('[MeshX] runtime', info))
  .catch((error) => console.warn('[MeshX] native bridge unavailable', error))

createApp(App).mount('#app')
