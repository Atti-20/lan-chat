<script setup lang="ts">
import { computed } from 'vue'
import type { AdminUser } from '../../types'
import AppleSwitch from '../base/AppleSwitch.vue'
import UserAvatar from '../base/UserAvatar.vue'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  user: AdminUser
  busy: boolean
}

const props = defineProps<Props>()
const muteStart = defineModel<string>('muteStart', { required: true })
const muteEnd = defineModel<string>('muteEnd', { required: true })
const emit = defineEmits<{
  saveMute: []
  broadcastPermission: [enabled: boolean]
  resetPassword: []
  toggleStatus: []
  delete: []
  changeOwnPassword: []
}>()

const isAdministrator = computed(() => props.user.username === 'admin')
const canSaveMute = computed(() => Boolean(muteStart.value && muteEnd.value) && !props.busy)

function requestBroadcastPermission(enabled: boolean): void {
  emit('broadcastPermission', enabled)
}
</script>

<template>
  <article class="account-card" :class="{ 'account-card--banned': user.status === 0 }" :aria-busy="busy">
    <header class="account-card-header">
      <UserAvatar :name="user.nickname || user.username" :avatar="user.avatar" :size="40" />
      <span class="account-identity">
        <strong>{{ user.nickname || user.username }}</strong>
        <small>@{{ user.username }} · ID {{ user.id }}</small>
      </span>
      <span class="account-status" :class="{ 'account-status--banned': user.status === 0 }">
        {{ user.status === 0 ? '已封禁' : '正常' }}
      </span>
    </header>

    <section v-if="!isAdministrator" class="account-tools" aria-label="账号控制">
      <div class="broadcast-permission">
        <span>
          <strong>广播发布权限</strong>
        </span>
        <AppleSwitch
          :model-value="user.canSendBroadcast === 1"
          :disabled="busy"
          :aria-label="`${user.username} 的广播发布权限`"
          @update:model-value="requestBroadcastPermission"
        />
      </div>

      <div class="account-mute" aria-label="禁言时段">
        <strong class="account-tool-label">禁言</strong>
        <div class="account-time-fields">
          <input v-model="muteStart" type="time" :aria-label="`${user.username} 禁言开始时间`" />
          <span aria-hidden="true">至</span>
          <input v-model="muteEnd" type="time" :aria-label="`${user.username} 禁言结束时间`" />
          <button
            class="save-mute-button"
            type="button"
            :disabled="!canSaveMute"
            :aria-label="busy ? '正在保存禁言时段' : '保存禁言时段'"
            title="保存禁言时段"
            @click="emit('saveMute')"
          >
            <UiIcon name="check" :size="16" />
          </button>
        </div>
      </div>

      <footer class="account-actions">
        <button type="button" :disabled="busy" @click="emit('resetPassword')">重置密码</button>
        <button type="button" :disabled="busy" @click="emit('toggleStatus')">
          {{ user.status === 0 ? '解封' : '封禁' }}
        </button>
        <button class="danger-button" type="button" :disabled="busy" @click="emit('delete')">删除</button>
      </footer>
    </section>

    <p v-else class="administrator-note">系统管理员不受禁言和封禁限制。</p>

    <footer v-if="isAdministrator" class="account-actions account-actions--single">
      <button type="button" @click="emit('changeOwnPassword')">修改管理员密码</button>
    </footer>
  </article>
</template>

<style scoped>
.account-card {
  display: grid;
  padding: 11px 12px;
  gap: 9px;
  border: 1px solid var(--separator);
  border-radius: 15px;
  background: var(--surface-raise);
  box-shadow: 0 5px 16px color-mix(in srgb, var(--shadow-color) 42%, transparent);
}
.account-card--banned { border-color: color-mix(in srgb, var(--coral) 24%, var(--separator)); }
.account-card-header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
}
.account-identity { display: grid; min-width: 0; gap: 3px; }
.account-identity strong,
.account-identity small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account-identity strong { color: var(--ink); font-size: 14px; }
.account-identity small { color: var(--ink-faint); font-size: 10px; }
.account-status {
  padding: 4px 7px;
  border-radius: 999px;
  color: var(--green);
  font-size: 10px;
  font-weight: 700;
  background: color-mix(in srgb, var(--green) 11%, transparent);
}
.account-status--banned { color: var(--coral); background: color-mix(in srgb, var(--coral) 10%, transparent); }
.account-tools {
  display: grid;
  gap: 8px;
}
.broadcast-permission {
  display: flex;
  min-height: 46px;
  padding: 8px 10px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-radius: 10px;
  background: var(--fill);
}
.broadcast-permission span { display: grid; min-width: 0; gap: 2px; }
.broadcast-permission strong { color: var(--ink); font-size: 11px; }
.broadcast-permission small { color: var(--ink-faint); font-size: 9px; line-height: 1.4; }
.account-mute {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
}
.account-tool-label { color: var(--ink-soft); font-size: 10px; font-weight: 700; }
.account-time-fields {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 5px;
}
.account-time-fields input {
  width: 100%;
  min-width: 0;
  height: 36px;
  padding: 0 7px;
  border: 1px solid var(--separator);
  border-radius: 9px;
  color: var(--ink);
  font: inherit;
  font-size: 12px;
  background: var(--surface);
}
.account-time-fields > span { color: var(--ink-faint); font-size: 10px; }
.save-mute-button,
.account-actions button {
  min-height: 36px;
  border: 0;
  border-radius: 9px;
  color: var(--blue);
  font: inherit;
  font-size: 10px;
  font-weight: 700;
  background: var(--active);
  cursor: pointer;
}
.save-mute-button { display: grid; width: 36px; padding: 0; place-items: center; }
.save-mute-button:disabled,
.account-actions button:disabled { cursor: default; opacity: .45; }
.administrator-note {
  margin: 0;
  padding: 9px 10px;
  border-radius: 10px;
  color: var(--ink-soft);
  font-size: 10px;
  line-height: 1.5;
  background: var(--fill);
}
.account-actions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 7px; }
.account-actions--single { grid-template-columns: 1fr; }
.account-actions .danger-button { color: var(--coral); background: color-mix(in srgb, var(--coral) 9%, transparent); }
.account-card button:focus-visible,
.account-card input:focus-visible { outline: 2px solid color-mix(in srgb, var(--blue) 48%, transparent); outline-offset: 2px; }

@media (max-width: 360px) {
  .account-card { padding: 10px; }
  .account-card-header { gap: 7px; }
  .account-identity strong { font-size: 13px; }
  .account-identity small { font-size: 9px; }
  .account-status { padding-inline: 6px; font-size: 9px; }
  .account-mute { gap: 6px; }
  .account-time-fields { gap: 4px; }
  .account-time-fields input { padding-inline: 5px; font-size: 11px; }
  .account-actions { gap: 5px; }
  .account-actions button { font-size: 9px; }
}
</style>
