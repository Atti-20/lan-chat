<script setup lang="ts">
import { shallowRef } from 'vue'
import UserAvatar from '../../src/components/base/UserAvatar.vue'
import UiIcon from '../../src/components/base/UiIcon.vue'
import ConversationSidebar from '../../src/components/chat/ConversationSidebar.vue'
import ChatWorkspace from '../../src/components/chat/ChatWorkspace.vue'
import ConnectionStatusBar from '../../src/components/chat/ConnectionStatusBar.vue'
import { useTheme } from '../../src/composables/useTheme'
import { conversations, messages, user } from './data'
import type { ConnectionState } from '../../src/types'

const { mode, toggle } = useTheme()
const query = shallowRef('')
const currentMessages = shallowRef(messages)
const selectedId = shallowRef(2)
const event = shallowRef('none')
const avatar = shallowRef('letter:长:#007aff')
const connection = shallowRef<ConnectionState>('DEGRADED')

function retry(clientMsgId: string) {
  event.value = `retry:${clientMsgId}`
  // A test parent responds to the actual emit; no transport/ACK is simulated here.
  currentMessages.value = currentMessages.value.map(message => message.clientMsgId === clientMsgId
    ? { ...message, deliveryState: 'SENDING' } : message)
}
</script>

<template>
  <main class="design-fixture" data-testid="design-fixture">
    <header class="fixture-heading">
      <h1>MeshX 现有组件基线</h1>
      <button class="secondary-button" type="button" @click="toggle">切换主题</button>
      <output data-testid="theme">{{ mode }}</output>
    </header>

    <section class="fixture-controls" aria-label="基础控件">
      <div class="fixture-buttons">
        <button class="primary-button" type="button" @click="event = 'primary'">发送消息</button>
        <button class="secondary-button" type="button" disabled @click="event = 'disabled'">暂不可用</button>
        <button class="primary-button" type="button" disabled aria-busy="true">正在发送…</button>
        <button class="icon-button" type="button" aria-label="添加"><UiIcon name="plus" :size="20" /></button>
      </div>
      <label>昵称<input class="field" placeholder="请输入昵称" value="中文 English" /></label>
      <label>说明<input class="field" disabled value="不可编辑" /></label>
      <label>校验示例<input class="field" aria-invalid="true" aria-describedby="fixture-error" value="示例错误值" /></label>
      <span id="fixture-error" class="fixture-error">请核对输入内容。</span>
      <div class="fixture-avatar">
        <UserAvatar name="长昵称验证 Long display name" :avatar="avatar" :online="true" />
        <label>头像测试地址<input class="field" v-model="avatar" /></label>
      </div>
    </section>

    <section class="fixture-chat" aria-label="真实会话组件">
      <ConversationSidebar
        v-model:query="query" section="messages" :conversations="conversations" :all-conversations="conversations"
        :message-results="[]" :requests="[]" :selected-id="selectedId" selected-kind="private"
        @select="selectedId = $event.id; event = `select:${$event.conversationId}`"
      />
      <ChatWorkspace
        :conversation="conversations[0]!" :messages="currentMessages" :user="user" :members="[]"
        :connected="false" :file-allowed="false" status-label="离线测试场景"
        @retry="retry" @cancel-pending="event = `cancel:${$event}`" @reply="event = `reply:${$event.messageId}`"
      />
    </section>

    <section class="fixture-connection" aria-label="独立连接条规范样例">
      <label>连接状态
        <select class="field" v-model="connection">
          <option v-for="state in ['CONNECTING', 'AUTHENTICATING', 'SYNCING', 'ONLINE', 'DEGRADED', 'RECONNECTING', 'OFFLINE']" :key="state">{{ state }}</option>
        </select>
      </label>
      <ConnectionStatusBar :state="connection" :pending-count="2" :failed-count="1" node-name="MeshX 本地节点" :can-view-diagnostics="true" @retry="event = 'retry-all'" @reconnect="event = 'reconnect'" />
    </section>
    <output data-testid="event">{{ event }}</output>
  </main>
</template>

<style scoped>
/* Test-only layout. Product rendering/styles remain in the imported real components. */
.design-fixture { display: grid; max-width: 1440px; margin: auto; padding: 16px; gap: 16px; }
.fixture-heading { display: flex; flex-wrap: wrap; align-items: center; gap: 16px; }
.fixture-heading h1 { margin: 0; font-size: var(--font-title); }
.fixture-controls { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); padding: 16px; gap: 12px; background: var(--panel); }
.fixture-controls label { display: grid; min-width: 0; gap: 4px; font-size: var(--font-caption); }
.fixture-buttons { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 12px; }
.fixture-error { color: var(--danger); font-size: var(--font-caption); }
.fixture-avatar { display: flex; min-width: 0; align-items: center; gap: 12px; }
.fixture-avatar label { flex: 1; }
.fixture-chat { display: grid; grid-template-columns: 330px minmax(0, 1fr); height: 820px; min-width: 0; }
.fixture-chat :deep(.conversation-sidebar) { width: 100%; }
.fixture-connection { display: grid; grid-template-columns: minmax(0, 1fr); min-width: 0; gap: 8px; background: var(--panel); }
.fixture-connection > label { width: 100%; max-width: 320px; min-width: 0; }
@media (max-width: 760px) {
  .design-fixture { padding: 8px; gap: 12px; }
  .fixture-controls { grid-template-columns: minmax(0, 1fr); padding: 12px; }
  .fixture-chat { height: auto; grid-template-columns: minmax(0, 1fr); }
  .fixture-chat :deep(.conversation-sidebar) { height: 320px; }
  .fixture-chat :deep(.chat-workspace) { height: 960px; }
}
</style>
