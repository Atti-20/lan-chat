# LanChat — LAN-first 私有即时协作系统

LanChat 面向校园、工厂、办公室、项目现场和应急环境，目标是在组织自有网络中提供可控、可靠的即时沟通能力。

当前代码已经完成第一阶段 P0 改造：**可靠消息协议、断网文本发件箱、重连补拉、统一会话模型、设备会话鉴权和私有化部署骨架**。WebRTC 局域网直传、临时房间、应急广播和多节点同步仍在后续路线图中，尚未标记为已实现。

## 当前完成状态

| 能力 | 状态 | 当前范围 |
|---|---|---|
| 账号、好友、私聊、群聊、设备管理 | 已实现 | Web 客户端与单节点服务端 |
| 统一会话模型 | 已实现 | 私聊 `private:min:max`、群聊 `group:id` |
| 可靠消息 | 已实现 | `clientMsgId`、事务后 ACK、会话序列号、幂等去重 |
| 断网文本消息 | 已实现 | 当前已认证标签页内：会话目录/消息缓存、IndexedDB 发件箱、恢复后自动补发 |
| 遗漏消息恢复 | 已实现 | 按会话位置增量补拉，单次 100 条、自动分页 |
| 连接诊断 | 部分实现 | 连接阶段、延迟、重连次数、待发/失败数量 |
| 文件安全 | 已实现基础版 | SHA-256、100 MB、会话权限、显式文件授权、短期预览链接 |
| 私有部署 | 已实现基础版 | Dockerfile、Compose、MySQL、Redis、本地文件卷 |
| 局域网节点自动发现 | 规划中 | 尚无 mDNS/桌面端 Agent |
| WebRTC 文件直传与中转降级 | 规划中 | 尚未实现 |
| 临时协作房间、应急广播 | 规划中 | 尚未实现 |
| 分片上传、断点续传、MinIO | 规划中 | 当前仍为单请求本地文件存储 |
| 多节点同步、跨实例消息路由 | 规划中 | 当前为单 Spring Boot 节点 |
| 端到端加密、本地 AI | 暂缓 | 不属于当前版本 |

更完整的需求到代码映射见 [实施状态-LAN-first-V2.0.md](实施状态-LAN-first-V2.0.md)。

## 可靠消息主链路

```text
本地创建消息并写入 IndexedDB
  → 生成 clientMsgId，显示“等待网络/发送中”
  → WebSocket CHAT_SEND
  → 服务端校验会话权限与附件权限
  → 同一事务内分配 conversation sequence、保存消息、更新会话摘要
  → CHAT_ACK（只有事务成功后才返回）
  → 发送端移出发件箱，接收端收到 CHAT_DELIVER
  → 断线重连后发送 SYNC_REQUEST，按最后 sequence 补齐缺口
```

数据库唯一约束共同保证重试安全：

```sql
UNIQUE (from_user_id, client_msg_id)
UNIQUE (conversation_id, sequence)
```

协议详情见 [可靠消息协议-V1.md](可靠消息协议-V1.md)。

## 技术栈

| 层面 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.5、Spring Security |
| 实时通信 | Spring WebSocket、自定义 V1 JSON 信封 |
| 数据 | MySQL 8、MyBatis Plus 3.5、Redis |
| Web 前端 | Vue 3.5、TypeScript 5.9、Vite 8、Composition API |
| 本地离线 | IndexedDB |
| 认证 | JWT Access Token、轮换 Refresh Token、HttpOnly Cookie |
| 部署 | 多阶段 Dockerfile、Docker Compose |

## 安全与权限边界

- WebSocket 地址固定为 `/ws/chat`，访问令牌通过连接后的 `AUTH` 事件提交，不进入 URL 或代理访问日志。
- Access Token 校验固定算法、`iss`、`aud`、有效期和设备会话状态。
- Refresh Token 仅放入 `HttpOnly + SameSite=Strict` Cookie，并在刷新时轮换；退出时同时吊销设备会话。
- 浏览器仅在 `sessionStorage` 保存短期 Access Token，不把 Refresh Token 暴露给 JavaScript。
- 普通令牌过期不会删除离线发件箱；本地缓存按用户属主隔离。明确退出或设备强制下线会清理本地数据。
- 历史、同步、发送、撤回、焚毁、文件上传和预览均在服务端重新校验权限。
- 文件哈希存在不代表有访问权。完整上传并通过服务端 SHA-256 校验后才创建显式授权；单纯哈希探测不会获得他人的物理文件。
- 默认拒绝网页与可执行格式；非受控媒体类型以 `application/octet-stream` 强制下载，避免同源上传内容被浏览器执行。
- REST 响应与日志使用 `X-Request-ID` 关联，在线用户事件不序列化密码或设备令牌。

## 项目结构

```text
frontend/                              Vue 3 Web 客户端
  src/composables/useWebSocket.ts      认证、心跳、重连和连接状态
  src/composables/useOutbox.ts         离线发件箱状态
  src/services/localChatDb.ts          IndexedDB 消息、位置和发件箱
src/main/java/com/lanchat/
  websocket/ChatWebSocketHandler.java  V1 实时协议入口
  service/impl/ChatMessageServiceImpl  可靠消息事务与历史查询
  service/impl/ConversationServiceImpl 统一会话、序列号和成员位置
  service/impl/FileServiceImpl         文件存储、授权和签名预览
sql/
  init.sql                             新环境完整结构
  migration-v2.0-reliable-messaging.sql 已有 V1 数据升级脚本
compose.yaml                           单节点私有部署
```

## 快速启动

### 方式一：Docker Compose

需要 Docker Compose v2。首次启动会创建 MySQL、Redis、LanChat 服务和持久化卷：

```bash
export JWT_SECRET='replace-with-at-least-32-random-characters'
export DB_PASSWORD='replace-with-a-strong-database-password'
docker compose up --build
```

浏览器访问 `http://localhost:8080/app/`。

若通过局域网 IP 访问，例如 `http://192.168.1.20:8080`，还需允许对应浏览器来源：

```bash
export WEBSOCKET_ALLOWED_ORIGINS='http://192.168.1.20:8080'
docker compose up --build
```

HTTPS 部署时同时设置 `AUTH_COOKIE_SECURE=true`，并把实际 HTTPS Origin 加入允许列表。

### 方式二：本地开发

环境要求：JDK 17、Node.js 20.19+、MySQL 8、Redis。

1. 初始化数据库：

```bash
mysql -u root -p < sql/init.sql
```

2. 构建前端：

```bash
cd frontend
npm ci
npm run build
cd ..
```

3. 设置环境变量并启动：

```bash
export DB_URL='jdbc:mysql://localhost:3306/lan_chat?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USERNAME='root'
export DB_PASSWORD='your_password'
export REDIS_HOST='localhost'
export JWT_SECRET='replace-with-at-least-32-random-characters'
./mvnw spring-boot:run
```

Vite 构建产物写入 `src/main/resources/static/app/`，由 Spring Boot 同源托管。

### 已有 V1 数据库升级

执行前先备份数据库，然后运行：

```bash
mysql -u root -p lan_chat < sql/migration-v2.0-reliable-messaging.sql
```

迁移会创建统一会话、会话成员和文件授权表，回填旧消息的 `conversation_id` 与 `sequence`，并增加幂等唯一索引。不要对已有数据执行 `sql/init.sql`，因为初始化脚本会重建表。

### 本地演示账号

`sql/init.sql` 只为开发环境写入以下账号，统一密码为 `LanChat123!`：

| 用户名 | 昵称 |
|---|---|
| `admin` | 管理员 |
| `alice` | 爱丽丝 |
| `bob` | 鲍勃 |

生产环境必须删除演示账号、更换 JWT 密钥，并启用 HTTPS 安全 Cookie。

## 常用接口

所有 REST 接口使用 `/api/v1` 前缀，响应包含 `code`、`msg`、`data` 和 `requestId`。

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/auth/login` | 登录并设置 Refresh Cookie |
| POST | `/api/v1/auth/refresh` | 轮换 Refresh Token，获取新 Access Token |
| POST | `/api/v1/auth/logout` | 吊销当前设备会话 |
| GET | `/api/v1/chat/history` | 按 `conversationId` 与 `beforeSequence` 游标查询 |
| PUT | `/api/v1/chat/conversation/read` | 更新会话最后已读序列 |
| POST | `/api/v1/file/upload` | 在指定会话权限下上传附件 |
| POST | `/api/v1/file/avatar` | 上传头像图片 |
| POST | `/api/v1/file/preview-url` | 生成 10 分钟签名预览地址 |

旧的私聊/群聊历史接口暂时保留兼容，新前端统一使用 `/api/v1/chat/history`。

## 验证

```bash
./mvnw test
cd frontend && npm run typecheck && npm run build
```

当前自动化覆盖会话 ID、消息幂等、序列分配、WebSocket 连接后认证、敏感字段不外泄、Refresh Cookie 轮换、文件哈希权限边界、控制器权限与应用上下文。

## 配置项

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DB_URL` | 本机 `lan_chat` | MySQL JDBC 地址 |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / `root` | 数据库认证；生产必须覆盖 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 节点 |
| `JWT_SECRET` | 本地开发密钥 | 生产必须覆盖 |
| `JWT_ISSUER` / `JWT_AUDIENCE` | `lanchat-node` / `lanchat-client` | JWT 约束 |
| `WEBSOCKET_ALLOWED_ORIGINS` | localhost、127.0.0.1 | WebSocket Origin 白名单 |
| `AUTH_COOKIE_SECURE` | `false` | HTTPS 部署设为 `true` |
| `FILE_STORAGE_PATH` | `./uploads/` | 私有文件目录 |
| `TUNNEL_ENABLED` | `false` | 默认不启动外部隧道 |

## 文档说明

- 本轮代码重构依据用户提供的《需求分析-LAN-first-V2.0》和《功能分析-LAN-first-V2.0》。两份文档描述目标状态，不等于全部完成。
- 仓库内 [需求分析.md](需求分析.md) 与 [功能分析.md](功能分析.md) 是 V1.0 历史稿，仅用于版本对照。
- 实际完成边界以本 README 和 [实施状态-LAN-first-V2.0.md](实施状态-LAN-first-V2.0.md) 为准。

## License

MIT
