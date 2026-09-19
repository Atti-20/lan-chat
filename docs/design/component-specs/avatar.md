# UserAvatar

日期：2026-09-09；MX-A04 当前 UI 提取。

用途：展示身份识别与在线提示。实际组件 [UserAvatar.vue](../../../apps/web/src/components/base/UserAvatar.vue)。结构：role=img 的圆形容器，图片或加载占位/昵称首字，可选在线点。图片异步状态来自头像缓存服务结果。

| 视觉项 | 当前规则 |
|---|---|
| 尺寸 | size prop 默认 46，会话项使用 50；首字 max(14,size×.4) px |
| 间距/圆角 | 容器内居中；当前局部 border-radius:50%；在线点位置/尺寸保留组件局部值 |
| 字体/颜色 | 字重和白色前景沿用组件；新语义 text.on-accent、presence.online、background.surface；用户自定义色及按昵称生成的 HSL 渐变保留，不作为品牌 palette |

| 状态 | 当前实现与约束 |
|---|---|
| default | 昵称首字；空昵称回退问号；旧 emoji/svg 配置按现有文字策略回退 |
| image | 可用图片 object-fit cover；装饰 img 的 alt 为空，由外层提供名称 |
| online | 在线点 + 外层名称中的“在线”，不是仅靠绿色 |
| loading | 请求期间已有 spinner；不伪造在线或错误业务状态 |
| error | 解析失败或图片 error 后清缓存并回退首字；DOM 用 404 验证 |
| hover / focus / pressed / selected / disabled | 非操作组件，不自行聚焦或禁用；若可点击，由父 button 承担操作名称和上述状态 |

light/dark 保持同一身份渐变，外围/在线点边线用主题语义。A04 修复 role=img 和在线可访问名称；不改变图片/缓存算法。首字作为身份标记仍是固定 px，用户自定义渐变的对比度未全覆盖，列为设计债；不能当作正文 Dynamic Type 验收。
## 共用约定

以下为当前 Vue 实现提取；“组合”表示由调用方负责，“未实现”不得当成功能。颜色在 light/dark 下通过同名 semantic token 切换；具体映射以 [Token source](../../../packages/design-tokens/tokens.json) 为准，旧 alias 在迁移期保留。数值为基础字号 16 下的当前值，不能据此锁死未来移动端的文本高度。状态目录及实现程度由 [components.json](../../../packages/design-tokens/components.json) 校验。
