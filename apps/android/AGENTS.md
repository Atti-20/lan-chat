# Android 原生壳

这是保留的旧 Capacitor + Vue 兼容工程，主机源码位于 `app/src/main/java/com/meshx/android`，UI 来自 `../web`。移动端目标 Flutter；此目录的现状和验证不代表继续采用 Capacitor。

- `MeshXAuthClient` / `EncryptedOriginSessionStore`：认证与 Keystore 会话；`MeshXDiscoveryPlugin`：NSD；`MeshXFilesPlugin`：系统文件保存。
- UI 不在此处复制。接口变更同时核对根 `packages/platform-ports`、`../web/src/platform` 与 iOS 对应实现。
- `app/build.gradle.kts` 的 `syncVueUi` 必须追踪 Web 源码和共享 packages；`scripts/sync-web.mjs` 把 `dist-mobile` 同步到 assets。assets 是生成物。
- Secure 变体默认 HTTPS/WSS；LAN HTTP 仅调试变体。保持 applicationId、权限和密钥语义。
- 工程构建使用本目录的 `gradlew`，需要 Java 21、Android SDK（当前 compile/target 37）和已安装的 npm 依赖。
- 根执行 `./tooling/verify android`（测试、lint、Secure Debug APK）；设备多播、后台、通知、弱网和签名发布另行验证。
- 目标路线见 [ADR 0004](../../docs/adr/0004-client-target-and-contracts.md)。不在治理任务中自动改写或删除本工程；Flutter 后续功能需独立任务和真实验收。
