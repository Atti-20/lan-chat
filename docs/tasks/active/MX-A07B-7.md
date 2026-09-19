# MX-A07B-7 — Flutter 权限恢复与通知路由

开始/完成日期：2026-09-13。状态：**B08 本地实现与真机回调阶段完成；115 项 Flutter 回归、双平台模拟器构建、iPhone Profile 安装/启动及 Settings 公开 API 三轮回调与生命周期证据 PASS。** 手机设置页视觉到达、Android/iPhone真实横幅/通知中心与前台/后台/冷启动点击仍 NOT_RUN，G02/G05 保持未关闭。

## 基线与范围

- 分支 `feature/v0.3.1`；HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；保留用户已有大规模 dirty workspace。基于 A06、A07A、A07B-6 与 `IOS-SETTINGS-01` 当前事实继续，不改应用标识、最低系统版本、签名身份或远程 Push 范围。
- 只修改 Flutter 权限恢复、通知路由与 Android/iOS 原生载荷。没有引入 APNs/FCM、私有 Settings scheme、定位 Port、协议或服务端变更。

## 已实现

- `NotificationRoute` 增加可选、正整数广播 ID。技术通知账号新广播仍只显示 generic OS 文案；原生通知载荷只保存 owner、conversation 与可选 broadcastId，不包含广播标题、正文、token 或节点凭据。
- 通知点击先等待当前账号、当前节点恢复 ONLINE；普通消息进入存在的会话。广播点击重新调用当前节点详情 API，只有 ACTIVE、未过期、目标仍 ACTIVE 才进入详情；取消、过期、移除或 403 回安全广播列表。
- 账号退出/切换会先改变 native owner 并清除通知；旧 owner 的离线待处理点击不会在新账号上线后复现。广播校验中的账号切换也通过 generation/owner 双重检查丢弃迟到结果。
- iOS Settings 从真实设备上失败的 `UIWindowScene.open` 改为主线程 `UIApplication.shared.open(UIApplication.openSettingsURLString)`，先检查前台 presenter 与 `canOpenURL`，保留 `background`、`settingsUrlUnavailable`、`settingsOpenRejected`、`settingsOpenAccepted` reason。Android 保持公开 `ACTION_APPLICATION_DETAILS_SETTINGS`。
- Settings 打开改为单飞操作，页面显示原始状态对应的用户文案；拒绝或打开失败时提供 iOS/Android 人工导航路径和“重新检查”。返回前台仍通过原有生命周期恢复连接并静默查询通知权限，不触发重复授权提示。
- A06 专用真机探针补记 Settings `reason`；没有把回调成功当成设置页视觉到达。

## 验证

证据位于 [output/mx-a07b7-2026-09-13](../../../output/mx-a07b7-2026-09-13/)。

| 范围 / 命令 | 状态 | 结果 |
|---|---|---|
| `./tooling/verify flutter --report output/mx-a07b7-2026-09-13/flutter-final.json` | PASS / 0 | 文档/生成物写回后最终复验：纯 Dart 边界、14 向量、Token drift、analyze、115 项测试全部通过 |
| 新增路由/权限自动测试 | PASS | 广播载荷、ONLINE/owner等待、目标复查、安全列表、旧账号点击丢弃、Settings单飞/reason及人工fallback widget |
| `./tooling/verify flutter-android --report .../flutter-android.json` | PASS / 0 | Kotlin 广播通知 extra 编译并生成 debug APK；Android 实机点击 NOT_RUN |
| `./tooling/verify flutter-ios --report .../flutter-ios.json` | PASS / 0 | Swift Settings/广播通知载荷编译并生成 Simulator app |
| `flutter run --profile -d 00008140-000039280E08801C -t integration_test/a06_device_probe.dart ...` | PASS（限定） | iPhone 16 Pro Max / iOS 26.6.1 完成 Profile 签名安装启动；三轮公开 Settings API 均 `success/settingsOpenAccepted`，对应 foreground/background 各3次，见 `iphone-settings-callback-3.json` 与 `iphone-settings-summary.json` |
| 手机设置页视觉到达、真实横幅/通知中心及点击 | NOT_RUN | 工具不能截取手机屏幕且本轮无人工点击记录；不以回调/后台转换关闭 C04/G02/G05 |
| `./tooling/verify workspace` / `hygiene` | PASS / 0 | 正式 generate 后 workspace 1 步、hygiene 5 步通过 |
| `./tooling/verify security --report .../security.json` | FAIL / 1 | 全历史63 commits仍为同一既有finding；规则未修改或放宽 |
| `gitleaks dir` 定向扫描 Flutter lib、路由/权限测试、A06探针与本任务卡 | PASS / 0 | 五个变化范围均无 finding |

真机探针使用专用测试入口和不可达本机节点，因此整体 `stage=failed` 是预期的聊天 fixture 缺失，不是 Settings 调用失败；本卡只采信独立 `commands` 回调与生命周期计数。三轮间通过公开设备启动命令把应用带回前台，再发送专用命令；没有修改正式 main 或写入凭据。第一次 workspace 验证因新增测试尚未进入生成的 repo map 而 FAIL；执行正式 `python3 tooling/workspace.py generate` 后重跑 PASS，没有手改生成文件或隐藏首次失败。

## 未完成与下一步

1. C04 仍需人工/设备自动化证明实际到达 MeshX 应用设置页，并在通知拒绝与 LAN 拒绝场景各执行恢复、返回和复查；当前只能把 IOS-SETTINGS-01 记为代码修复候选与 fallback 已实现。
2. Android/iPhone真实横幅、通知中心、普通会话/广播的前台、后台、冷启动点击以及目标失效后的最终页面仍 NOT_RUN；本地通知不承诺应用被系统终止期间收到远端消息。
3. B03 通用消息变更恢复仍由 A07B-1R `READY_FOR_APPROVAL` 候选控制；B10/B11 与 A07C/A07D 保持当前状态。

## 回滚与停止边界

本卡只改 Flutter 通知 route/coordinator/UI、Kotlin/Swift通知/Settings桥、A06探针字段、测试与状态文档。回滚需逐文件核对后续编辑，不回滚整个 dirty tree。没有 commit、push、merge、release或上传分发。
