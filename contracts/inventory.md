# REST / WebSocket / 认证契约盘点

核对日期：2026-09-09，MX-A02。本文件描述当前本地源码，不把路线图视为客户端能力。

## 唯一维护来源与生成链

| 范围 | 可编辑事实来源 | 生成/验证产物 | 实际消费者 |
|---|---|---|---|
| REST 路由、方法、DTO | `services/server` Controller、DTO/Entity、SecurityConfig；业务规则在 Service | `RestContractTest` + `CoreRestSemantics` 导出 `contracts/rest/openapi.json`；生成 REST 导航 | MVC、Web API 包装层、原生认证适配 |
| REST TS 模型 | 上述 MVC 导出产物 | `packages/protocol/src/rest.ts`，锁定 openapi-typescript 7.13.0 | Web 登录/注册/刷新输入和契约编译样例 |
| REST 方法/路径与结果结构 | 同一 OpenAPI | `packages/protocol/src/rest-contract.ts` | Web 核心登录/刷新/退出、设备、节点、会话/历史 |
| 最小 Dart REST | 同一 OpenAPI；`generation.json` 只选择操作，不重复定义字段 | `apps/flutter-prototype/lib/data/rest_contract.g.dart` | `meshx_api.dart` 的核心路径/方法与登录请求；共享解析样例 |
| WS envelope | `contracts/websocket/envelope.schema.json` | Java `WebSocketEnvelope.java`、TS `envelope.ts`、Dart 基础帧解析 | Java Handler、Web composable、Dart 解析测试 |
| WS event/payload | `contracts/websocket/events.schema.json` | TS `events.ts`、Dart `ws_contract.g.dart` | Web/Flutter 文本发送 DTO、ACK/同步模型及解析测试 |
| 鉴权、事务、幂等、游标推进 | Handler、Service、`useWebSocket/useChat`、`domain-ts/sequence.ts` | Java/TS/Dart 行为测试和共享向量 | 应用编排；不写入生成 SDK |

OpenAPI 是 **导出产物**，不手工维护 Java→OpenAPI→TS→Dart 四套字段。Java envelope 已由 schema 生成；Java Handler 的事件实现通过扫描和真实行为测试与 event schema 对照。展示模型仍由 `domain-ts` / Flutter 管理，它们有默认值、交付状态等应用语义，不是额外的网络契约来源。

## REST 覆盖

当前 MVC 导出 **101 条路径、109 个操作、108 个模型**。完整清单见 [API 操作清单](../docs/generated/api-routes.md) 和 [按 Controller 导航](../docs/generated/api-summary.md)。每个已注册 `/api/**` 方法都必须出现在快照中；开放 Map 并不会因进入清单而变成完整 SDK。

| 核心范围 | 当前方法和路径 | 机器表示及行为证据 |
|---|---|---|
| 登录 / 刷新 / 退出 / 注册 | POST `/api/v1/auth/login`、`refresh`、`logout`、`register` | MVC DTO/安全入口；真实本地 HTTP 登录、Cookie 刷新、退出测试；Service 输入与轮换测试 |
| 会话 / 历史 | GET `/api/v1/chat/conversations`、`history` | 真实 HTTP 会话样例；Controller/Service 历史边界、WS 同步测试 |
| 节点 | GET `/api/v1/node/info` | NodePublicInfo 可空字段、生成操作；地址构造留在平台适配 |
| 设备 | GET `/api/v1/user/devices`、DELETE `/api/v1/user/devices/{deviceId}` | 实际路径以生成操作清单为准；DeviceLoginVO、服务端权限/撤销回归 |
| 其余 REST | 用户/好友/群组/广播/文件/临时房间/控制面等 | 全路由结构和现有业务回归；Map、权限触发条件与 DTO 服务层条件仍有未形式化部分 |

`ContractHttpIntegrationTest` 启动实际 loopback Tomcat，使用真正的 MVC、Security Filter、Jackson 和 Controller；JWT/持久化服务为替身，不连接真实数据库。它把登录和会话响应逐字段与同一份跨语言 JSON 比较。不能用它声称真实账户、数据库、设备或 LAN 验收。

### 字段、错误和分页

- `Result<T>` 的 `code/msg` 是工厂保证字段；`data/requestId` 可以为 null，也允许旧响应省略。Web 的旧应用包装层继续信任成功业务返回，不把生成类型当运行时数据清洗。错误既看 HTTP，也看 `Result.code`。
- `CoreRestSemantics` 从 Jackson 的 Java 引用类型恢复核心模型的 nullability；optional 与 nullable 分开。登录/注册 username/password 的必填来自真实 Service 前置断言。非空白、UTF-8 长度等服务规则没有全部转成 schema；注册还在服务层执行 trim 后用户名 3–50 位/ASCII 格式与保留名、密码 8–20 位且含字母和数字、非空昵称 2–16 字符；这些条件未全部生成。不存在“有 TS 类型就自动通过后端校验”的保证。
- 普通业务错误可为 HTTP 200 + code 400/500/403；过滤器为 HTTP 401/403；策略冲突 409、设备身份暂不可用 503。binary/multipart/导出和空错误 body 见 [REST 说明](rest/README.md)。
- `deviceType` 归一值为 android/desktop/ios/web；输入先 trim/lowercase，未知/空输入归一 web。它是归一值集合，不是拒绝未知字符串的输入 enum。`deviceName` trim、最多 100 字符，空值回退设备类型；refresh 的设备类型由既有 refresh token 确定。
- REST history 的 `beforeSequence > 0` 为排他上界；limit 夹在 1–100，返回升序。会话是服务端权威快照。WS sync 每会话 limit 1–200；一轮最多 100 会话/200 消息；检查 hasMore 和 deniedConversationIds。
- REST ChatMessage 用 `type`，WS delivery 同时带 `type/contentType`；送达/历史 `isBurn/isRecalled` 是整数，CHAT_SEND 的 `isBurn` 是布尔值。Flutter 适配已兼容真实整数撤回标记和 REST type。
- Java long 与 TS number 跨端尚未建立超出 JS 安全整数范围的编码策略。当前向量使用安全整数；不声称任意 64 位 sequence/ID 都能无损跨所有运行时。

## WebSocket 覆盖和状态

40 个事件名称，49 个方向分支（18 client / 31 server），其中 14 个分支有字段 schema，35 个仅冻结方向并使用 OpaquePayload。14 个 payload 定义含公共开放模型。具体来源见 `x-events`，不能把方向覆盖写成全事件字段验证。

规范信封要求 version=1、event、timestamp、payload，可选元数据允许显式 null，TS/Dart/Java 由同一来源表达。Java 兼容旧输入：缺失 version 默认 1，显式 null/不支持版本返回 UNSUPPORTED_VERSION。未知客户端事件在认证后返回 UNKNOWN_EVENT；客户端可解析未知服务端事件后交业务层忽略。附加字段保持可接受。

Web 现行 `AUTH_OK → SYNCING → ONLINE`：同步未结束不能视为可发业务消息；onReady 独立运行，避免等待同一入站队列中的 SYNC_RESPONSE 导致死锁。入站异步处理保持顺序。重连重新认证并补同步；TOKEN_EXPIRED 关闭旧连接、刷新一次并重连；FORCE_LOGOUT 清理连接并通知应用退出。

clientMsgId 为发送者作用域的 8–64 位 ID；同 ID/相同内容重试返回已提交 ACK，内容不同拒绝。ACK 在持久化事务成功后发送；ACK 后再次收到事件只更新原消息。实时游标只沿连续序列前进，不能把最新历史页最大值直接作为已接收游标。补同步的 `latestPositions` 结合服务端分页结果处理永久缺口，见 Handler/useChat 与既有回归。

Flutter 当前使用 REST history 恢复，不具备 Web 的 WS SYNCING/连续游标和同等入站串行状态机。A02 证明 Dart 生成、独立解析、当前消息合并与既有认证恢复；RealtimeConnection 仍使用旧解析入口，未接入新 WsFrame 的完整规范校验。结构负向量不证明原型运行时拒绝所有畸形帧，未实现移动实时功能对齐。[向量覆盖](fixtures/README.md) 逐项区分结构和行为证据。

## 生成和边界

OpenAPI 固定 3.0.1（3.0 语义），springdoc 2.8.15 仅 test classpath；Node 工具锁定 openapi-typescript 7.13.0 / TypeScript 5.9.3 / AJV 8.20.0。Python 使用标准库；Dart 切片由 `generation.json` 固定 9 个操作，并拒绝未支持的模型组合。SDK 中没有 HTTP I/O、令牌、存储或状态机。

Dart fromJson 保留缺失/null/未知字段，执行范围内的类型与 required 校验；schema 格式化 date-time、完整开放业务对象和应用鉴权不属于验证器。参考 [OpenAPI 3.0.3 的 nullable/ref 语义](https://spec.openapis.org/oas/v3.0.3.html) 和 [Dart JSON 转换](https://dart.dev/libraries/dart-convert)。
