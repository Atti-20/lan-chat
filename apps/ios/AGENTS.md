# iOS 原生壳

这是保留的旧 Capacitor + Vue 兼容工程；移动端目标 Flutter，见 [ADR 0004](../../docs/adr/0004-client-target-and-contracts.md)。Swift 位于 `ios/App/App/`；Xcode 工程 `ios/App/App.xcodeproj`。不在治理任务中自动改写或删除旧工程。

- `MeshXAuthClient / MeshXKeychainStore`：原生认证和 Keychain；`MeshXDiscoveryPlugin`：Bonjour；`MeshXFilesPlugin`：系统导出；`MeshXViewController`：安全区、键盘与 WebView 容器。
- UI 来自 `../web`；平台接口在根 `packages/platform-ports`，适配在 `../web/src/platform`。不要复制聊天规则。
- 保留 bundle identifier、Keychain 服务命名、局域网权限说明、Bonjour 服务与安全网络配置。
- Node >=22；根执行 `npm --prefix apps/ios run sync:ios` 会先构建共享 UI。仅改 Vue 后直接启动旧 Xcode 产物会看到旧页面。
- 根执行 `./tooling/verify ios`：同步 + 无签名模拟器构建，需要 macOS/Xcode。真机安装需要本机 provisioning；无签名构建不是 TestFlight/App Store 证据。
