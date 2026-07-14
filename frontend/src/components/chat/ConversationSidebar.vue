<script setup lang="ts">
import { computed } from 'vue'
import type { ChatSection } from '../../composables/useChat'
import type { Conversation, FriendRequest } from '../../types'
import { formatTime } from '../../utils/format'
import UserAvatar from '../base/UserAvatar.vue'

interface Props {
  section: ChatSection
  conversations: readonly Conversation[]
  requests: readonly FriendRequest[]
  selectedId?: number
  selectedKind?: Conversation['kind']
  loading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  selectedId: undefined,
  selectedKind: undefined,
  loading: false,
})
const query = defineModel<string>('query', { required: true })
const emit = defineEmits<{
  select: [conversation: Conversation]
  handleRequest: [requestId: number, accept: boolean]
  searchPeople: []
  createGroup: []
}>()

const kicker = computed(() => ({
  messages: 'MESSAGE',
  contacts: 'FRIENDS',
  groups: 'GROUPS',
}[props.section]))
const title = computed(() => ({
  messages: '消息',
  contacts: '好友',
  groups: '群组',
}[props.section]))
const emptyCopy = computed(() => {
  if (query.value.trim()) return '没有匹配的结果'
  if (props.section === 'groups') return '还没有加入群聊'
  if (props.section === 'contacts') return '搜索并添加第一位好友'
  return '新的对话会出现在这里'
})
</script>

<template>
  <aside class="conversation-sidebar">
    <header class="sidebar-header">
      <div>
        <p>{{ kicker }}</p>
        <h1>{{ title }}</h1>
      </div>
      <div class="header-actions">
        <button class="mini-button" type="button" aria-label="搜索用户" @click="emit('searchPeople')">
          <svg viewBox="0 0 24 24" fill="none"><circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.6"/><path d="m15.5 15.5 4.5 4.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
        </button>
        <button class="mini-button" type="button" aria-label="创建群聊" @click="emit('createGroup')">
          <svg viewBox="0 0 24 24" fill="none"><path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
        </button>
      </div>
    </header>

    <label class="sidebar-search">
      <span class="sr-only">搜索当前列表</span>
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.6"/><path d="m15.5 15.5 4.5 4.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
      <input v-model="query" type="search" placeholder="搜索对话" />
    </label>

    <div v-if="loading" class="sidebar-loading" aria-label="正在载入">
      <span v-for="index in 5" :key="index" />
    </div>

    <div v-else class="conversation-list">
      <div v-if="section === 'contacts' && requests.length > 0" class="request-section">
        <div class="request-section-title">好友申请</div>
        <article v-for="request in requests" :key="request.id" class="request-card">
          <UserAvatar :name="request.sender?.nickname || `用户${request.fromUserId}`" :avatar="request.sender?.avatar" :size="46" />
          <div class="request-copy">
            <strong>{{ request.sender?.nickname || `用户 ${request.fromUserId}` }}</strong>
            <p>{{ request.message || '想添加你为好友' }}</p>
            <span>{{ formatTime(request.createTime) }}</span>
          </div>
          <div class="request-actions">
            <button type="button" class="accept" @click="emit('handleRequest', request.id, true)">接受</button>
            <button type="button" @click="emit('handleRequest', request.id, false)">忽略</button>
          </div>
        </article>
      </div>

      <button
        v-for="conversation in conversations"
        :key="`${conversation.kind}-${conversation.id}`"
        type="button"
        class="conversation-item"
        :class="{ 'conversation-item--active': selectedId === conversation.id && selectedKind === conversation.kind }"
        @click="emit('select', conversation)"
      >
        <UserAvatar :name="conversation.name" :avatar="conversation.avatar" :size="50" :online="conversation.online" />
        <span class="conversation-copy">
          <span class="conversation-line">
            <strong>{{ conversation.name }}</strong>
            <time>{{ formatTime(conversation.lastMessageTime) }}</time>
          </span>
          <span class="conversation-line conversation-preview">
            <span>{{ conversation.lastMessage }}</span>
            <i v-if="conversation.muted" aria-label="已免打扰">⌁</i>
            <i v-if="conversation.pinned" class="pin" aria-label="已置顶">●</i>
          </span>
        </span>
      </button>

      <div v-if="conversations.length === 0" class="empty-list">
        <span class="empty-icon">
          <svg viewBox="0 0 24 24" fill="none"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </span>
        <strong>{{ emptyCopy }}</strong>
        <p>使用右上角按钮开始新的连接。</p>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.conversation-sidebar { display: flex; width: 330px; min-width: 0; min-height: 0; flex-direction: column; border-radius: 18px; overflow: hidden; }
.sidebar-header { display: flex; padding: 24px 22px 16px; align-items: center; justify-content: space-between; }
.sidebar-header p { margin: 0 0 2px; color: #5480aa; font-family: "SF Mono", monospace; font-size: 9px; font-weight: 700; letter-spacing: .14em; text-transform: uppercase; }
.sidebar-header h1 { margin: 0; font-size: 25px; letter-spacing: -.04em; }
.header-actions { display: flex; gap: 7px; }
.mini-button { display: grid; width: 36px; height: 36px; padding: 0; place-items: center; border: 1px solid rgba(255,255,255,.75); border-radius: 13px; color: #3b5c7c; background: rgba(255,255,255,.46); box-shadow: inset 0 1px 0 #fff; cursor: pointer; }
.mini-button:hover { color: var(--blue); background: rgba(255,255,255,.72); }
.mini-button svg { width: 18px; }
.sidebar-search { display: flex; min-height: 42px; margin: 0 16px 12px; padding: 0 12px; align-items: center; gap: 8px; border: 1px solid rgba(144,169,194,.17); border-radius: 14px; background: rgba(221,235,248,.42); }
.sidebar-search svg { width: 17px; color: var(--ink-faint); }
.sidebar-search input { width: 100%; min-width: 0; border: 0; outline: none; color: var(--ink); background: none; }
.sidebar-search input::-webkit-search-cancel-button { display: none; }
.sidebar-search kbd { padding: 3px 5px; border: 1px solid rgba(138,163,188,.2); border-radius: 5px; color: #8293a5; font-family: inherit; font-size: 9px; background: rgba(255,255,255,.4); }
.conversation-list { min-height: 0; padding: 0 10px 14px; overflow-y: auto; scrollbar-width: thin; scrollbar-color: rgba(92,124,156,.22) transparent; }
.conversation-item { display: flex; width: 100%; min-height: 74px; padding: 10px; align-items: center; gap: 12px; border: 1px solid transparent; border-radius: 19px 15px 19px 15px; text-align: left; background: transparent; cursor: pointer; transition: 200ms var(--ease-liquid); }
.conversation-item:hover { background: rgba(255,255,255,.42); transform: translateX(2px); }
.conversation-item--active { border-color: rgba(255,255,255,.82); background: linear-gradient(145deg, rgba(255,255,255,.78), rgba(218,239,255,.52)); box-shadow: inset 0 1px 0 #fff, 0 9px 20px rgba(51,93,136,.1); }
.conversation-copy { display: grid; min-width: 0; flex: 1; gap: 6px; }
.conversation-line { display: flex; min-width: 0; align-items: center; gap: 8px; }
.conversation-line strong { overflow: hidden; flex: 1; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.conversation-line time { color: var(--ink-faint); font-size: 10px; }
.conversation-preview { color: #718398; font-size: 12px; }
.conversation-preview > span { overflow: hidden; flex: 1; text-overflow: ellipsis; white-space: nowrap; }
.conversation-preview i { font-style: normal; }
.conversation-preview .pin { color: var(--blue); font-size: 8px; }
.request-section { margin-bottom: 4px; }
.request-section-title { padding: 10px 10px 6px; color: var(--ink-faint); font-size: 11px; font-weight: 700; }
.request-card { display: grid; padding: 14px; grid-template-columns: auto 1fr; gap: 11px; border: 1px solid rgba(255,255,255,.68); border-radius: 20px 16px 20px 16px; background: rgba(255,255,255,.4); box-shadow: inset 0 1px 0 rgba(255,255,255,.85); }
.request-copy { min-width: 0; }
.request-copy strong { font-size: 13px; }
.request-copy p { margin: 3px 0; overflow: hidden; color: var(--ink-soft); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.request-copy span { color: var(--ink-faint); font-size: 9px; }
.request-actions { display: flex; grid-column: 1 / -1; gap: 8px; }
.request-actions button { min-height: 32px; padding: 0 13px; border: 0; border-radius: 11px; color: var(--on-surface); font-size: 11px; font-weight: 700; background: var(--fill); cursor: pointer; }
.request-actions .accept { color: white; background: var(--blue); box-shadow: 0 6px 13px rgba(10,132,255,.2); }
.empty-list { display: grid; min-height: 260px; padding: 30px; place-items: center; align-content: center; text-align: center; }
.empty-list > span { display: grid; width: 52px; height: 52px; margin-bottom: 15px; place-items: center; border: 1px solid rgba(255,255,255,.8); border-radius: 19px; color: var(--blue); font-size: 23px; background: rgba(255,255,255,.48); box-shadow: inset 0 1px 0 #fff; }
.empty-list strong { font-size: 14px; }
.empty-list p { margin: 6px 0 0; color: var(--ink-soft); font-size: 11px; line-height: 1.5; }
.sidebar-loading { display: grid; padding: 10px 18px; gap: 14px; }
.sidebar-loading span { height: 58px; border-radius: 17px; background: linear-gradient(90deg, rgba(255,255,255,.18), rgba(255,255,255,.62), rgba(255,255,255,.18)); background-size: 200% 100%; animation: shimmer 1.4s infinite; }
@keyframes shimmer { to { background-position: -200% 0; } }

@media (max-width: 1020px) { .conversation-sidebar { width: 300px; } }
@media (max-width: 760px) {
  .conversation-sidebar { width: 100%; border-radius: 25px; padding-bottom: 76px; }
}

.conversation-sidebar {
  width: 320px;
  border-right: 1px solid var(--separator);
  border-radius: 0;
  background: var(--surface-raise);
}
.sidebar-header { padding: 22px 18px 13px; }
.sidebar-header p {
  margin-bottom: 3px;
  color: var(--blue);
  font-family: inherit;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0;
  text-transform: none;
}
.sidebar-header h1 { font-size: 24px; font-weight: 700; letter-spacing: -0.03em; }
.header-actions { gap: 5px; }
.mini-button {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 50%;
  color: var(--on-surface);
  background: var(--fill);
  box-shadow: none;
}
.mini-button:hover { color: var(--blue); background: var(--button-hover); }
.sidebar-search {
  min-height: 38px;
  margin: 0 12px 10px;
  border: 0;
  border-radius: 11px;
  background: var(--fill);
}
.sidebar-search kbd { border-color: var(--separator); background: rgba(255, 255, 255, 0.58); }
.conversation-list { padding: 0 8px 12px; }
.conversation-item {
  min-height: 68px;
  padding: 9px 10px;
  border: 0;
  border-radius: 12px;
  gap: 11px;
}
.conversation-item:hover { background: var(--hover); transform: none; }
.conversation-item--active { background: var(--active); box-shadow: none; }
.conversation-copy { gap: 5px; }
.conversation-line strong { font-size: 14px; }
.conversation-line time { font-size: 10px; }
.conversation-preview { color: var(--ink-faint); font-size: 12px; }
.request-section { margin-bottom: 6px; padding-bottom: 4px; border-bottom: 1px solid var(--separator); }
.request-section-title { padding: 8px 10px 4px; }
.request-card {
  padding: 12px 10px;
  border: 0;
  border-bottom: 1px solid var(--separator);
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
.empty-list > span,
.empty-list > .empty-icon {
  border: 0;
  border-radius: 50%;
  background: var(--fill);
  box-shadow: none;
}
.empty-icon { display: grid; place-items: center; width: 52px; height: 52px; }
.empty-icon svg { width: 24px; color: var(--blue); }
.sidebar-loading span { height: 54px; border-radius: 11px; background-color: var(--fill); }

@media (max-width: 1020px) { .conversation-sidebar { width: 294px; } }
@media (max-width: 760px) {
  .conversation-sidebar {
    width: 100%;
    padding-bottom: 84px;
    border: 0;
    border-radius: 0;
    background: var(--surface);
  }
  .sidebar-header { padding-top: max(20px, env(safe-area-inset-top)); }
}
</style>
