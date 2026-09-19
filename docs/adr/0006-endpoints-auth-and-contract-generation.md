# ADR 0006：端点、认证与跨端契约生成边界

日期：2026-09-09。状态：采纳，延续 ADR 0004/0005；范围 MX-A02。

## 决策

沿用 code-first：真实 Controller/DTO/Service 与 MVC 测试恢复 REST，OpenAPI 是导出产物，TS 和最小 Dart 模型/操作从它生成。WS envelope/event schema 生成结构，Handler 和业务测试拥有鉴权、事务、幂等与同步行为。来源冲突先复现并修正漂移，不设计一套新 API 强制迁移。源码、产物和消费者见 [契约盘点](../../contracts/inventory.md)。

生成层只处理传输字段、方法、路径和结构验证。Web/Tauri/原生壳的认证存储、刷新竞争、节点隔离、网络权限与状态机继续在手写平台/应用层。REST/WS 路径和 deviceType 归一值从来源导出，应用展示模型可以包含自己的默认值和交付状态。没有建立新的空 SDK 模块或复制 Java DTO 字段。

## 端点与 ServerProfile 设计

| 资料 | 当前来源与责任 |
|---|---|
| origin / nodeId | 受验证的用户节点选择；浏览器使用当前同源 origin |
| apiBasePath / webSocketPath | NodePublicInfo 与当前 selected node，默认 `/api/v1`、`/ws/chat` |
| 身份与信任 | 原生节点身份/可信来源检查；不能把显示名称当服务器身份 |
| 资源 URL | 平台资源适配；文件内容、签名预览和应用页面是不同来源边界 |
| 会话索引 | 当前原生实现按 origin；Web 刷新编排按 node key，并检查响应提交时仍是该节点 |

未来 ServerProfile 的最小逻辑字段为 `nodeId, origin, apiBasePath, webSocketPath, protocolVersion`，外加平台管理的信任记录引用。安全会话接口承担 `login(profile, credentials) / refresh(profile) / logout(profile) / clear(profile)`；调用方获取 access session，不获取浏览器/原生持久 refresh secret。当前 `NativeBridge.nativeLogin/nativeRefresh/nativeLogout/clearNodeSession` 和 Flutter `MeshXApi` 已承担对应责任；本阶段记录设计并复用，不建立第二套应用接口。

浏览器 HTTP 保留同源 `/api/v1`，WebSocket 从 http/https 映射 ws/wss。Tauri 使用选定节点的 apiBasePath/webSocketPath；Dart 操作只拼接经过平台校验的 API base，不自己选择服务器。Android/iOS 旧认证壳仍固定 `/api/v1`，与 Tauri/Flutter 的动态 base 能力不同。原生 HTTPS/WSS、调试 HTTP 和跳转/来源校验由各适配器执行；生成 SDK 不放宽它们。

跨节点不能提交迟到的刷新响应或复用另一个 origin 的会话。用户切换、撤销、退出需要清理对应缓存和令牌。当前实现仍存在不同平台存储持久性与键设计的差异；未来迁移需兼容既有键、账号/服务器隔离和注销行为，本次不更换存储、应用 ID 或 Cookie 语义。

## 认证与撤销

1. 登录 POST `/api/v1/auth/login`；JSON 返回 access token，refresh token 由服务端写入 `lanchat_refresh` Cookie。`LoginVO.refreshToken` 有 JsonIgnore，禁止向浏览器 JSON 暴露。
2. Cookie 为 HttpOnly、SameSite=Strict、Path=/api/v1/auth，Secure 由现有配置决定。原生客户端在各自 session/cookie store 保存它；Tauri 使用按 origin 分组的 reqwest Cookie session，Android 使用加密 store，iOS 使用 Keychain，Flutter 原型目前只在 MeshXApi 内存保存 refresh Cookie，尚未建立持久安全会话存储。
3. refresh 的非空 body token 优先，否则回退 Cookie。刷新轮换及设备类型由原 token/服务端活跃记录决定；并发轮换在服务端条件更新和客户端协调器约束下处理，不引入第二套明文 token 方案。
4. logout 同时撤销当前 bearer session（若已认证）和 Cookie 对应 refresh session，并写 Max-Age=0。设备撤销通过 FORCE_LOGOUT 传播；TOKEN_EXPIRED 触发现有刷新恢复。后端每个 WS 业务帧继续校验会话活跃状态。

## 兼容与验证

已实现 [保守结构门禁](../../contracts/compatibility/README.md)：生成零差异与兼容基线是不同检查。更新生成文件不能自动接受不兼容变更；字段/方法/版本等语义变化需审阅并记录版本/兼容窗口。初始基线允许开放附加字段和旧版本输入边界。

真实 loopback HTTP 服务器比较共享登录/会话 JSON，并验证 Cookie、401 和退出；服务端依赖替身不代表真实数据库联调。Java/TS/Dart 共用正反向量；Web composable 回归保护 SYNCING→ONLINE、入站顺序、重连与鉴权恢复。Flutter 只证明生成/解析和已有恢复，不具备完整 WS 同步状态机。原生构建、设备/LAN和发布证据在任务 handoff 分层记录。

## 替代方案与后果

未采用从手写 OpenAPI 反向生成现有 Java Controller，因为它会引入双向维护和无业务收益的服务端改写。未让生成器接管认证/存储，因为不同平台的 Cookie、原生密钥和节点信任责任已有实现。代价是开放 REST/WS 业务字段、64 位数值跨 JS 表示及原生适配差异仍需后续范围明确的任务治理；生成成功不能消除这些边界。
