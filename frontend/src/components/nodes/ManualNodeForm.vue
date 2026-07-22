<script setup lang="ts">
import { shallowRef } from 'vue'

defineProps<{
  busy: boolean
}>()

const emit = defineEmits<{
  submit: [address: string]
}>()

const address = shallowRef('')

function submit(): void {
  const value = address.value.trim()
  if (!value) return
  emit('submit', value)
}
</script>

<template>
  <form class="manual-node" @submit.prevent="submit">
    <label for="manual-node-address">没有找到节点？</label>
    <div>
      <input
        id="manual-node-address"
        v-model="address"
        :disabled="busy"
        inputmode="url"
        autocomplete="url"
        placeholder="http://192.168.1.10:8080"
      />
      <button type="submit" :disabled="busy || !address.trim()">
        {{ busy ? '验证中' : '验证并连接' }}
      </button>
    </div>
  </form>
</template>

<style scoped>
.manual-node { display: grid; margin-top: 10px; gap: 5px; }
.manual-node label { color: var(--ink-faint); font-size: 8px; }
.manual-node > div { display: flex; gap: 6px; }
.manual-node input { min-width: 0; flex: 1; padding: 7px 9px; border: 1px solid var(--glass-border); border-radius: 9px; color: var(--ink); font: inherit; font-size: 9px; background: var(--surface); outline: none; }
.manual-node input:focus { border-color: color-mix(in srgb, var(--blue) 45%, transparent); }
.manual-node button { padding: 6px 9px; border: 0; border-radius: 9px; color: #fff; font: inherit; font-size: 9px; font-weight: 700; background: var(--blue); cursor: pointer; white-space: nowrap; }
.manual-node button:disabled { opacity: .5; cursor: default; }
</style>
