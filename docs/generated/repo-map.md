# 仓库地图（自动生成）

来源：`tooling/workspace.json` 与源文件；更新：`python3 tooling/workspace.py generate`。
文件数只帮助定位，不能作为功能完成证据。不要默认加载全部模块。

| 模块 | 职责 | 源文件数 | 指令 |
|---|---|---:|---|
| `services/server` | Spring Boot 控制面、业务 API、WebSocket 与 Relay | 334 | [AGENTS.md](../../services/server/AGENTS.md) |
| `apps/web` | Web / Tauri 共享 Vue 应用；迁移期间仍服务 Capacitor | 130 | [AGENTS.md](../../apps/web/AGENTS.md) |
| `apps/desktop` | Tauri 原生适配与桌面打包 | 17 | [AGENTS.md](../../apps/desktop/AGENTS.md) |
| `apps/android` | 迁移期间保留的 Capacitor Android 客户端 | 13 | [AGENTS.md](../../apps/android/AGENTS.md) |
| `apps/ios` | 迁移期间保留的 Capacitor iOS 客户端 | 11 | [AGENTS.md](../../apps/ios/AGENTS.md) |
| `apps/flutter-prototype` | 目标移动端路线的 Flutter 切片原型；完整迁移未完成 | 120 | [AGENTS.md](../../apps/flutter-prototype/AGENTS.md) |
| `packages/protocol` | REST TS / WS TS 和 Dart 的机器契约生成边界 | 5 | [AGENTS.md](../../packages/protocol/AGENTS.md) |
| `packages/domain-ts` | 平台无关的聊天模型、消息合并/排序、outbox 恢复、重连与序列规则 | 12 | [AGENTS.md](../../packages/domain-ts/AGENTS.md) |
| `packages/platform-ports` | 现有宿主接口与小型 Outbox/Realtime Ports；无平台实现 | 4 | [AGENTS.md](../../packages/platform-ports/AGENTS.md) |
| `packages/design-tokens` | 中立设计 token 和组件状态，生成 CSS / Dart | 3 | [AGENTS.md](../../packages/design-tokens/AGENTS.md) |
| `apps/website` | 独立静态产品官网，不进入聊天应用构建 | 1 | [AGENTS.md](../../apps/website/AGENTS.md) |
| `contracts` | REST 与 WS 结构源、兼容规则及测试帧 | 10 | [AGENTS.md](../../contracts/AGENTS.md) |
| `tooling` | 按需上下文、生成文档和统一验证工具 | 17 | [AGENTS.md](../../tooling/AGENTS.md) |

## server

- [pom.xml](../../services/server/pom.xml)
- [src/main/java/com/lanchat/LanChatServerApplication.java](../../services/server/src/main/java/com/lanchat/LanChatServerApplication.java)
- [src/main/java/com/lanchat/websocket/ChatWebSocketHandler.java](../../services/server/src/main/java/com/lanchat/websocket/ChatWebSocketHandler.java)

## web

- [src/main.ts](../../apps/web/src/main.ts)
- [src/App.vue](../../apps/web/src/App.vue)
- [src/services/api.ts](../../apps/web/src/services/api.ts)
- [src/composables/useWebSocket.ts](../../apps/web/src/composables/useWebSocket.ts)
- [DESIGN.md](../../apps/web/DESIGN.md)

## desktop

- [src-tauri/src/lib.rs](../../apps/desktop/src-tauri/src/lib.rs)
- [src-tauri/tauri.conf.json](../../apps/desktop/src-tauri/tauri.conf.json)

## android

- [app/src/main/java/com/meshx/android/MainActivity.java](../../apps/android/app/src/main/java/com/meshx/android/MainActivity.java)
- [app/build.gradle.kts](../../apps/android/app/build.gradle.kts)
- [scripts/sync-web.mjs](../../apps/android/scripts/sync-web.mjs)

## ios

- [ios/App/App/AppDelegate.swift](../../apps/ios/ios/App/App/AppDelegate.swift)
- [ios/App/App/MeshXViewController.swift](../../apps/ios/ios/App/App/MeshXViewController.swift)
- [capacitor.config.json](../../apps/ios/capacitor.config.json)

## flutter

- [README.md](../../apps/flutter-prototype/README.md)
- [lib/main.dart](../../apps/flutter-prototype/lib/main.dart)
- [lib/chat_controller.dart](../../apps/flutter-prototype/lib/chat_controller.dart)
- [lib/application/attachment_controller.dart](../../apps/flutter-prototype/lib/application/attachment_controller.dart)
- [lib/data/attachments_models.dart](../../apps/flutter-prototype/lib/data/attachments_models.dart)
- [lib/ui/attachments/attachment_message.dart](../../apps/flutter-prototype/lib/ui/attachments/attachment_message.dart)
- [lib/platform/discovery.dart](../../apps/flutter-prototype/lib/platform/discovery.dart)

## protocol

- [src/index.ts](../../packages/protocol/src/index.ts)
- [src/envelope.ts](../../packages/protocol/src/envelope.ts)
- [src/events.ts](../../packages/protocol/src/events.ts)
- [src/rest.ts](../../packages/protocol/src/rest.ts)

## domain

- [src/models.ts](../../packages/domain-ts/src/models.ts)
- [src/conversation.ts](../../packages/domain-ts/src/conversation.ts)
- [src/sequence.ts](../../packages/domain-ts/src/sequence.ts)
- [src/messages.ts](../../packages/domain-ts/src/messages.ts)
- [src/outbox.ts](../../packages/domain-ts/src/outbox.ts)
- [src/realtime.ts](../../packages/domain-ts/src/realtime.ts)

## ports

- [src/index.ts](../../packages/platform-ports/src/index.ts)
- [src/runtime.ts](../../packages/platform-ports/src/runtime.ts)
- [src/outbox.ts](../../packages/platform-ports/src/outbox.ts)
- [src/realtime.ts](../../packages/platform-ports/src/realtime.ts)

## design

- [README.md](../../packages/design-tokens/README.md)
- [tokens.json](../../packages/design-tokens/tokens.json)
- [components.json](../../packages/design-tokens/components.json)
- [tokens.css](../../packages/design-tokens/tokens.css)

## website

- [index.html](../../apps/website/index.html)

## contracts

- [README.md](../../contracts/README.md)
- [rest/README.md](../../contracts/rest/README.md)
- [websocket/README.md](../../contracts/websocket/README.md)

## workspace

- [workspace.py](../../tooling/workspace.py)
- [workspace.json](../../tooling/workspace.json)
- [api_summary.py](../../tooling/api_summary.py)
- [verification.py](../../tooling/verification.py)
