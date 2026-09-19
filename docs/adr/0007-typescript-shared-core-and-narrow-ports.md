# ADR 0007：TypeScript 纯聊天规则与小型平台接口

日期：2026-09-09。状态：接受（MX-A03 限定范围）。

## 背景

共享包已被 Web/Tauri 构建稳定消费，因而保留 `packages/domain-ts`、`packages/platform-ports` 和 `apps/web`，不另建 packages/ts、包管理系统或第二套 PlatformService。实际耦合是 useChat 的消息模型合并依赖当前 Vue 用户、useOutbox 同时执行队列规则与 IndexedDB、socket 接口和 Web/Tauri 实现混在 nativeTransport。

## 决定

- `domain-ts/messages.ts` 保存 normalize/merge/sort；当前 userId 由调用者显式传入。`outbox.ts` 保存恢复、upsert、patch、待发排序。`realtime.ts` 保存现有业务在线判定和 backoff 计算，随机输入由调用者提供；既有 conversation/sequence 保持。
- `OutboxPort` 只定义 load/save/delete，隔离真实 I/O。`RealtimePort` 只定义 open 和原有文本 socket 形状，确有 Web WebSocket / Tauri Channel 两种实现。两者都不负责认证或业务状态。
- `platform/web/outboxStore.ts` 委托原 localChatDb；Tauri WebView 继续使用同一个 IndexedDB adapter，不凭空声称已迁移 SQLite。`platform/web/realtime.ts`、`platform/tauri/realtime.ts` 保留原传输逻辑；`nativeTransport` 保留选择和旧导出 facade，HTTP 部分不动，nativeBridge 不新增竞争入口。
- Vue composable 仍维护 ref/computed、生命周期、入站队列、AUTH、SYNCING→ONLINE、ACK 等待、同步副作用和 UI 提示。纯函数不创建接口；没有需要时不添加 ServerConfig/Device/Session/MessageStore/Network/Clock 全套空 Ports。
- Core 只依赖自身和协议；Ports 可依赖 Core 模型与协议。应用编排同时消费 Core/Port，具体 adapter 实现 Port。避免为了 Core→Port 的字面箭头制造 domain ↔ ports 循环。
- `DirectFileRecord.blob` 是实际浏览器文件缓存形状，移到 `platform/web/fileStorageTypes.ts`，原 `apps/web/src/types.ts` 继续转导出；不改变存储结构或文件业务。

## 可执行边界

`npm --prefix apps/web run check:core` 使用现有锁定 TypeScript 编译器：AST 检查静态/dynamic import、require、export-from、import-type、不可解析的动态载入和浏览器全局访问；仅允许规定方向的相对源模块。随后以 ES2022、不加载 DOM 或 Node ambient 类型编译全部 domain 及其传递依赖、新小型 Ports。负例用真实子进程检查 exit 1；普通注释不被当成 import。

`./tooling/verify core` 提供同一入口，Web typecheck/build 与 CI 现有 typecheck 步骤也会运行。Python workspace 检查仍作为无需 Node 依赖安装的轻量第一层。

明确边界：生成 REST multipart 类型已有 Blob，旧 NativeBridge 的文件保存选项已有 AbortSignal；保持 A02 契约和原文件能力 ABI，不把这些表面纳入纯 domain 的依赖图。若以后 Core 导入它们，无 DOM 编译将失败。架构检查防常规依赖回流，不是恶意代码沙箱。

## 兼容与后果

先对旧实现运行消息映射、合并/排序和 outbox 恢复用例，再将同样输入/断言迁到 Core。A02 JSON 向量继续驱动真实消息处理器和 websocket composable。新测试覆盖重复 ACK/投递、gap、恢复、重试、两种 socket adapter 和原账号/节点清理顺序。

发现既有异步刷新竞态：TOKEN_EXPIRED 刷新未完成时 disconnect，成功结果仍会 connect；在刷新完成前手动重连，新连接 AUTH_OK 又被旧刷新阻塞。使用已有 socketEpoch 隔离刷新结果/单次刷新标记，仅将刷新生命周期 I/O 移出入站等待链；普通业务/存储处理仍保持串行，旧 socket 的排队事件仍被丢弃。没有改变 REST/WS 契约或令牌策略。其他协议、数据库名/版本、缓存 owner 格式和本轮业务默认值保持。

本轮仍为单 owner 数据库，切换时清空；不证明多 Tab/多窗口同时登录不同账号的隔离，也没有引入分布式 outbox claim。Tauri adapter 模拟边界、Rust 单测、桌面构建与实际安装/系统集成/发布网络必须分别验收。最终证据见 [MX-A03 handoff](../tasks/active/MX-A03.md)。
