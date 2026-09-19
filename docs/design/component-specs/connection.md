# ConnectionStatus / StatusIndicator

日期：2026-09-09；MX-A04 当前 UI 提取。

用途：解释连接和待发送/失败数量，提供有资格的恢复动作。事实组件 [ConnectionStatusBar.vue](../../../apps/web/src/components/chat/ConnectionStatusBar.vue) 当前没有生产父级引用；A04 只用测试 fixture 单独挂载，不宣称已接入当前工作区。

结构：role=status 容器、装饰状态点、节点/路径/状态文案、数量、重试/重连/诊断按钮。当前 min-height 34，padding 6×18（窄屏横向 13），gap space-2；micro 字号、ink-soft、surface-glass、separator；圆点 7px，按钮 radius-sm/徽章 radius-pill。这些旧 alias 现已指向新语义，未进行组件全面迁移。

| 状态 | 当前实现与边界 |
|---|---|
| connecting / authenticating / syncing | 明确对应 CONNECTING / AUTHENTICATING / SYNCING 文案；属于进度，不宣称完成 |
| online | ONLINE，显示节点在线/可选延迟，成功色 |
| degraded | DEGRADED，同步未完成，警告色，可重连 |
| reconnecting / offline | RECONNECTING 带尝试次数；OFFLINE 显示离线文案/重连入口 |
| error | failedCount>0 优先危险色、失败数和重试失败项；不由延迟阈值猜测失败 |
| loading | 上述连接阶段文案；没有独立 spinner |
| disabled | 当前恢复按钮无处理中 disabled prop，需要生产接入时明确防重复策略 |
| hover / focus / pressed | 原生操作按钮/全局焦点；没有通用 hover/pressed 变体 |
| selected | 状态展示无 selected 语义 |
| sessionExpired | 不是此组件 ConnectionState 枚举；应用鉴权流程处理，原目录仅有规范，不声称此组件已展示 |

浅深色通过旧 alias → 新语义保证一致。role=status 保留；文字和数量补充颜色。窄屏数量被隐藏、恢复触摸目标/播报频率、文案与实际适配器持久化能力是否匹配，需要生产接入时验收；仅有设计规范不能证明本机持久化或重连成功。
## 共用约定

以下为当前 Vue 实现提取；“组合”表示由调用方负责，“未实现”不得当成功能。颜色在 light/dark 下通过同名 semantic token 切换；具体映射以 [Token source](../../../packages/design-tokens/tokens.json) 为准，旧 alias 在迁移期保留。数值为基础字号 16 下的当前值，不能据此锁死未来移动端的文本高度。状态目录及实现程度由 [components.json](../../../packages/design-tokens/components.json) 校验。
