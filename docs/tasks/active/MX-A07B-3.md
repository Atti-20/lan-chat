# MX-A07B-3 — Flutter 个人资料与基础设置

开始/完成日期：2026-09-13。状态：**B09 本阶段交付完成；实现、本地回归、Android/iOS 模拟器构建及真实 Spring 资料/头像/密码撤销闭环 PASS。** Android/iPhone 真实 UI 与发布仍 NOT_RUN/BLOCKED，安全门禁保留既有 FAIL；未启动 A07B-1I/B03、A07C、发布或安全规则处置。

## 基线与范围

- 分支 `feature/v0.3.1`；HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；起点仍是用户已有的大规模 dirty workspace，全部保留。以 `docs/tasks` 当前进度、A07A Scope/Matrix/Gates/Work Plan 和 A07B-1R 停止边界为准，不重做 A00–A07B-2。
- 本切片消费既有 `GET /user/info`、`PUT /user/profile`、`POST /file/avatar`、`PUT /user/password`，没有修改 REST/WS schema、Java 服务端、数据库、依赖锁、应用标识、最低系统版本、签名或发布配置。
- 主题是普通非敏感偏好；访问令牌与 refresh cookie 仍只走 Keychain/Keystore。未实现复杂头像裁切、设备管理、通知远程推送或完整诊断控制台。

## 已实现

- 新增严格 `UserProfile` / `AvatarUpload` DTO；ID、必需字符串和头像上传结果异常均 fail closed。文字头像格式与 Web 保持 `letter:<首字>:#RRGGBB` 及同一 12 色预设。
- `MeshXApi` 接入资料读取/保存、密码修改、头像 multipart 上传与同节点/HTTPS头像读取；保持 Bearer、401 单次刷新、账号一致性、5 MiB、图片 MIME、非重定向和二进制内容边界。
- 新增 `ProfileController`：加载、失败、保存、图片上传、密码校验/修改均有独立状态；资料变更操作互斥，避免并发上传/保存/改密交叉覆盖。服务端或本地凭据写入失败不显示假成功。资料成功后同步当前 Session 与安全凭据；凭据写入失败会回滚内存 Session。
- Android/iOS 的文件能力新增 `readFile`：只读取已由用户选择并复制到 app-owned cache 的 opaque handle，最大 5 MiB；不接收或返回任意文件路径，账号切换/释放通过 epoch 取消竞态。
- 新增个人资料与设置页面和聊天菜单入口：昵称、签名展示、文字/图片头像、系统/浅/深主题、节点、通知权限入口、修改密码与退出。密码修改成功后按现有服务端规则清除本地会话并回登录页。
- 新增普通偏好原子文件存储，主题重启恢复；延迟返回的初始化读取不会覆盖用户刚选择的主题，偏好文件不保存凭据。新增真实 Spring 探针 `tool/a07_profile_live_test.dart`，目标覆盖合成账号资料、文字/图片头像、密码变更、旧会话撤销及新密码重登；环境阻塞时不伪造成功。

## 验证

所有统一 scope 先运行 `--dry-run`。证据位于 [output/mx-a07b3-2026-09-13](../../../output/mx-a07b3-2026-09-13/)。

| 范围 / 命令 | 状态 | 结果 |
|---|---|---|
| `./tooling/verify flutter --report output/mx-a07b3-2026-09-13/flutter-final-3.json` | PASS / 0 | 纯 Dart 边界、14 向量、Token drift、analyze、87 项测试；新增 8 个资料/API/持久化用例、3 个小屏/大字体/主题 widget 用例，覆盖资料操作互斥与延迟主题读取竞态，并扩展 opaque bytes 负向验证；修正后的真实探针亦通过 analyze |
| `./tooling/verify flutter-android --report output/mx-a07b3-2026-09-13/flutter-android.json` | PASS / 0 | Kotlin 适配编译并生成 debug APK；不代表 Android 真机、签名或发布通过 |
| `./tooling/verify flutter-ios --report output/mx-a07b3-2026-09-13/flutter-ios.json` | PASS / 0 | Swift 适配编译并生成 iOS Simulator app；不代表 iPhone 真机、签名或发布通过 |
| `./tooling/verify dart-core --report output/mx-a07b3-2026-09-13/dart-core.json` | PASS / 0 | 3 个 Core 文件无 Flutter/platform 依赖；14 项向量 |
| `./tooling/verify contracts --report output/mx-a07b3-2026-09-13/contracts.json` | PASS / 0 | 来源/生成无漂移，Node 9，Java MVC/Handler/HTTP 7，兼容回归 3 |
| `./tooling/verify workspace --report output/mx-a07b3-2026-09-13/workspace-final-3.json` | PASS / 0 | 首次因新增 B09 文件使 repo-map 过期而 FAIL；正式 generate 后最终复验 PASS |
| `./tooling/verify hygiene --report output/mx-a07b3-2026-09-13/hygiene-final.json` | PASS / 0 | 最终 tracked/native icon/version/工作树与 index whitespace 共 5 步 |
| `gitleaks dir apps/flutter-prototype ...` | PASS / 0 | Flutter 模块无 finding；报告 `flutter-gitleaks.json` |
| `./tooling/verify security --report output/mx-a07b3-2026-09-13/security.json` | FAIL / 1 | 全历史 63 commits 仍为同一既有 finding；本轮未处置或放宽规则 |
| `flutter test --no-pub tool/a07_profile_live_test.dart --dart-define=MESHX_NODE=http://127.0.0.1:18421` | PASS / 0 | 专用 Spring/MySQL/Redis fixture 中完成合成账号资料读取、文字头像、真实 PNG 上传/读取、图片头像保存、密码变更、旧会话/旧密码失效及新密码重登；证据 `profile-live-2.json` |

验证失败亦保留：`flutter.json` 证明首次把 `dart:typed_data` 放入 Core 被边界门禁拒绝；端口随后收敛为纯 `List<int>`。`flutter-2.json` 证明一个测试冗余 import 使 analyze FAIL；移除后最终 `flutter-3.json` PASS。一次误用宽范围 `dart format lib` 机械触及 4 个非目标生成/既有文件；3 个生成文件经正式 generate 恢复，`wire_validation.dart` 按 A06 快照恢复，4 个文件均以 `cmp` 确认完全一致。未使用 reset/checkout/clean。

## 未完成与下一步

1. Android/iPhone 的真实图片选择、5 MiB/非法图片、重启主题、密码登出、小屏/键盘/读屏点击仍属于设备证据，当前 NOT_RUN；模拟器构建不能关闭 A07C/Gates。
2. 第一次真实探针因合成密码超过服务端 20 位上限而 FAIL；按实际 Service 8–20 位规则修正后复跑 PASS。与 A07B-2 共用的专用 fixture 已按所有权停止，命名容器、状态文件和端口监听均已清除。
3. B03 消息变更恢复仍由 A07B-1R `READY_FOR_APPROVAL` 候选控制，未经批准不实施。B05/B06/B07/B08/B10/B11 与 A07C/A07D 保持其现有状态。

## 回滚与停止边界

本卡只改 Flutter 资料/设置/API/普通偏好、Android/iOS opaque file 读取、测试/探针与状态文档。回滚需逐文件核对后续编辑，不回滚整个 dirty tree。没有 commit、push、merge、release、签名或远程操作。
