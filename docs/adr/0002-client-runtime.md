# ADR 0002：当前保留共享 Vue 与原生壳

长期路线已由 [ADR 0004](0004-client-target-and-contracts.md) 更新；下文保留现有客户端与迁移前的决策依据。

状态：接受（适用于当前工作区重构）。日期：2026-09-08。

## 目标与比较

用户允许在原方案成熟、适配当前需求时保留，也允许选择 Flutter 等方案。决策以现有能力、原生边界和可验证收益为依据。

| 维度 | 现有 Vue + Capacitor / Tauri | Flutter 移动端 |
|---|---|---|
| UI 复用 | 当前 Web、桌面、Android、iOS 共用 Vue 代码 | Android/iOS 可共用 Dart UI；现有 Vue 页面须迁写 |
| 原生接口 | 已有 Java/Swift 插件和 Rust 适配 | 通过插件、Platform Channels 或 Pigeon 对接原生能力 |
| 消息可靠性 | 已有客户端重连/发件箱/序列逻辑和对应测试 | 需移植并重新验证等价性 |
| UI 控制 | 受 WebView 渲染与键盘/容器适配影响 | 提供自身 widget/渲染体系，更适合移动 UI 深度定制 |
| 后台通知/发现 | 要实现系统允许的原生机制并实机验证 | 同样需要平台机制；框架迁移不自动完成该能力 |
| HarmonyOS | 尚无正式客户端 | Flutter 官方支持平台表未列 HarmonyOS，不能视作开箱覆盖 |

以上关于框架的判断参考 [Capacitor 原生运行时与插件](https://capacitorjs.com/docs)、[Flutter 平台代码](https://docs.flutter.dev/platform-integration/platform-channels)、[Flutter 平台支持表](https://docs.flutter.dev/reference/supported-platforms)。Capacitor 的后台任务受到系统调度和执行时长约束，不能把 Background Runner 视作永久后台连接。[后台执行限制](https://capacitorjs.com/docs/apis/background-runner)

## 当前代码证据

- Android：`MeshXAuthClient`、`EncryptedOriginSessionStore`、`MeshXDiscoveryPlugin`、`MeshXFilesPlugin` 已实现原生认证、Keystore、NSD 和系统文件。
- iOS：`MeshXAuthClient`、`MeshXKeychainStore`、`MeshXDiscoveryPlugin`、`MeshXFilesPlugin` 已有对应实现；容器单独处理键盘和安全区。
- 共享 UI 已有可靠消息、广播回执、离线发件箱、文件传输和多端适配；现有工程不是只有网页加载器的空壳。
- 当前代码检索未发现完整 APNs/FCM 推送投递链路或 Android 前台服务长期收信实现；本地通知和前后台重连不能替代这类保障。

## 决策与边界

本轮保留正式客户端技术栈，重构源码边界与验证体系。理由是现有方案具备生产应用所需的原生扩展路径，尚无经过对照测量的证据表明重写 UI 能解决当前主要缺口。这个结论不等于 MeshX 已达到企业交付或可靠性标准，也不保证所有未来需求都适合 WebView。

当前不新增空 Flutter 工程、不删除现有可构建客户端；不把 Flutter 或 Harmony 支持标记为完成。后续非 TS 客户端共享 schema/设计定义，模型和业务实现需有跨语言一致性验证。

## 重新评估触发条件

- 大会话列表、复杂编辑/多媒体、手势或键盘体验在目标真机上持续不达标，且已测得瓶颈位于 WebView/UI 层。
- 移动端成为独立主产品，需要大量移动专属界面和交互，继续共享 Vue 的收益明显下降。
- 目标系统支持、关键插件维护、可访问性或组织部署要求出现现有架构无法合理满足的缺口。

触发后对同一业务切片做 Flutter 对照实现，比较冷启动、列表流畅度、内存、离线同步、原生接口和可维护性；通过验收再制定迁移与旧数据兼容计划。不要仅按框架名称决定“企业级”。

2026-09-08：用户已授权并实施独立 Flutter 对照原型，范围、证据边界与后续决策门槛见 [ADR 0003](0003-flutter-prototype.md)。正式运行时暂未替换。
