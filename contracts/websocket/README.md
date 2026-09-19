# WebSocket V1

端点 `/ws/chat`。信封字段为 `version / event / requestId / clientMsgId / conversationId / timestamp / payload`，不是另造的 `id/type/body` 协议。

[envelope.schema.json](envelope.schema.json) 定义共用信封，可选元数据允许显式 null，事件再细化必填和非空规则；[events.schema.json](events.schema.json) 按方向列出事件和 payload。双向事件可能使用不同 payload，验证时选择 `x-direction`，不能只看事件同名就共用输入结构。`x-events.coverage=fields` 表示已有字段 schema，`direction` 表示只冻结事件方向，详细字段仍需核对 Handler 和测试。

生成的 [TypeScript](../../packages/protocol/src/events.ts) 和 [Dart](../../apps/flutter-prototype/lib/data/ws_contract.g.dart) 来自同一份 schema。Vue 的 `ChatSendPayload` 与 Flutter 文本发送都已接入。消息送达中的 `isBurn / isRecalled / status` 是服务端整数；客户端发送的 `isBurn` 是布尔值。`mentionUserIds` 是逗号分隔字符串，不是用户数组。为了兼容旧消息，消息快照可包含 nullable 字段。

Java `WebSocketEnvelope.java` 也由基础 schema 生成。它保留缺失 version 默认 1 的旧行为；规范出站帧必须显式写 version=1，显式 null/不支持版本被 Handler 拒绝。生成的 Dart `WsFrame` 允许未知服务端事件被解析，再由调用者选择忽略；`validateKnownEvent(direction)` 才执行已知事件与方向的规范验证。

## 连接与可靠消息

1. 建连后在 10 秒内发送 `AUTH`，携带 access token；收到 `AUTH_OK` 后才开始业务帧。`TOKEN_EXPIRED / FORCE_LOGOUT` 表示认证需要恢复或设备会话已失效。
2. 心跳使用 `PING / PONG`。每个业务帧仍校验令牌和设备会话，建连成功不表示永久授权。
3. `CHAT_SEND` 使用稳定 `clientMsgId`（8–64 位字母、数字、下划线或短横线）和 `requestId`。同一发送者重试必须复用原 ID 和原内容；内容不一致会被拒绝。
4. 服务端保存成功后才发 `CHAT_ACK`，随后派发 `CHAT_DELIVER`。重复请求返回 `duplicated=true` 的 ACK，客户端只更新原消息。
5. 最终顺序使用每个会话的 `sequence`。接收游标只推进到连续位置；收到 42 但缺少 41，不能把已连续接收游标推进到 42。
6. 重连以 `SYNC_REQUEST.positions={conversationId:sequence}` 补拉。单次处理最多 100 个会话、200 条消息，检查 `hasMore`；`latestPositions` 是服务端最新位置，不是客户端已经完整收到的位置。被拒绝会话通过 `deniedConversationIds` 返回。
7. `CHAT_READ` 的已读游标只同步阅读者的设备；不据此声称其他人已经读过消息。提及回执有单独权限接口，实时事件只发缓存失效提示。
8. 广播实时事件通常通知客户端刷新相关 `broadcastId`，不可当作完整广播详情。文件传输包含提议、响应、开始、完成、失败和中继回退的独立状态。

服务端接受的部分旧输入有宽松转换或默认值；生成出站模型采用当前客户端规范写法。附加字段被允许以支持渐进升级；未知事件应忽略/记录并触发适当重同步，不能作为已处理业务显示成功。

## 验证与兼容

```sh
python3 tooling/workspace.py generate
./tooling/verify contracts
npm --prefix tooling/contracts run check
./mvnw -pl services/server test
```

共享 [测试帧](fixtures/core.json) 覆盖发送、重复 ACK、补同步、权限拒绝和业务错误。JSON Schema 测试包含错误方向、缺少幂等键、错误布尔表示等反例。字段/方向扫描负责发现已知源码漂移，服务端行为测试负责授权、提交、路由和补同步；二者不可互相替代。

MX-A02 扩展的 [跨语言向量](../fixtures/README.md) 包含真实 Java Handler 和 Web composable 消费方，以及 Dart 生成/解析验证。A05 Flutter 已实现真实 AUTH/分页 WS SYNC、连续序列和持久 outbox；Dart 单测与 Android/iOS 模拟器真实互通分别见 A05 任务卡，解析通过本身仍不代表运行验收。

兼容原则：增加可选字段可渐进发布；删除字段、改变类型、重定义 ACK/sequence/权限含义属于破坏性变更，需要新协议版本或双方兼容窗口。客户端默认不应拒绝未知附加字段。生成的 Dart fromJson 是模型解析器，完整帧的授权和运行时校验仍由服务端承担。
