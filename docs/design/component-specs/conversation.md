# ConversationItem

日期：2026-09-09；MX-A04 当前 UI 提取。

用途：选择一个稳定会话。实际位于 [ConversationSidebar.vue](../../../apps/web/src/components/chat/ConversationSidebar.vue)，没有单独 ConversationItem.vue。结构：原生 button → 50px UserAvatar + 名称/时间 + 预览/未读/静音/待发送 + 可选置顶标志。

| 视觉项 | 当前规则 |
|---|---|
| 尺寸/间距 | 最终样式 min-height 68，padding 9×10、gap 11；窄屏覆盖保留；内容 min-width 0 |
| 字体 | 名称 body 14，时间/预览 caption 12；长昵称和预览单行省略 |
| 颜色 | text.primary/tertiary，interaction.hover/selected；action.text；未读 decorative.coral |
| 圆角 | shape.radius.control=12；内部未读点/徽章维持原局部尺寸 |

| 状态 | 当前实现与约束 |
|---|---|
| default / hover | 默认透明；hover 使用 interaction.hover，不再位移（最终覆盖规则） |
| focus / pressed | 原生按钮与全局可见焦点；没有单独行 pressed 色 |
| selected | selectedId AND selectedKind 匹配；interaction.selected；A04 添加 aria-current=true |
| unread / muted / pinned / pending | 来自会话数据；分别显示数量、静音标记、置顶图标、待发送数；不由颜色/时间猜测 |
| loading | 列表级占位，由 loading prop 控制；没有逐项 spinner |
| error | 列表搜索等外层错误；无通用单项 error 样式 |
| disabled | 当前无单项 disabled prop；无权会话由上游过滤，不声称已有禁用行 |
| removed | 上游移除后不再渲染；不是一条“已移除”可访问会话 |

light/dark 用相同语义映射。名称/数量保留文本，选中语义与视觉同步。长昵称、128 未读、静音、待发送、320px/2 倍字号均进入 fixture；徽章固定 20px/9px、小字和长名字无法完整视觉读取是既有债，详见 debt。焦点顺序仍是原生按钮，不引入 listbox/roving tabindex 新模式。
## 共用约定

以下为当前 Vue 实现提取；“组合”表示由调用方负责，“未实现”不得当成功能。颜色在 light/dark 下通过同名 semantic token 切换；具体映射以 [Token source](../../../packages/design-tokens/tokens.json) 为准，旧 alias 在迁移期保留。数值为基础字号 16 下的当前值，不能据此锁死未来移动端的文本高度。状态目录及实现程度由 [components.json](../../../packages/design-tokens/components.json) 校验。
