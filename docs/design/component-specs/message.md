# MessageBubble

日期：2026-09-09；MX-A04 当前 UI 提取。

用途：展示既有消息内容与可靠投递状态。实际气泡在 [MessageThread.vue](../../../apps/web/src/components/chat/MessageThread.vue)，附件在 [AttachmentBubble.vue](../../../apps/web/src/components/chat/AttachmentBubble.vue)。结构：消息行（稳定标识）→ 身份/作者 → 回复可选 → 文本或附件气泡 → 时间、投递文案及允许的操作。Vue 负责映射和 emit，不修改 Shared Core 状态。

| 视觉项 | 当前规则 |
|---|---|
| 尺寸/间距 | 气泡 padding 9×13；桌面最大宽 min(72%,620px)，窄屏 82%；内部局部间距保留 |
| 字体 | body 14；component.message.line-height=1.55；元信息保持 11px 现值 |
| 颜色 | 自己：message.own → action.primary，message.on-own → text.on-accent；对方：message.peer → background.fill，message.on-peer → text.primary；失败 status.danger |
| 圆角 | shape.radius.message=18，component.message.tail-radius=6；自己/对方对应尾角，不新增形状 |

| 状态 | 稳定输入与当前显示 |
|---|---|
| own / peer | 发送者身份与当前用户 ID 比较；不能根据气泡颜色反推身份 |
| waitingNetwork | deliveryState=WAITING_NETWORK，显示“等待连接”；本人且有 clientMsgId 时可以重试/取消 |
| sending | deliveryState=SENDING，显示“发送中” |
| sent | SENT（以及已有 DELIVERED/READ 值）不显示等待/失败文案；CHAT_ACK 表示服务端确认，不表示对方已读；专门提及回执另走授权数据 |
| failed / error | deliveryState=FAILED，显示“发送失败”；合资格原消息提供重试/取消 |
| retrying | 是 UI 操作阶段，不新增 RETRYING 枚举；emit retry 原消息后由父层提供同一 clientMsgId 的 SENDING；不创建另一条气泡 |
| recalled | isRecalled=1，显示撤回占位，原内容/附件不可见 |
| burned | 已焚毁状态显示占位，不展示旧内容 |
| selected | 没有消息批量选中状态/样式；原有 selectstart.prevent 也是文本复制债，A04 不重写 |
| hover / focus / pressed | 气泡非按钮；原有上下文操作保持；内部原生操作按钮采用可见焦点，没有气泡通用 pressed 样式 |
| loading / disabled | loading 是 SENDING 或附件各自状态；气泡本身无 disabled。操作资格由既有业务判断，不能仅靠灰色阻止 |
| attachment | type 决定图片/文件分支；图片请求 loading、成功缩略图、error 回退/本地不可取提示；文件下载 saving/progress 禁用重复下载。传输进度与 CHAT_ACK 是不同输入，不能互相推断 |

浅深色下 own/peer 配色必须同时检查文本对比度。fixture 验证长中英文本/URL、waiting/sending/failed、重试原 ID、撤回/焚毁内容隐藏及 2 倍根字号；重试父层是测试模拟，不替代 outbox/网络端到端证据。附件状态这里只做源码对照，A04 未进行真实上传下载/各状态截图。

可访问性：失败有文字和按钮，不能仅红色；状态更新的完整屏幕阅读器播报、消息选取/复制、小按钮触摸目标、窄屏放大后元信息断行均保留设计债。禁止为了气泡像素一致冻结 Flutter 文本缩放。
## 共用约定

以下为当前 Vue 实现提取；“组合”表示由调用方负责，“未实现”不得当成功能。颜色在 light/dark 下通过同名 semantic token 切换；具体映射以 [Token source](../../../packages/design-tokens/tokens.json) 为准，旧 alias 在迁移期保留。数值为基础字号 16 下的当前值，不能据此锁死未来移动端的文本高度。状态目录及实现程度由 [components.json](../../../packages/design-tokens/components.json) 校验。
