# Button

日期：2026-09-09；MX-A04 当前 UI 提取。

用途：触发明确操作。实现是 [main.css](../../../apps/web/src/assets/main.css) 的原生 button 类；目前没有独立 Button.vue。结构为按钮容器、可选 UiIcon 和可见名称，加载/结果由调用方组合。

| 视觉项 | 当前规则 |
|---|---|
| 尺寸/间距 | primary/secondary 最小高 `size.control.default` 44；上下 spacing.2=8，左右 primary spacing.5=20 / secondary spacing.4=16；icon 边长 `size.control.icon` |
| 字体 | body 14，primary 650 / secondary 600；系统中英文字体 |
| 色彩 | 主操作 action.primary / text.on-accent；次操作 text.primary / material.surface / border.default |
| 圆角/动效 | shape.radius.control=12；motion.duration.fast；组件特例 component.button.press-scale=.97；原阴影保留本地 |

| 状态 | 当前实现与约束 |
|---|---|
| default | 原生 button，表单外显式 type=button |
| hover | primary 使用 action.primary-hover；禁用时不改变主背景；secondary/icon 没有独立通用 hover 背景 |
| focus | 全局 :focus-visible 焦点环；保持键盘可达 |
| pressed | 原有 .97 缩放；禁用时无缩放 |
| selected | 无通用选中样式；切换按钮由调用方提供 aria-pressed 及明确状态 |
| loading | 组合：保留操作名称，可添加进度，设置 aria-busy/disabled，防重复提交；没有共享 spinner 状态 API |
| error | 组合：就近显示错误与可恢复操作；按钮颜色不能代替错误描述 |
| disabled | 原生 disabled，opacity .45；不是单靠 CSS 阻断点击 |

可访问性：图标按钮必须有 aria-label；普通按钮用可见名称。DOM 回归覆盖 hover、focus、disabled、组合 aria-busy。小型局部按钮仍有触摸目标债，见设计债；不能宣称整个产品已满足 44 高。
## 共用约定

以下为当前 Vue 实现提取；“组合”表示由调用方负责，“未实现”不得当成功能。颜色在 light/dark 下通过同名 semantic token 切换；具体映射以 [Token source](../../../packages/design-tokens/tokens.json) 为准，旧 alias 在迁移期保留。数值为基础字号 16 下的当前值，不能据此锁死未来移动端的文本高度。状态目录及实现程度由 [components.json](../../../packages/design-tokens/components.json) 校验。
