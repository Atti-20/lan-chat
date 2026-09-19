# 跨语言共享向量

[core-v1.json](core-v1.json) 是同一份 JSON 输入与期望；Java、Node/TS、Dart 都读取它。旧 [WS 样例](../websocket/fixtures/core.json) 继续保留。

| 场景 | Java | TypeScript / Web | Dart |
|---|---|---|---|
| 正常帧、未知事件、版本 | 真实 Handler AUTH/ERROR；Jackson 缺失版本默认值 | AJV 规范帧；真实 composable 丢弃不支持版本、向业务层分派未知事件 | WsFrame 规范验证及未知事件可解析 |
| nullable / missing | Java DTO 序列化与真实 HTTP 登录/会话响应 | AJV、TS 编译正反例 | 生成 REST/WS fromJson；保留缺失/null/附加字段 |
| duplicate clientMsgId | 已提交记录重试实际返回 duplicated ACK | 规范 ACK + 既有发送/去重测试 | 解析 ACK；实际 message merge 去重 |
| sequence gap / ACK 后重复事件 | 实际 sync 查询 after cursor；既有可靠消息 Service 测试 | 实际 domain-ts 连续游标及入站排队测试 | delivery/ACK/补同步结构、实际 message merge；**未建立 WS 连续游标状态机** |
| 重连补同步 | 真实 Handler 查询并返回消息/最新位置 | 真实 composable 两次 AUTH/SYNCING/ONLINE，无队列死锁 | A05 真实 WS AUTH/逐会话分页 SYNC，连续游标与离线 outbox；另有双模拟器真实后端证据 |
| token expired / force logout | 真实 Handler 过期令牌/撤销设备分支 | 真实 composable 刷新一次/强制断开 | schema 帧解析 + 既有 RealtimeConnection/ChatController 恢复测试 |

```sh
./tooling/verify contracts
./tooling/verify web
./tooling/verify flutter
```

`contracts` 运行 Node/TS 样例、真实 Java Handler/MVC/HTTP 和兼容突变测试；Web scope 会执行实际 composable 的传输替身回归。Flutter scope 运行生成解析测试和现有应用恢复测试。没有创建独立的“永远成功”协议状态机；每个行为断言都指向现有实现。`valid=false` 表示规范校验拒绝，不代表 Java 兼容入口必然拒绝；`javaEvent/javaCode/legacy` 单独描述已验证旧行为。
