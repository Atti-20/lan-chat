# LanChat 故障排查

## 页面可以打开，但实时连接失败

1. 确认浏览器访问地址与 `WEBSOCKET_ALLOWED_ORIGINS` 完全一致，包括协议、主机和端口；
2. 确认代理已转发 `/ws/chat` 的 WebSocket Upgrade；
3. 不要在地址后追加 `?token=`，新协议会在连接建立后发送 `AUTH`；
4. 查看页面顶部连接状态和服务端同一 `requestId` 的日志。

局域网示例：

```bash
export WEBSOCKET_ALLOWED_ORIGINS='http://192.168.1.20:8080'
```

## 刷新子路由出现 404

正式入口为 `/app/`。Spring Boot 会把 `/app/chat` 等前端路由回退到 Vue 的 `index.html`。若前面还有 Nginx，需要保留 `/api/**` 和 `/ws/**`，其他 `/app/**` 请求回源给 Spring Boot。

## 已有数据库启动时报缺少字段或表

不要执行会重建表的 `sql/init.sql`。先备份，然后运行：

```bash
mysql -u root -p lan_chat < sql/migration-v2.0-reliable-messaging.sql
mysql -u root -p lan_chat < sql/migration-v2.1-security-diagnostics.sql
mysql -u root -p lan_chat < sql/migration-v2.2-file-transfer.sql
mysql -u root -p lan_chat < sql/migration-v2.2-temporary-rooms.sql
mysql -u root -p lan_chat < sql/migration-v2.2-emergency-broadcast.sql
mysql -u root -p lan_chat < sql/migration-v2.2-broadcast-permission.sql
mysql -u root -p lan_chat < sql/migration-v2.3-resumable-object-storage.sql
```

迁移要求 MySQL 8，因为旧消息序列回填使用窗口函数。广播权限迁移必须位于应急广播迁移之后；V2.3 迁移会把既有文件记录标记为 `LOCAL`，并创建上传会话、分片和 `file_object_cleanup_task`，不会自动把本地文件搬到 MinIO。

## 消息一直显示等待网络

- `OFFLINE`：浏览器或节点不可达，文本消息保留在 IndexedDB；
- `RECONNECTING`：客户端正在指数退避重连，可手动点击重连；
- `DEGRADED`：认证已完成，但同步或心跳异常；
- `FAILED`：连续三次没有 ACK 或服务端拒绝，可手动重试/取消。

服务端 ACK 只在数据库事务提交后产生。不要修改 `clientMsgId` 后重试，否则会被视为新消息。

## 文件上传成功但消息发送失败

附件必须同时满足：

- 当前用户可以在目标会话发言；
- 当前用户是上传者、完整上传校验后的授权者，或已经通过会话拥有该文件；
- 消息中的存储名来自上传接口，不能手工引用其他文件。

单纯知道 SHA-256 不会获得文件权限。

## 分片上传无法继续或完成

先使用创建接口返回的 `uploadId` 查询服务端状态：

```text
GET /api/v1/file/uploads/{uploadId}
```

响应中的 `uploadedParts` 是已经通过分片大小和 SHA-256 校验的编号，编号范围为 `1..totalParts`。客户端只需重新发送缺失分片：

```text
PUT /api/v1/file/uploads/{uploadId}/parts/{partNumber}?sha256=<该分片SHA-256>
Content-Type: application/octet-stream
```

常见原因：

- 上传任务超过 `FILE_UPLOAD_TTL_HOURS`，需要重新创建；
- 当前账号不再拥有原会话的文件上传权限；
- 分片编号、大小或 `sha256` 与任务不匹配；
- 同一编号已经上传了不同内容，服务端会拒绝冲突覆盖；
- `complete` 时仍有缺片，或合并后的总大小/完整 SHA-256 不一致；
- 合并后的扩展名、MIME、文件头或容器结构未通过安全检查；
- `FILE_STAGING_PATH` 不可写或暂存空间不足。

取消任务会提交取消状态，并把暂存分片加入持久化对象清理队列；存储暂时不可用时会自动重试，不会删除已经完成并被消息引用的文件：

```text
DELETE /api/v1/file/uploads/{uploadId}
```

## MinIO 存储异常

先确认当前模式和配置：

```bash
export FILE_STORAGE_TYPE=MINIO
export MINIO_ENDPOINT=http://127.0.0.1:9000
export MINIO_ACCESS_KEY='...'
export MINIO_SECRET_KEY='...'
export MINIO_BUCKET=lanchat-files
```

Compose 部署使用 `.env` 中的 `MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD` 和 `MINIO_BUCKET` 创建私有服务，并把对应凭据映射给应用。检查：

```bash
docker compose ps
docker compose logs minio
docker compose logs lanchat lanchat-2
```

Bucket 不存在时应用会尝试创建；若账号无建桶权限，应由管理员预先创建并授予对象读写/删除权限。不要把 Bucket 设为公开，也不要让浏览器直接使用 MinIO 根凭据。

从 `LOCAL` 切换到 `MINIO` 只影响新对象。历史文件元数据保留 `LOCAL` 存储类型，仍需保留原上传卷；若要清理本地卷，应先使用专门迁移流程搬运对象，不能只修改环境变量。

完成、取消、过期、重置上传以及文件元数据删除都会先写入持久化对象清理任务。MinIO 短暂不可用时不需要手工删除数据库记录；恢复服务后，应用会按 `FILE_OBJECT_CLEANUP_FIXED_DELAY_MS` 自动重试。可用管理员数据库连接查看积压和最近错误：

```sql
SELECT id, storage_type, object_key, reason, attempts,
       next_retry_at, last_error
FROM file_object_cleanup_task
ORDER BY next_retry_at, id;
```

不要直接清空该表，否则对应无主对象将失去自动清理机会。若任务持续失败，应检查 Bucket 删除权限、对象键对应的存储类型以及应用日志；清理成功后任务会自动删除。

## 同一账号在不同实例间收不到实时事件

默认 Compose 通过 `gateway:8080` 统一访问 `lanchat` 和 `lanchat-2`。检查代理是否转发 WebSocket Upgrade，并确认两个实例：

- 连接同一 MySQL、Redis 和 MinIO；
- 使用相同的 `LANCHAT_CLUSTER_ID` 与 `LANCHAT_CLUSTER_CHANNEL`；
- 分别使用唯一的 `LANCHAT_INSTANCE_ID`；
- `LANCHAT_CLUSTER_ENABLED=true`；
- `LANCHAT_PRESENCE_HEARTBEAT_SECONDS` 小于 `LANCHAT_PRESENCE_TTL_SECONDS`。

Redis Pub/Sub 是实时快路径。Redis 短暂失败时，本实例上的连接和 MySQL 写入仍可工作，但另一个实例上的输入状态、Presence 或实时通知可能暂时不可见；恢复 Redis 并重连后，可靠聊天消息会通过 MySQL 会话序列和 `SYNC_REQUEST` 补齐。若 MySQL 已有消息但另一实例从未收到实时帧，应同时检查 Redis 日志、Cluster ID/Channel 和目标用户实际连接的实例。

V2.3 不会在两套独立数据库之间复制消息或合并冲突。把两个完全独立部署配置成相同 Cluster ID 并不能把它们变成一个集群。

## Refresh Token 或登录异常

- 本地 HTTP 开发保持 `AUTH_COOKIE_SECURE=false`；
- HTTPS 部署必须设置 `AUTH_COOKIE_SECURE=true`；
- Refresh Cookie 的 Path 是 `/api/v1/auth`，不会发送到其他业务接口；
- 清理浏览器 Cookie 后需要重新登录；
- 同类型设备重新登录会使旧设备会话失效。

## 前端构建

项目要求 Node.js 20.19+：

```bash
cd frontend
npm ci
npm run typecheck
npm run build
```

构建结果写到 `src/main/resources/static/app/`。

## 回归测试

```bash
./mvnw test
cd frontend && npm run typecheck && npm run build
```

若 Docker 可用，还应执行：

```bash
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

确认 MySQL、Redis、MinIO、`lanchat`、`lanchat-2` 和 `gateway` 健康后，使用两个浏览器会话验证跨实例聊天、全局 Presence、WebRTC 信令，以及中断 Redis 后的重连补拉。
