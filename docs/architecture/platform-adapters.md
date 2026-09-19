# 客户端与平台适配

共享接口位于 `packages/platform-ports/src/index.ts`；运行时/导航数据类型位于 `runtime.ts`。两者都不注册插件、不访问宿主。

| 能力 | 接口/应用入口 | 原生实现 |
|---|---|---|
| 离线发件箱 | `OutboxPort` → `platform/web/outboxStore.ts` → 原 localChatDb | Web/Tauri WebView 共用既有 IndexedDB；不是原生 SQLite |
| 实时文本帧 | `RealtimePort`、nativeTransport 兼容入口 → `platform/web/realtime.ts` / `platform/tauri/realtime.ts` | 原 Rust open/send/close_node_socket 与 Channel |
| 运行时选择 | `apps/web/src/platform/runtimeKind.ts`、`nativeBridge.ts` | 宿主注入的能力信号 |
| 登录/刷新/退出 | `NativeBridge.nativeLogin/nativeRefresh/nativeLogout` | Rust native_auth；Android MeshXAuthClient；iOS MeshXAuthClient |
| 节点发现 | `discoveredNodes/discoverNodeOrigins` | Rust mDNS；Android NSD；iOS Bonjour；Web 使用服务端/手动节点 |
| 文件 | `saveFile`、应用上传服务 | Rust 文件保存；Android/iOS 系统文档接口 |
| 通知与导航 | `notify/listenForNavigation` | Tauri 通知/事件；Capacitor 本地通知 |
| 更新/窗口/自启 | 对应 NativeBridge 方法、桌面适配 | 桌面原生 API；其他运行时按现有语义回退 |

`apps/web/src/platform/nativeBridge.ts` 保留 `webBridge`、`capacitorBridge`、`tauriBridge` 宿主组合入口。MX-A03 将原 socket 两种实现与小型 Port 分开，nativeTransport 的旧导出和运行时选择继续有效；不重写命令名、事件名或认证。纯聊天规则与应用编排边界见 [ADR 0007](../adr/0007-typescript-shared-core-and-narrow-ports.md)。
`services/api.ts`、`localChatDb.ts`、`composables/useWebSocket.ts` 是应用服务，依赖节点和浏览器生命周期；它们尚不是可直接给 Flutter 使用的 SDK。

`useOutbox` 调用 domain-ts 纯队列规则并通过 OutboxPort 持久化，保留 Vue 状态与 durable 提示；`useChat` 保留消息处理与 ACK/补同步编排，调用共享模型映射/合并/排序。`./tooling/verify core` 校验 AST 依赖方向、Core 无 DOM 编译以及 Core/Port/Adapter 回归。

扩展能力时先定义调用者需要的接口和不可用语义，再实现平台适配及共享调用点。不要在 domain 内堆叠 `if Android/iOS`，也不要把接口声明当成实际能力。

Android 增量构建监视 `apps/web` 和 `packages`。iOS 的 `sync:ios`、Tauri 的 `beforeBuildCommand` 会重新构建对应模式。共享变化至少验证三种前端输出；涉及原生行为时额外验证真实壳。
