# Flutter 对照原型

这是 Flutter 移动目标路线的已有切片验证，独立于 Web/Tauri Vue 及保留的旧壳。不要把原型功能或模拟器结果标记为生产迁移完成；仅在用户授权的移动阶段扩展功能，当前任务状态见 `docs/tasks/active/MX-A06.md`。

- 范围：真实节点登录、会话与文本消息、长列表/输入框、Android NSD / iOS Bonjour 原生发现。应用标识独立，禁止覆盖正式应用数据。
- `lib/data` 对接当前 Spring Controller / WebSocket；`lib/platform` 定义原生调用；`lib/ui` 负责页面。不得依赖或执行 TypeScript 源码。
- 设计值从 `packages/design-tokens/tokens.json` 生成；执行 `python3 apps/flutter-prototype/tool/generate_tokens.py`，不手改生成文件。
- MX-A05 的长期凭据只经 `CredentialStore` → Keychain/Keystore；平台不可用必须报错，不得明文降级。普通消息/outbox/游标按服务器和账号分区，原子保存。测试配置、日志与截图放根 `output/mx-a05-2026-09-09/`；不把用户令牌/密码写入源码或文档。
- 使用 `flutter analyze`、`flutter test`；真实后端与原生行为另跑 integration_test。测试/调试模拟器的帧数据不能证明真机发布性能。
- 后端与协议以 `services/server` 和 `contracts` 为准；同一 clientMsgId 重试，收到服务端 ACK 才显示已发送。未实现的能力明确留边界，不静默假成功。
- 原生调试允许本地 HTTP，release 仍要求 HTTPS。原型尚不用于正式分发；不配置真实签名或推送。

- MX-A06 的纯 Dart 系统能力 Port 在 `lib/core/platform_ports.dart`，应用策略在 `lib/application/platform_coordinator.dart`，原生实现只在 platform/Kotlin/Swift；结果状态不可用 false/null/空列表吞掉。证据放 `output/mx-a06-2026-09-09/`。
