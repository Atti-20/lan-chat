# 服务端

Spring Boot / Java 版本以本模块 POM 为准；原包名 `com.lanchat` 保持不变。根 POM 聚合本模块，Maven wrapper 在仓库根。接口修改可从根 `python3 tooling/workspace.py context api` 进入。

- 入口：`src/main/java/com/lanchat/LanChatServerApplication.java`。
- `controller/` 与 `control/`：HTTP API 和控制面；`dto/`：请求/响应；`service/`：业务；`mapper/`：数据库；`websocket/`：实时协议；`cluster/`：跨实例路由。
- 修改接口时同时核对 DTO、权限检查、共享客户端模型和测试。REST 索引在 `../../docs/generated/api-summary.md`，它只用于定位；REST 结构快照在 `../../contracts/rest/openapi.json`，由 `RestContractTest` 比较和导出。
- SQL 在根 `sql/`，包含初始化与顺序迁移；禁止把已有库当新库重建。配置样例在根 `.env.example`。
- 保持 `clientMsgId` 幂等、事务提交后 ACK、权限重查与会话序列；不要用 README 的历史状态替代测试。
- Web 静态产物在 `src/main/resources/static/app/`，不得手工编辑或作为上传目录。根启动与模块启动的路径保护都需覆盖。
- 根执行 `./tooling/verify server`；聚焦测试：`./tooling/verify server -- -Dtest=FileServiceSecurityTest`。Maven 缓存不可写时用 `MESHX_MAVEN_REPO=/tmp/lanchat-m2`。
- `./mvnw -pl services/server spring-boot:run` 保持根目录运行数据位置；JAR 在本模块 `target/`。
