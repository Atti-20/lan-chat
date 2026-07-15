# LanChat — LAN-first 私有即时协作系统

LanChat 面向校园、工厂、办公室、项目现场和应急环境，目标是在组织自有网络中提供可控、可靠的即时沟通能力。

当前代码已完成可靠消息、断网文本发件箱、重连补拉、统一会话模型和设备会话鉴权，并在 V2.1 落地了**文件安全、连接诊断、单节点私有部署和服务端 mDNS 局域网节点发现**；管理员控制台还提供账号管理、连接诊断和运行日志查看/导出。WebRTC 局域网直传、临时房间、应急广播和多节点数据同步仍在后续路线图中。

## 当前完成状态

| 能力 | 状态 | 当前范围 |
|---|---|---|
| 账号、好友、私聊、群聊、设备管理 | 已实现 | Web 客户端与单节点服务端 |
| 统一会话模型 | 已实现 | 私聊 `private:min:max`、群聊 `group:id` |
| 可靠消息 | 已实现 | `clientMsgId`、事务后 ACK、会话序列号、幂等去重 |
| 断网文本消息 | 已实现 | 当前已认证标签页内：会话目录/消息缓存、IndexedDB 发件箱、恢复后自动补发 |
| 遗漏消息恢复 | 已实现 | 按会话位置增量补拉，单次 100 条、自动分页 |
| 连接诊断 | 已实现 | 用户连接路径、心跳/同步/重连/发件箱诊断；管理员依赖、存储、JVM 与 WebSocket 诊断 |
| 管理员运行日志 | 已实现 | 控制台与滚动文件双输出、级别/关键字筛选、错误说明、堆栈查看和当前日志导出 |
| 文件安全 | 已实现 | 扩展名、MIME、文件头三重校验，私有存储、权限复核、签名预览和访问审计 |
| 私有部署 | 已实现（单节点） | 强配置启动校验、首次管理员引导、关闭自助注册、内网依赖隔离和容器健康检查 |
| 局域网节点自动发现 | 已实现（服务端阶段） | JmDNS/DNS-SD 广播与发现、节点握手接口、登录页节点列表与安全切换 |
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
- 上传时同时核对扩展名、客户端 MIME 和服务端文件头/容器结构；默认拒绝网页、脚本和可执行格式，并限制头像类型、大小和图片像素数。
- 文件先写入暂存区并校验磁盘余量、内容与哈希，再原子移动到私有目录；签名地址每次访问都会重新检查当前权限。
- 文件响应带 `nosniff`、CSP、CORP 和私有缓存策略；预览、下载与拒绝记录写入 `file_access_log`，日志不保存签名令牌或正文。
- REST 响应与日志使用 `X-Request-ID` 关联，在线用户事件不序列化密码或设备令牌。
- 运行日志查询与导出仅对 `admin` 开放；服务端只读取配置中的固定日志文件，前端不能提交文件路径，页面日志数量与扫描字节数均有上限。

## V2.1 四项能力范围

### 文件安全

- 允许列表覆盖常用图片、文档、压缩包、音视频和 UTF-8 文本；高风险后缀在读取正文前即拒绝。
- JPEG、PNG、GIF、WebP、PDF、Office、ZIP/RAR/7z、媒体与文本均由服务端识别，Office Open XML 还会校验 ZIP 内部目录结构。
- 100 MB 通用文件与 5 MB 头像分别限流；路径归一化、随机物理文件名和最小剩余空间防止目录穿越与磁盘写满。
- 上传者/会话成员授权、10 分钟签名地址、访问时权限复核和审计日志组成完整访问链路。

当前尚未包含杀毒引擎、内容审核、分片上传、断点续传和 MinIO，这些仍属于后续增强。

### 连接诊断

聊天页连接状态条可打开诊断面板，显示当前节点、连接路径（本机/局域网/远程）、WebSocket 阶段、延迟、最后心跳与同步时间、重连次数及待发/失败任务，并提供重连、重试、清理本地缓存和导出脱敏诊断信息。

`admin` 账号还能查看 MySQL/Redis 探测延迟、文件存储容量、JVM 堆与线程、在线连接、消息 ACK/失败计数和最近连接生命周期；依赖地址、令牌与密码不会返回前端。

### 私有部署

- `private` Spring Profile 默认关闭自助注册，由首次启动创建的 `admin` 在管理控制台创建普通账号。
- 私有模式会拒绝开发 JWT 密钥、过短/占位数据库与 Redis 密钥、弱管理员密码、公开静态目录文件存储，以及 `LOCAL_INDEPENDENT` 模式下的公网隧道。
- 初始化脚本不再内置任何账号。`LANCHAT_BOOTSTRAP_ADMIN_PASSWORD` 仅在数据库尚无 `admin` 时生效，重启不会覆盖已有强密码；若检测到历史演示口令 `LanChat123!`，则轮换管理员密码、停用其他演示账号并吊销其旧设备会话。
- Compose 仅发布应用端口；MySQL 和 Redis 位于内部数据网络，Redis 启用认证，应用容器以非 root、只读根文件系统、无 Linux capabilities 运行。

### 局域网节点自动发现

服务端使用 `_lanchat._tcp.local.` DNS-SD 服务广播节点 ID、名称、组织、版本、运行模式、协议和 Web 地址，并在每个可用的 IPv4 多播网卡上发现同类节点。`/api/v1/node/info` 提供脱敏握手信息，`/api/v1/node/discoveries` 返回去重且会过期的节点列表；登录页可以扫描并切换到发现的节点。

浏览器自身不能直接监听 mDNS，因此第一次打开 Web 客户端仍需要一个已知种子地址；访问任一节点后，页面才能展示该服务端发现的其他节点。Docker bridge 通常不会把 mDNS 多播送到物理局域网，自动发现建议使用原生 JVM 进程，或由部署环境显式提供 host 网络/多播转发。

## 管理员运行日志

- Spring Boot 输出同时保留在控制台和 UTF-8 日志文件中；默认活动文件为 `./logs/lan-chat.log`，单文件 20 MB、保留 30 天、归档总量上限 1 GB。Cloudflare 子进程输出也会转入同一 SLF4J 链路。
- Compose 使用 `/app/logs` 独立持久卷，适配应用容器的只读根文件系统，容器重建后日志仍保留。
- `admin` 在“管理 → 运行日志”中查看最近记录；页面每 10 秒更新，支持级别和关键字筛选，并突出显示错误、附加常见原因与处理方向，错误堆栈按需展开。
- 页面最多返回 1000 条并默认只扫描活动文件末尾 4 MB，避免大日志占满内存；“导出完整日志”以流式响应下载当前活动文件，历史轮转文件仍保留在日志目录/卷中。
- 日志正文以纯文本插值展示，不执行其中的 HTML。导出的运行日志可能包含内部类名、节点状态和请求 ID，应按运维资料妥善保管。

## 项目结构

```text
frontend/                              Vue 3 Web 客户端
  src/composables/useWebSocket.ts      认证、心跳、重连和连接状态
  src/composables/useOutbox.ts         离线发件箱状态
  src/composables/useDiagnostics.ts    用户/管理员连接诊断
  src/composables/useRuntimeLogs.ts    管理员运行日志查询与导出状态
  src/composables/useNodeDiscovery.ts  节点扫描、去重和切换
  src/services/localChatDb.ts          IndexedDB 消息、位置和发件箱
src/main/java/com/lanchat/
  websocket/ChatWebSocketHandler.java  V1 实时协议入口
  service/impl/ChatMessageServiceImpl  可靠消息事务与历史查询
  service/impl/ConversationServiceImpl 统一会话、序列号和成员位置
  service/impl/FileServiceImpl         文件存储、授权和签名预览
  service/NodeDiagnosticsService       公开握手与管理员健康诊断
  service/RuntimeLogService            固定日志文件尾部解析、错误说明与导出
  service/LanNodeDiscoveryService      mDNS 广播和节点发现
sql/
  init.sql                             新环境完整结构
  migration-v2.0-reliable-messaging.sql 已有 V1 数据升级脚本
  migration-v2.1-security-diagnostics.sql 文件访问审计升级脚本
compose.yaml                           单节点私有部署
```

## 快速启动

### 方式一：Docker Compose

需要 Docker Compose v2。私有部署不提供默认密钥或默认账号，先复制示例配置并为五个空白项生成互不相同的随机值：

```bash
cp .env.example .env
chmod 600 .env
# 使用密码管理器，或通过 openssl rand -base64 32 生成随机值后写入 .env
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

`DB_ROOT_PASSWORD`、`DB_PASSWORD`、`REDIS_PASSWORD` 至少 12 位，`JWT_SECRET` 至少 32 位；首次管理员密码至少 12 位并同时包含大小写字母、数字和符号。浏览器访问 `http://localhost:8080/app/`，使用用户名 `admin` 和 `.env` 中的首次管理员密码登录。首次登录并修改管理员密码后，可以清空 `.env` 中的 `LANCHAT_BOOTSTRAP_ADMIN_PASSWORD`；已有 `admin` 不会因该变量为空而被重建或覆盖。

若通过局域网 IP 访问，例如 `http://192.168.1.20:8080`，还需允许对应浏览器来源：

```bash
export WEBSOCKET_ALLOWED_ORIGINS='http://192.168.1.20:8080'
docker compose up --build
```

HTTPS 部署时同时设置 `AUTH_COOKIE_SECURE=true`、`LANCHAT_NODE_SECURE=true`，并把实际 HTTPS Origin 加入允许列表。

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

随后执行 V2.1 文件访问审计迁移：

```bash
mysql -u root -p lan_chat < sql/migration-v2.1-security-diagnostics.sql
```

V2.0 迁移会创建统一会话、会话成员和文件授权表，回填旧消息的 `conversation_id` 与 `sequence`，并增加幂等唯一索引；V2.1 迁移可重复执行。不要对已有数据执行 `sql/init.sql`，因为初始化脚本会重建表。

若沿用旧版 Compose 的 `mysql-data` 卷，镜像不会在已有数据库中自动创建新的 `lanchat` 应用用户。切换新版 Compose 前应使用数据库管理员账号创建/更新该用户，以 `.env` 中同一 `DB_PASSWORD` 授予 `lan_chat` 的运行时读写权限；也可以备份数据后使用全新卷初始化。旧卷的 `DB_ROOT_PASSWORD` 同样不会被环境变量自动重置。

### 本地演示账号

`sql/init.sql` 不再写入任何默认账号。仅在隔离的开发数据库中需要演示数据时，手动执行：

```bash
mysql -u root -p lan_chat < sql/demo-data.sql
```

该脚本写入以下账号，统一演示密码为 `LanChat123!`：

| 用户名 | 昵称 |
|---|---|
| `admin` | 管理员 |
| `alice` | 爱丽丝 |
| `bob` | 鲍勃 |

不要在私有或生产部署执行 `sql/demo-data.sql`。

## 常用接口

所有 REST 接口使用 `/api/v1` 前缀，响应包含 `code`、`msg`、`data` 和 `requestId`。

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/auth/login` | 登录并设置 Refresh Cookie |
| POST | `/api/v1/auth/refresh` | 轮换 Refresh Token，获取新 Access Token |
| POST | `/api/v1/auth/logout` | 吊销当前设备会话 |
| GET | `/api/v1/node/info` | 登录前节点握手、能力与注册策略 |
| GET | `/api/v1/node/health` | 容器与负载均衡存活探针 |
| GET | `/api/v1/node/discoveries` | 当前节点发现的局域网节点列表 |
| GET | `/api/v1/chat/history` | 按 `conversationId` 与 `beforeSequence` 游标查询 |
| PUT | `/api/v1/chat/conversation/read` | 更新会话最后已读序列 |
| POST | `/api/v1/file/upload` | 在指定会话权限下上传附件 |
| POST | `/api/v1/file/avatar` | 上传头像图片 |
| POST | `/api/v1/file/preview-url` | 生成 10 分钟签名预览地址 |
| POST | `/api/v1/admin/users` | 管理员创建普通账号 |
| GET | `/api/v1/admin/diagnostics` | 管理员依赖、存储、JVM 与连接诊断 |
| GET | `/api/v1/admin/logs` | 管理员按级别、关键字读取受限日志尾部 |
| GET | `/api/v1/admin/logs/export` | 管理员流式导出当前活动日志文件 |

旧的私聊/群聊历史接口暂时保留兼容，新前端统一使用 `/api/v1/chat/history`。

## 验证

```bash
./mvnw test
cd frontend && npm run typecheck && npm run build
```

当前后端共 53 项测试，覆盖会话 ID、消息幂等、序列分配、WebSocket 连接后认证、Refresh Cookie 轮换、文件内容识别（含 WebP 解码）与权限撤销、私有部署强配置/管理员引导、诊断信息脱敏、运行日志解析/说明/权限、mDNS 节点解析、控制器权限和应用上下文。Compose 还通过带强测试变量的 `docker compose config --quiet` 校验。

### 原生启动 mDNS 节点发现

在所有参与发现的原生 JVM 节点上启用，并填写其他设备能够访问的局域网地址：

```bash
export LANCHAT_DISCOVERY_ENABLED=true
export LANCHAT_ADVERTISED_HOST='192.168.1.20'
export LANCHAT_ADVERTISED_PORT=8080
./mvnw spring-boot:run
```

防火墙需允许节点 TCP 端口，并允许 UDP 5353 多播。节点 ID 最好通过 `LANCHAT_NODE_ID` 固定，避免主机名变更后被识别为新节点。

## 配置项

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DB_URL` | 本机 `lan_chat` | MySQL JDBC 地址 |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / `root` | 数据库认证；生产必须覆盖 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 节点 |
| `REDIS_PASSWORD` | 空 | Redis 认证；Compose 私有部署必填 |
| `JWT_SECRET` | 本地开发密钥 | 生产必须覆盖 |
| `JWT_ISSUER` / `JWT_AUDIENCE` | `lanchat-node` / `lanchat-client` | JWT 约束 |
| `WEBSOCKET_ALLOWED_ORIGINS` | localhost、127.0.0.1 | WebSocket Origin 白名单 |
| `AUTH_COOKIE_SECURE` | `false` | HTTPS 部署设为 `true` |
| `FILE_STORAGE_PATH` | `./uploads/` | 私有文件目录 |
| `FILE_MIN_FREE_SPACE` | 50 MB | 上传完成后必须保留的最小磁盘空间 |
| `FILE_MAX_IMAGE_PIXELS` | 4000 万 | 图片解码前的最大像素数 |
| `LANCHAT_LOG_FILE` | `./logs/lan-chat.log` | 当前进程活动日志文件；Compose 固定为 `/app/logs/lan-chat.log` |
| `LANCHAT_LOG_MAX_FILE_SIZE` / `LANCHAT_LOG_MAX_HISTORY` | `20MB` / `30` | 单个日志文件上限和保留天数 |
| `LANCHAT_LOG_TOTAL_SIZE_CAP` | `1GB` | 所有日志归档的总容量上限 |
| `LANCHAT_RUNTIME_LOG_MAX_READ_BYTES` | `4194304` | 管理页面单次从活动日志末尾扫描的最大字节数，服务端最多接受 16 MB |
| `TUNNEL_ENABLED` | `false` | 默认不启动外部隧道 |
| `LANCHAT_PRIVATE_DEPLOYMENT` | `false` | 启用私有部署启动安全校验 |
| `LANCHAT_SELF_REGISTRATION_ENABLED` | `true`；private profile 为 `false` | 是否允许登录页自助注册；私有模式必须为 `false` |
| `LANCHAT_BOOTSTRAP_ADMIN_PASSWORD` | 空 | 新私有数据库首次创建 `admin` 的强密码 |
| `LANCHAT_NODE_ID` / `LANCHAT_NODE_NAME` | 自动生成 / `LanChat Node` | 稳定节点标识与显示名称 |
| `LANCHAT_ORGANIZATION_NAME` / `LANCHAT_MODE` | 本地组织 / `LAN_FIRST` | 节点组织和运行模式 |
| `LANCHAT_DISCOVERY_ENABLED` | `false` | 启用服务端 mDNS 广播与发现 |
| `LANCHAT_DISCOVERY_INTERFACE_ADDRESS` | 空 | 可选：只在指定本机 IPv4 地址对应的网卡上启用发现 |
| `LANCHAT_ADVERTISED_HOST` / `LANCHAT_ADVERTISED_PORT` | 当前网卡 / `8080` | 对其他局域网节点公布的访问地址 |
| `LANCHAT_NODE_SECURE` | `false` | 公布的节点地址是否使用 HTTPS；反向代理终止 TLS 时需显式设为 `true` |

## 文档说明

- 本轮代码重构依据用户提供的《需求分析-LAN-first-V2.0》和《功能分析-LAN-first-V2.0》。两份文档描述目标状态，不等于全部完成。
- 仓库内 [需求分析.md](需求分析.md) 与 [功能分析.md](功能分析.md) 是 V1.0 历史稿，仅用于版本对照。
- 实际完成边界以本 README 和 [实施状态-LAN-first-V2.0.md](实施状态-LAN-first-V2.0.md) 为准。

## License

MIT
