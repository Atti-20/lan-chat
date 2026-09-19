# MX-A07B-4 — Flutter 基本群聊

开始/完成日期：2026-09-13。状态：**B05 本阶段交付完成；实现、本地回归、Android/iOS 模拟器构建及真实 Spring 群组/WS 闭环 PASS。** Android/iPhone 真实 UI 与发布仍 NOT_RUN/BLOCKED，安全门禁保留既有 FAIL；远端移除/解散后的通用缓存恢复仍受 A07B-1R/B03 边界约束。

## 基线与范围

- 分支 `feature/v0.3.1`；HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；起点仍为用户已有的大规模 dirty workspace，全部保留。以 `docs/tasks` 当前进度、A07A Scope/Matrix/Gates/Work Plan 和 A07B-1R 停止边界为准，不重做 A00–A07B-3。
- 本切片只消费既有 `POST /group`、`GET /group/my`、`GET /group/{groupId}`、`GET /group/{groupId}/members`、`POST /group/{groupId}/leave` 与现有群聊 `CHAT_SEND/CHAT_ACK`。没有修改 REST/WS schema、Java 服务端、数据库、依赖锁、应用标识、最低系统版本、签名或发布配置。
- 仅实现 P0 创建、列表、成员查看、进入群聊和普通成员主动退出；不新增群改名、成员增删、管理员、转让、禁言或解散入口。服务端确认本人退群后清理该群本地会话，不将其扩展为远端移除/解散的通用 B03 恢复实现。

## 已实现

- 新增严格 `MeshXGroup` / `GroupMemberInfo` DTO；群 ID、群主 ID、名称、人数上限、加入方式、成员角色与重复成员异常均 fail closed。
- `MeshXApi` 接入群列表、详情、成员、创建与退群；会话目录改用同一严格群 DTO，并保持 Bearer、HTTP/Result.code、401 单次刷新与同节点边界。
- 新增 `GroupsController`：并发创建/退群去重，只允许当前好友作为初始成员；群名按实际 Service 的 2–20 字符规则校验。服务端失败不显示假成功，群主退出拒绝原样可见。
- 新增群聊页面与消息菜单入口：群列表、从好友多选建群、空成员建群、成员角色/在线状态、进入已有群聊、普通成员确认退群；320 px 深色大字体下关键操作仍可达。
- 创建成功立即进入 `group:<id>` 空会话并复用现有文字 outbox/ACK 管线；本人退群只有在服务端成功后才清理该群消息、游标、草稿与待发计时器。
- 新增真实 Spring 探针 `tool/a07_groups_live_test.dart`，覆盖好友关系、重复成员去重建群、双方群目录、角色、群聊 ACK、群主退出拒绝、普通成员退出、退出后详情/发送拒绝及仅群主空群。

## 验证

证据位于 [output/mx-a07b4-2026-09-13](../../../output/mx-a07b4-2026-09-13/)；统一 scope 均按仓库验证入口执行。

| 范围 / 命令 | 状态 | 结果 |
|---|---|---|
| `./tooling/verify flutter --report output/mx-a07b4-2026-09-13/flutter-final.json` | PASS / 0 | 纯 Dart 边界、14 向量、Token drift、analyze、94 项测试；新增 5 个群 DTO/API/controller 用例与 2 个 widget 用例 |
| `./tooling/verify flutter-android --report output/mx-a07b4-2026-09-13/flutter-android.json` | PASS / 0 | 生成 debug APK；不代表 Android 真机、签名或发布通过 |
| `./tooling/verify flutter-ios --report output/mx-a07b4-2026-09-13/flutter-ios.json` | PASS / 0 | 生成 iOS Simulator app；不代表 iPhone 真机、签名或发布通过 |
| `flutter test --no-pub --machine tool/a07_groups_live_test.dart --dart-define=MESHX_NODE=http://127.0.0.1:18431` | PASS / 0 | 当前 Spring/MySQL/Redis fixture 完成建群、成员角色、群聊 ACK、两类退群及退群后拒绝；证据 `groups-live.json` |
| `./tooling/verify contracts --report output/mx-a07b4-2026-09-13/contracts.json` | PASS / 0 | 契约来源/生成无漂移，Node 9、Java 7、兼容回归 3 全部 PASS |
| `./tooling/verify workspace --report output/mx-a07b4-2026-09-13/workspace.json` | PASS / 0 | 正式 generate 后新文件与 repo-map 一致 |
| `./tooling/verify hygiene --report output/mx-a07b4-2026-09-13/hygiene.json` | PASS / 0 | tracked/native icon/version/工作树与 index whitespace 共 5 步 |
| `./tooling/verify security --report output/mx-a07b4-2026-09-13/security.json` | FAIL / 1 | 全历史 63 commits 仍为同一既有 finding；规则未修改或放宽 |
| `gitleaks dir` 定向扫描 Flutter `lib/test/tool` 与本任务卡 | PASS / 0 | 四个变化范围均无 finding；JSON 报告保存在本轮 output |

首次定向 widget 运行因创建弹窗关闭动画仍访问已释放 `TextEditingController` 而 FAIL；改为弹窗局部表单值后 7 项定向测试与 94 项完整回归 PASS。一次从 Flutter 模块目录串接根 `./tooling/verify` 因相对路径不存在而退出，随后从仓库根按正式入口运行成功；这些失败未被伪装成首次成功。

## 未完成与下一步

1. Android/iPhone 的真实建群、成员查看、进入空会话、群聊收发、退群、返回/键盘/字号/读屏点击仍属于 A07C 设备证据，当前 NOT_RUN；模拟器构建不能关闭发布 Gate。
2. 专用 fixture 已按所有权停止；`meshx-a07b4-*` 命名容器、state 文件与 18431 监听均已清除。真实 Spring 探针是服务端/API/WS 证据，不是假称手机 UI 实测。
3. 被他人移出群、群解散或离线期间授权变化的通用缓存失效仍属于 B03，并由 A07B-1R `READY_FOR_APPROVAL` 候选控制；本卡没有实施该契约。
4. B06/B07/B08/B10/B11 与 A07C/A07D 保持其当前状态。

## 回滚与停止边界

本卡只改 Flutter 群组模型/API/controller/UI、本人退群后的局部会话清理、测试/探针与状态文档。回滚需逐文件核对后续编辑，不回滚整个 dirty tree。没有 commit、push、merge、release、签名或远程操作。
