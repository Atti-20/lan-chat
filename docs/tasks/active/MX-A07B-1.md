# MX-A07B-1 — Message Mutation, Read State & Cache Consistency

日期：2026-09-09。**阶段结果：BLOCKED_BY_CONTRACT，已交接并停止。** 用户允许发现契约缺口后以此状态结束。本轮仅A07B-1；未启动A07B-2/A07C/其他阶段。未commit/push/merge/release。

## 基线与范围

`feature/v0.3.1` / HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`，dirty。先保存 [git status](../../../output/mx-a07b1-2026-09-09/git-status-before.txt) 与 [1535路径基线](../../../output/mx-a07b1-2026-09-09/baseline.json)，保持A00–A07A及已有未提交/未追踪文件。根AGENTS.override.md不存在；已读取根/Flutter/Web/server/contracts/docs/tooling/domain适用指令、AI入口、A07A交接、四份product冻结文档及ADR0006/0008/0009。没有重跑A00–A06或全仓产品扫描。

聚焦F04/F07/F09/F27/F35/F36与R02。没有新增好友/群/广播/文件/Profile页面，没有改REST/WS schema、数据库、应用ID/最低OS/依赖锁/签名、TLS、安全规则或目录结构。

## 交付与停止原因

- [正式契约补充提案](../../proposals/message-mutation-reconciliation-v1-supplement.md)：当前v1、失败/安全影响、全量重建备选、增量变更日志、两类位点、Web/Dart/Java/老客户端、migration/rollback及测试要求。
- [完整报告](../../reports/仓库审查/MX-A07B-1_消息变更与缓存一致性复现_2026-09-09.md)：前后结果、命令、风险与证据。
- [7项共享复现向量](../../../contracts/test-vectors/README.md)；12个Dart characterization回归、8个Web handler回归和1个Web旧连接回归。
- [真实Spring探针](../../../apps/flutter-prototype/tool/a07_mutation_probe.mjs)；[实际Dart控制器/网络/磁盘测试](../../../apps/flutter-prototype/tool/a07_live_mutation_test.dart)，不是仅测reducer。

**原始复现**：缓存消息1/2及60条后续消息→真实socket离线→真实CHAT_RECALL/CHAT_BURN→重连AUTH/SYNC(after62)为空。完整从0重放显示服务器正文已清空，最新50条history遗漏1/2。进一步实际Flutter控制器缓存63/64至124、离线REST变更、恢复并重启后，旧正文仍保留。

该事实触发用户“存在协议缺口则停止扩大实现”的规则。**Core、persistence、运行时Web/Flutter/Java均未修复，修复后安全结果NOT_RUN**。没有用UI隐藏、改sequence或增schema字段制造通过。当前v1增量路径为PROTOCOL_GAP；全量重建可能是备选，不声称所有v1方案都绝对不可行。

## 行为矩阵

“测试PASS”表示观测被重复验证；安全目标是否满足单独列出。

| 场景 | Server事实 | Web结果 | Flutter结果 | 分类/安全结论 |
|---|---|---|---|---|
| 离线撤回/焚毁→增量恢复 | 原行清空，事件沿用旧sequence，after62为空 | 原cache/内存保留 | 控制器/文件/重启保留 | PROTOCOL_GAP；安全FAIL |
| 从0重放保留消息 | isRecalled=1/status=2、正文空 | 可消费新快照，但无有界全量重建验收 | 可消费新快照，原逻辑不触发全量 | 已有读取能力PASS；完整替代方案NOT_RUN |
| 实时撤回/焚毁 | 当前在线事件存在 | 当前消息清空PASS；未打开消息burn缓存仍在 | 事件忽略，原内容仍在 | CLIENT_GAP |
| 重复/先撤回后原消息 | 实时帧可重复 | 当前运行内recall保护PASS；持久tombstone完整矩阵未验 | 仍显示原文 | CLIENT_GAP；长期恢复未完成 |
| 已读恢复 | GET conversations恢复本人lastReadSequence=62 | 有前台/当前会话可见性规则 | 未接CHAT_READ/markRead | Server能力PASS；Flutter CLIENT_GAP；ACK≠peer read |
| 未打开/后台收到消息 | 无自动读要求 | 新读位点前台门禁PASS | 不发CHAT_READ PASS | 阴性PASS不代表“正在看→已读”完成 |
| 移出群后访问旧会话 | SYNC denied、history code400、send ERROR、目录移除 | REMOVED清UI/访问集合，消息cache保留 | 摘要消失跳过SYNC，active/cache/cursor/queued仍在 | Server权限PASS；持久清理CLIENT_GAP |
| 显式denied响应 | 现有可表达 | 有不可访问状态 | 清消息/队列/游标/active PASS | 现有契约可修的客户端路径，未扩大实施 |
| 好友关系解除 | 历史可读、SYNC不denied；发送ERROR | 需区分可读与可发 | 无发送权限预检/隔离策略 | 当前语义已复现；不能把删好友等同读权限撤销 |
| 未知受限status | 测试以受控输入验证客户端 | 本轮不声称完整未知状态安全 | status被丢弃，普通正文仍可显示 | CLIENT_GAP；安全FAIL |
| 旧连接事件 | 不改变本轮服务端隔离 | 旧RECALL/READ/REMOVED/DELIVER被connection fence拒绝 | 旧RECALL和支持的DELIVER对照被epoch拒绝 | 连接隔离PASS；新mutation业务隔离未实施 |
| 历史状态同步通知 | 不等于新消息 | 空SYNC无通知PASS | 真实恢复onLiveMessage计数0 PASS | 全部状态通知资格/点击E2E NOT_RUN |

## 九项完成问题

1. **离线撤回如何知道？** 当前afterSequence不会知道已缓存旧变更；从0读取可知道保留行变化。正式恢复方案等待契约/全量重建评审。
2. **burn/removed最终一致？** burn同样漏增量；群移除已有denied/目录事实，但客户端必须原子清理旧缓存，当前未完成。
3. **权限撤销后的cache/outbox？** 当前Flutter显式denied能清，但摘要消失保留旧数据和queued（此次不发送，不代表永不重发）。提案默认移除时丢弃正文并留无正文失败原因，不自动复活；仅禁发需独立权限状态。
4. **markRead真实规则？** Flutter尚无；Web为当前会话、前台、页面可见且非加载时按传入消息最大sequence，已赚取读位点可延迟发送。逐消息viewport是后续验收要求，不拿ACK当read。
5. **v1无法覆盖的场景？** 当前增量游标无法回放旧变更，且没有稳定快照/持久mutation游标/过期重建边界；未证明所有全量v1替代方案不可行。
6. **Web与Flutter一致吗？** 普通文字可靠链条保持；消息mutation/持久清理目前不一致，两端均有离线缺口，不能关闭G03。
7. **重启后正确吗？** FAIL：实际FileChatStore恢复仍保留已撤回/焚毁正文。
8. **旧连接/账号隔离？** 现有generation/owner不改，完整原回归通过；新增旧连接测试PASS。未实施的新mutation账号/节点持久转换仍NOT_RUN。
9. **共享regression vectors？** 已建立7项，由TS/Dart实际消费者测试读取；绿表示复现已知行为，不是安全目标PASS。

## 精确验证

根目录执行；先分别运行各scope的`--dry-run`，输出在 [证据目录](../../../output/mx-a07b1-2026-09-09/)。

| 命令 | 状态与证据 |
|---|---|
| `./mvnw -pl services/server -DskipTests package` | PASS；只构建当前JAR，随后另跑完整server测试；package.log |
| `./tooling/verify server --report output/mx-a07b1-2026-09-09/server.json` | PASS；313测试、0失败/跳过 |
| `./tooling/verify web --report output/mx-a07b1-2026-09-09/web.json` | PASS；119测试、typecheck/build |
| `./tooling/verify flutter --report output/mx-a07b1-2026-09-09/flutter-final.json` | PASS；Core边界/14VM向量检查/tokens/analyze/68Flutter测试 |
| `./tooling/verify contracts --report output/mx-a07b1-2026-09-09/contracts.json` | PASS；原schema与生成/兼容/真实MVC快照不变 |
| `./tooling/verify workspace --report output/mx-a07b1-2026-09-09/workspace.json` | PASS；最终JSON/log已保存 |
| `./tooling/verify tooling --report output/mx-a07b1-2026-09-09/tooling.json` | PASS；最终JSON/log已保存 |
| `./tooling/verify hygiene --report output/mx-a07b1-2026-09-09/hygiene.json` | PASS；最终JSON/log已保存 |
| `./tooling/verify security --report output/mx-a07b1-2026-09-09/security.json` | FAIL；1条历史finding，配置未改 |

独立fixture与实际Dart测试（新输出目录首次运行；已有backend-state时先按脚本核对所有权，不覆盖他人服务）：

```sh
export PROBE_OUTPUT_DIR="$PWD/output/mx-a07b1-2026-09-09/backend"
export PROBE_FIXTURE_NAME=meshx-a07b1
export PROBE_HTTP_PORT=18401 PROBE_MYSQL_PORT=13329 PROBE_REDIS_PORT=16402
python3 apps/flutter-prototype/tool/backend_fixture.py start
node apps/flutter-prototype/tool/a07_mutation_probe.mjs
cd apps/flutter-prototype
flutter test tool/a07_live_mutation_test.dart
```

`PROBE_OUTPUT_DIR`必须保持绝对路径；脚本写出的live-client-config仅含短期合成账号令牌，留在忽略目录且权限0600，不进入共享向量。退出模块回根后，用同一环境执行`python3 apps/flutter-prototype/tool/backend_fixture.py stop`。本轮停止结果见报告。

单独回归：模块内`flutter test test/mutation_characterization_test.dart`；根`node --test apps/web/tests/message-mutation-characterization.test.mjs apps/web/tests/websocket-contract-vectors.test.mjs`。最初探针的测试账号长度失败、测试binding截获HTTP失败、Flutter新增测试lint失败均保留原日志；修正测试工具后通过，不伪装为产品修复。

## Handoff与后续边界

本轮变化仅为上述探针、测试/共享向量、提案、报告/任务与索引/R02说明。业务Core/persistence/contract **未改**。原dirty树保留结果以最终 [preservation](../../../output/mx-a07b1-2026-09-09/preservation.json) 为准。

全历史安全仍 **FAIL — known historical false positive**，精确指纹与A07A一致；本轮变化源码/文档独立Gitleaks **PASS（0 finding）**。workspace、tooling（58测试）、hygiene、347个本地链接及原有文件保留审计PASS。Android实体机、原生构建/设备、通知点击、签名发布、远程CI、TLS修复全部NOT_RUN（不属于本轮），不借用A05/A06数字关闭它们。

下一步应单独审阅契约补充或批准现有v1全量重建试验。好友/搜索可独立开发，但必须继续阻止旧outbox不经权限判断重发；不把B-1视为已实现前置。**A07B-2需要另行授权且未启动；A07C/Release Ready条件未具备。**
