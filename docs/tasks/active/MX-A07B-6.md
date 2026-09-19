# MX-A07B-6 — Flutter 广播接收与回执

2026-09-13后续进度见 [MX-A07C-3](MX-A07C-3.md)：真实Web→Android无凭证完成→Web回执已取得证据；同时修复已打开详情未消费实时失效更新的问题。下文数字与NOT_RUN仅代表本卡原始验收轮次。

开始/完成日期：2026-09-13。状态：**B07 本阶段实现与本地验证完成；110 项 Flutter 回归、Android/iOS 模拟器构建、57 项服务端广播规则测试 PASS。** 真实 Web 发起到手机回执、图片选择器实机、取消/移除/过期在线竞态与发布仍 NOT_RUN/BLOCKED；安全门禁保留既有 FAIL。

## 基线与范围

- 分支 `feature/v0.3.1`；HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；起点为用户已有的大规模 dirty workspace，全部保留。以 `docs/tasks` 当前进度、A07A Scope/Matrix/Gates/Work Plan 与 A07B-1R 停止边界为准，不重做 A00–A07B-5。
- 只消费既有 `GET /api/v1/broadcast`、`/pending`、`/{id}`，接收者 `view/confirm/complete`，`POST /api/v1/file/broadcast-image`，以及既有 `BROADCAST*`/技术通知账号卡片。没有修改 REST/WS schema、Java 服务端、数据库、定位 Port、应用标识、最低系统版本、签名或发布配置。
- 移动端不创建/编辑目标/提醒/取消/查看管理统计。`requireLocationProof=true` 明确只读并引导 Web，不调用 `EXECUTED` 或其他回执绕过凭证。

## 已实现

- 新增严格广播 summary/detail/receiver 模型：生命周期、优先级、目标状态、ID 与确认选项异常均 fail closed；待办与全部列表每次从当前节点读取。
- 详情打开先重新鉴权读取；首次查看提交 `view` 后再次读取权威状态。卡片缓存不能替代权限，详情 403/删除会清空本地详情。
- 只展示服务端返回的确认选项；重复点击通过 controller 单飞保护，超时后可用同一值重试并依赖服务端幂等。取消、过期、目标移除或非 ACTIVE 状态不会从旧卡片提交。
- 无定位任务可调用 `complete`。要求图片时先通过原有 opaque app-owned FilePicker 读取最多 5 MiB 图片，专用上传完成取得 file ID 后才提交完成；选取/读取/上传失败不产生完成回执。
- `BROADCAST`、`BROADCAST_UPDATED`、`BROADCAST_REMINDER`、`BROADCAST_PERMISSION_UPDATED` 触发列表刷新。技术通知账号 `type=broadcast` 消息渲染为卡片；点击只携带 `broadcastId`，再从服务器读取详情，不尝试把技术账号认证为好友。
- 新增菜单入口、待办/全部列表、320px 详情布局、查看/确认/完成状态与定位只读提示；没有在历史补拉阶段额外生成系统通知。

## 验证

证据位于 [output/mx-a07b6-2026-09-13](../../../output/mx-a07b6-2026-09-13/)。

| 范围 / 命令 | 状态 | 结果 |
|---|---|---|
| `./tooling/verify flutter --report output/mx-a07b6-2026-09-13/flutter-final-2.json` | PASS / 0 | 最终代码与文档写回后复验：边界、14 向量、Token drift、analyze 与 110 项测试全部通过；含 6 个广播模型/API/controller 用例和 2 个 widget 用例 |
| `./tooling/verify flutter-android --report output/mx-a07b6-2026-09-13/flutter-android.json` | PASS / 0 | 生成 debug APK；不代表 Android 真机文件选择、点击或发布通过 |
| `./tooling/verify flutter-ios --report output/mx-a07b6-2026-09-13/flutter-ios.json` | PASS / 0 | 生成 iOS Simulator app；不代表 iPhone 真机文件选择、点击或发布通过 |
| `./mvnw -B -pl services/server -Dtest=BroadcastControllerTest,BroadcastMapperIntegrationTest,BroadcastNoticeOutboxServiceImplTest,BroadcastNotificationAccountServiceTest,BroadcastServiceImplTest,UserServiceImplBroadcastPermissionTest,ChatWebSocketBroadcastTest test` | PASS / 0 | 57 项：服务规则、权限、广播卡 outbox、WS 路由和映射回归通过；日志 `server-broadcast-tests.log` |
| `./tooling/verify contracts --report output/mx-a07b6-2026-09-13/contracts.json` | PASS / 0 | 契约来源/生成、Node 9、Java 7、兼容回归 3 全部通过 |
| `./tooling/verify workspace` / `hygiene` | PASS / 0 | workspace 1 步、hygiene 5 步通过 |
| `./tooling/verify security --report output/mx-a07b6-2026-09-13/security.json` | FAIL / 1 | 全历史 63 commits 仍为同一既有 finding；规则未修改或放宽 |
| `gitleaks dir` 定向扫描 Flutter `lib`、2 个新增测试与本任务卡 | PASS / 0 | 四个变化范围均无 finding；未使用被中断的包含 build 产物的过宽扫描作为证据 |

首次统一 Flutter 验证因一次过宽的 `dart format lib test` 格式化生成的 `tokens.g.dart` 而在 token drift 步骤 FAIL，后续步骤按规则 NOT_RUN。使用正式 `python3 tooling/workspace.py generate` 恢复生成物后，完整统一 scope 重跑 PASS；未把首次失败隐藏成成功。第一次定向服务测试命令使用了 zsh 只读变量名 `status`，测试已执行但命令自身失败；改用 `rc` 后原命令重跑并取得 57/57 PASS。第一次定向 Gitleaks 把整个 Flutter 测试/构建树一并纳入，因扫描 build 产物过久而人工中止；随后按实际变化范围拆成四次扫描并全部 PASS。

## 未完成与下一步

1. C01/C06 的真实 Web 发起 → 手机技术账号卡片 → 查看/回执/图片完成，以及取消、过期、移除后的手机在线竞态仍 NOT_RUN；模拟器构建、Dart fake 与服务端单测不能替代真机闭环。
2. 定位凭证仍为 P1/Web-only 边界，本卡没有新增定位 Port，也不宣称 F20 定位能力完成。
3. B03 通用消息变更/授权恢复仍由 A07B-1R `READY_FOR_APPROVAL` 候选控制；B08/B10/B11 与 A07C/A07D 保持其当前状态。

## 回滚与停止边界

本卡只改 Flutter 广播模型/API/controller/UI、广播实时刷新、测试与状态文档。回滚需逐文件核对后续编辑，不回滚整个 dirty tree。没有 commit、push、merge、release、签名或远程操作。
