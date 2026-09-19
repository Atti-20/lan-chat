# 协议与数据事实入口

| 范围 | 维护来源与产物 | 检查 |
|---|---|---|
| REST 结构 | [OpenAPI](rest/openapi.json)，由 Controller / DTO 导出；[TS 类型](../packages/protocol/src/rest.ts) | `RestContractTest` 对比快照、逐条路由覆盖，锁定工具生成检查 |
| REST 认证、错误与开放模型边界 | [REST 说明](rest/README.md)；SecurityConfig、Controller / Service | 权限与错误行为测试 |
| WS 信封 | [基础 schema](websocket/envelope.schema.json) → Java DTO / [TS](../packages/protocol/src/envelope.ts) / Dart | 生成漂移与实际版本兼容行为 |
| WS 事件 | [事件 schema](websocket/events.schema.json) → [TS](../packages/protocol/src/events.ts) / [Dart](../apps/flutter-prototype/lib/data/ws_contract.g.dart) | 方向/关键字段漂移、正反例、两端编译 |
| WS 可靠消息、鉴权与路由 | [协议规则](websocket/README.md)，Handler / Service | 服务端单测与隔离环境跨端验证 |
| 客户端展示模型 | `packages/domain-ts/src/models.ts`、Flutter 自身状态 | 不能和网络 DTO 混为一层 |
| 数据库 | 根 `sql/` 初始化与迁移 | [索引](../docs/generated/sql-map.md)；目标数据库状态另外核实 |

MX-A02 的 [完整盘点与 source→artifact→consumer 映射](inventory.md)、[共享向量覆盖](fixtures/README.md)、[兼容基线](compatibility/README.md) 和 [端点/认证 ADR](../docs/adr/0006-endpoints-auth-and-contract-generation.md) 描述当前范围。REST 已生成所有 TS 操作身份与 10 个核心 Dart 操作（A05 增选既有 getCurrentUserInfo，见 ADR 0008）及关联模型；生成层没有认证、存储或状态机。

```sh
npm ci --prefix tooling/contracts
python3 tooling/workspace.py generate
./tooling/verify contracts
```

REST 结构由实际 MVC 映射生成；业务必填、权限和动态 Map 并未全部形式化。WS 的字段覆盖以 `x-events` 为准；`direction` 仍是待补字段的显式边界。生成类型不自动实现客户端认证、存储、重试、同步或原生 SDK。

变更协议时审阅 schema 和生成差异、保留附加字段兼容、检查旧客户端；改变字段类型或 ACK/sequence 含义须有版本策略。历史资料在本地 `docs/archive/local/PRD`，现行协议以本入口及测试为准。
