# LanChat — LAN-first 私有即时协作系统

LanChat 面向校园、工厂、办公室、项目现场和应急环境，目标是在组织自有网络中提供可控、可靠的即时沟通能力。

当前开发版本为 **V3.0.0**，正式发布基线仍为 V2.3.0。V3.0 继承了可靠消息、断网文本发件箱、重连补拉、文件安全、WebRTC、临时房间、应急广播、分片上传、LOCAL/MinIO 私有对象存储及多实例实时路由，并在本轮加入 macOS Tauri 桌面端 P0、原生局域网发现和 CI/Release/E2E 代码。尚未生成经外部证书签名、公证并正式发布的 V3.0 安装包。

V2.3 的多实例能力属于同一逻辑 LanChat 节点的横向扩展：实例共享持久化数据和对象存储，并通过 Redis 分发实时事件。它不等同于两个独立数据库节点之间的双向复制；独立节点的数据同步、冲突检测和冲突合并仍在后续路线图中。

## 当前完成状态

| 能力 | 状态 | 当前范围 |
|---|---|---|
| 账号、好友、私聊、群聊、设备管理 | 已实现 | Web 客户端与共享数据库的单实例/多实例服务端 |
| 统一会话模型 | 已实现 | 私聊 `private:min:max`、群聊 `group:id` |
| 可靠消息 | 已实现 | `clientMsgId`、事务后 ACK、会话序列号、幂等去重 |
| 断网文本消息 | 已实现 | 当前已认证标签页内：会话目录/消息缓存、IndexedDB 发件箱、恢复后自动补发 |
| 遗漏消息恢复 | 已实现 | 按会话位置增量补拉，单次 100 条、自动分页 |
| 连接诊断 | 已实现 | 用户连接路径、心跳/同步/重连/发件箱诊断；管理员依赖、存储、JVM 与 WebSocket 诊断 |
| 管理员运行日志 | 已实现 | 控制台与滚动文件双输出、级别/关键字筛选、错误说明、堆栈查看和当前日志导出 |
| 文件安全 | 已实现 | 扩展名、MIME、文件头三重校验，私有存储、权限复核、签名预览和访问审计 |
| 私有部署 | 已实现 | 强配置启动校验、首次管理员引导、关闭自助注册、内网依赖隔离、容器健康检查和多实例共享依赖部署 |
| 局域网节点自动发现 | 已实现 | 服务端 JmDNS 广播/发现；Tauri 原生 mDNS 浏览、握手、健康探测、缓存和手动地址回退 |
| WebRTC 文件直传与中转降级 | 已实现 | 节点中转为默认；可在本机开启 WebRTC DataChannel 直传，协商失败、超时或不支持时自动切换服务端中转；信令可跨应用实例路由 |
| 临时协作房间 | 已实现 | 房间码、成员与角色、有效期、上传/下载/转发策略和冻结/归档/销毁生命周期 |
| 应急广播 | 已实现 | 管理员授权发布、全体或好友复选、在线推送/离线补拉、回执统计和保留历史的撤销 |
| 分片上传、断点续传、MinIO | 已实现 | 上传会话、分片幂等、缺片查询、恢复上传、最终哈希与内容复核；LOCAL/MinIO 可切换；对象清理持久化重试 |
| 多实例实时路由与全局 Presence | 已实现 | 共享 MySQL、Redis、MinIO 的单逻辑节点；跨实例消息、业务通知、WebRTC 信令和在线状态 |
| macOS 桌面端 P0 | 代码已实现，待安装回归 | Tauri 壳、托盘、通知、单实例、开机自启、受限深链、动态节点与原生 Refresh Cookie Jar |
| Android 客户端 P1 | 工程已实现，待设备/签名回归 | Capacitor 8、共享 Vue UI、手动节点握手、前后台重连、文件选择上传、本地通知、HTTPS/WSS 默认与受控 LAN Debug HTTP 变体 |
| CI、Release 与 E2E | 流水线代码已实现，待外部验证 | 通用 CI、三平台无签名 smoke build、签名草稿 Release、服务端镜像、双实例与断网测试 |
| 独立节点数据同步 | 规划中 | 尚未实现独立数据库节点间双向复制、权限传播和冲突合并 |
| 端到端加密、本地 AI | 暂缓 | 不属于当前版本 |

V2.x 的需求到代码映射见 [实施状态-LAN-first-V2.0.md](实施状态-LAN-first-V2.0.md)；V3.0 的精确完成边界、外部依赖和验收证据见 [实施状态-V3.0.md](PRD/v3/docs/v3/实施状态-V3.0.md)。

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
| 实时通信 | Spring WebSocket、自定义 V1 JSON 信封、Redis 跨实例事件总线 |
| 数据 | MySQL 8、MyBatis Plus 3.5、Redis |
| 文件存储 | 本地私有目录或 MinIO/S3 兼容私有对象存储 |
| Web 前端 | Vue 3.5、TypeScript 5.9、Vite 8、Composition API |
| 桌面端 | Tauri 2、Rust、系统 WebView、`mdns-sd` |
| Android 端 | Capacitor 8、Android WebView、API 24+ |
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
- 文件分片先写入私有暂存区，合并后重新校验总大小、SHA-256、扩展名、MIME 和文件内容，再写入当前启用的 LOCAL 或 MinIO 私有存储；签名地址每次访问都会重新检查当前权限。
- 文件响应带 `nosniff`、CSP、CORP 和私有缓存策略；预览、下载与拒绝记录写入 `file_access_log`，日志不保存签名令牌或正文。
- REST 响应与日志使用 `X-Request-ID` 关联，在线用户事件不序列化密码或设备令牌。
- 运行日志查询与导出仅对 `admin` 开放；服务端只读取配置中的固定日志文件，前端不能提交文件路径，页面日志数量与扫描字节数均有上限。
- 广播发布权限由管理员按账号授予；普通发布者只能选择自己的有效好友，管理员不进入回执人数且只有管理员可以撤销广播。
- 上传会话只能由创建者在原会话权限仍有效时查询、续传、完成或取消；重复分片必须具有相同大小和哈希，不能借断点续传绕过最终内容复核。
- 跨实例事件不携带访问令牌或完整用户对象。Redis Pub/Sub 是实时快路径，MySQL 中的消息和会话序列仍是可靠恢复的最终依据。

## V2.1 四项能力范围

### 文件安全

- 允许列表覆盖常用图片、文档、压缩包、音视频和 UTF-8 文本；高风险后缀在读取正文前即拒绝。
- JPEG、PNG、GIF、WebP、PDF、Office、ZIP/RAR/7z、媒体与文本均由服务端识别，Office Open XML 还会校验 ZIP 内部目录结构。
- 100 MB 通用文件与 5 MB 头像分别限流；路径归一化、随机物理文件名和最小剩余空间防止目录穿越与磁盘写满。
- 上传者/会话成员授权、10 分钟签名地址、访问时权限复核和审计日志组成完整访问链路。

V2.3 已在这套安全基线上增加上传会话、分片幂等、缺片查询、断点续传和 LOCAL/MinIO 存储切换。杀毒引擎、内容审核和异步转码仍属于后续增强。

### 连接诊断

聊天页连接状态条可打开诊断面板，显示当前节点、连接路径（本机/局域网/远程）、WebSocket 阶段、延迟、最后心跳与同步时间、重连次数及待发/失败任务，并提供重连、重试、清理本地缓存和导出脱敏诊断信息。

`admin` 账号还能查看 MySQL/Redis 探测延迟、文件存储容量、JVM 堆与线程、在线连接、消息 ACK/失败计数和最近连接生命周期；依赖地址、令牌与密码不会返回前端。

### 私有部署

- `private` Spring Profile 默认关闭自助注册，由首次启动创建的 `admin` 在管理控制台创建普通账号。
- 私有模式会拒绝开发 JWT 密钥、过短/占位数据库与 Redis 密钥、弱管理员密码、公开静态目录文件存储，以及 `LOCAL_INDEPENDENT` 模式下的公网隧道。
- 初始化脚本不再内置任何账号。`LANCHAT_BOOTSTRAP_ADMIN_PASSWORD` 仅在数据库尚无 `admin` 时生效，重启不会覆盖已有强密码；若检测到历史演示口令 `LanChat123!`，则轮换管理员密码、停用其他演示账号并吊销其旧设备会话。
- Compose 仅发布统一应用入口；MySQL、Redis 和 MinIO 位于内部数据网络，Redis 与 MinIO 启用认证，应用容器以非 root、只读根文件系统、无 Linux capabilities 运行。

### 局域网节点自动发现

服务端使用 `_lanchat._tcp.local.` DNS-SD 服务广播节点 ID、名称、组织、版本、运行模式、协议和 Web 地址，并在每个可用的 IPv4 多播网卡上发现同类节点。`/api/v1/node/info` 提供脱敏握手信息，`/api/v1/node/discoveries` 返回去重且会过期的节点列表；登录页可以扫描并切换到发现的节点。

Tauri 桌面端可在未连接种子节点时原生浏览同一服务类型。发现结果必须通过协议版本、`nodeId`、`/api/v1/node/info` 和健康端点校验后才进入节点列表；客户端记录来源、延迟和最近成功时间，并保留最多 32 个缓存节点及经握手确认的手动地址。桌面端切换节点时 REST、WebSocket、资源地址与本地缓存属主一起切换，避免不同节点上相同用户 ID 复用缓存。

浏览器自身仍不能直接监听 mDNS，因此 Web 客户端第一次打开仍需要已知种子地址。Docker bridge 通常不会把 mDNS 多播送到物理局域网；真实桌面发现应在具有多播网卡的 macOS 或自托管 runner 上验收。

### V3.0 桌面认证与安全边界

- 桌面登录、刷新和退出由 Rust 原生 HTTP Client 执行，Refresh Cookie 保存在按节点隔离的内存 Cookie Jar，不暴露给 JavaScript、`localStorage` 或 IndexedDB。
- Vue 侧只保存短期 Access Token；WebSocket 仍在连接后使用 `AUTH` 事件认证，Token 不进入 URL。
- 服务端明确识别 `desktop` 设备类型，并对 Tauri WebView 使用精确 Origin 的 CORS 与 WebSocket 白名单，不使用通配符凭据策略。
- `lanchat://` 只接受 `node`、`room`、`conversation`、`broadcast` 白名单目标，拒绝凭据、片段、重复/未知参数和非 HTTP(S) 节点地址。

## V2.2 协作能力

### WebRTC 文件传输

节点中转是默认文件发送路径，因而文件可受权限保护地长期保存并在其他已登录设备上下载。用户可在“个人资料 → 文件传输方式”中按本机开启“优先尝试局域网设备直传”：仅私聊、双方在线且文件不超过 100 MB 时才会尝试 WebRTC DataChannel，协商最多等待 2 秒；协商失败、超时、浏览器不支持或直传中断时会立即切换到受会话权限保护的服务端中转上传。直传文件只保存在完成传输的两台设备上；完成后的附件仍需通过服务端任务校验才能进入聊天消息，不能用伪造的直传元数据绕过文件权限。

### 临时协作房间

临时房间使用独立会话 ID 和房间码，支持成员角色、人数上限、到期时间、文件上传/下载/转发开关以及 `FREEZE`、`ARCHIVE`、`DESTROY` 到期策略。生命周期任务会更新房间状态，WebSocket 将成员变化和只读状态同步到在线客户端。

### 应急广播

`admin` 可在账号管理中授予或撤销普通账号的广播发布权限。已授权普通账号只能从自己的有效好友中复选接收者；管理员可选择全体普通账号或自己的好友。管理员不提交回执，只查看实时统计；撤销广播只把状态改为 `CANCELLED`，正文、接收快照和历史统计都会保留。

## V2.3 存储与多实例能力

### 分片上传与断点续传

客户端以 2 MiB 窗口增量计算完整 SHA-256，不依赖仅限安全上下文的 Web Crypto，因此默认局域网 HTTP 地址也能工作且不会把整个 100 MB 文件一次性读入内存。随后创建上传会话，服务端返回 `uploadId`、固定分片大小、总分片数、已完成分片和过期时间。每个分片使用从 `1` 开始、到 `totalParts` 结束的 `partNumber` 并携带 SHA-256；相同分片可安全重试，内容不一致的重复请求会被拒绝。客户端可随时查询上传会话，跳过已存在分片并继续上传。

完成请求会在服务端检查缺失分片、按编号合并，并重新计算完整文件的大小和 SHA-256，同时复用既有扩展名、MIME、文件头与容器结构检查。只有全部复核成功后才创建文件元数据和访问授权；失败、取消或过期的任务不会产生可引用附件。完成、取消、过期、重置或元数据删除产生的对象清理意图会先写入 `file_object_cleanup_task`，提交后尝试删除，存储暂时不可用时按退避时间持久化重试，避免数据库提交与 LOCAL/MinIO 对象删除之间留下永久不一致。

### LOCAL 与 MinIO 私有存储

文件对象通过统一存储接口写入。`LOCAL` 适合单机开发和兼容已有本地文件；`MINIO` 适合多实例共享对象。数据库通过 `file_metadata.storage_type` 记录实际存储类型，并复用 `file_metadata.file_path` 保存本地相对路径或 MinIO 对象键；因此切换默认存储后，历史 LOCAL 对象仍按原类型读取，不会被误当作 MinIO 对象。MinIO Bucket 保持私有，浏览器仍通过 LanChat 的鉴权或短期签名入口访问文件。

### 多实例实时路由与全局 Presence

同一逻辑节点的多个 Spring Boot 实例共享 MySQL、Redis 和 MinIO。实例只保存本机 WebSocket Session；面向远端实例用户的聊天投递、已读、撤回、焚毁、好友/资料变更、临时房间、广播、设备下线和 WebRTC 信令通过 Redis Pub/Sub 路由到持有目标 Session 的实例。事件带唯一 ID、来源实例和目标信息，接收端执行去重，来源实例不会再次回放自己的事件。

全局在线状态使用 Redis 带过期时间的 Presence 记录，并由实例周期续约；实例异常退出后记录会自动过期。Presence 只保存用户、实例、Session 和设备标识，不保存 Token 或完整用户正文。

Redis 短暂不可用时，本实例已有连接和 MySQL 持久化消息仍可继续工作，但跨实例输入状态等瞬时事件会降级。聊天消息恢复以 MySQL 会话序列和 `SYNC_REQUEST` 为准，客户端重连后补齐缺口。当前没有实现各自持有独立数据库的节点之间的数据复制、同步任务或冲突合并。

## 管理员运行日志

- Spring Boot 输出同时保留在控制台和 UTF-8 日志文件中；默认活动文件为 `./logs/lan-chat.log`，单文件 20 MB、保留 30 天、归档总量上限 1 GB。Cloudflare 子进程输出也会转入同一 SLF4J 链路。
- Compose 为 `lanchat` 与 `lanchat-2` 分配各自的 `/app/logs` 持久卷和日志文件，适配只读根文件系统并避免多个 JVM 竞争写同一活动日志；容器重建后日志仍保留。
- `admin` 在“管理 → 运行日志”中查看最近记录；页面每 10 秒更新，支持级别和关键字筛选，并突出显示错误、附加常见原因与处理方向，错误堆栈按需展开。
- 页面最多返回 1000 条并默认只扫描活动文件末尾 4 MB，避免大日志占满内存；“导出完整日志”以流式响应下载当前活动文件，历史轮转文件仍保留在日志目录/卷中。
- 日志正文以纯文本插值展示，不执行其中的 HTML。导出的运行日志可能包含内部类名、节点状态和请求 ID，应按运维资料妥善保管。

## 项目结构

```text
frontend/                              Vue 3 Web 客户端
  src/composables/useWebSocket.ts      认证、心跳、重连和连接状态
  src/composables/useOutbox.ts         离线发件箱状态
  src/composables/useDiagnostics.ts    用户/管理员连接诊断
  src/composables/useResumableUpload.ts 分片上传、缺片查询和恢复
  src/composables/useRuntimeLogs.ts    管理员运行日志查询与导出状态
  src/composables/useNodeDiscovery.ts  节点扫描、去重和切换
  src/services/localChatDb.ts          IndexedDB 消息、位置和发件箱
  src/platform/nativeBridge.ts         Web/Tauri 原生能力适配
  src/platform/nodeContext.ts          桌面节点 REST/WS/资源地址上下文
apps/desktop/
  src-tauri/src/                       生命周期、托盘、深链、原生认证与 mDNS
  src-tauri/capabilities/              Tauri 最小权限声明
apps/mobile/                           Capacitor Android 工程、受控 LAN Debug 变体与构建脚本
src/main/java/com/lanchat/
  cluster/                              Redis 跨实例实时路由、去重与全局 Presence
  websocket/ChatWebSocketHandler.java  V1 实时协议入口
  service/impl/ChatMessageServiceImpl  可靠消息事务与历史查询
  service/impl/ConversationServiceImpl 统一会话、序列号和成员位置
  service/impl/FileServiceImpl         文件存储、授权和签名预览
  service/ResumableUploadService       上传会话、分片幂等、合并与复核
  service/FileObjectCleanupService     文件/分片对象的持久化清理与重试
  service/storage/                     LOCAL/MinIO 私有对象存储适配
  service/NodeDiagnosticsService       公开握手与管理员健康诊断
  service/RuntimeLogService            固定日志文件尾部解析、错误说明与导出
  service/LanNodeDiscoveryService      mDNS 广播和节点发现
  service/impl/FileTransferServiceImpl WebRTC 任务、信令权限与中转降级状态
  service/impl/TemporaryRoomServiceImpl 临时房间成员和生命周期
  service/impl/BroadcastServiceImpl    广播授权、接收快照、回执和撤销
sql/
  init.sql                             新环境完整结构
  migration-v2.0-reliable-messaging.sql 已有 V1 数据升级脚本
  migration-v2.1-security-diagnostics.sql 文件访问审计升级脚本
  migration-v2.2-file-transfer.sql     文件传输任务升级脚本
  migration-v2.2-temporary-rooms.sql   临时房间升级脚本
  migration-v2.2-emergency-broadcast.sql 应急广播升级脚本
  migration-v2.2-broadcast-permission.sql 广播发布权限升级脚本
  migration-v2.3-resumable-object-storage.sql 分片上传与对象存储升级脚本
deploy/nginx.conf                      两个应用实例的 HTTP/WebSocket 统一网关
compose.yaml                           MySQL、Redis、MinIO、双应用实例与网关
compose.e2e.yaml                       隔离双实例端口与 E2E 配置覆盖
tests/e2e/                             双实例消息和浏览器断网恢复测试
.github/workflows/                     通用 CI、桌面构建、E2E 与草稿 Release
```

## 快速启动

### 方式一：Docker Compose

需要 Docker Compose v2。私有部署不提供应用默认密钥或默认账号，先复制示例配置，并为其中所有空白密码和密钥生成互不相同的随机值：

```bash
cp .env.example .env
chmod 600 .env
# 使用密码管理器，或通过 openssl rand -base64 32 生成随机值后写入 .env
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

`DB_ROOT_PASSWORD`、`DB_PASSWORD`、`REDIS_PASSWORD`、MinIO 凭据至少 12 位，`JWT_SECRET` 至少 32 位；首次管理员密码至少 12 位并同时包含大小写字母、数字和符号。浏览器访问 `http://localhost:8080/app/`，使用用户名 `admin` 和 `.env` 中的首次管理员密码登录。首次登录并修改管理员密码后，可以清空 `.env` 中的 `LANCHAT_BOOTSTRAP_ADMIN_PASSWORD`；已有 `admin` 不会因该变量为空而被重建或覆盖。

默认 Compose 显式启动 `lanchat` 与 `lanchat-2` 两个应用实例，它们共享 MySQL、Redis 和私有 MinIO Bucket，并由 `gateway` 在 `8080` 端口提供支持 WebSocket Upgrade 的统一入口。网关关闭上传请求缓冲并放宽上传/合并超时，避免大文件先完整落到代理临时目录。两个实例使用相同的 `LANCHAT_CLUSTER_ID` 和 Redis Channel，分别使用 `lanchat-1`、`lanchat-2` 作为唯一 `LANCHAT_INSTANCE_ID`，并写入各自的日志卷。MinIO 管理控制台仅默认绑定 `127.0.0.1:9001`，不应直接暴露到局域网或公网。

若通过局域网 IP 访问，例如 `http://192.168.1.20:8080`，还需允许对应浏览器来源：

```bash
export WEBSOCKET_ALLOWED_ORIGINS='http://192.168.1.20:8080'
docker compose up --build
```

HTTPS 部署时同时设置 `AUTH_COOKIE_SECURE=true`、`LANCHAT_NODE_SECURE=true`，并把实际 HTTPS Origin 加入允许列表。

### 方式二：本地开发

环境要求：JDK 17、Node.js 20.19+、MySQL 8、Redis。默认 `FILE_STORAGE_TYPE=LOCAL`，不需要 MinIO；要验证共享对象存储时还需 MinIO 或兼容的 S3 服务。

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
# 可选：启用 MinIO
# export FILE_STORAGE_TYPE='MINIO'
# export MINIO_ENDPOINT='http://127.0.0.1:9000'
# export MINIO_ACCESS_KEY='your_minio_access_key'
# export MINIO_SECRET_KEY='your_minio_secret_key'
# export MINIO_BUCKET='lanchat-files'
./mvnw spring-boot:run
```

Vite 构建产物写入 `src/main/resources/static/app/`，由 Spring Boot 同源托管。

### macOS 桌面端开发

需要 Node.js 20.19+、Rust stable、Xcode Command Line Tools 和 Tauri 2 的 macOS 构建环境：

```bash
npm ci --prefix frontend
npm ci --prefix apps/desktop
npm --prefix apps/desktop run dev
```

执行本地 ad-hoc 签名打包：

```bash
npm run build:desktop --prefix frontend
npm run build:app --prefix apps/desktop
npm run build:dmg --prefix apps/desktop
```

本地 `.app` 会使用 ad-hoc Bundle 签名，确保 Info.plist、资源和 entitlements 被完整密封，但该签名不建立发布者信任，`.app`/`.dmg` 仍仅用于开发回归。正式 macOS 分发还必须配置 Developer ID 证书和 Apple 公证凭据，并在真实 Release 中验证签名、公证、stapling、安装和 Updater 升级。仓库不会提交或生成占位私钥。

### 已有 V1 数据库升级

执行前先备份数据库，然后运行：

```bash
mysql -u root -p lan_chat < sql/migration-v2.0-reliable-messaging.sql
```

随后执行 V2.1 文件访问审计迁移：

```bash
mysql -u root -p lan_chat < sql/migration-v2.1-security-diagnostics.sql
```

继续执行 V2.2 协作能力迁移：

```bash
mysql -u root -p lan_chat < sql/migration-v2.2-file-transfer.sql
mysql -u root -p lan_chat < sql/migration-v2.2-temporary-rooms.sql
mysql -u root -p lan_chat < sql/migration-v2.2-emergency-broadcast.sql
mysql -u root -p lan_chat < sql/migration-v2.2-broadcast-permission.sql
mysql -u root -p lan_chat < sql/migration-v2.3-resumable-object-storage.sql
```

V2.0 迁移会创建统一会话、会话成员和文件授权表，回填旧消息的 `conversation_id` 与 `sequence`，并增加幂等唯一索引；V2.1/V2.2 迁移补充审计、文件传输、临时房间、广播及账号广播权限；V2.3 增加上传会话、上传分片、持久化对象清理任务以及 `file_metadata.storage_type`，现有 `file_path` 同时承担本地相对路径或 MinIO 对象键。历史文件会回填为 `LOCAL`，不会因为默认存储切换为 MinIO 而改变读取位置。跨实例路由使用 Redis，无额外数据库迁移。不要对已有数据执行 `sql/init.sql`，因为初始化脚本会重建表。

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
| POST | `/api/v1/file/uploads` | 创建或恢复幂等的分片上传会话 |
| GET | `/api/v1/file/uploads/{uploadId}` | 查询状态、已完成分片和缺片信息 |
| PUT | `/api/v1/file/uploads/{uploadId}/parts/{partNumber}?sha256=...` | 上传带 SHA-256 的原始二进制分片 |
| POST | `/api/v1/file/uploads/{uploadId}/complete` | 合并分片并执行完整哈希与内容复核 |
| DELETE | `/api/v1/file/uploads/{uploadId}` | 取消上传并把暂存分片加入持久化清理队列 |
| POST | `/api/v1/file/avatar` | 上传头像图片 |
| POST | `/api/v1/file/preview-url` | 生成 10 分钟签名预览地址 |
| POST | `/api/v1/rooms` | 创建临时协作房间 |
| POST | `/api/v1/rooms/join` | 使用房间码加入临时房间 |
| POST | `/api/v1/broadcast` | 按权限发布应急广播 |
| POST | `/api/v1/broadcast/{id}/cancel` | 管理员撤销广播并保留历史 |
| POST | `/api/v1/admin/users` | 管理员创建普通账号 |
| PUT | `/api/v1/admin/user/{id}/broadcast-permission?enabled=true|false` | 管理员授予或撤销广播发布权限 |
| GET | `/api/v1/admin/diagnostics` | 管理员依赖、存储、JVM 与连接诊断 |
| GET | `/api/v1/admin/logs` | 管理员按级别、关键字读取受限日志尾部 |
| GET | `/api/v1/admin/logs/export` | 管理员流式导出当前活动日志文件 |

旧的私聊/群聊历史接口暂时保留兼容，新前端统一使用 `/api/v1/chat/history`。

## 验证

```bash
npm ci --prefix frontend
npm ci --prefix apps/desktop
npm ci --prefix tests/e2e

./mvnw -B test
npm test --prefix frontend
npm run typecheck --prefix frontend
npm run build --prefix frontend
npm run build:desktop --prefix frontend

cargo fmt --all --manifest-path apps/desktop/src-tauri/Cargo.toml -- --check
cargo check --locked --manifest-path apps/desktop/src-tauri/Cargo.toml
cargo test --locked --manifest-path apps/desktop/src-tauri/Cargo.toml

npm run typecheck --prefix tests/e2e
docker compose -f compose.yaml -f compose.e2e.yaml config --quiet
git diff --check
```

后端测试应覆盖会话 ID、消息幂等、序列分配、WebSocket 连接后认证、Refresh Cookie 轮换、文件内容识别与权限撤销、上传会话/分片幂等/缺片恢复/完整复核、LOCAL/MinIO 存储适配、跨实例去重/目标路由/全局 Presence、WebRTC 传输任务、临时房间生命周期、广播授权/好友边界/回执/撤销、私有部署、诊断、运行日志、mDNS 节点解析、控制器权限和应用上下文。最终测试数量以本次 `./mvnw test` 输出为准，不在文档中写死。

Compose 配置至少执行一次带完整强密钥的解析校验；具备 Docker 环境时，使用 `compose.e2e.yaml` 启动共享 MySQL、Redis、MinIO 和两个应用实例，再执行 `npm test --prefix tests/e2e`。当前自动 E2E 覆盖跨实例消息幂等与浏览器断网文本发件箱恢复；Presence、WebRTC、Redis 中断补偿、上传恢复和真实 mDNS 多播仍需补充自动化证据。

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
| `CORS_ALLOWED_ORIGINS` | Web/Tauri 开发 Origin | REST 精确 Origin 白名单；不接受 `*` |
| `AUTH_COOKIE_SECURE` | `false` | HTTPS 部署设为 `true` |
| `FILE_STORAGE_PATH` | `./uploads/` | 私有文件目录 |
| `FILE_STORAGE_TYPE` | 应用 `LOCAL`；Compose `MINIO` | `LOCAL` 或 `MINIO`；多实例必须使用共享 MinIO |
| `FILE_STAGING_PATH` | 系统临时目录 | 分片合并和内容复核的私有暂存目录 |
| `FILE_UPLOAD_CHUNK_SIZE` | 应用 `5242880`；Compose `8388608` | 服务端分片大小，应用默认 5 MiB、Compose 默认 8 MiB |
| `FILE_UPLOAD_TTL_HOURS` | `24` | 未完成上传会话的有效小时数 |
| `FILE_UPLOAD_MAX_CONCURRENCY` | `4`；Compose 为 `3` | 每个用户允许同时保持的未完成上传会话上限；浏览器单个任务固定并发上传 3 个分片 |
| `FILE_UPLOAD_CLEANUP_FIXED_DELAY_MS` | `600000` | 过期上传任务的清理周期 |
| `FILE_UPLOAD_CLEANUP_INITIAL_DELAY_MS` | `60000` | 应用启动后首次清理延迟 |
| `FILE_OBJECT_CLEANUP_FIXED_DELAY_MS` | `60000` | LOCAL/MinIO 对象清理失败后的任务扫描周期 |
| `FILE_OBJECT_CLEANUP_INITIAL_DELAY_MS` | `60000` | 应用启动后首次扫描持久化对象清理任务的延迟 |
| `MINIO_ENDPOINT` | 空 | MinIO/S3 兼容服务地址；`MINIO` 模式必填 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 空 | MinIO 应用访问凭据；生产必须覆盖 |
| `MINIO_BUCKET` / `MINIO_REGION` | `lanchat-files` / 空 | 私有 Bucket 和可选 Region |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | 空 | Compose MinIO 根凭据，并映射为应用访问凭据 |
| `MINIO_CONSOLE_PORT` | `9001` | Compose 控制台的本机回环端口 |
| `FILE_MIN_FREE_SPACE` | 50 MB | 上传完成后必须保留的最小磁盘空间 |
| `FILE_MAX_IMAGE_PIXELS` | 4000 万 | 图片解码前的最大像素数 |
| `LANCHAT_LOG_FILE` | `./logs/lan-chat.log` | 当前进程活动日志文件；Compose 两个实例分别使用 `/app/logs/lan-chat-1.log` 和 `lan-chat-2.log` |
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
| `LANCHAT_CLUSTER_ENABLED` | 应用 `false`；Compose `true` | 启用同一逻辑节点内的跨实例实时路由与全局 Presence |
| `LANCHAT_CLUSTER_ID` | 当前逻辑节点 ID | 共享 Redis/数据库/对象存储的一组实例必须一致 |
| `LANCHAT_INSTANCE_ID` | 自动生成 | 应用实例唯一标识；多个实例不得重复 |
| `LANCHAT_CLUSTER_CHANNEL` | `lanchat:{clusterId}:realtime:v1` | Redis Pub/Sub 实时事件频道模板 |
| `LANCHAT_PRESENCE_TTL_SECONDS` | 应用 `90`；Compose `75` | 全局 Presence 过期时间 |
| `LANCHAT_PRESENCE_HEARTBEAT_SECONDS` | 应用 `25`；Compose `20` | Presence 续约间隔，必须小于 TTL |

## 文档说明

- 当前正式发布基线为 V2.3.0，V3.0.0 仍是开发版本。V3.0 P0 的代码边界和未完成验收以 [V3.0 实施状态](PRD/v3/docs/v3/实施状态-V3.0.md) 为准。
- 《需求分析-LAN-first-V2.0》和《功能分析-LAN-first-V2.0》中的独立节点复制、冲突合并和性能目标不等于已经完成。
- 仓库内 [需求分析.md](需求分析.md) 与 [功能分析.md](功能分析.md) 是 V1.0 历史稿，仅用于版本对照。
- P1/P2 的 Server Manager、iOS、离线任务增强和完整可观测性仍未实现；Android 工程和无签名 CI 构建已接线，但真实设备安装、内网 HTTP 回归、签名 AAB 与发布仍需要 Android SDK、受保护 keystore 和发布环境，不能仅凭工程或工作流文件标记完成。

## License

MIT
