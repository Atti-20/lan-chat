# MX-A07B-2 — Flutter 好友、申请与搜索

开始日期：2026-09-12，完成日期：2026-09-13。状态：**B04 本阶段交付完成；实现、本地回归、Android/iOS 模拟器构建及真实 Spring/WS 好友闭环 PASS。** Android/iPhone 真实 UI 与发布仍 NOT_RUN/BLOCKED，安全门禁保留既有 FAIL；未批准或启动 A07B-1I、B03 mutation recovery、A07C、发布或安全规则处置。

## 基线与范围

- 分支 `feature/v0.3.1`；HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；起点 `git status --short` 仍是大规模 dirty workspace。当前汇总为 94 个修改、493 个既有删除、40 个折叠未追踪入口；全部作为用户已有成果保留。
- 已完整核对 `docs/proposals/meshx-architecture-kit`、`docs/tasks` 的阶段记录，并以 A07A Scope/Matrix/Gates/Work Plan 和 A07B-1R 的停止边界为准。A05/A06 已交付能力不重做；A07B-1R 仍是 `READY_FOR_APPROVAL`，本卡没有把候选契约当成已批准实现。
- 本切片只消费既有 `/api/v1/friend/*` 与 `/api/v1/user/search`。未改 REST/WS schema、Java 服务端、数据库、依赖锁、应用标识、最低系统版本、签名或发布配置。

## 已实现

- 新增严格 Dart 模型：好友、待处理申请、用户搜索结果；缺失/非法 ID 和未知申请状态 fail closed，不把开放 Map 静默解析成有效业务对象。
- `MeshXApi` 接入好友列表、待处理申请、发送/处理申请、搜索、备注、删除和申请人资料补名；保留既有 HTTP 状态、Result.code、401 单次刷新与同 origin 认证边界。
- 新增独立 `FriendsController`，处理加载、空、成功、失败、搜索排除当前账号及 `FRIEND_CHANGED` 后刷新。接受申请、修改备注、删除好友后同步会话/关系状态。
- 新增“好友 / 申请 / 搜索”Flutter 页面及聊天菜单入口：可发验证消息、同意/拒绝、修改备注、删除、从好友进入私聊；成功与失败均显示，不用 seed 才能出现入口。
- 删除好友或恢复时发现已失去好友关系，会把对应私聊的 queued/sending 消息转为 failed，并阻止继续发送/重试。历史消息仍可读，不把“删除好友”误作历史访问撤销；该边界不替代仍待批准的 B03 mutation/access-revocation 契约。
- 新增独立真实 Spring 探针 `tool/a07_friends_live_test.dart`，目标覆盖注册两名合成账号、搜索、重复申请、拒绝后重申、接受、备注、CHAT_ACK、删除后服务端拒绝发送。探针未因环境阻塞而伪造成功。

## 验证

所有统一 scope 均先运行 `--dry-run`。本轮证据 JSON 位于 [output/mx-a07b2-2026-09-12](../../../output/mx-a07b2-2026-09-12/)。

| 范围 / 命令 | 状态 | 结果 |
|---|---|---|
| `./tooling/verify flutter --report output/mx-a07b2-2026-09-12/flutter-final-6.json` | PASS / 0 | 纯 Dart 边界、14 向量、Token drift、analyze、76 项 Flutter 测试；含 6 个好友数据/状态/重复提交/权限回归和 2 个 widget 测试（含 320×568、深色、大字体和长文本） |
| `./tooling/verify flutter-android --report output/mx-a07b2-2026-09-12/flutter-android.json` | PASS / 0 | 生成 `build/app/outputs/flutter-apk/app-debug.apk`；仅 debug 构建，不代表 Android 真机、签名或发布通过 |
| `./tooling/verify flutter-ios --report output/mx-a07b2-2026-09-12/flutter-ios.json` | PASS / 0 | 生成 `build/ios/iphonesimulator/Runner.app`；仅 iOS Simulator 构建，不代表 iPhone 真机、签名或发布通过 |
| `./tooling/verify dart-core --report output/mx-a07b2-2026-09-12/dart-core.json` | PASS / 0 | 3 个 Core 文件无 Flutter/platform 依赖；14 项向量 |
| `./tooling/verify contracts --report output/mx-a07b2-2026-09-12/contracts.json` | PASS / 0 | 源/生成无漂移，Node 9，Java MVC/Handler/HTTP 7，兼容回归 3 |
| `./tooling/verify workspace --report output/mx-a07b2-2026-09-12/workspace-final-6.json` | PASS / 0 | 首次因新增文件使 repo-map 过期而 FAIL；运行正式 generate 后多次复验 PASS |
| `./tooling/verify hygiene --report output/mx-a07b2-2026-09-12/hygiene.json` | PASS / 0 | tracked/native icon/version/工作树与 index whitespace 共 5 步 |
| `gitleaks dir apps/flutter-prototype ...` | PASS / 0 | 整个 Flutter 模块扫描无 finding；报告 `flutter-gitleaks.json` |
| `./tooling/verify security --report output/mx-a07b2-2026-09-12/security.json` | FAIL / 1 | 全历史 63 commits 仍为 1 条既有 finding；本轮未处置或放宽规则 |
| 定向 `flutter test --no-pub test/friends_feature_test.dart` | PASS / 0 | 冻结 v1 method/path/body/query、严格 DTO、控制器失败、删除关系 outbox、首次恢复旧关系阻断 |
| 定向 `flutter test --no-pub test/friends_widget_test.dart` | PASS / 0 | 好友、申请处理、搜索与申请发送入口；小屏深色大字体长文本无异常 |
| `flutter test --no-pub tool/a07_friends_live_test.dart --dart-define=MESHX_NODE=http://127.0.0.1:18421` | PASS / 0 | 专用 Spring/MySQL/Redis fixture 中完成双合成账号搜索、重复申请拒绝、拒绝后重申、接受、双方列表、备注、CHAT_ACK、删除及删除后 WS ERROR；证据 `friends-live-2.json` |

第一次对 `lib test` 的宽范围格式化触及 6 个非目标文件；已从 A06 保存快照逐文件恢复并以 `cmp` 确认完全一致。没有用 reset/checkout/clean，未覆盖其他已有改动。

完整 Flutter 首次收口复跑 `flutter-final-3.json` 时，既有 mutation characterization 用例出现一次时序失败；同一用例定向复跑 PASS，随后不改代码的完整 `flutter-final-4.json` 复跑 76 项全部 PASS。格式检查后仅机械格式化两个目标文件，`flutter-final-5.json` 暴露并保留 2 条缺少花括号的 lint，补齐后最终 `flutter-final-6.json` 再次 76 项全 PASS。失败证据保留，不将单次复跑解释为稳定性证明。

## 未完成与下一步

1. Android/iPhone 页面、返回/键盘/大字体/读屏和真实多账号点击属于后续设备验收，当前 NOT_RUN；A07C 及发布 Gate 不关闭。
2. 第一次真实探针因合成密码超过服务端 20 位上限而 FAIL；按实际 Service 8–20 位规则修正探针后复跑 PASS。fixture 已按所有权停止，命名容器、状态文件和 18421/13341/16421 监听均已清除。
3. B03 的离线撤回/焚毁、会话移除与通用 access mutation 仍由 A07B-1R 候选控制，未经批准不实施。

## 回滚与停止边界

本卡变更限 Flutter 好友模型/API/controller/UI/测试/探针及任务索引。回滚时逐文件核对后续编辑，不回滚 A00–A07B-1R 或整个 dirty tree。没有 commit、push、merge、release、签名或远程操作。
