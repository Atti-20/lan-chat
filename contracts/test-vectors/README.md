# 消息状态复现向量

[message-mutation-v1.json](message-mutation-v1.json) 是 MX-A07B-1 的**问题复现输入**，不是新的REST/WS schema，也不是接受不安全行为的兼容基线。

数据来自当前Spring真实fixture的sequence/事件结果，标识已归一，正文为合成测试文本。7项包括重复、先后乱序、离线增量恢复、旧连接、未知事件和会话移除。

- [Dart controller与磁盘测试](../../apps/flutter-prototype/test/mutation_characterization_test.dart)消费全部7项，并记录当前CLIENT_GAP。
- [Web实际handler测试](../../apps/web/tests/message-mutation-characterization.test.mjs)消费6项；[WebSocket实际composable测试](../../apps/web/tests/websocket-contract-vectors.test.mjs)消费旧连接向量。
- [真实Spring探针](../../apps/flutter-prototype/tool/a07_mutation_probe.mjs)和[实际Dart/HTTP/WS/磁盘集成](../../apps/flutter-prototype/tool/a07_live_mutation_test.dart)提供源行为证据，精确命令在[任务卡](../../docs/tasks/active/MX-A07B-1.md)。不是浏览器UI或原生设备验收。

测试绿表示复现断言符合当前实现。安全结果仍有FAIL/PROTOCOL_GAP/CLIENT_GAP，阶段为BLOCKED_BY_CONTRACT；修复前不得用于关闭移动首发安全门禁。后续安全期望、版本/迁移提案见[契约补充](../../docs/proposals/message-mutation-reconciliation-v1-supplement.md)。现有[core-v1](../fixtures/README.md)与兼容基线保持不变。

## A07B-1R 目标期望（待批准）

[mutation-recovery-v1-targets.json](mutation-recovery-v1-targets.json)另存38项安全目标，TARGET_PENDING_APPROVAL；旧characterization不变。执行规则与[设计候选](../../docs/proposals/mutation-recovery-v1/design.md)一致，`expected`是真正目标而非旧缺陷输出。当前仅artifact结构/覆盖验证，Java/TS/Dart业务适配器执行NOT_RUN，待独立1I。
