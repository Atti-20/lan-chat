# Flutter Android/iOS 范围规则（候选模板）

只在移动工程真实建立时使用本文件。遵循根规则与对应任务卡。
Flutter/Dart/原生工具链以锁定配置为准，不自动追随最新版或抬高最低系统版本。
不采用 Capacitor，不嵌入 JS 引擎复用 TS core，不创建第二套后端。

Dart core 不 import Flutter/插件/平台实现；原生能力封装在 adapters。
通过同源 REST/WS 契约和测试向量与 TS 对齐，不手写第二份协议事实。
生成 SDK 与手写 mapping/usecase 分开；不用 UI 状态模型替代传输 DTO。
Android/iOS 共用 Flutter 产品层，保留平台导航、输入、权限、安全区差异。
敏感长期凭据使用安全存储；账号/服务器的本地数据与同步游标必须隔离。
成熟插件优先封装；原生扩展使用类型明确的 Pigeon/Channel，避免全局字符串桥接。
加入插件前检查维护、许可、最低系统、工具链、权限和 Google 服务依赖。
不承诺 iOS 纯 LAN 后台常驻；消息恢复靠可靠同步，不滥用音频/VoIP/定位。

新增功能必须接入真实测试服务器；mock 测试通过不代表跨端互通。
运行 Dart一致性、flutter analyze/test 和可用平台构建；iOS 构建需对应环境。
真机发现/通知/后台、模拟器/单测、签名发布分别记录，不能混称全部验证。
