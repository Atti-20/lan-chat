# MeshX Compose Multiplatform Spike

这是一个与正式客户端隔离的 Android/Desktop 技术验证。它不修改当前 `apps/desktop`、`frontend` 或正在迁移的 `apps/android`。

## 验证范围

- `shared/commonMain` 复用同一套 Compose 登录 UI、状态机和 Control 登录客户端。
- Android 使用系统 `NsdManager` 发现 `_meshx-control._tcp.local.`，并校验 MeshX Control V2 TXT 字段。
- Desktop 使用 JmDNS 完成同协议发现。
- Android 使用 Storage Access Framework 选择文件，再通过系统 `ACTION_VIEW` 预览。
- Desktop 使用 AWT 文件选择器和操作系统默认应用预览。
- Android 17 / API 37 使用 `ACCESS_LOCAL_NETWORK` 运行时权限；未授权时不启动 `NsdManager`，首次拒绝可再次请求，固定拒绝后跳转应用系统设置。
- Android 按 Control 隔离生成 Ed25519 设备身份，私钥种子由 Android Keystore AES-GCM 密钥加密保存，并公开与 V2.8 一致的 X.509 公钥、SHA-256 指纹和 `android-...` 设备标识。

## 明确边界

- 这是技术选型 Spike，不是正式客户端替换。
- 登录验证到 `/api/v1/auth/login` 以及 HttpOnly Cookie 接收为止；设备身份存储尚未接入 V2.8 注册、Control 证书验签、吊销同步和本地 SQLite Node Runtime，因此不能作为生产登录客户端发布。
- 当前 Android `compileSdk/targetSdk=37`，使用 Android SDK Platform 37 revision 2。权限链路已在 API 37.1 模拟器验证，真实 Wi-Fi 环境仍需真机验证。
- `usesCleartextTraffic=true` 仅为验证局域网 HTTP Control；正式版应改为按域名/环境收敛的 Network Security Config。
- Android Keystore 当前原生密钥算法不提供 Ed25519，因此这里保护的是“加密包裹后的 Ed25519 种子”；签名时种子会短暂进入应用进程。若生产安全门槛要求签名私钥始终不可导出，需要让 Control 协议支持 Android Keystore 原生 P-256，不能把本实现描述为硬件不可导出的 Ed25519。

## 构建

复用仓库现有 Gradle 9.5 wrapper，不复制新的 wrapper 二进制：

```bash
../../apps/android/gradlew -p . :shared:desktopTest :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug :desktopApp:compileKotlin
```

连接 API 37+ 模拟器或真机后验证 Android Keystore：

```bash
../../apps/android/gradlew -p . :androidApp:connectedDebugAndroidTest
```

若未通过环境变量配置 Android SDK，可复用仓库 `apps/android/local.properties` 中的 `sdk.dir`，或在命令前设置有效的 `ANDROID_HOME`。

运行桌面端：

```bash
../../apps/android/gradlew -p . :desktopApp:run
```

输出 APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

## 2026-08-12 验证结果

- 5 个公共测试分别在 Desktop JVM 与 Android Host 执行，另有 5 个 Android 局域网权限状态测试和 3 个真实 Android Keystore 仪器测试，共 18 次、0 失败。
- Android Debug APK 构建成功（加入 Ed25519 实现后约 16 MB），Android Lint 0 Error、0 Warning。
- API 37.1 Pixel 模拟器冷启动成功，无 `AndroidRuntime` 崩溃；共享窄屏 UI 可滚动。
- Android 文件入口已真实启动系统 DocumentsUI；选择 PNG 后由 Google Photos 打开，不依赖 WebView 预览。
- API 37.1 模拟器已验证局域网权限首屏说明、系统授权弹窗、首次拒绝后再次请求、授权后自动启动 mDNS、固定拒绝后进入应用设置，以及从设置返回后自动刷新并恢复扫描。
- 修复了权限对象在 `Activity` 尚未附着 Context 时提前读取 `SharedPreferences` 的启动崩溃；平台服务现在统一在 `super.onCreate` 后初始化。
- API 37.1 模拟器已验证 Keystore 包裹密钥跨 Store 实例保持同一身份、Ed25519 真实签名验签、不同 Control 身份隔离，以及显式删除后的安全轮换（3/3、0 失败）。
- Desktop 实际窗口显示完整双栏 UI，JmDNS 扫描在后台运行且未阻塞界面。

尚未验证：真实同网 Control 的 mDNS 命中、Android 真机厂商文件提供方与 Wi-Fi 切换、Android 17 真机上的厂商权限/Keystore 硬件级别，以及 V2.8 注册、设备证书和吊销链路。
