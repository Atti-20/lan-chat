# ADR 0008 — Flutter 移动文字聊天的最小可靠性边界

日期：2026-09-09。状态：接受本阶段实施选择；平台验收以 [MX-A05](../tasks/active/MX-A05.md) 为准，不代表正式发布。

## 决定与依据

复用 `apps/flutter-prototype`，保留 Android `com.meshx.meshx_flutter_probe` / iOS `com.meshx.meshxFlutterProbe`，不初始化覆盖、不创建竞争 apps/mobile。Flutter 负责 UI/导航/应用编排，沿用一个 ChangeNotifier；`lib/core` 保持纯 Dart，旧 data/models 为兼容导出。Core 只共享契约、JSON 向量和设计数据，不执行 TS，不依赖 Flutter/Theme/插件。暂不引入 Dart workspace 或空包。

沿用 CI 已固定 Flutter 3.44.8（058e0af2c2）/Dart 3.12.2、pubspec.lock。Android 现有 minSdk 26、compile/target 37、AGP 9.0.1、Gradle 9.1.0、Kotlin plugin 2.3.20；直接 Gradle launcher 为 JDK 21.0.12；实际 Flutter build 使用 Android Studio JBR 21.0.10，Java/Kotlin 字节码目标 17。iOS 保留项目的 13.0 deployment target；已安装 Flutter 同版 iOS 模板也声明 13.0，本轮 Xcode 26.6 / iOS 26.5 模拟器编译验证，不宣称在 iOS 13 真机验证。提案中的 API 24/iOS 15 是候选，不覆盖本地已存在的配置。没有新增第三方依赖、批量升级或提高最低版本。

凭据经窄 CredentialStore 封装，Android 使用系统 Keystore 中不可导出的 AES-GCM 密钥加密，密文在 noBackupFilesDir；iOS 使用 Keychain `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`。参照 [Android Keystore](https://developer.android.com/privacy-and-security/keystore) / [KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec) / [Apple Keychain accessibility](https://developer.apple.com/documentation/security/ksecattraccessiblewhenunlockedthisdeviceonly)。安全存储失败不回退到明文。消息/outbox/连续接收位置写同一原子快照，按 origin + userId 隔离；不把令牌或刷新 Cookie 放进该快照。这个实现适合最小文字切片，未宣称数据库级多进程并发或无限历史容量。

AUTH_OK 只代表鉴权成功；真实逐会话 SYNC 分页完成且保存后才 ONLINE。普通入站只能推进连续位置；权威有序 SYNC 可跨服务端物理删除造成的间隙。先持久化 outbox 再发送，重试保留 clientMsgId/内容；新连接及新账号隔离旧异步结果。协议、事务、权限与 ACK 含义保持原实现。

## A02 消费清单兼容审阅

为了登录/恢复后核对当前用户，将实际 Java 已有的 `GET /api/v1/user/info`（operationId `getCurrentUserInfo`）加入 Dart 生成选择。门禁原始结果为 `generation/dartOperations: changed`，不是 REST/WS 协议改变。

审阅确认：仅选择数组末尾新增该 operationId；原有 9 项顺序与全部既有 REST paths/components、WS schema、共享向量哈希相同。生成增加已有接口的 Dart 操作和响应模型；TS/Java 不变。当前 Java 全量 313 通过，Node 契约/兼容突变和 Flutter 真实调用另见本轮证据。

因此按既有 `--accept-reviewed-change` 流程仅接受这个受控消费清单增量，审阅 JSON 与基线前副本位于 `output/mx-a05-2026-09-09/`。无协议迁移窗口需求：新 Dart 消费的是已经部署支持的同一接口。兼容检查代码、负例和安全扫描规则保持。

## 运行与停止边界

使用独立测试容器/账号/短期令牌；真实 TCP 故障代理只控制测试移动链路。Web 测试操作真正 Vue 页面，协调器不生成业务回复。`flutter drive --keep-app-running` 后显式 force-stop/terminate，再运行恢复阶段；默认 drive 会卸载应用，不能拿它验证进程重启。

不扩展完整发现、附件、推送、后台保活、HarmonyOS、发布签名或商店资源。系统截图/调试 JIT、模拟器功能、真实硬件、LAN 和 release 性能分别报告。A05 结束不自动启动 A06。
