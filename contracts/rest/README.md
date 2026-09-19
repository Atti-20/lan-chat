# REST 契约

[openapi.json](openapi.json) 从真实 Spring MVC Controller / DTO 导出，包含路径、方法、参数、JSON 模型、Cookie 与二进制响应。`RestContractTest` 启动独立 MVC slice，服务依赖全部替身，不启动数据库、发现、Tunnel 或初始化 Runner。springdoc 仅为 Maven **test** 依赖，发布包不增加文档端点。

## 生成与检查

```sh
./mvnw -pl services/server -Dtest=RestContractTest -Dmeshx.contract.update=true test
npm ci --prefix tooling/contracts
npm --prefix tooling/contracts run generate
python3 tooling/workspace.py generate
npm --prefix tooling/contracts run check
./mvnw -pl services/server -Dtest=RestContractTest test
```

常规 `./mvnw test` 会对比快照；变更 Controller / DTO 后必须审阅差异再更新。类型输出在 [packages/protocol/src/rest.ts](../../packages/protocol/src/rest.ts)，Web 登录/注册请求已使用生成 DTO 做编译检查。生成模型不含认证刷新、缓存、重试或业务状态，不能替代现有客户端编排。

## 认证和错误

公开 URL 规则从 [SecurityConfig](../../services/server/src/main/java/com/lanchat/security/SecurityConfig.java) 的 `requestMatchers(...).permitAll()` 提取；其他操作声明 HTTP Bearer JWT。这个清单只表示网关认证入口，权限代码、群组成员、组织/设备限制继续由 Controller / Service 保证。

浏览器登录将 refresh token 写入 HttpOnly Cookie `lanchat_refresh`，JSON 不包含它。`/auth/refresh` 同时接受可选请求体和 Cookie；原生端的令牌存储/刷新桥接不能用“公开路径”绕过校验。`/auth/logout` 的 Cookie 语义同样保留。access token 或设备会话失效后，客户端需要恢复认证或退出。

结构快照对普通成功响应保留业务 Result 与 ResultError 的 anyOf，401/403/default 声明 JSON 错误体。普通 JSON 返回 `Result<T>`：`code / msg / data / requestId`。客户端先检查 HTTP，再检查 `code == 200`；成功无内容时 `data` 可以为空。`GlobalExceptionHandler` 的参数、一般运行时和部分越权异常返回 HTTP 200，但 `Result.code` 是 400 / 500 / 403；Security Filter 直接返回 HTTP 401 / 403。策略版本冲突返回 HTTP 409，设备身份暂不可用返回 HTTP 503。下载/预览/导出为二进制内容，不能一律按 JSON 解析。流接口还会返回空 401/403/404；快照用 `x-empty-body-allowed` 明示这种分支，TS 生成器将 content 标为可选。客户端必须先看实际 body 和 Content-Type，再解析错误。binary 在 Web TS 中生成 Blob，避免当作 base64 字符串。

分片上传方法直接读取请求流，扫描器不会自动得到请求体；导出测试根据真实 `consumes=application/octet-stream` 映射补充 binary body。文件下载和预览保留任意媒体类型的 binary response。导出测试识别 MultipartFile，把文件及同方法的表单参数归入 multipart/form-data；真实 MVC 测试验证头像、广播图片和会话上传。广播 XLSX 导出显式映射正确媒体类型与 binary，并用 POI 解码真实响应字节验证。

## 覆盖边界

所有已注册 `/api/**` 方法均逐条检查存在于快照；Spring 路径中的参数正则被归一为 OpenAPI 模板。当前是 **结构契约**，不是所有业务条件的完整形式化定义：

- Java DTO 缺少校验注解，必填、长度、枚举、nullable 等不少条件仍在服务层；生成可选字段不表示服务一定接受缺失值。
- raw `Result`、`Map<String,Object>` 和直接暴露的 Entity 保留开放结构；不会凭字段名猜测更严格的模型。
- 幂等、事务提交、组织/成员权限和错误码具体触发条件继续由服务端业务测试证明。
- TS 类型由锁定版本工具生成。MX-A02 增加同源的 REST 操作常量和 9 个核心 Dart 操作/关联模型；原型 HTTP 包装层消费生成路径、方法和登录 DTO，认证/存储/刷新仍手写。Dart 是可验证的最小切片，不是全 REST SDK。

核心 Login/Refresh/Register/LoginVO/ConversationSummary/DeviceLoginVO/NodePublicInfo/ChatMessage 的引用类型 nullability 由 `CoreRestSemantics` 结合 Jackson 恢复；登录/注册 username/password 必填有真实 Service 前置断言。`Result` 的 data/requestId 允许缺失或 null。完整边界见 [盘点](../inventory.md)。

真实本地 HTTP 验证：`./mvnw -pl services/server -Dtest=ContractHttpIntegrationTest test`。它使用实际 Tomcat/MVC/Security/Jackson，服务依赖为替身；共享登录/会话结果也由 TS/Dart 解析。兼容与生成零差异分别由 [兼容门禁](../compatibility/README.md) 和 `./tooling/verify contracts` 检查。

参考：[springdoc 2.x](https://springdoc.org/v2/)、[OpenAPI TypeScript](https://openapi-ts.dev/introduction)。

## 按需定位

[API 导航](../../docs/generated/api-summary.md) 按 Controller 汇总，[操作清单](../../docs/generated/api-routes.md) 展开精确方法和路径。二者直接读取本快照，不再解析注解文本。每个操作的 `x-source.class / method` 来自 MVC Handler 的实际声明方法，只用于关联源码；不属于请求或响应字段，客户端类型保持不变。
