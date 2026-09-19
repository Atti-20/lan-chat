# MX-A04 — Design System Foundation

开始/完成日期：2026-09-09。状态：**交付完成；限定设计数据、组件规范、构建、DOM/视觉回归 PASS；安全仍 FAIL — known false positive**。已停止，未启动 MX-A05。

## 结果与入口

- 权威值来源：[tokens.json](../../../packages/design-tokens/tokens.json)，schemaVersion=2，121 Primitive / 92 Semantic / 3 Component；保留 87 个旧 aliases，原有非材质类型值在两主题完全一致。
- [source/schema/生成说明](../../../packages/design-tokens/README.md)；生成 [CSS](../../../packages/design-tokens/tokens.css) / [Dart](../../../apps/flutter-prototype/lib/ui/tokens.g.dart)。使用现有 Python 标准库，无新增框架、包管理器或依赖。
- 实际生产代表组件：UserAvatar、ConversationSidebar、MessageThread；公共 Button/Input 类也使用语义变量。props/emits、聊天业务与契约保持。
- [六类组件规范](../../design/component-specs/README.md) 与 [机器状态/图标目录](../../../packages/design-tokens/components.json)，区分 implemented/composed/not-implemented/not-applicable。
- [平台、主题、字体与图标](../../design/platform-and-theme.md)：Light/Dark 完整引用，保留启动系统 fallback/手动持久化；语义一致允许平台交互差异。无 Flutter 产品页面/私有字体/图标库替换。
- [设计债](../../design/debt/README.md)：未迁移 alias/局部硬编码、小字/触摸目标、消息选中/文本选择、状态栏未接生产、附件/原生窗口/辅助技术等。
- [完整报告与所有验证命令](../../reports/界面设计/MX-A04设计基础与视觉回归报告_2026-09-09.md)；本地 [命令清单](../../../output/mx-a04-2026-09-09/verification-runs.json)、[增量 patch](../../../output/mx-a04-2026-09-09/a04.patch)、[保护检查](../../../output/mx-a04-2026-09-09/preservation.json)。

## 完成条件

| # | 条件 | 结果与证据 |
|---|---|---|
| 1 | 权威 source | PASS：tokens.json v2，关闭字段结构/类型/单位约束 |
| 2 | Light/Dark semantic | PASS：每项显式双主题引用，缺失/循环/类型/上层引用失败 |
| 3 / 4 | CSS / Dart 可重复生成 | PASS：同 source 与逆序 key 输出一致；受控重新生成无漂移 |
| 5 | 至少三个 Vue 组件消费 | PASS：UserAvatar / ConversationSidebar / MessageThread 的真实样式 |
| 6 | 核心组件状态规范 | PASS：六类均覆盖所需状态；MessageBubble 有 own/peer/waiting/sending/sent/failed/retrying/recalled/selected/附件边界 |
| 7 | Dart 消费 | PASS：全颜色/别名/非颜色类型与 source 对照，既有主题 TextScaler(2) 最小验证 |
| 8 | 机器 drift | PASS：真实 CLI 对 CSS/Dart 故意漂移非零退出，不自动修复 |
| 9 | 保持当前视觉 | PASS（限定范围）：16 张 Chromium Web/Tauri CSS 上下文 × 浅深/320/1440/1x/2x，精确 0 像素差；安装 Tauri/WebKit 仍 NOT_RUN |
| 10 | A02 | PASS：契约生成/向量/MVC 7/兼容负例 3；Java 全量 313 |
| 11 | A03 | PASS：AST/无 DOM 编译/Core+Ports 30，含样式及设计实现负例；浏览器存储 4 |
| 12 | 验证债 | PASS（记录完整，不代表债已关闭）：见下文 |
| 13 | 安全真实状态 | **FAIL — known false positive**：62 提交/1 个既有缓存键；配置与历史未改变 |

## 实施与验证

六个阶段均完成：当前 UI baseline → 分层生成 → 三个代表组件接入 → 状态/平台规范 → 受控验证 → 报告/handoff。局部可访问性修复只有头像 role=img/在线名称、会话 aria-current；主题 meta 读取生成 canvas，生产入口先加载 CSS。真实应用启动测试捕获并验证了加载顺序修正，不改变主题偏好状态机。

| 验证 | 最终状态 |
|---|---|
| design / tooling / workspace / hygiene | PASS；设计 12（包含在工具 55 内），双主题/生成漂移/指令与文档入口 |
| Web | PASS：110，类型检查及 Web 构建；desktop/mobile UI 构建均通过 |
| Dart / Flutter | PASS：analyze + 33（A03 30 + 本轮 3）；仅原型/最小消费测试 |
| Rust | PASS：52，系统凭据库另 1 ignored / NOT_RUN；复用 A03 Cargo target 缓存 |
| Java / A02 contracts / A03 core | PASS：313 / 四步契约范围 / 30（Core 也包含在 Web 110 中） |
| Browser | PASS：16 DOM、16 视觉（0 差异）、4 存储；E2E TypeScript 通过 |
| security | **FAIL — known false positive** |
| all / 远程 CI / 安装包 / 原生窗口 / 设备/LAN / 签名发布 | NOT_RUN，不从分范围 PASS 推导全门禁 |

依赖按现有锁在隔离副本恢复，npm/Pub/Maven 从空缓存开始，Java 全测使用空 Maven settings；SDK/Chromium 和 Rust 编译缓存复用。原工作区 Web 106 / desktop 15 / mobile 15 产物保持。副本不含 Git/真实账号/本地 secrets，不是远程 clone 或发布环境验证。

## A00–A03 未关闭边界

- 全历史安全误报未处置；当前未提交源码不在 Git 历史扫描范围内。
- A00 Android cmdline-tools/许可证、CocoaPods 完整环境的历史 BLOCKED 保留，本轮未重测/修复；旧壳原生构建、系统凭据库、设备/LAN/后台、签名发布、远程 CI 仍未完成。
- 真实数据库/迁移、外部服务和完整聊天 E2E、多窗口/不同账号并行、outbox 并发 claim 未解决。浏览器 fixture 和 IndexedDB 回归是限定证据。
- A02 开放 Map/复杂 WS 分支/超 JS 安全整数 long、Flutter 旧实时解析链与串行同步状态机差距继续保留。A03 release 原生回环 2 是历史通过证据，本轮未重跑，不将其升级成安装应用验收。
- 仍有大量未追踪源码/锁；受控当前工作树重建不证明远程 CI Java 17/Node 20、其他 OS/SDK 成功。

## Handoff / MX-A05

**A05 的范围受控技术输入已具备：契约/核心边界保持，双主题 typed tokens、六类状态规范和视觉基线可供 Flutter 消费。完整原生环境及发布前提尚未具备。** 下一次需用户明确启动 A05，再读取本卡/原任务定义/Flutter 指令，核定最小切片和平台证据；本轮没有创建 A05 卡或 Flutter 产品页面。

起点分支 feature/v0.3.1，HEAD 16efe67dcaae432afd62e4fa10e1d6abbf55c419；987 个既有文件/493 个既有缺失路径保留，8 个 lock、A00–A03 handoff、契约/Core runtime/原生配置/数据库/安全规则不变。回滚仅按本轮 patch/source-before 核对明确文件，保护后续用户编辑；不整树 reset/checkout/clean。没有 commit/push/merge/release。完成 MX-A04 后停止。
