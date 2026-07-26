# MeshX Architecture Evolution TODO

> 仓库：`Atti-20/lan-chat`
> 当前分支：`feature/v0.3.0`
> 当前代码版本标记：`3.0.0`
> 目标定位：LAN-first 私有化 AI 协作基础设施
> 核心原则：控制面集中管理，数据面优先局域网直连，公网与云服务均为可选能力。

------

## 0. 当前架构判断

### 0.1 已经完成的基础

-  Spring Boot 账号、好友、私聊、群聊和设备会话。
-  WebSocket 实时通信。
-  `clientMsgId` 消息幂等。
-  会话序列号与断线补拉。
-  IndexedDB 文本发件箱。
-  文件安全检查、授权、签名预览和访问审计。
-  文件分片上传和断点续传。
-  LOCAL/MinIO 存储适配。
-  Redis 多实例消息路由和 Presence。
-  Spring Boot 服务端 mDNS 发布和发现。
-  Tauri 2 桌面端基础壳。
-  Rust 原生 mDNS 浏览、握手和健康探测。
-  桌面端原生登录、Refresh Cookie 隔离和退出。
-  桌面端托盘、单实例、开机自启、通知和深链。
-  Capacitor Android 工程。
-  Android NSD/DNS-SD 节点扫描。
-  Android 文件保存与本地通知插件。
-  WebRTC 文件直传与服务端中转降级。
-  初步 CI、Release 和 E2E 工作流代码。

### 0.2 当前架构的真实性质

当前被 mDNS 发布和发现的“节点”实际上是：

```text
Spring Boot Server Instance
├── /api/v1/node/info
├── /api/v1/node/health
├── /ws/chat
├── 用户认证
├── 消息持久化
├── 文件存储
└── 管理功能
```

当前桌面端和移动端是客户端壳，还不是能够独立收发和保存数据的完整 MeshX Node。

当前通信主链路仍然是：

```text
Desktop / Android / Web
          │
          ▼
Spring Boot Server
          │
          ├── MySQL
          ├── Redis
          └── MinIO / LOCAL
```

下一代目标架构是：

```text
                       MeshX Cloud（可选）
                  License、更新、商业授权
                              │
                              ▼
                 MeshX Control Server
         身份、组织、权限、设备、策略、审计
                              │
                 局域网控制面与离线信箱
                              │
          ┌───────────────────┴───────────────────┐
          │                                       │
  Desktop Full Node                       Mobile Node
  Rust + SQLite                          Kotlin/Swift
          │                                       │
          └──────── 消息和文件优先 P2P ────────────┘
```

------

# 1. P0：冻结当前基线，避免直接推倒重写

## 1.1 统一版本命名

当前存在以下不一致：

- 分支名称：`feature/v0.3.0`
- Maven：`3.0.0`
- Tauri：`3.0.0`
- Android：`3.0.0`
- README：V3.0.0

待办：

-  确定唯一版本策略。
-  项目尚未形成稳定正式发行时，建议采用 `0.x` 语义化版本。
-  统一 Maven、Cargo、npm、Capacitor、Tauri 和文档中的版本号。
-  统一 Git 分支和 Tag 规则。
-  新增版本检查脚本，CI 中校验各模块版本一致。

建议规则：

```text
开发分支：feature/v0.4.0-node-runtime
发布分支：release/v0.4.0
标签：v0.4.0
```

## 1.2 建立当前稳定基线

-  为当前可运行版本创建稳定 Tag。
-  保存当前数据库初始化脚本。
-  保存当前消息协议文档。
-  保存当前文件上传协议文档。
-  给关键主链路补充回归测试。
-  禁止在架构迁移期间直接删除原有服务端消息链路。
-  新旧通信链路使用 Feature Flag 切换。

建议增加：

```text
meshx.features.peer-message-enabled=false
meshx.features.peer-file-enabled=false
meshx.features.local-node-storage-enabled=false
```

------

# 2. P0：补充架构设计文档

新增目录：

```text
docs/
└── architecture/
    ├── 00-overview.md
    ├── 01-control-plane.md
    ├── 02-node-runtime.md
    ├── 03-identity-and-trust.md
    ├── 04-message-protocol.md
    ├── 05-file-transfer.md
    ├── 06-offline-sync.md
    ├── 07-rbac-and-license.md
    ├── 08-mobile-node-lifecycle.md
    └── decisions/
        ├── ADR-001-control-data-plane.md
        ├── ADR-002-rust-desktop-node.md
        ├── ADR-003-mobile-node-model.md
        └── ADR-004-protocol-versioning.md
```

待办：

-  明确定义 Control Plane 和 Data Plane。
-  明确定义 Control Node、Full Node 和 Mobile Node。
-  明确定义每种节点可以提供的能力。
-  明确定义断公网、断控制节点和节点离线时的系统行为。
-  明确定义消息和文件的权威数据来源。
-  明确定义当前服务端中心链路的兼容周期。
-  为重大架构决定编写 ADR。

------

# 3. P0：拆分两种节点发现

当前 `_lanchat._tcp.local.` 同时承担服务端入口和“节点”概念，后续会产生语义混乱。

## 3.1 新服务类型

建议逐步切换为：

```text
_meshx-control._tcp.local.   # 控制服务器
_meshx-node._tcp.local.      # 普通通信节点
```

兼容期内继续监听：

```text
_lanchat._tcp.local.
```

待办：

-  Spring Boot 只发布 `_meshx-control._tcp.local.`。
-  Desktop Full Node 发布 `_meshx-node._tcp.local.`。
-  Android/iOS 前台时可选择性发布 `_meshx-node._tcp.local.`。
-  桌面登录页只扫描 Control Node。
-  用户登录成功后才扫描普通 Peer Node。
-  普通节点不能伪装成控制节点。
-  为不同服务类型设置不同握手结构。
-  增加旧 `_lanchat` 类型的迁移兼容逻辑。
-  最终删除服务端“扫描其他控制服务器”的默认行为，改为显式联邦配置。

## 3.2 控制节点握手

新增：

```http
GET /api/v2/control/info
GET /api/v2/control/health
```

响应至少包含：

```json
{
  "controlId": "control_xxx",
  "organizationId": "org_xxx",
  "organizationName": "ABC Company",
  "protocolVersion": 2,
  "authMethods": ["PASSWORD", "INVITE_CODE", "DEVICE_CERTIFICATE"],
  "secure": true,
  "features": ["CHAT", "FILE", "NODE_MANAGEMENT"]
}
```

待办：

-  保留 `/api/v1/node/info` 兼容接口。
-  新增 `/api/v2/control/info`。
-  控制节点 ID 与普通节点 ID 分离。
-  握手信息不返回敏感配置。
-  验证 mDNS TXT 与 HTTP 握手一致性。
-  验证组织 ID、协议版本和证书指纹。

## 3.3 普通节点握手

新增：

```http
GET /meshx/v1/node/info
POST /meshx/v1/node/challenge
GET /meshx/v1/node/health
```

响应至少包含：

```json
{
  "nodeId": "node_xxx",
  "deviceId": "device_xxx",
  "organizationId": "org_xxx",
  "nodeType": "FULL_NODE",
  "platform": "WINDOWS",
  "protocolVersion": 1,
  "capabilities": [
    "DIRECT_MESSAGE",
    "DIRECT_FILE",
    "SYSTEM_METRICS"
  ]
}
```

------

# 4. P0：重新定义 Spring Boot 后端为 Control Server

当前 Spring Boot 暂时同时承担控制面与数据面。不要马上删除数据面，而是先建立清晰模块边界。

建议目录：

```text
src/main/java/com/lanchat/
├── control/
│   ├── identity/
│   ├── organization/
│   ├── rbac/
│   ├── device/
│   ├── policy/
│   ├── license/
│   └── audit/
├── relay/
│   ├── message/
│   ├── mailbox/
│   └── file/
├── compatibility/
│   └── v1/
└── shared/
```

待办：

-  将用户认证归入 `control.identity`。
-  将设备会话归入 `control.device`。
-  将权限判断归入 `control.rbac`。
-  将节点管理归入 `control.device`。
-  将管理员操作日志归入 `control.audit`。
-  将当前消息 WebSocket 标记为兼容 Relay。
-  将当前文件上传标记为兼容 Relay/File Store。
-  将 Redis 多实例能力保留在 Control Server 集群内部。
-  不把多个 Spring Boot 实例称为多个独立 MeshX Node。

------

# 5. P0：升级组织与权限模型

当前用户表不足以支撑多组织、分级管理员、设备授权和商业功能。

## 5.1 新增数据表

建议新增：

```text
organization
organization_member
role
permission
role_permission
member_role
device
device_credential
device_session
revocation_entry
organization_policy
audit_event
```

## 5.2 组织模型

`organization`：

```text
id
name
status
control_id
created_at
updated_at
```

`organization_member`：

```text
id
organization_id
user_id
status
department_id
joined_at
disabled_at
```

待办：

-  现有用户迁移到默认组织。
-  用户 ID 与组织成员 ID 分离。
-  用户名唯一约束调整为组织内唯一或全局登录标识唯一。
-  禁止通过修改客户端数据改变所属组织。

## 5.3 RBAC

初始权限建议：

```text
ORG_OWNER
ORG_ADMIN
SECURITY_ADMIN
DEVICE_ADMIN
DEPARTMENT_ADMIN
AUDITOR
MEMBER
```

权限代码示例：

```text
USER_CREATE
USER_DISABLE
USER_DELETE
DEVICE_APPROVE
DEVICE_REVOKE
ROLE_ASSIGN
POLICY_UPDATE
AUDIT_READ
LICENSE_READ
AI_TOOL_EXECUTE
AI_TOOL_APPROVE
```

待办：

-  删除通过用户名 `admin` 判断管理员的业务逻辑。
-  所有管理操作改为权限代码判断。
-  权限验证必须在服务端执行。
-  UI 隐藏按钮只能作为体验优化，不能作为安全边界。
-  高风险权限不能由部门管理员授予自己。
-  组织所有者转移需要二次确认和审计记录。
-  管理员不能修改或删除自己的关键审计记录。

------

# 6. P0：账号注册、删除与离线管理

## 6.1 注册模式

支持三种模式：

```text
CLOSED          关闭自助注册
INVITE_ONLY     邀请码/二维码注册
ADMIN_CREATED   管理员预创建账号
```

待办：

-  默认私有部署使用 `ADMIN_CREATED`。
-  管理员在局域网 Control Server 中创建账号。
-  支持一次性邀请码。
-  支持带有效期的加入二维码。
-  邀请内容只包含 Control 地址、组织 ID 和一次性代码。
-  邀请内容不得包含管理员凭据或永久访问令牌。
-  新设备加入必须经过设备注册流程。

## 6.2 没有公网时的管理

局域网中仍然运行 Control Server：

```text
Internet：不可用
LAN：可用
Control Server：可用
```

待办：

-  所有基础身份、组织、RBAC 和设备管理必须可在纯局域网完成。
-  不把云端 License Server 作为每次登录的强依赖。
-  Control Server 本地保存有效授权和组织策略。
-  管理员通过局域网 Web 管理后台操作。
-  支持管理员设备扫描 Control Node 后进入管理后台。
-  对完全隔离网络支持离线 License 文件导入。

## 6.3 用户禁用与删除

不要立即物理删除用户。

状态建议：

```text
ACTIVE
SUSPENDED
DISABLED
DELETED
```

待办：

-  “删除用户”首先转换为 `DISABLED`。
-  吊销用户所有 Device Session。
-  将用户和设备加入撤销列表。
-  生成签名撤销事件。
-  在线节点立即接收撤销事件。
-  离线节点在下一次连接 Control Server 时同步撤销列表。
-  节点拒绝来自已撤销身份的新消息。
-  历史消息保留发送者快照，避免删除用户后历史记录损坏。
-  物理删除需要单独的数据保留策略。

------

# 7. P0：实现 Desktop Full Node Runtime

桌面 Node Runtime 继续使用 Rust，不内嵌 Spring Boot 和 JVM。

## 7.1 重构桌面 Rust 模块

当前：

```text
apps/desktop/src-tauri/src/
├── discovery.rs
├── native_auth.rs
├── lifecycle.rs
└── ...
```

建议演进为：

```text
apps/desktop/src-tauri/src/
├── control/
│   ├── discovery.rs
│   ├── auth.rs
│   └── policy.rs
├── node/
│   ├── runtime.rs
│   ├── identity.rs
│   ├── discovery.rs
│   ├── listener.rs
│   ├── message.rs
│   ├── file.rs
│   ├── storage.rs
│   ├── outbox.rs
│   └── metrics.rs
├── protocol/
│   ├── envelope.rs
│   ├── message.rs
│   └── error.rs
└── platform/
```

待办：

-  将现有 `discovery.rs` 改名为 `control/discovery.rs`。
-  新增普通 Peer Node Discovery。
-  新增 `NodeRuntimeState`。
-  新增本地 HTTP/WebSocket Listener。
-  新增 Node Runtime 启动和停止生命周期。
-  窗口隐藏后 Node Runtime 继续运行。
-  应用退出时安全关闭 Listener 和数据库。
-  网络接口变化时重新发布 mDNS。
-  睡眠恢复后重新探测 Control 和 Peer。
-  切换组织时清理旧 Peer 目录。
-  禁止跨组织 Peer 自动连接。

## 7.2 Rust 依赖评估

建议评估：

```toml
axum
tokio
tower
rusqlite 或 sqlx
uuid 或 ulid
ed25519-dalek
sha2
rustls
```

待办：

-  选择 `rusqlite` 或 `sqlx`。
-  选择 HTTP/WebSocket Server。
-  选择密钥和签名库。
-  验证 macOS、Windows 和 Linux 构建兼容性。
-  检查新增依赖的 License 和维护状态。
-  限制 Listener 只绑定局域网接口或明确配置的接口。

## 7.3 本地数据库

建议：

```text
meshx-node.db
```

初始表：

```text
node_identity
peer_node
local_conversation
local_message
message_outbox
message_receipt
local_file
file_transfer_session
sync_cursor
control_snapshot
revocation_snapshot
```

待办：

-  引入数据库版本迁移机制。
-  数据库按组织隔离。
-  本地消息按账号和设备隔离。
-  明确退出和设备吊销时清理敏感数据。
-  文件正文和索引分离。
-  数据库损坏时提供安全恢复流程。
-  不继续使用 IndexedDB 作为桌面端最终权威消息库。
-  迁移期间允许 IndexedDB 与 SQLite 双写或一次性迁移。

------

# 8. P0：建立设备身份和节点信任

## 8.1 设备密钥

每台设备首次启动时生成：

```text
deviceId
nodeId
privateKey
publicKey
```

待办：

-  私钥由原生层生成。
-  私钥不得进入 Vue、localStorage 或 IndexedDB。
-  macOS 使用 Keychain。
-  Windows 使用 DPAPI/Credential Manager。
-  Android 使用 Android Keystore。
-  iOS 使用 Keychain/Secure Enclave。
-  Control Server 只保存公钥和设备证书。
-  设备重新安装后视为新设备。

## 8.2 设备注册流程

```text
客户端发现 Control
    ↓
用户登录或使用邀请码
    ↓
客户端提交设备公钥
    ↓
管理员或策略审批
    ↓
Control 签发设备证书
    ↓
设备加入组织
```

待办：

-  新增设备审批策略。
-  支持自动审批和人工审批。
-  支持设备名称、平台、版本和能力记录。
-  支持设备吊销。
-  支持设备证书轮换。
-  支持设备丢失后的远程停用。
-  所有节点握手验证组织签发的设备身份。

## 8.3 离线撤销策略

完全离线节点不可能立即知道管理员刚刚删除了用户。

需要明确安全边界：

-  定义撤销列表版本号。
-  Control Server 生成签名撤销快照。
-  节点连接 Control 时同步最新快照。
-  凭据设置最大离线有效期。
-  超过离线有效期后限制高风险操作。
-  普通聊天和高风险运维使用不同有效期。
-  在 UI 显示“权限快照可能已过期”。

------

# 9. P0：设计跨语言统一协议

新增独立目录：

```text
meshx-protocol/
├── schemas/
├── protobuf/
├── examples/
├── test-vectors/
└── CHANGELOG.md
```

## 9.1 通用信封

建议：

```json
{
  "protocolVersion": 1,
  "eventId": "evt_xxx",
  "eventType": "MESSAGE_SEND",
  "organizationId": "org_xxx",
  "senderUserId": "user_xxx",
  "senderDeviceId": "device_xxx",
  "senderNodeId": "node_xxx",
  "timestamp": "2026-07-24T00:00:00Z",
  "nonce": "random-value",
  "payload": {},
  "signature": "base64-signature"
}
```

待办：

-  定义字段长度限制。
-  定义时间戳容差。
-  定义重放保护。
-  定义错误码。
-  定义协议升级策略。
-  Java、Rust、Kotlin 和 Swift 使用相同测试向量。
-  CI 中运行跨语言协议兼容测试。
-  不直接复用 MyBatis Entity 作为通信 DTO。

------

# 10. P0：第一阶段 P2P 消息链路

不要第一步就实现所有群聊和完全分布式同步。

第一阶段仅支持：

```text
已登录同一组织
双方在线
一对一文本消息
桌面节点之间
```

## 10.1 消息字段

建议增加：

```text
messageId
clientMsgId
conversationId
senderUserId
senderDeviceId
receiverUserId
content
contentType
createdAt
deviceSequence
logicalTime
signature
```

待办：

-  使用 ULID/UUIDv7 生成跨节点消息 ID。
-  保留 `clientMsgId` 幂等语义。
-  每个发送设备维护单调递增 `deviceSequence`。
-  第一阶段不依赖服务端分配全局会话 sequence。
-  接收端验证设备证书和消息签名。
-  接收端先写 SQLite，再返回 ACK。
-  发送端收到 ACK 后更新本地状态。
-  超时消息进入 Outbox 重试。
-  相同 `messageId` 重复发送不重复入库。
-  双方分别保存消息副本。
-  Control Server 只记录必要的设备目录和策略。

## 10.2 连接策略

```text
优先：局域网 Peer 直连
降级：Control Relay
离线：Control Mailbox
```

待办：

-  检查目标用户的在线设备列表。
-  优先选择健康、低延迟的局域网节点。
-  直连失败后自动回退当前 WebSocket 服务端链路。
-  对用户展示实际连接路径。
-  记录直连失败原因。
-  不因 P2P 失败影响现有可靠消息能力。

## 10.3 暂不迁移的范围

-  群聊继续走服务端。
-  广播继续走服务端。
-  临时房间继续走服务端。
-  撤回、已读和阅后即焚暂时继续使用服务端语义。
-  完成一对一文本稳定后再迁移其他消息类型。

------

# 11. P1：离线信箱与消息同步

LAN-first 不等于任何时候都完全不需要服务器。

Control Server 可提供可选的离线信箱：

```text
发送方在线，接收方离线
    ↓
发送方写本地 Outbox
    ↓
提交加密消息信封到 Control Mailbox
    ↓
接收方重新上线
    ↓
拉取信封并本地入库
```

待办：

-  新增 `mailbox_message` 表。
-  信箱正文优先使用端到端加密信封。
-  Control Server 不负责修改正文。
-  定义信箱保留时长。
-  定义每个用户和设备的容量限制。
-  接收成功后发送确认并清理。
-  重复拉取不重复入库。
-  支持完全关闭离线信箱。
-  支持纯 P2P 组织策略。

## 11.1 独立节点同步

后续再实现：

-  节点交换消息摘要。
-  比较缺失消息 ID。
-  按会话和时间窗口拉取缺失消息。
-  使用墓碑记录撤回和删除事件。
-  定义成员变化的权限快照。
-  定义冲突策略。
-  定义设备时间错误处理。
-  不在第一版直接引入完整 CRDT。

------

# 12. P1：P2P 文件传输

复用当前分片上传的成熟语义，不直接复制 Spring Boot 实现代码。

## 12.1 Peer 文件协议

建议接口：

```http
POST /meshx/v1/files/sessions
GET  /meshx/v1/files/sessions/{id}
PUT  /meshx/v1/files/sessions/{id}/parts/{partNumber}
POST /meshx/v1/files/sessions/{id}/complete
DELETE /meshx/v1/files/sessions/{id}
```

待办：

-  保留当前完整 SHA-256 校验。
-  保留分片 SHA-256 校验。
-  保留分片幂等。
-  支持缺片查询。
-  支持应用重启后恢复。
-  接收端完成后重新校验完整文件。
-  接收端执行文件类型安全检查。
-  接收端确认后才创建聊天附件记录。
-  直传失败回退服务端分片上传。
-  记录文件实际保存在哪些设备。
-  区分本地文件引用和服务端文件引用。

## 12.2 文件可用性模型

文件消息增加：

```text
storageMode:
  PEER_ONLY
  CONTROL_STORED
  HYBRID

availableNodes:
  [nodeId...]

contentHash:
  sha256
```

待办：

-  明确 Peer-only 文件不会自动在所有设备可用。
-  支持接收设备重新提供文件。
-  支持用户主动备份到 Control Storage。
-  支持管理员限制文件大小和类型。
-  iOS 后台传输使用标准 HTTP/HTTPS。

------

# 13. P1：Android Mobile Node

当前 Android 已经有 Capacitor 和 Java NSD 插件，不应推倒重写。

建议路线：

```text
Vue + Capacitor
├── 共享 UI
├── Kotlin 原生插件
├── Room / SQLite
└── Mobile Node 生命周期
```

待办：

-  保留现有 `MeshXDiscoveryPlugin` 作为过渡实现。
-  后续新原生模块优先使用 Kotlin。
-  将当前扫描目标拆分为 Control Discovery 和 Peer Discovery。
-  引入 Room 本地数据库。
-  实现移动端本地消息缓存。
-  实现移动端 Outbox。
-  实现设备密钥和 Android Keystore。
-  实现前台状态下的 P2P 收发。
-  文件传输期间按需使用 Foreground Service。
-  应用进入后台后停止承诺持续监听。
-  恢复前台时重新发现节点和同步消息。
-  网络从 Wi-Fi 切换后重建 Peer 目录。
-  真实 Android 设备验证 NSD 和 MulticastLock。
-  完成 APK/AAB 签名回归。

Mobile Node 不承诺：

- 永远发布 mDNS；
- 锁屏后持续监听任意连接；
- 永久保持 WebSocket；
- 承担组织级消息中继。

------

# 14. P2：iOS Mobile Node

基于当前移动技术路线，优先采用：

```text
Vue + Capacitor iOS + Swift Plugin
```

而不是同时引入第二套 Tauri Mobile 工程。

待办：

-  初始化 Capacitor iOS 工程。
-  配置本地网络权限说明。
-  配置 Bonjour 服务类型。
-  使用 `NWBrowser` 浏览 Control 和 Peer。
-  使用 `NWConnection` 建立节点连接。
-  使用 Keychain 保存设备私钥。
-  使用本地 SQLite 存储消息。
-  使用后台 `URLSession` 处理文件上传和下载。
-  处理前后台切换。
-  应用恢复前台后执行补拉。
-  不把 iOS 设计为永远在线 Full Node。
-  完成真机和 TestFlight 验证。

------

# 15. P1：License 与商业功能控制

## 15.1 授权层级

建议支持：

```text
Product License
    ↓
Organization Entitlement
    ↓
Role Permission
    ↓
User / Device Assignment
```

三种概念必须分开：

- License：企业购买了什么。
- Permission：管理员允许谁使用什么。
- Capability：具体设备是否具备执行能力。

## 15.2 License 数据

新增：

```text
license_bundle
feature_entitlement
feature_assignment
license_audit
```

License Bundle 示例：

```json
{
  "licenseId": "lic_xxx",
  "organizationId": "org_xxx",
  "plan": "ENTERPRISE",
  "issuedAt": "2026-07-24T00:00:00Z",
  "expiresAt": "2027-07-24T00:00:00Z",
  "seatLimit": 100,
  "deviceLimit": 300,
  "features": {
    "AI_OPS": true,
    "AUDIT_EXPORT": true,
    "LARGE_FILE": true,
    "ADVANCED_RBAC": true
  },
  "signature": "vendor-signature"
}
```

待办：

-  MeshX Cloud 使用厂商私钥签发 License。
-  Control Server 只保存厂商公钥。
-  企业管理员不能修改 License 内容。
-  支持在线同步 License。
-  支持离线导入签名 License 文件。
-  节点可以本地验证 License 签名。
-  定义到期宽限期。
-  定义时钟回拨检测。
-  基础聊天和数据导出不能因授权到期被破坏。
-  高级功能到期后只禁用新增操作，不删除既有数据。
-  所有授权变更写入审计日志。

## 15.3 总管理员与企业管理员边界

平台总管理员：

```text
签发 License
管理产品版本
管理企业订阅
吊销商业授权
```

企业所有者：

```text
管理本组织管理员
管理组织策略
查看组织授权
```

企业管理员：

```text
创建和禁用成员
审批设备
分配已购买功能
```

部门管理员：

```text
管理授权范围内的部门成员
```

待办：

-  平台总管理员不能直接读取企业聊天正文。
-  企业管理员不能伪造 License。
-  部门管理员不能提权为组织所有者。
-  License 权限和组织 RBAC 使用不同签名及数据表。
-  高风险操作支持双人审批。
-  审计员只读，不能修改用户和权限。

------

# 16. P1：Server Manager

现有诊断、运行日志和节点发现可以整合成 Server Manager。

功能：

-  查看 Control Server 状态。
-  查看 MySQL、Redis 和 MinIO 状态。
-  查看服务端版本。
-  查看局域网接口和 mDNS 状态。
-  查看已注册设备。
-  查看 Full Node 和 Mobile Node。
-  查看节点能力。
-  查看节点最后上线时间。
-  审批新设备。
-  吊销设备。
-  导出脱敏诊断包。
-  查看 License 状态。
-  查看协议兼容性。
-  显示当前消息路径：P2P、Relay 或 Mailbox。

------

# 17. P2：AI Ops Agent

AI Agent 必须建立在可靠的 Node Tool API 之上，不能直接让模型执行任意命令。

## 17.1 第一批只读工具

```text
discover_nodes
get_node_health
get_cpu_usage
get_memory_usage
get_disk_usage
get_network_latency
get_service_status
read_recent_logs
```

待办：

-  Node Runtime 暴露结构化工具接口。
-  每个工具声明输入、输出和风险等级。
-  所有工具调用验证组织、角色和 License。
-  工具返回数据脱敏。
-  AI 先生成诊断，不直接执行修复。
-  所有工具调用写入审计日志。

## 17.2 后续可执行工具

```text
restart_service
restart_container
clear_application_cache
rotate_logs
run_health_check
```

待办：

-  默认使用 Dry Run。
-  执行前展示目标节点和具体动作。
-  高风险操作需要人工确认。
-  支持组织级工具白名单。
-  支持节点级工具白名单。
-  禁止模型直接提交任意 Shell。
-  命令参数必须经过结构化校验。
-  执行结果和失败原因写入审计日志。
-  提供紧急关闭 AI 执行能力的 Kill Switch。

------

# 18. P0：完成现有 V3 工程化收尾

在架构迁移之前，先让当前代码形成可靠发布基线。

## 18.1 桌面端

-  macOS 开发环境完整启动回归。
-  Windows 开发环境完整启动回归。
-  验证托盘隐藏和恢复。
-  验证单实例。
-  验证开机自启。
-  验证深链。
-  验证通知。
-  验证 mDNS 真机发现。
-  验证缓存节点和手动地址。
-  验证多个网卡。
-  验证睡眠恢复。
-  验证网络切换。
-  验证防火墙提示。
-  完成 macOS 签名、公证和 stapling。
-  完成 Windows 代码签名。
-  完成 Tauri Updater 真实升级。
-  验证升级失败回滚。

## 18.2 Android

-  配置 Android SDK。
-  真实设备运行 Secure Debug。
-  真实设备运行 LAN Debug。
-  验证 NSD 节点发现。
-  验证 Android 13+ 通知权限。
-  验证文件选择与保存。
-  验证前后台重连。
-  验证 HTTPS/WSS。
-  生成签名 AAB。
-  验证 Release 版本禁止明文 HTTP。

## 18.3 E2E

-  Presence 跨实例回归。
-  WebRTC 信令跨实例回归。
-  Redis 中断和恢复回归。
-  消息补拉分页回归。
-  分片上传中断恢复回归。
-  应用重启后上传恢复回归。
-  mDNS 真实多播回归。
-  节点切换后的缓存隔离回归。
-  Refresh Token 轮换回归。
-  设备吊销回归。

------

# 19. P1：可观测性

-  引入 Spring Boot Actuator。
-  暴露最小健康端点。
-  增加 Prometheus 指标。
-  记录消息 ACK 延迟。
-  记录 P2P 成功率。
-  记录 Relay 降级率。
-  记录 Mailbox 积压数量。
-  记录文件分片重试次数。
-  记录节点在线数量。
-  记录设备证书验证失败。
-  记录撤销列表版本。
-  增加 Trace ID 在 Control 和 Node 间传播。
-  建立基础 Grafana 看板。
-  增加异常告警。

------

# 20. 仓库结构演进建议

不要立即拆成多个仓库，先维持 Monorepo：

```text
meshx/
├── apps/
│   ├── desktop/
│   ├── mobile/
│   └── web/
├── control-server/
├── frontend/
├── crates/
│   ├── meshx-protocol/
│   ├── meshx-node-core/
│   └── meshx-crypto/
├── mobile-native/
│   ├── android/
│   └── ios/
├── protocol/
│   ├── schemas/
│   └── test-vectors/
├── sql/
├── tests/
│   ├── e2e/
│   ├── protocol/
│   └── interoperability/
└── docs/
    └── architecture/
```

迁移待办：

-  暂时保留当前根目录 Spring Boot 工程。
-  新建 Rust `crates` 目录后再迁移桌面公共代码。
-  前端目录暂不拆分，避免影响现有构建。
-  Android 工程暂不改为完全原生 UI。
-  等 Node Runtime 稳定后再考虑目录大调整。
-  每次迁移保持主分支可构建。

------

# 21. 明确不做的事情

当前阶段禁止同时启动以下工作：

-  不将完整 Spring Boot 和 JVM 打包进桌面客户端。
-  不把所有消息类型一次性改成 P2P。
-  不立即实现完整 CRDT。
-  不立即实现多 Control Server 双向数据库复制。
-  不同时重写桌面、Android 和 iOS。
-  不同时引入 E2EE、License 和 AI 自动执行。
-  不允许节点根据 UI Feature Flag 判断权限。
-  不在 mDNS TXT 中放令牌、邀请码或敏感数据。
-  不允许 AI Agent 执行任意 Shell。
-  不删除现有服务端可靠消息链路，直到 P2P 通过完整回归。

------

# 22. 推荐实施顺序

## Milestone 0：稳定当前版本

-  统一版本号。
-  完成桌面和 Android 真机回归。
-  完成正式安装包和更新验证。
-  补充现有 E2E。

退出条件：

```text
当前 server-centric 版本可以稳定安装、登录、聊天、传文件和升级。
```

## Milestone 1：控制面语义拆分

-  引入 `_meshx-control`。
-  新增 Control Info API。
-  区分 Control Node 和 Peer Node。
-  补充组织、设备和 RBAC 数据模型。
-  保留原消息链路。

退出条件：

```text
登录页明确发现的是 Control Server，不再把它和普通客户端节点混为一谈。
```

## Milestone 2：Desktop Full Node

-  Rust Node Runtime。
-  设备密钥。
-  SQLite。
-  Peer mDNS 发布和发现。
-  Node 握手。
-  一对一文本直连。
-  服务端 Relay 自动降级。

退出条件：

```text
两台桌面设备登录同一组织后，可以优先通过局域网直接发送文本消息；
关闭 P2P 后仍可使用原服务端链路。
```

## Milestone 3：P2P 文件

-  Peer 分片上传。
-  断点续传。
-  哈希复核。
-  本地文件索引。
-  服务端中转降级。

退出条件：

```text
两台桌面设备可直接传输文件，失败时自动使用现有服务端分片上传。
```

## Milestone 4：Mobile Node

-  Android Room 和设备密钥。
-  Android 前台 P2P。
-  后台恢复同步。
-  Capacitor iOS。
-  Swift Bonjour 和 Keychain。

退出条件：

```text
移动设备前台时可以直连，后台或锁屏后通过 Control Mailbox 恢复消息。
```

## Milestone 5：企业控制与商业化

-  完整 RBAC。
-  设备审批和吊销。
-  审计日志。
-  离线 License。
-  Feature Entitlement。
-  分级管理员。

退出条件：

```text
企业管理员可以在无公网环境管理账号、设备、权限和已购买功能，
但不能伪造厂商 License 或越权修改审计记录。
```

## Milestone 6：AI Ops

-  只读节点状态工具。
-  Agent 诊断。
-  审批式修复。
-  工具权限与审计。
-  License 控制。

退出条件：

```text
管理员可以用自然语言检查节点状态；
任何有副作用的操作都必须经过权限验证和明确确认。
```

------

# 23. 下一批建议创建的 GitHub Issues

1. `[Architecture] 区分 Control Node 与 Peer Node`
2. `[Protocol] 定义 MeshX Node Protocol V1`
3. `[Backend] 新增 Control Info V2 API`
4. `[Discovery] 迁移到 _meshx-control 与 _meshx-node`
5. `[Desktop] 创建 Rust Node Runtime 骨架`
6. `[Desktop] 引入 SQLite 本地存储`
7. `[Security] 设计设备密钥与注册协议`
8. `[RBAC] 设计组织和角色权限模型`
9. `[Message] 实现桌面端一对一 P2P 文本消息`
10. `[Message] 实现 Relay 自动降级`
11. `[File] 定义 Peer 分片传输协议`
12. `[Android] 拆分 Control Discovery 与 Peer Discovery`
13. `[Release] 完成 macOS 和 Windows 签名验证`
14. `[E2E] 增加 P2P 与 Relay 降级测试`
15. `[License] 设计离线签名授权模型`
16. `[AI Ops] 定义只读节点工具接口`

------

# 24. 第一阶段验收清单

架构调整的第一个可交付版本只要求：

-  登录页扫描的是 Control Server。
-  用户通过 Control Server 登录。
-  Control Server 返回组织、权限和设备凭据。
-  两个桌面客户端各自启动 Rust Full Node。
-  两个 Full Node 发布 `_meshx-node._tcp.local.`。
-  两个节点完成签名握手。
-  私聊文本消息优先直连。
-  消息在双方 SQLite 中落库。
-  直连失败自动回退现有 WebSocket Server。
-  原有 Web、群聊、文件和管理功能不受影响。
-  所有新能力默认可以通过 Feature Flag 关闭。

完成这些之后，MeshX 才真正从：

```text
支持局域网发现的中心化聊天系统
```

演进为：

```text
具有集中控制面和自治节点数据面的 LAN-first 协作基础设施
```