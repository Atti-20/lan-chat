<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { AdminUser } from '../../types'

interface Props {
  open: boolean
  users: readonly AdminUser[]
  loading?: boolean
  busyUserId?: number | null
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  busyUserId: null,
})
const emit = defineEmits<{
  close: []
  refresh: []
  status: [payload: { userId: number; status: 0 | 1 }]
  mute: [payload: { userId: number; muteStart: string; muteEnd: string }]
  delete: [userId: number]
}>()

const muteStarts = reactive<Record<number, string>>({})
const muteEnds = reactive<Record<number, string>>({})

watch(
  () => [props.open, props.users] as const,
  ([open, users]) => {
    if (!open) return
    users.forEach((user) => {
      muteStarts[user.id] = user.muteStart || ''
      muteEnds[user.id] = user.muteEnd || ''
    })
  },
  { immediate: true },
)

function saveMute(user: AdminUser): void {
  const muteStart = muteStarts[user.id] || ''
  const muteEnd = muteEnds[user.id] || ''
  if (!muteStart || !muteEnd) return
  emit('mute', { userId: user.id, muteStart, muteEnd })
}

function confirmDelete(user: AdminUser): void {
  if (!window.confirm(`确定永久删除用户“${user.nickname || user.username}”吗？该操作不可撤销。`)) return
  emit('delete', user.id)
}
</script>

<template>
  <div v-if="open" class="admin-backdrop" role="presentation" @click.self="emit('close')">
    <section class="admin-console" role="dialog" aria-modal="true" aria-labelledby="admin-console-title">
      <header class="admin-header">
        <div>
          <p>ADMINISTRATION</p>
          <h2 id="admin-console-title">管理控制台</h2>
          <span>共 {{ users.length }} 个账号</span>
        </div>
        <div class="header-actions">
          <button type="button" :disabled="loading" @click="emit('refresh')">
            {{ loading ? '刷新中…' : '刷新' }}
          </button>
          <button class="close-button" type="button" aria-label="关闭管理控制台" @click="emit('close')">×</button>
        </div>
      </header>

      <div class="admin-table-wrap">
        <table class="admin-table">
          <thead>
            <tr>
              <th>用户</th>
              <th>状态</th>
              <th>禁言时段</th>
              <th>账号操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading && users.length === 0">
              <td colspan="4" class="empty-cell">正在加载账号数据…</td>
            </tr>
            <tr v-else-if="users.length === 0">
              <td colspan="4" class="empty-cell">暂无用户数据</td>
            </tr>
            <tr v-for="user in users" :key="user.id">
              <td>
                <div class="user-cell">
                  <span>{{ (user.nickname || user.username).slice(0, 1).toUpperCase() }}</span>
                  <div><strong>{{ user.nickname || user.username }}</strong><small>@{{ user.username }} · ID {{ user.id }}</small></div>
                </div>
              </td>
              <td>
                <span class="status-badge" :class="{ banned: user.status === 0 }">
                  {{ user.status === 0 ? '已封禁' : '正常' }}
                </span>
              </td>
              <td>
                <div v-if="user.username !== 'admin'" class="mute-fields">
                  <input v-model="muteStarts[user.id]" type="time" :aria-label="`${user.username} 禁言开始时间`" />
                  <span>至</span>
                  <input v-model="muteEnds[user.id]" type="time" :aria-label="`${user.username} 禁言结束时间`" />
                  <button
                    type="button"
                    :disabled="busyUserId === user.id || !muteStarts[user.id] || !muteEnds[user.id]"
                    @click="saveMute(user)"
                  >保存</button>
                </div>
                <span v-else class="protected-copy">系统管理员不受限</span>
              </td>
              <td>
                <div v-if="user.username !== 'admin'" class="row-actions">
                  <button
                    type="button"
                    :disabled="busyUserId === user.id"
                    @click="emit('status', { userId: user.id, status: user.status === 0 ? 1 : 0 })"
                  >{{ user.status === 0 ? '解封' : '封禁' }}</button>
                  <button class="danger-button" type="button" :disabled="busyUserId === user.id" @click="confirmDelete(user)">删除</button>
                </div>
                <span v-else class="protected-copy">根账号受保护</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.admin-backdrop { position: fixed; z-index: 120; inset: 0; display: grid; padding: 24px; place-items: center; background: rgba(28, 28, 30, .22); backdrop-filter: blur(9px); }
.admin-console { width: min(1120px, 100%); max-height: min(780px, calc(100dvh - 48px)); overflow: hidden; border: 1px solid rgba(255,255,255,.9); border-radius: 24px; background: rgba(255,255,255,.94); box-shadow: 0 28px 80px rgba(24,35,52,.2), inset 0 1px 0 white; }
.admin-header { display: flex; padding: 22px 24px; align-items: center; justify-content: space-between; gap: 18px; border-bottom: 1px solid var(--separator); }
.admin-header p { margin: 0 0 5px; color: var(--blue); font-size: 9px; font-weight: 800; letter-spacing: .16em; }
.admin-header h2 { margin: 0; font-size: 23px; letter-spacing: -.04em; }
.admin-header span { color: var(--ink-soft); font-size: 11px; }
.header-actions { display: flex; align-items: center; gap: 8px; }
.header-actions button,
.row-actions button,
.mute-fields button { min-height: 34px; padding: 0 12px; border: 0; border-radius: 10px; color: var(--blue); font-size: 11px; font-weight: 700; background: rgba(0,122,255,.09); cursor: pointer; }
.header-actions button:disabled,
.row-actions button:disabled,
.mute-fields button:disabled { opacity: .45; cursor: default; }
.header-actions .close-button { width: 36px; min-height: 36px; padding: 0; border-radius: 50%; color: var(--ink-soft); font-size: 22px; background: var(--fill); }
.admin-table-wrap { max-height: calc(min(780px, 100dvh - 48px) - 106px); overflow: auto; }
.admin-table { width: 100%; border-collapse: collapse; }
.admin-table th { position: sticky; z-index: 1; top: 0; padding: 12px 18px; color: var(--ink-faint); text-align: left; font-size: 10px; font-weight: 700; background: rgba(247,248,250,.96); }
.admin-table td { padding: 14px 18px; border-top: 1px solid var(--separator); vertical-align: middle; }
.user-cell { display: flex; min-width: 180px; align-items: center; gap: 10px; }
.user-cell > span { display: grid; width: 36px; height: 36px; flex: 0 0 auto; place-items: center; border-radius: 12px; color: var(--blue); font-size: 13px; font-weight: 800; background: rgba(0,122,255,.09); }
.user-cell div { display: grid; gap: 3px; }
.user-cell strong { font-size: 12px; }
.user-cell small { color: var(--ink-faint); font-size: 9px; }
.status-badge { display: inline-flex; padding: 5px 9px; border-radius: 999px; color: #168548; font-size: 10px; font-weight: 700; background: rgba(52,199,89,.12); }
.status-badge.banned { color: #c43c46; background: rgba(255,59,48,.1); }
.mute-fields { display: flex; min-width: 260px; align-items: center; gap: 6px; }
.mute-fields input { width: 92px; height: 34px; padding: 0 8px; border: 1px solid var(--separator); border-radius: 9px; color: var(--ink); font: inherit; background: white; }
.mute-fields span { color: var(--ink-faint); font-size: 10px; }
.row-actions { display: flex; min-width: 112px; gap: 7px; }
.row-actions .danger-button { color: #c43c46; background: rgba(255,59,48,.09); }
.protected-copy { color: var(--ink-faint); font-size: 10px; }
.empty-cell { height: 180px; color: var(--ink-soft); text-align: center !important; font-size: 12px; }

@media (max-width: 760px) {
  .admin-backdrop { padding: 0; place-items: stretch; }
  .admin-console { max-height: 100dvh; border: 0; border-radius: 0; }
  .admin-header { padding-top: max(18px, env(safe-area-inset-top)); }
  .admin-table-wrap { max-height: calc(100dvh - 92px); }
  .admin-table th,
  .admin-table td { padding: 12px; }
}
</style>
