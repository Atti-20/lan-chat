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
```

迁移要求 MySQL 8，因为旧消息序列回填使用窗口函数。

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

若 Docker 可用，还应执行 `docker compose config` 和 `docker compose up --build` 验证实际私有部署。
