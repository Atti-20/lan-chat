<script setup lang="ts">
import type { Conversation, GroupMember } from '../../types'
import UserAvatar from '../base/UserAvatar.vue'

interface Props {
  conversation: Conversation
  members: readonly GroupMember[]
}

defineProps<Props>()
const emit = defineEmits<{
  togglePin: []
  toggleMute: []
  deleteFriend: []
}>()
</script>

<template>
  <aside class="context-panel">
    <div class="context-profile">
      <UserAvatar :name="conversation.name" :avatar="conversation.avatar" :size="72" :online="conversation.online" />
      <strong>{{ conversation.name }}</strong>
      <span>{{ conversation.kind === 'group' ? `${members.length} 位成员` : (conversation.online ? '现在在线' : '当前离线') }}</span>
      <p>{{ conversation.subtitle || (conversation.kind === 'group' ? '这个群还没有公告。' : '这个人还没有填写签名。') }}</p>
    </div>

    <template v-if="conversation.kind === 'private'">
      <div class="context-actions">
        <button type="button" @click="emit('togglePin')">
          <span aria-hidden="true">⌁</span>
          <strong>{{ conversation.pinned ? '取消置顶' : '置顶对话' }}</strong>
        </button>
        <button type="button" @click="emit('toggleMute')">
          <span aria-hidden="true">◌</span>
          <strong>{{ conversation.muted ? '恢复提醒' : '消息免打扰' }}</strong>
        </button>
      </div>
      <button class="danger-action" type="button" @click="emit('deleteFriend')">删除好友</button>
    </template>

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
</template>

<style scoped>
.context-panel { display: flex; width: 244px; min-width: 0; min-height: 0; padding: 22px 16px; flex-direction: column; border-radius: 18px 28px 28px 18px; overflow-y: auto; }
.context-profile { display: grid; padding: 8px 8px 22px; justify-items: center; text-align: center; }
.context-profile > strong { margin-top: 13px; font-size: 17px; letter-spacing: -.02em; }
.context-profile > span { margin-top: 3px; color: #4380b7; font-size: 10px; font-weight: 650; }
.context-profile p { margin: 13px 0 0; color: var(--ink-soft); font-size: 11px; line-height: 1.6; }
.context-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.context-actions button { display: grid; min-height: 82px; padding: 10px 6px; place-items: center; align-content: center; gap: 7px; border: 1px solid rgba(255,255,255,.7); border-radius: 17px 17px 17px 9px; color: #536e88; background: rgba(255,255,255,.38); box-shadow: inset 0 1px 0 rgba(255,255,255,.86); cursor: pointer; }
.context-actions button:hover { color: var(--blue); background: rgba(255,255,255,.64); }
.context-actions span { font-size: 22px; }
.context-actions strong { font-size: 10px; }
.danger-action { min-height: 42px; margin-top: auto; border: 1px solid rgba(255,107,107,.18); border-radius: 14px; color: #cf4350; font-size: 11px; font-weight: 700; background: rgba(255,107,107,.08); cursor: pointer; }
.member-section { min-height: 0; }
.member-heading { display: flex; padding: 12px 4px 10px; align-items: center; justify-content: space-between; }
.member-heading strong { font-size: 12px; }
.member-heading span { display: grid; min-width: 23px; height: 20px; padding: 0 5px; place-items: center; border-radius: 8px; color: var(--blue); font-size: 9px; background: rgba(10,132,255,.1); }
.member-list { display: grid; gap: 4px; }
.member-item { display: flex; padding: 7px 5px; align-items: center; gap: 9px; border-radius: 13px; }
.member-item:hover { background: rgba(255,255,255,.36); }
.member-item > span { display: grid; min-width: 0; gap: 2px; }
.member-item strong { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.member-item small { color: var(--ink-soft); font-size: 8px; }
@media (max-width: 1180px) { .context-panel { display: none; } }

.context-panel {
  width: 230px;
  padding: 22px 16px;
  border-left: 1px solid var(--separator);
  border-radius: 0;
  background: var(--panel-muted);
}
.context-profile { padding-bottom: 20px; }
.context-profile > strong { font-size: 16px; }
.context-profile > span { color: var(--ink-faint); font-weight: 500; }
.context-actions { grid-template-columns: 1fr; gap: 6px; }
.context-actions button {
  display: flex;
  min-height: 46px;
  padding: 0 12px;
  place-items: initial;
  justify-content: flex-start;
  gap: 10px;
  border: 0;
  border-radius: 11px;
  background: #fff;
  box-shadow: none;
}
.context-actions button:hover { background: #ececf1; }
.context-actions span { width: 22px; font-size: 17px; }
.context-actions strong { font-size: 11px; }
.danger-action { border: 0; border-radius: 11px; background: rgba(255, 59, 48, 0.08); }
.member-item { border-radius: 10px; }
.member-item:hover { background: rgba(118, 118, 128, 0.08); }
</style>
