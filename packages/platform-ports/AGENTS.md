# 平台能力接口

- `src/index.ts` 定义 `NativeBridge`、发现结果、认证、通知、文件、更新等类型；`runtime.ts` 定义运行时和导航目标类型。
- `outbox.ts` / `realtime.ts` 是独立小型 I/O 接口，按子模块导入；不让 Core 经 NativeBridge 导入旧文件保存的 AbortSignal ABI。
- 只依赖共享模型/协议，不包含平台检测、插件注册、I/O 或 Vue 状态。
- 宿主实现仍在 `apps/web/src/platform/nativeBridge.ts`；文本 socket 在 platform/web 与 platform/tauri，nativeTransport 保留 facade；Outbox 复用 Web/WebView 的原 IndexedDB。
- 新增能力需说明 Web 的可用性/回退并核对各原生壳；不能仅改接口就标记平台功能完成。
- 根执行 `./tooling/verify core`、`workspace`、`web`，并运行受影响原生平台验证。
