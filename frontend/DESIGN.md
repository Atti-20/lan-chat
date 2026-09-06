# MeshX 前端设计规范

版本：2026-09-05。基准：当前 `frontend/` 的 Vue 界面。适用于浏览器、Tauri 桌面壳及共享 Vue 的移动壳。

## 1. 方向与实现入口

保留现有的蓝色交互、系统字体、浅灰画布、分层玻璃面板和紧凑三栏工作区。深色主题使用相同的布局及语义，不另做一套页面。旧版绿色主题、Satoshi / JetBrains Mono 字体及禁止系统字体的规定已废止。

| 职责 | 唯一入口 |
| --- | --- |
| 颜色、字体、间距、圆角、基础控件、焦点与辅助偏好 | [`src/assets/main.css`](src/assets/main.css) |
| 全局样式加载 | [`src/main.ts`](src/main.ts)，只导入一次主 CSS |
| 主题状态、持久化、图标及浏览器主题色 | [`src/composables/useTheme.ts`](src/composables/useTheme.ts) |
| 通用线性图标 | [`src/components/base/UiIcon.vue`](src/components/base/UiIcon.vue) |
| 产品标识、头像、开关 | `BrandLogo.vue`、`UserAvatar.vue`、`AppleSwitch.vue` |
| 页面布局和组件专属状态 | 对应 `.vue` 的 `<style scoped>` |
| 基础颜色对比度、未定义变量检查 | [`tests/design-tokens.test.mjs`](tests/design-tokens.test.mjs)，随 `npm test` 执行 |

不新增第二套全局样式入口或 UI 框架。新增界面先复用主 CSS 的变量和控件；专属布局保留在组件内。修改规范中的基础值时，同时修改主 CSS 和受影响的页面，并进行渲染复查。

## 2. 颜色规范

以下为实际 CSS 变量，十六进制大小写不影响含义。

| 语义 / 变量 | 浅色 | 深色 | 用法 |
| --- | --- | --- | --- |
| 主文字 `--ink` | `#1d1d1f` | `#f5f5f7` | 标题、正文 |
| 次要文字 `--ink-soft` | `#63636a` | `#a1a1aa` | 说明、标签 |
| 辅助文字 `--ink-faint` | `#6a6a72` | `#9a9aa3` | 时间、元数据、占位符 |
| 画布 `--canvas` | `#edf0f4` | `#0f0f10` | 页面及浏览器主题色 |
| 面板 `--panel` / `--surface` | `#ffffff` | `#1c1c1e` | 内容区域 |
| 填充 `--fill` | `#f2f2f7` | `#2c2c2e` | 输入框、辅助底色 |
| 装饰蓝 `--blue` | `#007aff` | `#0a84ff` | 进度、状态点、装饰 |
| 交互文字 `--accent-text` | `#0064d2` | `#70b5ff` | 链接、文字操作、线性图标 |
| 主操作背景 `--action-bg` | `#006fe8` | 同浅色 | 主按钮、自己的消息气泡 |
| 主操作悬停 `--action-hover` | `#005ec7` | 同浅色 | 可用主按钮的 hover |
| 强色上的文字 `--on-accent` | `#ffffff` | 同浅色 | 实心操作背景上的文字 |
| 成功文字 `--success` | `#1d7534` | `#62dc7c` | 已完成、成功 |
| 警告文字 `--warning` | `#9d5900` | `#ffb54a` | 重要、过期、警告 |
| 错误文字 `--danger` | `#c52b24` | `#ff756b` | 错误、拒绝、危险操作 |
| 成功实心背景 `--success-bg` | `#21833b` | 同浅色 | 完成任务按钮 |
| 危险实心背景 `--danger-bg` | `#c52b24` | 同浅色 | 危险操作确认 |
| 预览工作区 `--preview-canvas` | `#e8eaee` | `#252528` | PDF 周围、文本预览 |

`--green`、`--coral`、`--cyan`、`--violet` 保留为状态点、头像或低透明度装饰。小号状态文字使用语义文字色；白字实心按钮使用对应 `*-bg`，避免直接用明亮状态色造成低对比度。状态必须同时有文字或图标，不能只靠颜色区分。

PDF 纸张使用 `--document-paper: #ffffff`，视频画布使用 `--media-canvas: #000000`，它们属于内容呈现，不随主题反转。用户头像和附件原始颜色不受界面主题限制。

普通文字的目标对比度为 4.5:1，大字号目标为 3:1，依据 [W3C 文字对比度说明](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)。自动检查覆盖基础文字色对 `panel / fill / canvas` 及白字对实心操作色；渐变、透明叠层、图片背景仍须在实际页面检查。

## 3. 字体规范

`--font-sans`：`-apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif`。不下载外部字体。`--font-mono`：`ui-monospace, "SF Mono", SFMono-Regular, Menlo, Consolas, monospace`，用于日志、代码和技术标识。

| 变量 | 默认 16px 根字号下 | 用途 |
| --- | --- | --- |
| `--font-micro` | 11px | 极少量时间、短标记；不用于主要说明和输入 |
| `--font-caption` | 12px | 表单标签、列表元数据 |
| `--font-body-sm` | 13px | 紧凑说明、管理页正文 |
| `--font-body` | 14px | 消息、输入框、基础按钮 |
| `--font-body-lg` | 15px | 重要内容、任务标题 |
| `--font-subtitle` | 16px | 描述、副标题 |
| `--font-title-sm` | 18px | 小节标题 |
| `--font-title` | 20px | 弹窗和管理模块标题 |
| `--font-page-title` | 24px | 侧栏及页面标题 |

登录与欢迎页的展示标题使用现有响应式 `clamp()`，不套用普通工作区标题大小。正文默认行高 `--line-body: 1.5`；文本预览等长内容使用约 `--line-reading: 1.65`。正文常规字重 400，标签 500–600，重要按钮 600–650，标题 700；短眉题可使用较重字重，不能替代正文层级。

字号优先使用 rem 变量，不修改根字号来抵消用户缩放。粗指针设备上的 input / textarea / select 至少 16px，避免 iOS 聚焦时放大页面。输入、下拉框、按钮统一继承字体。

## 4. 空间、形状与层次

基础间距使用 4px 网格：`--space-1/2/3/4/5/6/8/10` 对应 `4/8/12/16/20/24/32/40px`。组件内部可保留 1–3px 边线补偿或图标光学对齐；新增布局不要随意增加 15、19、23px 等相近档位。

| 用途 | CSS 变量 / 尺寸 |
| --- | --- |
| 紧凑小区域 | `--radius-sm: 8px` |
| 按钮、输入框、列表项 | `--radius-control: 12px` |
| 中型内容块 | `--radius-md: 14px` |
| 消息主体 | `--radius-bubble: 18px`，保留方向性小角 |
| 大型卡片 | `--radius-lg: 20px` |
| 弹窗 / 底部面板 | `--radius-sheet: 24px` |
| 大型浮层 | `--radius-xl: 26px` |
| 状态胶囊 | `--radius-pill: 999px` |
| 头像及圆形关闭按钮 | 50% |

主工作区仍为导航栏 + 列表侧栏 + 内容。桌面导航默认 72px，平板 68px；列表宽度可调整。视口 ≤760px 切换为单栏，底部导航与会话视图互斥。登录页的单栏断点为 860px，欢迎页为 720px，文件预览全屏断点为 600px，它们服务不同的内容结构。

`apple-structural-surface` 用于导航结构，`apple-content-surface` 用于阅读区域，`apple-float-surface` / `apple-modal-surface` 用于浮层。背景、边线、阴影取自主 CSS，组件不自建一套浅色专用材质。降低透明度时使用不透明主题面板；减少动态效果时关闭持续动画和过渡。

## 5. 控件与图标

- `.primary-button`、`.secondary-button` 和 `.field` 采用 `--control-height: 44px`。普通图标按钮采用 `--control-icon: 40px`，紧凑工具栏可使用 `--control-compact: 36px`。粗指针下的基础操作至少 44px。
- 表单标签在控件上方；焦点使用 `--focus-ring`。select、summary 和可聚焦元素也需要可见焦点。disabled 不触发 hover / active 反馈。
- 按钮文案对应具体动作。加载、错误、空列表要保留尺寸稳定的区域；错误提供后续操作，不把内部异常或堆栈放进普通聊天提示。
- 操作图标使用 `UiIcon` 的 24 × 24 画布、2px 圆头描边、`currentColor`。常用显示档位是 16、20、24px；18、22px 可用于已有组件的光学对齐。图标保持正方形，不单独拉伸宽或高。
- 图标按钮在按钮上提供 `aria-label`；装饰图标不重复朗读且不可聚焦。关闭使用 `close`，下载使用 `download`，设备使用 `monitor / smartphone / globe`。不使用 Unicode `×` 或系统 emoji 代替操作图标。
- Emoji 只作为用户消息、用户选择的头像内容保留。产品标识使用 `BrandLogo`，不使用普通功能图标替代。
- 44px 是本项目主要触摸操作的设计目标；不能把它描述为所有 WCAG AA 控件的硬性阈值。WCAG 2.2 最小目标规则及间距例外见 [W3C 目标尺寸说明](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html)。

## 6. 页面布局约束

| 场景 | 必须保持的行为 | 对应实现 |
| --- | --- | --- |
| 登录 / 注册 | 320px 可填写所有输入；中文标题自然换行；深色 Logo 与主题同步 | `AuthView.vue` |
| 欢迎 / 头像 | fieldset 和网格子项允许收缩；长昵称换行，不挤出上传按钮 | `WelcomeView.vue` |
| 消息 / 附件 | 内容区可滚动，输入区保持可达；长文件名不挤走操作 | `MessageThread.vue`、`AttachmentBubble.vue` |
| 广播 / 回执 | 警告、成功和错误使用相同语义色；窄屏证据按钮另起一行 | `BroadcastWorkspace.vue`、`BroadcastCompletionPanel.vue` |
| 创建账号 | 根据工作区实际宽度换列，不能只判断整个窗口；表单过高时内部滚动 | `AdminConsole.vue` 的容器查询 |
| 管理表格 | 表格可以在自身容器横向滚动，整页不得溢出；状态词不被压成竖排 | `AdminConsole.vue` |
| 日志 / 审计 | 筛选器和工具栏可换行；长标识可折行；不把列表挤出视口 | `RuntimeLogToolbar.vue`、`AuditLogToolbar.vue`、`AuditLogList.vue` |
| 文件 / PDF | 标题、内容和操作栏独立分行；矮窗口允许内容收缩及滚动；文档比例保持不变 | `FilePreviewModal.vue`、`PdfPreview.vue` |
| 原生安全区 | 页面及全屏预览避让四边安全区；不在多层重复累加底部空白 | 主布局与预览样式 |

主要交互过渡为 140–180ms，使用 `--duration-fast: 160ms` 和已有 `--ease-liquid`；登录分段切换为 240ms。内容不依赖动画才能出现，持续动画服从 `prefers-reduced-motion`。

## 7. 本轮审查与修复（2026-09-05）

1. 将旧绿色设计稿替换为与当前蓝色前端对应的规范；主 CSS 继续作为唯一全局入口。
2. 将 47 个组件中的常用字号、圆角、等宽字体及语义状态颜色迁移至变量；后续补充了间距变量和模块标题统一。
3. 合并登录 / 欢迎页旧版覆盖样式；另移除 66 条被后续同名规则覆盖的样式声明。
4. 修复文件预览的 `--surface-raised` 拼写漂移，统一错误色与深浅预览底色；移除阻碍矮窗口收缩的固定最小高度。
5. 统一关闭、设备图标；补齐 SVG 的不可聚焦属性和 select / summary 的焦点样式。
6. 768px 窗口中复现创建账号表单宽 515px、可用内容宽 394px、确认按钮右边界到 876px；修复后表单与内容区同宽，全部输入和确认按钮均在窗口内。
7. 日志筛选器按容器宽度换行；审计筛选器复用 44px 基础控件，长审计内容不撑宽布局。
8. 基础文字及按钮颜色增加对比度回归检查；未定义的 CSS 变量引用会使测试失败。
9. PDF 页面缩窄时原先只收缩宽度，固定 CSS 高度使 3:4 页面被拉长；改为由位图比例决定高度，并在四种视口重新测量。
10. 降低透明度偏好原先被弹窗高优先级的模糊样式覆盖；统一选择器优先级，实际检查导航、个人资料、设备弹窗和遮罩均取消模糊，内容面板使用不透明底色。

本地截图和日志位于仓库的 `output/playwright/design-audit/`，属于验证输出，不是构建输入。测试使用本机隔离验证节点及专用浏览器会话。UI 预览地址为 `http://127.0.0.1:5174/app/`，仅在本次预览进程运行期间有效。

### 回归检查清单

- 主题：浅色、深色，通过 `useTheme` 切换，不能只改 DOM 属性而漏掉 Logo 状态。
- 常用窗口：1440 × 900、1024 × 768、768 × 1024、390 × 844、320 × 568；预览另测 844 × 390 横屏。
- 页面：登录 / 注册、欢迎、聊天、广播、账号管理、日志、审计、个人资料与文件预览。
- 状态：加载、空列表、错误、长昵称、长标识、长文件名、禁用按钮、展开详情、键盘焦点、减少动态效果。
- 命令：`npm --prefix frontend test`、`npm --prefix frontend run typecheck`、`npm --prefix frontend run build`。

本轮结果：49 项测试通过，Web 和桌面前端生产构建通过（均包含 `vue-tsc` 检查）。完整范围、截图和未覆盖项见本机验证输出 `docs/reports/界面设计/界面设计审查报告_2026-09-05.md`；该目录不随代码发布。浏览器视口模拟不等同于 Android/iOS 实体设备验收，也不代表已重新打包或安装 Tauri 应用；本规范不是完整的 WCAG 合规认证。
