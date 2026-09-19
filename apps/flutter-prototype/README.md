# MeshX Flutter 对照原型

独立 Android / iOS 应用，Android 标识 `com.meshx.meshx_flutter_probe`，iOS 标识 `com.meshx.meshxFlutterProbe`，显示名“MeshX 体验版”。用于验证已选定的 Flutter 移动路线；旧客户端尚未移除，原型不等于正式迁移完成。

已实现真实节点握手与登录、私聊/群聊列表、文本历史分页、实时收发、真实 AUTH/分页 SYNC、服务端 ACK 后确认、持久 outbox 同一 clientMsgId 重试、进程重启恢复、明暗主题、多行输入框，以及 Kotlin NSD / Swift Bonjour 原生发现。数据来自同一 Spring Boot 服务，不使用静态演示聊天。

A07B-2 已接入好友列表、申请处理、用户搜索、备注、删除和私聊入口，并在好友关系失效后阻止旧私聊 outbox 自动重发。代码、本地回归、双平台模拟器构建及真实 Spring/WS 好友闭环已通过；真实设备与发布边界见 [任务卡](../../docs/tasks/active/MX-A07B-2.md)，不能据此宣称移动 P0 已完成。

A07B-3 已接入个人资料、文字/图片头像、密码修改、主题偏好持久化及节点/通知/退出设置入口。图片读取只消费用户选择后的 opaque app-owned copy，最大 5 MiB，不暴露路径；代码、本地回归、双平台模拟器构建及真实 Spring 资料/头像/密码撤销闭环已通过，设备与发布结论以 [任务卡](../../docs/tasks/active/MX-A07B-3.md) 为准。

A07B-4 已接入群列表、从好友选择成员建群、群成员角色查看、进入空群会话和普通成员主动退群，并复用现有可靠文字 ACK 管线。远端移除/解散后的通用恢复仍受 B03 契约边界约束；本地回归、双平台模拟器构建和真实 Spring/WS 证据见 [任务卡](../../docs/tasks/active/MX-A07B-4.md)。

A07B-5 已接入私聊/群聊的节点中转文件与图片：opaque 私有副本按最多 1 MiB 分块读取，前台上传上限 25 MiB，上传完成后才进入原有 `clientMsgId`/ACK outbox；接收侧仅下载当前节点授权引用并校验大小与 SHA-256，图片可预览，验证后的文件可交给系统保存/分享。WebRTC 直传与跨进程续传不在本切片，真实设备文件提供方和系统面板证据仍由 A07C/C06 验收，详见 [任务卡](../../docs/tasks/active/MX-A07B-5.md)。

A07B-6 已接入广播待办/详情、查看回执、服务器允许的确认值、无定位任务完成和图片凭证上传，并渲染既有技术通知账号广播卡。所有卡片点击都会重新向当前节点鉴权读取；取消/过期/移除不能依赖旧卡提交。定位凭证任务保持只读并引导 Web，真实 Web/手机闭环仍由 A07C/C01/C06 验收，详见 [任务卡](../../docs/tasks/active/MX-A07B-6.md)。

A07B-7 已补齐普通会话/广播通知的账号与节点恢复路由：广播点击会重新检查目标资格，无效目标回安全列表，旧账号点击不能在新账号复现。iOS Settings 改用公开 `UIApplication.shared.open`，保留细分 reason，并提供人工导航与返回后复查；真机三轮回调/生命周期已通过，但设置页视觉到达和真实通知点击仍由 C04 验收，详见 [任务卡](../../docs/tasks/active/MX-A07B-7.md)。

A07B-8 已增加用户可见的最小支持信息：只读取应用/OS版本，组合当前节点、连接阶段和脱敏错误码，必须先预览再复制；不包含聊天内容、token、Cookie、凭据、文件路径、设备标识或管理员日志。局部小屏/大字/语义回归、双端模拟器构建及 iPhone Profile 原生版本读取已通过，完整真机UI、读屏和性能仍由 C02/C08 验收，详见 [任务卡](../../docs/tasks/active/MX-A07B-8.md)。

A07B-9 已准备候选构建但没有批准发布：`candidate-readiness.json` 固定正式main、SDK、版本/身份、lock hash、无fixture/debug注入和五个审批blocker；统一verify可生成并审计Android/iOS unsigned release-mode prototype工件。CI已声明quality/Android/iOS三个job，但未远程触发，当前身份/版本也不是正式候选，详见[准备清单](../../docs/product/flutter-candidate-readiness-v0.3.1.md)与[任务卡](../../docs/tasks/active/MX-A07B-9.md)。

## 快速运行

本轮环境：Flutter 3.44.8 / Dart 3.12.2，Android Studio JBR 21.0.10（字节码目标 17），Android SDK 37，Xcode 26.6 / iOS 26.5 模拟器。仓库已提供 SDK 约束与 pubspec.lock；首次执行 `flutter pub get`。

```sh
# 仓库根：普通检查不依赖后端
./tooling/verify flutter

# B11候选准备检查；只生成未签名prototype工件，不执行发布
./tooling/verify flutter-candidate
./tooling/verify flutter-android-candidate
./tooling/verify flutter-ios-candidate

# 进入原型目录，选择 flutter devices 返回的设备
cd apps/flutter-prototype
flutter run -d <device-id> --dart-define=MESHX_NODE=https://your-node.example
```

节点地址也可以在登录页输入或通过原生扫描选择。选择节点后仍需手动登录。地址校验只允许调试私有网络/回环 HTTP；Android 调试网络策略进一步限 localhost、127.0.0.1、10.0.2.2，iOS 调试使用 NSAllowsLocalNetworking。正式模式要求 HTTPS。Android 17 对手动局域网连接也先申请权限。权限拒绝时阻止网络请求并提示恢复方式；扫描不可用或找不到节点时保留手动连接。

## 隔离测试环境

以下命令在仓库根执行。需要 Docker、Node 22+、Python 3 和当前服务端 JAR；若 JAR 不存在，先构建 Web 与服务端。

```sh
npm --prefix apps/web run build
./mvnw -B -DskipTests package
python3 apps/flutter-prototype/tool/backend_fixture.py start
node apps/flutter-prototype/tool/seed_fixture.mjs
# 如需包含系统键盘的整屏截图，另一个终端运行：
python3 apps/flutter-prototype/tool/capture_host.py
```

专用后端端口 18381，数据库与 Redis 仅绑定回环端口 13316 / 16389。脚本核对 `flutter-probe` 节点身份，创建三个专用用户、1200 条群消息及24条私聊消息。随机测试凭据只写入根 `output/flutter-prototype-2026-09-08/integration-config.json` 和 Android 对应配置，不提交，不在应用源码内嵌入。重复 seed 会创建新一组数据。

从原型目录执行以下命令；Android 换用 `integration-android-config.json`（宿主机地址 `10.0.2.2`）。Android 37 应先通过系统对话框允许“局域网”权限；自动化可仅对该测试包授权。

```sh
flutter drive --driver=test_driver/integration_driver.dart \
  --target=integration_test/chat_flow_test.dart -d <device-id> \
  --dart-define-from-file=../../output/flutter-prototype-2026-09-08/integration-config.json \
  --dart-define=PROBE_NATIVE_SCREENSHOT=true
```

另外可用 `integration_test/manual_permission_test.dart` 作为 target 验证直接连接：在 Android 17 撤销该测试包的局域网权限后运行，依次在真实系统对话框选择拒绝、允许。使用 `PROBE_EVIDENCE_DIR` 指向单独目录，避免覆盖聊天闭环指标。

`PROBE_REQUIRE_DISCOVERY=true` 会额外要求真实发现测试节点。模拟器网络不保证能透传宿主局域网广播，不能把手动连接成功算作原生发现成功。默认截图目录为根 `output/flutter-prototype-2026-09-08/`。

结束后可在根执行 `python3 apps/flutter-prototype/tool/backend_fixture.py stop`，仅关闭有专用标识的后端与测试容器并删除其中测试数据。截图与日志保留。

## 模块入口与共享范围

| 入口 | 职责 |
|---|---|
| `lib/chat_controller.dart` | 会话状态、发送确认、重连和同步调度 |
| `lib/data/meshx_api.dart` / `attachments_models.dart` / `models.dart` | 当前 REST / WS、附件认证传输和严格模型；模型兼容导出 |
| `lib/core` / `lib/platform/storage.dart` | 纯 Dart 规则/存储接口；原子账号快照、Keychain/Keystore 通道 |
| `lib/platform/discovery.dart` | 平台通道接口，不依赖 Vue / Capacitor |
| `android/.../MainActivity.kt`、`NsdProbe.kt` | Android NSD 与权限 |
| `ios/Runner/AppDelegate.swift`、`FlutterMeshXDiscovery.swift` | iOS 引擎通道与 Bonjour |
| `lib/ui` | 页面、懒加载消息行、附件预览/保存操作、主题 |
| `tool/generate_tokens.py` | 从 `packages/design-tokens/tokens.json` 生成 CSS/Dart；`--check` 防止漂移 |
| `test` / `integration_test` | 认证/一致性回归、组件交互及真实后端闭环 |

Dart 已消费同源生成的 WS DTO；REST HTTP 层仍为手写适配，完整 Dart API SDK 尚未生成，也没有直接复用 TypeScript 核心。结构与行为权威见 [contracts](../../contracts/README.md)。

## 验收边界

原型未覆盖跨进程附件续传、WebRTC直传、视频预览/编辑、通话、已读/提及回执、完整撤回事件、推送、锁屏后台收信、完整无障碍和正式签名发布。凭据经系统安全存储，消息/outbox/游标按 origin + userId 持久化；草稿和已验证附件预览仅在内存，账号切换会释放 native 临时副本。存储失败真实报错，无明文凭据回退。普通消息和附件的发送确认不等于对方已读。

模拟器功能验证、调试帧统计和实体设备发布性能分开解释。结论见 [ADR 0003](../../docs/adr/0003-flutter-prototype.md)；本地报告由 [文档索引](../../docs/README.md) 引用。

## MX-A05 真实互通与恢复

操作步骤、准确环境、失败重试记录及截图见 [A05 报告](../../docs/reports/实机验证/MX-A05_Flutter移动文字聊天验收_2026-09-09.md) 和 [任务交接](../../docs/tasks/active/MX-A05.md)。新增 `dart-core`、`flutter-android`、`flutter-ios` 验证 scope。恢复测试必须使用 `flutter drive --keep-app-running`，然后显式终止进程再执行 resume；默认 drive 卸载行为不能验证持久恢复。

## MX-A06 系统能力

本轮边界和验收见 [ADR0009](../../docs/adr/0009-mobile-platform-capability-boundaries.md) 与 [A06任务卡](../../docs/tasks/active/MX-A06.md)。Discovery/Notification/FilePicker/Share/Lifecycle/NetworkChange/PermissionSettings 是纯 Dart Port；Kotlin/Swift 系统 API 由 `lib/platform` 适配，应用策略在 `lib/application/platform_coordinator.dart`。未新增插件、GMS、远程推送或最低系统版本要求。

登录页和会话菜单可进入“设备权限与文件”。权限按操作请求；A06 建立的限量缓存副本及系统分享能力现由 A07B-5 附件聊天复用，但系统接受/完成分享仍不等于远端收到。后台不承诺持续在线，回到前台先 AUTH/SYNC 补齐。扫描结果只作为候选，不绕过认证。

`flutter test test/platform_capabilities_test.dart` 覆盖能力状态、扫描归属、去重/移除、通知和文件账号隔离、恢复并发；`integration_test/a06_platform_test.dart` 使用真实原生适配器与独立 Spring fixture，真实锁屏/网络操作由操作者执行，禁止注入假的 lifecycle 代替。真机运行传 `PROBE_PHYSICAL=true`，人工矩阵传 `PROBE_INTERACTIVE=true`；测试账号通过忽略的 `--dart-define-from-file` 文件传入，不写源码。使用 `--keep-app-running` 避免 driver 自动卸载干扰权限恢复与后续验收。

真机后台会断开Debug VM服务；本轮使用独立Profile入口 `integration_test/a06_device_probe.dart` 持久记录真实OS事件和服务器同步指标，详情见 [A06报告](../../docs/reports/实机验证/MX-A06_Flutter系统能力与真机验收_2026-09-09.md)。此测试入口及输出目录独立ATS例外不用于正式分发。iPhone现场应用内设置跳转返回失败，页面显示失败后需手动进入系统设置；权限恢复本身已实测。

## MX-A07C-1 C08 Profile 性能切片

`integration_test/c08_profile_performance_test.dart` 使用独立的220条与>=2000条真实Spring消息组，分别测30秒列表滚动，并测1000字输入、键盘反复开合、2倍字号/主题、发送控件可访问名称、发现与重连并行及5轮控制器恢复。它记录显示刷新率、build+raster工作耗时、total span、>250ms工作帧和RSS趋势；`tool/check_c08_evidence.py`按测量前冻结的预算校验，且固定输出`c08GateClosed=false`。

该入口显式允许隔离fixture HTTP并通过忽略配置读取测试账号，因此不是正式`lib/main.dart`、生产HTTPS、真实冷启动或真实OS前后台证据。iPhone 16 Pro Max真机Profile分段预算PASS；Android 17 Pixel 10 Pro虚拟机流程完成但Lavapipe图形栈下性能FAIL。两者不能代替Android实体机、正式候选、读屏或旧OS矩阵，详见 [任务卡](../../docs/tasks/active/MX-A07C-1.md) 与 [实机报告](../../docs/reports/实机验证/MX-A07C-1_Flutter性能切片验收_2026-09-13.md)。

`integration_test/c08_process_probe.dart`再对iPhone Profile进程/真实OS生命周期留持久证据：首次fixture准备后，5次终止并重新启动进程都必须从原生安全存储与账号快照恢复2050条唯一消息；每轮同时记录Dart首帧、缓存可操作、ONLINE/ready、RSS与连接峰值。`tool/c08_process_control.py`另记录主机调用公开`devicectl launch`到设备报告ready的上界，并通过Runner→系统Settings→Runner执行5轮真实后台/offline/前台/ONLINE。`tool/check_c08_process_evidence.py`要求双时钟、5+5轮和单连接都在预算内，仍固定不关闭C08。结果见 [A07C-2任务卡](../../docs/tasks/active/MX-A07C-2.md) 与 [报告](../../docs/reports/实机验证/MX-A07C-2_Flutter冷进程与OS恢复验收_2026-09-13.md)。
