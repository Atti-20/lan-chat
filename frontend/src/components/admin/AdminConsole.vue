<script setup lang="ts">
import { reactive, shallowRef, watch } from 'vue'
import type { AdminUser } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'
import AdminAccountCard from './AdminAccountCard.vue'

interface Props {
  users: readonly AdminUser[]
  loading?: boolean
  creating?: boolean
  createdUsername?: string | null
  busyUserId?: number | null
  mobile?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  creating: false,
  createdUsername: null,
  busyUserId: null,
  mobile: false,
})
const emit = defineEmits<{
  refresh: []
  create: [payload: { username: string; password: string; nickname: string }]
  status: [payload: { userId: number; status: 0 | 1 }]
  mute: [payload: { userId: number; muteStart: string; muteEnd: string }]
  resetPassword: [user: AdminUser]
  changeOwnPassword: []
  delete: [userId: number]
}>()

const muteStarts = reactive<Record<number, string>>({})
const muteEnds = reactive<Record<number, string>>({})
const createOpen = shallowRef(false)
const createError = shallowRef('')
const account = reactive({ username: '', nickname: '', password: '' })

watch(
  () => props.users,
  (users) => {
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

function submitAccount(): void {
  const username = account.username.trim()
  const nickname = account.nickname.trim()
  createError.value = ''
  if (!/^[a-zA-Z0-9_.@-]{3,50}$/.test(username) || username.toLowerCase() === 'admin') {
    createError.value = '用户名需为 3–50 位字母、数字或 ._@-，且不能使用 admin'
    return
  }
  if (nickname.length < 2 || nickname.length > 16) {
    createError.value = '昵称需为 2–16 个字符'
    return
  }
  if (account.password.length < 8 || account.password.length > 20
    || !/[A-Za-z]/.test(account.password) || !/\d/.test(account.password)) {
    createError.value = '初始密码需为 8–20 位，并同时包含字母和数字'
    return
  }
  emit('create', { username, nickname, password: account.password })
}

watch(() => props.createdUsername, (createdUsername) => {
  if (createdUsername && createdUsername === account.username.trim()) {
    account.username = ''
    account.nickname = ''
    account.password = ''
    createOpen.value = false
  }
})
</script>

<template>
  <section class="admin-console" aria-labelledby="admin-console-title">
    <header class="admin-header">
      <div>
        <p>ADMINISTRATION</p>
        <h2 id="admin-console-title">管理控制台</h2>
        <span>共 {{ users.length }} 个账号</span>
      </div>
      <div class="header-actions">
        <button type="button" :aria-expanded="createOpen" @click="createOpen = !createOpen">
          {{ createOpen ? '收起' : '创建账号' }}
        </button>
        <button type="button" :disabled="loading" @click="emit('refresh')">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </header>

    <form v-if="createOpen" class="account-create" @submit.prevent="submitAccount">
      <label><span>用户名</span><input v-model="account.username" maxlength="50" autocomplete="off" placeholder="例如 chen.li" /></label>
      <label><span>昵称</span><input v-model="account.nickname" maxlength="16" autocomplete="off" placeholder="成员显示名称" /></label>
      <label><span>初始密码</span><input v-model="account.password" maxlength="20" type="password" autocomplete="new-password" placeholder="8–20 位字母与数字" /></label>
      <button type="submit" :disabled="creating">{{ creating ? '创建中…' : '确认创建' }}</button>
      <p v-if="createError" role="alert">{{ createError }}</p>
    </form>

    <div v-if="mobile" class="admin-card-list" aria-live="polite">
      <p v-if="loading && users.length === 0" class="mobile-empty-state">正在加载账号数据…</p>
      <p v-else-if="users.length === 0" class="mobile-empty-state">暂无用户数据</p>
      <AdminAccountCard
        v-for="user in users"
        :key="user.id"
        v-model:mute-start="muteStarts[user.id]"
        v-model:mute-end="muteEnds[user.id]"
        :user="user"
        :busy="busyUserId === user.id"
        @save-mute="saveMute(user)"
        @reset-password="emit('resetPassword', user)"
        @toggle-status="emit('status', { userId: user.id, status: user.status === 0 ? 1 : 0 })"
        @delete="confirmDelete(user)"
        @change-own-password="emit('changeOwnPassword')"
      />
    </div>

    <div v-else class="admin-table-wrap">
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
                <UserAvatar :name="user.nickname || user.username" :avatar="user.avatar" :size="36" />
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
                  @click="emit('resetPassword', user)"
                >重置密码</button>
                <button
                  type="button"
                  :disabled="busyUserId === user.id"
                  @click="emit('status', { userId: user.id, status: user.status === 0 ? 1 : 0 })"
                >{{ user.status === 0 ? '解封' : '封禁' }}</button>
                <button class="danger-button" type="button" :disabled="busyUserId === user.id" @click="confirmDelete(user)">删除</button>
              </div>
              <div v-else class="row-actions row-actions--admin">
                <button type="button" @click="emit('changeOwnPassword')">修改密码</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.admin-console { display: flex; width: 100%; height: 100%; min-width: 0; min-height: 0; flex-direction: column; overflow: hidden; background: var(--surface); }
.admin-header { display: flex; flex: 0 0 auto; padding: 22px 24px; align-items: center; justify-content: space-between; gap: 18px; border-bottom: 1px solid var(--separator); background: var(--surface-glass); }
.admin-header p { margin: 0 0 5px; color: var(--blue); font-size: 9px; font-weight: 800; letter-spacing: .16em; }
.admin-header h2 { margin: 0; font-size: 23px; letter-spacing: -.04em; }
.admin-header span { color: var(--ink-soft); font-size: 11px; }
.header-actions { display: flex; align-items: center; gap: 8px; }
.header-actions button,
.row-actions button,
.mute-fields button { min-height: 34px; padding: 0 12px; border: 0; border-radius: 10px; color: var(--blue); font-size: 11px; font-weight: 700; background: var(--active); cursor: pointer; }
.header-actions button:disabled,
.row-actions button:disabled,
.mute-fields button:disabled { opacity: .45; cursor: default; }
.account-create { display: grid; grid-template-columns: repeat(3, minmax(140px, 1fr)) auto; gap: 10px; padding: 14px 24px; align-items: end; border-bottom: 1px solid var(--separator); background: var(--surface-raise); }
.account-create label { display: grid; gap: 6px; }
.account-create label span { color: var(--ink-soft); font-size: 10px; font-weight: 700; }
.account-create input { min-width: 0; height: 38px; padding: 0 11px; border: 1px solid var(--separator); border-radius: 10px; color: var(--ink); font: inherit; background: var(--surface); }
.account-create button { height: 38px; padding: 0 15px; border: 0; border-radius: 10px; color: white; font-size: 11px; font-weight: 750; background: var(--blue); cursor: pointer; }
.account-create button:disabled { opacity: .5; cursor: default; }
.account-create p { grid-column: 1 / -1; margin: 0; color: var(--coral); font-size: 10px; }
.admin-card-list { display: none; }
.admin-table-wrap { min-height: 0; flex: 1; overflow: auto; }
.admin-table { width: 100%; border-collapse: collapse; }
.admin-table th { position: sticky; z-index: 1; top: 0; padding: 12px 18px; color: var(--ink-faint); text-align: left; font-size: 10px; font-weight: 700; background: var(--surface-raise); }
.admin-table td { padding: 14px 18px; border-top: 1px solid var(--separator); vertical-align: middle; }
.user-cell { display: flex; min-width: 180px; align-items: center; gap: 10px; }
.user-cell div { display: grid; gap: 3px; }
.user-cell strong { font-size: 12px; }
.user-cell small { color: var(--ink-faint); font-size: 9px; }
.status-badge { display: inline-flex; padding: 5px 9px; border-radius: 999px; color: var(--green); font-size: 10px; font-weight: 700; background: color-mix(in srgb, var(--green) 12%, transparent); }
.status-badge.banned { color: var(--coral); background: color-mix(in srgb, var(--coral) 10%, transparent); }
.mute-fields { display: flex; min-width: 260px; align-items: center; gap: 6px; }
.mute-fields input { width: 92px; height: 34px; padding: 0 8px; border: 1px solid var(--separator); border-radius: 9px; color: var(--ink); font: inherit; background: var(--surface); }
.mute-fields span { color: var(--ink-faint); font-size: 10px; }
.row-actions { display: flex; min-width: 206px; gap: 7px; }
.row-actions--admin { min-width: 0; }
.row-actions .danger-button { color: var(--coral); background: color-mix(in srgb, var(--coral) 9%, transparent); }
.protected-copy { color: var(--ink-faint); font-size: 10px; }
.empty-cell { height: 180px; color: var(--ink-soft); text-align: center !important; font-size: 12px; }

@media (max-width: 760px) {
  .admin-header {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    padding: 14px max(14px, env(safe-area-inset-right)) 14px max(14px, env(safe-area-inset-left));
    gap: 10px;
  }
  .admin-header p { margin-bottom: 3px; }
  .admin-header h2 { font-size: 20px; }
  .header-actions { gap: 5px; }
  .header-actions button { min-height: 40px; padding: 0 10px; }
  .account-create {
    grid-template-columns: 1fr;
    padding: 14px max(14px, env(safe-area-inset-right)) 16px max(14px, env(safe-area-inset-left));
  }
  .account-create input,
  .account-create button { height: 44px; }
  .admin-table-wrap { display: none; }
  .admin-card-list {
    display: grid;
    min-height: 0;
    flex: 1;
    padding: 12px max(12px, env(safe-area-inset-right)) max(18px, env(safe-area-inset-bottom)) max(12px, env(safe-area-inset-left));
    align-content: start;
    gap: 10px;
    overflow-y: auto;
    overscroll-behavior: contain;
  }
  .mobile-empty-state {
    margin: 0;
    padding: 44px 18px;
    color: var(--ink-soft);
    text-align: center;
    font-size: 12px;
  }
}
</style>
