# 按任务定位源码（自动生成）

来源：`tooling/workspace.json` 的 taskRoutes；更新：`python3 tooling/workspace.py generate`。
先读 [AI 入口](INDEX.md)，选择一条路线，不加载全部源码。模块地图见 [模块入口](../generated/repo-map.md)。

## chat：聊天发送、ACK、重连与同步

导航：`python3 tooling/workspace.py context chat`。
先读该命令列出的适用 AGENTS/override；下列测试按修改范围选择。

源码入口：

- [apps/web/src/composables/useWebSocket.ts](../../apps/web/src/composables/useWebSocket.ts)
- [apps/web/src/composables/useChat.ts](../../apps/web/src/composables/useChat.ts)
- [services/server/src/main/java/com/lanchat/websocket/ChatWebSocketHandler.java](../../services/server/src/main/java/com/lanchat/websocket/ChatWebSocketHandler.java)
- [packages/domain-ts/src/sequence.ts](../../packages/domain-ts/src/sequence.ts)
- [apps/web/src/composables/useOutbox.ts](../../apps/web/src/composables/useOutbox.ts)
- [packages/domain-ts/src/messages.ts](../../packages/domain-ts/src/messages.ts)
- [packages/platform-ports/src/outbox.ts](../../packages/platform-ports/src/outbox.ts)

对应测试：

- [apps/web/tests/sequence.test.mjs](../../apps/web/tests/sequence.test.mjs)
- [services/server/src/test/java/com/lanchat/service/ChatMessageServiceReliableTest.java](../../services/server/src/test/java/com/lanchat/service/ChatMessageServiceReliableTest.java)
- [tests/e2e/specs/messaging.spec.ts](../../tests/e2e/specs/messaging.spec.ts)
- [apps/web/tests/chat-delivery-core-contract.test.mjs](../../apps/web/tests/chat-delivery-core-contract.test.mjs)
- [apps/web/tests/outbox-contract.test.mjs](../../apps/web/tests/outbox-contract.test.mjs)

验证范围：`core`、`web`、`server`、`contracts`。

## tauri：桌面原生能力与平台桥接

导航：`python3 tooling/workspace.py context tauri`。
先读该命令列出的适用 AGENTS/override；下列测试按修改范围选择。

源码入口：

- [apps/web/src/platform/nativeBridge.ts](../../apps/web/src/platform/nativeBridge.ts)
- [apps/web/src/platform/nativeTransport.ts](../../apps/web/src/platform/nativeTransport.ts)
- [apps/desktop/src-tauri/src/lib.rs](../../apps/desktop/src-tauri/src/lib.rs)
- [packages/platform-ports/src/index.ts](../../packages/platform-ports/src/index.ts)
- [apps/web/src/platform/tauri/realtime.ts](../../apps/web/src/platform/tauri/realtime.ts)
- [packages/platform-ports/src/realtime.ts](../../packages/platform-ports/src/realtime.ts)

对应测试：

- [apps/web/tests/native-bridge-runtime.test.mjs](../../apps/web/tests/native-bridge-runtime.test.mjs)
- [apps/desktop/src-tauri/src/native_transport.rs](../../apps/desktop/src-tauri/src/native_transport.rs)
- [apps/web/tests/realtime-adapters.test.mjs](../../apps/web/tests/realtime-adapters.test.mjs)

验证范围：`web`、`desktop`。

## api：后端接口、DTO 与权限

导航：`python3 tooling/workspace.py context api`。
先读该命令列出的适用 AGENTS/override；下列测试按修改范围选择。

源码入口：

- [docs/generated/api-summary.md](../generated/api-summary.md)
- [services/server/src/main/java/com/lanchat/controller/AuthController.java](../../services/server/src/main/java/com/lanchat/controller/AuthController.java)
- [services/server/src/main/java/com/lanchat/security/SecurityConfig.java](../../services/server/src/main/java/com/lanchat/security/SecurityConfig.java)

对应测试：

- [services/server/src/test/java/com/lanchat/contract/RestContractTest.java](../../services/server/src/test/java/com/lanchat/contract/RestContractTest.java)
- [services/server/src/test/java/com/lanchat/controller/AuthControllerTest.java](../../services/server/src/test/java/com/lanchat/controller/AuthControllerTest.java)

验证范围：`server`、`contracts`、`web`。
