# 来源与检查范围

源码通过已连接 GitHub 读取；以下文件链接固定在审查提交。分支引用 R01 会变化，本文记录的检查结果为 `d7571ded11fa4d6e66fd0c05e37e0541c2004b23`。

本包中的架构、目录、阶段、阈值及验收规则是建议设计，不是引用来源已经替 MeshX 完成的功能。

## 仓库源码

### R01 — 远程默认分支检查时的提交

`https://api.github.com/repos/Atti-20/lan-chat/git/refs/heads/master`

### R02 — 后端 Maven 配置

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/pom.xml`

### R03 — Vue/npm 配置

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/frontend/package.json`

### R04 — 桌面 npm 配置

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/apps/desktop/package.json`

### R05 — Tauri 构建路径与应用身份

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/apps/desktop/src-tauri/tauri.conf.json`

### R06 — Vite Web/桌面构建及开发代理

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/frontend/vite.config.ts`

### R07 — 既有 NativeBridge

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/frontend/src/platform/nativeBridge.ts`

### R08 — API 包装前180行，本次仅读取该段

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/frontend/src/services/api.ts`

### R09 — WebSocket实现前145行，本次仅读取该段

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/frontend/src/composables/useWebSocket.ts`

### R10 — 本地数据库实现前100行，本次仅读取该段

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/frontend/src/services/localChatDb.ts`

### R11 — 主题CSS前125行，本次仅读取该段

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/frontend/src/assets/main.css`

### R12 — 现有安全CI

`https://github.com/Atti-20/lan-chat/blob/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/.github/workflows/repository-hygiene.yml`

### R13 — 固定提交根目录和apps目录，目录记录不等于完整逻辑审查

`https://github.com/Atti-20/lan-chat/tree/d7571ded11fa4d6e66fd0c05e37e0541c2004b23`

### R14 — composables目录与文件元数据

`https://github.com/Atti-20/lan-chat/tree/d7571ded11fa4d6e66fd0c05e37e0541c2004b23/frontend/src/composables`

## 官方资料（2026-09-08 查询）

O01：Codex AGENTS 指令发现、优先级与大小限制。官方入口可能重定向至 ChatGPT Learn。

`https://developers.openai.com/codex/guides/agents-md`

O02：Flutter 平台通信与 Pigeon。

`https://docs.flutter.dev/platform-integration/platform-channels`

O03：Tauri capabilities/permissions。

`https://v2.tauri.app/security/capabilities/`

`https://v2.tauri.app/security/permissions/`

O04：npm workspaces。文档版本不等于建议强制升级项目到该 npm 版本。

`https://docs.npmjs.com/cli/v11/using-npm/workspaces/`

O05：Flutter 支持平台。查询时页面针对 Flutter 3.47.2，列 Android API 24–37、iOS 15–26；这是框架支持，不是 MeshX 测试结论。

`https://docs.flutter.dev/reference/supported-platforms`

O06：OpenAPI Generator TypeScript Fetch。

`https://openapi-generator.tech/docs/generators/typescript-fetch/`

O07：OpenAPI Generator Dart Dio。

`https://openapi-generator.tech/docs/generators/dart-dio/`

O08：Apple 官方关于后台执行的说明。iOS 后台调度具有系统约束，不能由 Flutter 或 Swift 自行取消。

`https://developer.apple.com/videos/play/wwdc2025/227/`

`https://developer.apple.com/documentation/xcode/configuring-background-execution-modes`

选择具体 SDK/插件、签名与商店要求时应重新核对，不把 2026-09-08 的资料当成永久固定的最新状态。
