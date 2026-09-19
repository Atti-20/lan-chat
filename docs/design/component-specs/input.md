# Input

日期：2026-09-09；MX-A04 当前 UI 提取。

用途：输入用户数据。实现为 [main.css](../../../apps/web/src/assets/main.css) 的 `.field`，结构由可见 label、原生 input/textarea、可选帮助/错误文案组合；没有 Input.vue 状态机。

| 视觉项 | 当前规则 |
|---|---|
| 尺寸/间距 | width 100%、min-width 0、最小高 44；padding spacing.2 / spacing.3，即 8 / 12 |
| 字体/颜色 | body 14；text.primary、background.fill、border.default/焦点色；窄屏现有输入字号规则保留 |
| 圆角 | shape.radius.control=12；最小高度而非固定行高 |

| 状态 | 当前实现与约束 |
|---|---|
| empty / filled | 原生 value/placeholder；placeholder 不替代 label |
| hover / pressed | 没有单独通用样式，保留原生文本交互 |
| focus | field 焦点边线与 focus ring；不靠移除 outline 隐藏键盘焦点 |
| selected | 文本选区由浏览器负责；不存在业务 selected 状态 |
| loading | 组合：由表单提供进度、只读或禁用策略，不从输入长度推断 |
| error | 组合：aria-invalid=true，aria-describedby 指向就近错误；无通用错误边框类 |
| disabled / readOnly | 原生属性；disabled 不参与交互，readonly 可聚焦/复制，不混同 |

浅深色引用同名语义。中文、英文长输入与 320px/2 倍根字号 fixture 无页面横向溢出。DOM 验证字段错误描述；没有替所有生产表单完成标签审计。Flutter 后续沿用字号层级，遵守系统缩放/键盘，不将文本容器固定到单行高度。
## 共用约定

以下为当前 Vue 实现提取；“组合”表示由调用方负责，“未实现”不得当成功能。颜色在 light/dark 下通过同名 semantic token 切换；具体映射以 [Token source](../../../packages/design-tokens/tokens.json) 为准，旧 alias 在迁移期保留。数值为基础字号 16 下的当前值，不能据此锁死未来移动端的文本高度。状态目录及实现程度由 [components.json](../../../packages/design-tokens/components.json) 校验。
