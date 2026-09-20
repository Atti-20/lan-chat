<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import type { Conversation, GroupMember, TemporaryRoom } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'
import UiIcon from '../base/UiIcon.vue'

interface Props {
  open: boolean
  conversation: Conversation
  members: readonly GroupMember[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  close: []
  togglePin: []
  toggleMute: []
  deleteFriend: []
  updateRemark: [remark: string]
  leaveRoom: []
}>()

const editingRemark = shallowRef(false)
const remarkInput = shallowRef('')
const remarkSaving = shallowRef(false)

// 获取好友的原始昵称（从 source 中取）
function friendNickname(): string {
  const src = props.conversation.source
  return ('nickname' in src && src.nickname) || props.conversation.name
}

function friendRemark(): string {
  const src = props.conversation.source
  return ('remark' in src && src.remark) || ''
}

function temporaryRoom(): TemporaryRoom | null {
  return props.conversation.kind === 'temporary'
    ? props.conversation.source as TemporaryRoom
    : null
}

function isSystemNotification(): boolean {
  const source = props.conversation.source
  return props.conversation.kind === 'private'
    && 'username' in source
    && source.username === 'broadcast-notify'
    && source.signature === '系统广播通知账户'
}

function roomStatusLabel(status?: TemporaryRoom['status']): string {
  return status ? {
    ACTIVE: '协作中',
    EXPIRING: '即将到期',
    FROZEN: '已冻结',
    ARCHIVED: '已归档',
    DESTROYED: '已销毁',
  }[status] : '状态未知'
}

function formatRoomExpiry(value?: string): string {
  if (!value) return '未设置'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

watch(() => props.open, (open) => {
  if (open) {
    editingRemark.value = false
    remarkInput.value = friendRemark()
  }
})

function startEditRemark(): void {
  remarkInput.value = friendRemark()
  editingRemark.value = true
}

function cancelEditRemark(): void {
  editingRemark.value = false
}

function saveRemark(): void {
  const value = remarkInput.value.trim()
  remarkSaving.value = true
  emit('updateRemark', value)
  // 外层会异步处理，这里直接关闭编辑态
  editingRemark.value = false
  remarkSaving.value = false
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="context-backdrop detail-backdrop apple-modal-backdrop" role="presentation" @click.self="emit('close')">
      <aside class="context-panel detail-panel apple-modal-surface" role="dialog" aria-modal="true" aria-label="详情">
        <button class="context-close apple-modal-close" type="button" aria-label="关闭" @click="emit('close')">
          <UiIcon name="close" :size="15" />
        </button>

        <div class="context-profile">
          <UserAvatar :name="conversation.name" :avatar="conversation.avatar" :size="72" :online="conversation.online" />
          <strong>{{ conversation.name }}</strong>
          <span class="status-line">{{ conversation.kind === 'group' ? `${members.length} 位成员` : conversation.kind === 'temporary' ? roomStatusLabel(temporaryRoom()?.status) : (conversation.online ? '在线' : '离线') }}</span>
          <p v-if="conversation.subtitle" class="bio">{{ conversation.subtitle }}</p>
        </div>

        <template v-if="conversation.kind === 'private' && !isSystemNotification()">
          <!-- 备注管理 -->
          <div class="remark-section">
            <div class="remark-header">
              <span class="remark-label">好友备注</span>
              <button v-if="!editingRemark" class="remark-edit-btn" type="button" @click="startEditRemark">
                <UiIcon name="edit" :size="12" />
                编辑
              </button>
            </div>
            <div v-if="editingRemark" class="remark-edit">
              <input
                v-model="remarkInput"
                class="field remark-field"
                maxlength="50"
                placeholder="输入备注名称"
                autofocus
                @keydown.enter.prevent="saveRemark"
                @keydown.escape="cancelEditRemark"
              />
              <div class="remark-actions">
                <button type="button" class="remark-cancel" @click="cancelEditRemark">取消</button>
                <button type="button" class="remark-save" :disabled="remarkSaving" @click="saveRemark">保存</button>
              </div>
            </div>
            <p v-else class="remark-display">
              <template v-if="friendRemark()">{{ friendRemark() }}</template>
              <span v-else class="remark-empty">昵称：{{ friendNickname() }}</span>
            </p>
          </div>

          <div class="action-list">
            <button type="button" @click="emit('togglePin')">
              <span class="action-icon">
                <UiIcon name="pin" :size="17" />
              </span>
              <span>{{ conversation.pinned ? '取消置顶' : '置顶对话' }}</span>
            </button>
            <button type="button" @click="emit('toggleMute')">
              <span class="action-icon">
                <UiIcon :name="conversation.muted ? 'bell-off' : 'bell'" :size="17" />
              </span>
              <span>{{ conversation.muted ? '恢复提醒' : '消息免打扰' }}</span>
            </button>
          </div>

          <button class="danger-action" type="button" @click="emit('deleteFriend')">
            <UiIcon name="trash" :size="16" />
            <span>删除好友</span>
          </button>
        </template>

        <div v-else-if="conversation.kind === 'private'" class="system-notification-detail">
          <UiIcon name="bell" :size="20" />
          <strong>系统通知账户</strong>
          <p>它只会发送广播内容概览和创建者的确认提醒，不能作为普通好友聊天。</p>
        </div>
        <div v-else-if="conversation.kind === 'temporary' && temporaryRoom()" class="room-detail-section">
          <dl class="room-facts">
            <div><dt>到期时间</dt><dd>{{ formatRoomExpiry(temporaryRoom()?.expiresAt) }}</dd></div>
            <div><dt>成员</dt><dd>{{ temporaryRoom()?.memberCount || 1 }} / {{ temporaryRoom()?.maxMembers }}</dd></div>
            <div v-if="temporaryRoom()?.roomCode"><dt>房间码</dt><dd class="room-code">{{ temporaryRoom()?.roomCode }}</dd></div>
            <div><dt>文件权限</dt><dd>{{ temporaryRoom()?.allowFileUpload ? '可上传' : '禁止上传' }} · {{ temporaryRoom()?.allowFileDownload ? '可下载' : '禁止下载' }}</dd></div>
          </dl>
          <button
            v-if="temporaryRoom()?.currentUserRole !== 'OWNER'"
            class="danger-action"
            type="button"
            @click="emit('leaveRoom')"
          >
            <UiIcon name="back" :size="16" />
            <span>退出临时房间</span>
          </button>
        </div>

        <div v-else class="member-section">
          <div class="member-heading">
            <strong>群成员</strong>
            <span>{{ members.length }}</span>
          </div>
          <div class="member-list">
            <div v-for="member in members.slice(0, 12)" :key="member.userId" class="member-item">
              <UserAvatar :name="member.nickname" :avatar="member.avatar" :size="34" :online="member.online === 1" />
              <span><strong>{{ member.nickname }}</strong><small>{{ member.role === 2 ? '群主' : member.role === 1 ? '管理员' : '成员' }}</small></span>
            </div>
          </div>
        </div>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped>
.context-backdrop {
  position: fixed;
  z-index: 120;
  inset: 0;
  display: grid;
  place-items: center;
}

.context-panel {
  position: relative;
  display: flex;
  width: min(100%, 320px);
  max-height: calc(100dvh - 60px);
  padding: 32px 24px 24px;
  flex-direction: column;
  gap: var(--space-4);
  border-radius: var(--radius-sheet);
  box-shadow: 0 20px 60px var(--shadow-color), inset 0 1px 0 var(--highlight-soft);
  overflow-y: auto;
}

.context-close {
  position: absolute;
  top: 12px;
  right: 12px;
  display: grid;
  width: 32px;
  height: 32px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 50%;
  color: var(--ink-soft);
  background: var(--fill);
  cursor: pointer;
  transition: background-color 150ms ease;
}
.system-notification-detail { display: grid; padding: 16px; justify-items: center; gap: 8px; border-radius: var(--radius-control); color: var(--ink-soft); text-align: center; background: var(--fill); }
.system-notification-detail .ui-icon { color: var(--accent-text); }
.system-notification-detail p { margin: 0; font-size: var(--font-caption); line-height: 1.55; }
.context-close:hover { background: var(--button-hover); }
.context-close .ui-icon { width: 15px; }

.context-profile {
  display: grid;
  padding: 4px 0 0;
  justify-items: center;
  text-align: center;
}
.context-profile > strong { margin-top: 12px; font-size: var(--font-title-sm); font-weight: 700; letter-spacing: -0.02em; }
.status-line { margin-top: 3px; color: var(--ink-faint); font-size: var(--font-caption); font-weight: 500; }
.bio { margin: 10px 0 0; color: var(--ink-soft); font-size: var(--font-caption); line-height: 1.6; }

/* 备注 */
.remark-section {
  padding: 14px;
  border-radius: var(--radius-md);
  background: var(--fill);
}
.remark-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.remark-label {
  color: var(--ink-soft);
  font-size: var(--font-micro);
  font-weight: 600;
}
.remark-edit-btn {
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 0;
  border: 0;
  color: var(--accent-text);
  font-size: var(--font-micro);
  font-weight: 600;
  background: none;
  cursor: pointer;
}
.remark-edit-btn:hover { text-decoration: underline; }
.remark-edit-btn .ui-icon { width: 12px; }

.remark-display {
  margin: 0;
  font-size: var(--font-body);
  font-weight: 500;
  word-break: break-all;
}
.remark-empty {
  color: var(--ink-faint);
  font-weight: 400;
}

.remark-edit { display: grid; gap: var(--space-2); }
.remark-field {
  width: 100%;
  min-height: 36px;
  padding: 8px 10px;
  border: 1px solid var(--separator, #e5e5ea);
  border-radius: var(--radius-control);
  font-size: var(--font-body-sm);
  background: var(--surface);
  outline: none;
  transition: border-color 150ms ease;
}
.remark-field:focus { border-color: var(--accent-text); }
.remark-actions { display: flex; gap: 6px; justify-content: flex-end; }
.remark-actions button {
  min-height: 30px;
  padding: 0 14px;
  border: 0;
  border-radius: var(--radius-sm);
  font-size: var(--font-caption);
  font-weight: 600;
  cursor: pointer;
  transition: background-color 150ms ease;
}
.room-detail-section { display: grid; gap: 14px; }
.room-facts { display: grid; margin: 0; gap: 1px; overflow: hidden; border: 1px solid var(--separator); border-radius: var(--radius-md); background: var(--separator); }
.room-facts > div { display: grid; padding: 11px 12px; grid-template-columns: 78px minmax(0, 1fr); gap: 10px; background: var(--surface-raise); }
.room-facts dt { color: var(--ink-faint); font-size: var(--font-micro); }
.room-facts dd { margin: 0; color: var(--ink); font-size: var(--font-micro); text-align: right; overflow-wrap: anywhere; }
.room-code { font-family: var(--font-mono); letter-spacing: .06em; }
.remark-cancel { color: var(--ink); background: var(--button-hover); }
.remark-cancel:hover { background: var(--button-hover-strong); }
.remark-save { color: #fff; background: var(--action-bg); }
.remark-save:hover { background: color-mix(in srgb, var(--action-bg) 88%, #000); }
.remark-save:disabled { opacity: 0.5; }

.action-list { display: grid; gap: var(--space-1); }
.action-list button {
  display: flex;
  height: 48px;
  padding: 0 14px;
  align-items: center;
  gap: var(--space-3);
  border: 0;
  border-radius: var(--radius-md);
  color: var(--ink);
  font-size: var(--font-body-sm);
  font-weight: 500;
  background: var(--fill);
  cursor: pointer;
  transition: background-color 150ms ease;
}
.action-list button:hover { background: var(--button-hover); }
.action-icon {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: var(--radius-control);
  color: var(--accent-text);
  background: rgba(0, 122, 255, 0.08);
}
.action-icon .ui-icon { width: 17px; }

.danger-action {
  display: flex;
  height: 48px;
  padding: 0 14px;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  border: 0;
  border-radius: var(--radius-md);
  color: var(--danger);
  font-size: var(--font-body-sm);
  font-weight: 600;
  background: color-mix(in srgb, var(--coral) 8%, transparent);
  cursor: pointer;
  transition: background-color 150ms ease;
}
.danger-action:hover { background: color-mix(in srgb, var(--coral) 14%, transparent); }
.danger-action .ui-icon { width: 16px; }

.member-section { min-height: 0; }
.member-heading {
  display: flex;
  padding: 4px 4px 10px;
  align-items: center;
  justify-content: space-between;
}
.member-heading strong { font-size: var(--font-body-sm); }
.member-heading span {
  display: grid;
  min-width: 23px;
  height: 22px;
  padding: 0 6px;
  place-items: center;
  border-radius: var(--radius-sm);
  color: var(--accent-text);
  font-size: var(--font-caption);
  font-weight: 600;
  background: rgba(0, 122, 255, 0.09);
}

.member-list { display: grid; gap: 2px; }
.member-item {
  display: flex;
  padding: 8px 6px;
  align-items: center;
  gap: 10px;
  border-radius: var(--radius-control);
  transition: background-color 150ms ease;
}
.member-item:hover { background: var(--hover); }
.member-item > span { display: grid; min-width: 0; gap: 2px; }
.member-item strong { overflow: hidden; font-size: var(--font-caption); text-overflow: ellipsis; white-space: nowrap; }
.member-item small { color: var(--ink-soft); font-size: var(--font-micro); }

@media (max-width: 520px) {
  .context-panel { width: calc(100% - 32px); padding: 28px 18px 18px; border-radius: var(--radius-lg); }
}
</style>
