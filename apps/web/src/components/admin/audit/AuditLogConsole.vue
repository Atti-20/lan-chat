<script setup lang="ts">
import { onMounted, shallowRef } from 'vue'
import { useAuditLogs } from '../../../composables/useAuditLogs'
import AuditLogToolbar from './AuditLogToolbar.vue'
import AuditLogList from './AuditLogList.vue'

const { events, loading, error, load } = useAuditLogs()
const action = shallowRef('')
const outcome = shallowRef('')
function refresh(): void { void load(action.value, outcome.value) }
onMounted(refresh)
</script>

<template>
  <section class="audit-console" aria-label="操作审计">
    <AuditLogToolbar v-model:action="action" v-model:outcome="outcome" :loading="loading" @refresh="refresh" />
    <AuditLogList :events="events" :loading="loading" :error="error" />
  </section>
</template>

<style scoped>
.audit-console { display: flex; min-width: 0; min-height: 0; height: 100%; flex-direction: column; background: var(--surface); }
</style>
