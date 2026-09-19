# MX-A07C-1 — Flutter C08 Profile 性能切片

开始/阶段日期：2026-09-13。状态：**C08_PARTIAL。iPhone 16 Pro Max / iOS 26.6.1 真机 Profile measured slice PASS；Pixel 10 Pro / Android 17 API 37 虚拟机流程 PASS、性能预算 FAIL。G08/G14/G15 均不关闭。**

## 基线与边界

- 分支 `feature/v0.3.1`；基线 HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；保留用户已有大规模 dirty workspace。
- 本卡只启动 A07C 的 C08 验证切片：新增集成测量入口、证据校验器和可选双数据集 fixture。没有修改生产 `lib/`、应用标识、版本、最低OS、签名、协议、安全规则或发布配置。
- 用户同意 Android 借助虚拟机。本卡使用该能力做跨端诊断，但依冻结规则不把 emulator 填入 C02/G01 的实体机格。
- 测量入口使用忽略文件中的专用账号和隔离 HTTP fixture，内部显式 `allowLocalHttp=true`；正式 `lib/main.dart` 与候选传输未变化。因此结果不是生产 HTTPS、正式 main、签名分发或发布就绪证明。

## 实现与测量口径

- `integration_test/c08_profile_performance_test.dart` 强制 Profile 模式，分别选择恰好220条和2050条真实Spring消息组，各连续滚动30秒；随后持续写入1000字符、开合键盘5轮、切换2倍字号/主题、检查发送控件可访问名称，并把原生发现与重连并发执行。
- 每个窗口记录实际刷新率、帧数、`buildDuration+rasterDuration` 工作耗时p95/max、`totalSpan`、>2刷新周期与>250ms工作帧、RSS min/max/delta。`Future.delayed + pump()`避免把测试时钟的等待值伪装成应用帧耗时。
- 额外记录登录到ONLINE、发现并行重连和5轮 `ChatController.pause/resume` 恢复耗时。后者是控制器恢复路径，不冒充真实OS Home/锁屏/前后台。
- `tool/check_c08_evidence.py`校验数据集、Profile/实体标志、窗口时长、帧预算、1000字、异常、发现重连和5轮控制器恢复；即使通过也固定输出 `PASS_MEASURED_SLICE` 与 `c08GateClosed=false`。3项Python负向/正向测试防止虚拟机冒充真机或超预算仍通过。
- `tool/seed_fixture.mjs`仅在传 `PROBE_SMALL_MESSAGE_COUNT` 时额外创建小数据组；默认既有行为仍为单组1200条。

## 证据与结果

证据在 [output/mx-a07c1-2026-09-13](../../../output/mx-a07c1-2026-09-13/)，完整报告见 [实机验证报告](../../reports/实机验证/MX-A07C-1_Flutter性能切片验收_2026-09-13.md)。源码探针 SHA-256 为 `b3387711d06a05778fd5d67ae1be0837e3d3d9ba681b554b2d950a7ca99d2335`，锁文件 SHA-256 保持 `85c4b5f05d12a2a35b801b823394354fcb619eb8820d793bb3853ac7e3ba41fe`。

| 环境/窗口 | 状态 | p95工作耗时 / 两帧预算 | max / >250ms | RSS delta |
|---|---|---:|---:|---:|
| iPhone真机 220条滚动30.4s | PASS | 1.721ms / 16.666ms | 19.612ms / 0 | +4.56MiB |
| iPhone真机 2050条滚动30.4s | PASS | 1.759ms / 16.666ms | 11.602ms / 0 | +4.38MiB |
| iPhone真机 1000字输入/键盘 | PASS | 2.321ms / 16.666ms | 5.516ms / 0 | +37.52MiB；仅记录趋势，不据单窗宣称长期稳定 |
| Android虚拟机 220条滚动 | FAIL | 118.966ms / 33.334ms | 605.065ms / 12 | -2.36MiB |
| Android虚拟机 2050条滚动 | FAIL | 122.637ms / 33.334ms | 410.879ms / 10 | -10.58MiB |
| Android虚拟机 1000字输入/键盘 | FAIL | 99.360ms / 33.334ms | 345.179ms / 2 | +3.28MiB |

- iPhone：登录到ONLINE 824ms；发现并行重连1217ms；5轮控制器恢复157/120/120/126/120ms；无Flutter异常、无>250ms工作帧。最终证据SHA-256为 `d801d6052ff4d4db4d9dc7ad793816812f848d4ec1710f427612ad19b2d494b3`。
- Android emulator：登录到ONLINE 5254ms；发现并行重连3362ms；5轮控制器恢复1081/1234/1244/1268/1232ms。流程完成但ONLINE略超5秒且全部工作窗口超预算，启动日志有大量 skipped frames/Davey；AVD为API37 arm64、60Hz、Lavapipe软件Vulkan，结果不外推真机但必须保留。最终证据SHA-256为 `e9eefc864fc7cdd01fe89a09e0092eb375b64402f54e9aecbe9a07abc77569c2`。
- 最终校验器对iPhone返回 `PASS_MEASURED_SLICE`，对Android返回FAIL（ONLINE 5254ms超过5秒，且三个窗口均有预算失败）；失败原因顺序由校验器检查顺序决定，不据单条掩盖其余失败。

## 统一回归

所有要求范围先执行 `--dry-run`，再独立运行；报告均在本卡output目录。`flutter`为6步PASS（新增4项校验器测试、边界、14向量、Token drift、analyze、117项测试）；`contracts` 4步PASS（含7项MVC/HTTP测试）、`server` 313项PASS、`web` 119项与生产构建PASS、`core` 31项PASS、`design` 12项及生成漂移PASS。`dart-core`、`tooling` 58项、`workspace`、`hygiene` 5步、Android debug APK、iOS Simulator debug app均PASS。

`./tooling/verify security`仍按原规则FAIL：扫描63 commits后命中同一既有历史finding；本卡未改规则。对本卡Flutter探针/工具/fixture、workspace配置及状态文档组成的精确变化集执行`gitleaks dir`为PASS、无finding。

## 失败历史

1. Android首轮把 `pump(250ms)`测试时钟计入 `FrameTiming.totalSpan`，且在2050条组上错误标记220场景，最后语义finder过严失败。该轮见 `android-emulator-first-fail/`，不用于预算结论；修正为独立数据组与真实等待后重跑。
2. 一次从仓库根执行 `flutter drive` 因无 `pubspec.yaml` 返回失败，随后从Flutter模块重跑；这是操作路径错误。
3. iPhone首轮签名构建完成但无线启动未发现VM Service，设备进程列表确认Runner未运行后中止；用公开 `devicectl` 成功启动确认设备可用，显式终止后重跑通过。失败未计为应用性能结果。
4. 首次单测从Flutter目录以模块名加载时找不到校验器；从`tool/`执行后3项通过，并已把统一workspace命令配置为discover模式。

## 仍未完成

1. C08/G08：批准候选的正式 `lib/main.dart`、生产HTTPS、5次真实冷启动、5次真实OS后台/前台/锁屏恢复、持续内存趋势和零崩溃/ANR仍 NOT_RUN；本卡不关闭A05 Debug ANR风险。
2. C02/G01/G14：Android实体机、iOS 13最低版本及代表中间OS仍 BLOCKED/NOT_RUN。Android模拟器只能帮助开发与定位。
3. G15：真实系统字号、VoiceOver/TalkBack、返回/安全区/权限拒绝恢复完整P0矩阵仍 NOT_RUN；本卡只有2倍测试字号和发送控件名称的局部证据。
4. Android虚拟机性能退化在硬件加速真机复现前不做针对性生产优化；若实体机仍FAIL，应开独立修复卡并按同一预算复验。

## 停止与清理

`meshx-c08-v2-*` 专用容器/18408 Spring与本轮Pixel虚拟机均已停止；证据保留，未触碰用户服务。iPhone测试进程在最终检查时已不运行，开发Profile包不称正式安装。没有commit、push、merge、远程CI、签名渠道或发布动作。
