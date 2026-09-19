# Tauri 桌面壳

共享 UI 在 `../web`，本目录只负责 Rust 原生能力与打包。入口 `src-tauri/src/lib.rs`，构建配置 `src-tauri/tauri.conf.json`。跨桥接修改从根 `python3 tooling/workspace.py context tauri` 进入，版本以现有 package/Cargo 配置为准。

- `native_auth.rs / native_transport.rs` 处理原生认证和网络；`discovery.rs` 处理发现；`node_runtime.rs / device_identity.rs` 处理本地运行时与设备身份。
- 托盘、通知、深链、窗口、自启、更新都通过现有平台接口提供。不要在 Rust 复制聊天 UI/业务权限。
- 同时核对根 `packages/platform-ports` 与 `../web/src/platform/nativeBridge.ts` 的命令和事件映射。
- 保留 CSP、受限 Origin、Cookie 隔离、应用 identifier 与签名配置。不要通过放宽网络策略修复路径问题。
- 根执行 `./tooling/verify desktop`（前端桌面构建 + Rust 测试）。应用包用 `npm --prefix apps/desktop run build:app`；跨平台签名构建参照对应 CI。
- 构建成功不等于已安装/已启动新包；实机验证需重启新 bundle，并检查真实 Tauri 运行时。
