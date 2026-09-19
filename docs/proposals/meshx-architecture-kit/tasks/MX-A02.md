# MX-A02 — 冻结真实 v1 契约与认证/地址边界

前置：A00、A01。

## 读取入口

实际 Controller/DTO/安全和刷新实现；frontend/src/services/api.ts、types.ts；useWebSocket、相关服务端 WS 代码；节点/文件 URL 构造；相应已有测试。不能只依赖前端类型猜后端契约。

## 操作

盘点 REST/WS 的真实结构，先覆盖登录、刷新、退出、会话/历史、文本收发/同步和节点基础信息，其余接口列出覆盖状态。保留 `/api/v1`、v1 envelope 和已发布枚举/字段。

确认端点权威、认证刷新/撤销机制和 Web cookie 语义，编写 endpoint-auth ADR。设计桌面/移动 ServerProfile 与安全会话接口，明确 base URL、资源来源、用户/服务器隔离与刷新竞争。需要后端变化时单独增量实现且测试旧 Web 兼容，不把 cookie 一律换成客户端明文 token。

编写并校对契约基线，选择唯一可编辑源。默认采用已确认 openapi.yaml 契约；本地已有 code-first 时先 ADR 决策，禁止双向手工维护。建立源→产物→消费者映射。

固定 OpenAPI/schema 版本、生成器和配置，生成 TS 与 Dart SDK。检查 nullable/optional、enum、文件和错误响应。生成结果与包装层分开，提交后在固定环境再生成应零差异。生成客户端不是已经完成移动业务。

为 WS 和核心同步制定共享测试向量，先覆盖 AUTH_OK→SYNCING→ONLINE、入站队列非死锁、重复确认、重连、未知事件和 ID/序列边界。测试要有真实实现消费者，不创建永远成功的占位 runner。

## 交付

contracts/rest、contracts/ws、test-vectors、compatibility.md；生成配置/固定版本；TS/Dart API 包；endpoint-auth ADR；契约验证 CI。

## 验收

至少一组登录/会话请求与真实测试服务器一致；生成客户端可编译/分析；服务器返回与契约一致性测试通过；旧 Web 客户端行为不变；更改契约但不更新产物能让 CI 失败。缺少 Dart 工具链需报告阻塞，不标注 Dart 验证通过。

## 非目标与回滚

不重写 Controller/Entity/数据库，不升级消息协议。新增规范/产物可回滚；任何新增后端认证行为需保持旧路径并具有独立回滚方案。后续任务不能通过直接修改生成代码临时修契约问题。
