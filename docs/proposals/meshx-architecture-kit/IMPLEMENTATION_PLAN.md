# MeshX：Monorepo + 双语言核心 + 平台适配 + 设计系统

状态：待在本地基线核对后执行的建议方案。不是已完成架构，也不是对未来代码的保证。

## 1. 总体决策

采用同一仓库、多个独立构建工程。Web 和桌面使用同一条 Vue/TypeScript 产品线；Android 和 iOS 使用同一条 Flutter/Dart 产品线；后端继续 Spring Boot。多端共享网络契约、错误码、测试向量、设计 token 和产品语义。

**不把“共享规范”说成“共享源码”。** TS 与 Dart 的状态机、用例和模型映射通常需要各自实现，使用同一组测试向量约束行为。Java 的业务校验和授权仍由服务端执行。首期不引入跨 TS/Dart 的通用 Rust/WASM 核心，也不嵌入 JS 引擎运行 TS；将来只有性能测量或算法复杂度证明有必要时再用 ADR 评估。

四个名词的交付含义：

| 部分 | 应交付 | 不算完成 |
|---|---|---|
| Monorepo | 可复现安装、版本固定、统一命令、影响范围映射 | 单纯挪成 apps/packages 目录 |
| Shared Core | 可独立测试的核心、同源契约生成、TS/Dart 一致性测试 | 把所有 utils 搬到 shared |
| Platform Adapter | 按能力定义接口、真实平台实现、失败/拒绝/不支持语义 | 给业务代码套一层仍到处读取 window/Platform.isIOS 的包装 |
| Design System | 同源 token、两套 UI 实现、状态规范、视觉与无障碍验收 | 单纯创建 Figma 文件或复制颜色 |

HarmonyOS 只预留契约与设计资产；单独建立支持矩阵与选型 ADR 后再启动。当前所查 Flutter 官方支持列表不把 HarmonyOS 列为官方目标，不能承诺本工程加一个 target 就能完成鸿蒙适配。[O05]

## 2. 首期目录：不先搬家

以下是目标形态，目录按对应任务实际需要创建，不一次生成大量空包。

```text
meshx/
├── AGENTS.md
├── ARCHITECTURE.md
├── package.json                  # 新增；npm workspace/统一入口
├── package-lock.json             # workspace 建立成功后统一 JS lock
├── pom.xml / mvnw / mvnw.cmd / .mvn/
├── src/                          # 保留当前 Java 服务端位置
├── frontend/                     # 保留 Vue；也供 Tauri 使用
│   └── AGENTS.md
├── apps/
│   ├── desktop/                  # 保留 Tauri 壳和 Rust 实现
│   │   └── AGENTS.md
│   └── mobile/                   # 到 MX-A05 才新增 Flutter
│       ├── AGENTS.md
│       ├── lib/bootstrap/
│       ├── lib/features/
│       ├── lib/presentation/
│       ├── android/
│       └── ios/
├── packages/
│   ├── ts/
│   │   ├── core/                 # 纯 TS：模型、用例、状态机、ports
│   │   ├── api-client/           # 生成客户端 + 手写边界包装
│   │   ├── platform-web/         # fetch/WS/IndexedDB/浏览器实现
│   │   ├── platform-tauri/       # Tauri JS bridge
│   │   └── ui-vue/               # 仅提取稳定、确有复用的组件
│   └── dart/
│       ├── meshx_core/           # 纯 Dart，无 Flutter/插件依赖
│       ├── meshx_api/            # 生成客户端 + DTO/domain 映射
│       ├── meshx_ui/             # Flutter Theme/组件
│       └── meshx_platform/       # Flutter插件封装，必要时Swift/Kotlin
├── contracts/
│   ├── AGENTS.md
│   ├── rest/openapi.yaml
│   ├── ws/                       # 事件结构、版本、方向
│   ├── test-vectors/             # 共享输入/预期结果，不是聊天记录
│   └── compatibility.md
├── design/
│   ├── tokens/                   # 唯一可编辑 token 源
│   └── component-specs/          # 两端组件语义、状态与适配规则
├── tooling/
│   ├── meshx.mjs                 # 待实现的跨平台命令入口
│   └── checks/                   # 边界、契约、生成一致性检查
├── docs/
│   ├── ai/INDEX.md               # 短索引，不复制 README
│   ├── ai/repo-map.md
│   ├── architecture/             # 认证、实时、存储、平台策略
│   ├── adr/
│   ├── runbooks/
│   ├── tasks/active/
│   ├── tasks/completed/
│   └── proposals/meshx-architecture-kit/
├── sql/ / deploy/ / compose.yaml / Dockerfile
└── .github/workflows/            # 保留已有 hygiene，逐步增加测试
```

npm workspaces 仅管理 JS/TS 包。Maven、Cargo、Dart/Flutter 仍使用各自的构建与依赖管理，不强制塞进 npm。当前仓库已有 npm 锁文件，首期继续 npm，而非为了架构名词迁移到 pnpm/Nx/Turborepo。npm 官方支持由根包管理多个本地包及自动链接。[O04]

合并 JS lock 时必须保持现有主要依赖版本，验证根 `npm ci` 和两个消费者。不能先删除旧锁文件再假定所有依赖会保持不变。不要使用只适用于其他包管理器、当前 npm 不支持的依赖语法。第一轮不改包名、应用 identifier、数据库名或 protocol version。

日后可单独把 `frontend/` 搬到 `apps/web/`，把后端搬到 `services/server/`。这不是前置条件。执行时必须同时处理 Vite outDir/base、Tauri before*Command/frontendDist、Docker COPY、compose 构建上下文、资源打包、CI paths、文档和启动脚本。[R05–R06]

## 3. 共享边界与依赖方向

编译期依赖方向：

```text
Vue UI / Flutter UI → 应用用例、领域模型与 ports
平台 adapters      → ports
启动入口 bootstrap → 创建 core + adapters，并注入
```

core 不反向 import adapter。调用时 core 可以调用注入的接口，不等于源码依赖实现。

| 可以直接共享 | 不能直接共享但要一致 |
|---|---|
| Vue/Tauri 的 TS 模型、用例、组件 | Vue 组件与 Flutter Widget |
| Android/iOS 的 Dart 核心与多数 Flutter 界面 | TypeScript 与 Dart 业务实现源码 |
| OpenAPI/WS schema、错误码、测试向量 | 两种语言的生成 SDK 文件 |
| 设计 token 源、许可允许的图标资产 | CSS 样式表与 Flutter Theme |

最初 core 内只迁移已有实际用例需要的模型、消息合并/去重、发送状态、序列比较、重连策略与接口。不要为每个 GET 请求制造六层 Service/Repository/UseCase 包装。

现有 `useWebSocket.ts` 包含 Vue、浏览器 WebSocket 和 DOM 地址逻辑；拆成纯状态/策略 + 传输适配 + 薄 composable。保留现有 AUTH_OK→SYNCING→ONLINE 约束、入站事件串行处理，以及 onReady 等待后续同步响应时不得堵塞队列的行为。[R09]

现有 `localChatDb.ts` 包含 IndexedDB 和 Vue toRaw；留在存储/展示边界，转换为 plain DTO 后再进入存储。核心只认识 MessageStore/OutboxStore/SyncCursorStore 接口。Flutter 另用经过选型的本地数据库适配，不尝试直接复用 IndexedDB。[R10]

## 4. 先解决“服务端地址与认证”，再搬 API 文件

为三类客户端定义明确的 ServerProfile/EndpointProvider 设计：API base、WS base、允许的资源来源、受信任节点身份，以及与账号绑定的会话上下文。实际字段在任务中确认，不强行按本文名称重命名现有协议。

Web 可继续同源 `/api/v1` 与 `/ws/chat`；Tauri/Flutter 应从用户选择且已确认的服务器配置得到地址，不能依赖页面自己的 host。开发环境 Vite proxy 能用，不代表发布后的 Tauri 一定能访问后端。[R05–R06、R08–R09]

针对现有 `api.ts` 的 refreshPromise、同源 cookie 以及 deviceType=web，需要单独审计客户端身份和刷新流程。选择可行、经测试的 Web cookie 策略及桌面/移动凭据策略；若需后端增量支持，保持旧 Web 客户端兼容。浏览器的 HttpOnly refresh cookie 不应为了“统一”变成 JS 可读 token。移动端长效敏感凭据使用平台安全存储，不写明文普通首选项、源码或日志。

统一：并发 401 只触发一次刷新；最多一次有界重试；退出/撤销/换账号期间的旧异步结果不得覆盖新会话；刷新失败的可恢复性要有明确状态。

不同服务器、账号、设备的 token、消息缓存与同步游标必须隔离。必须测试切换服务器、退出再登录、同设备不同账号以及旧请求晚到的情况。

资源地址、文件下载、深链和发现广播都视为不受信输入。不得把 Authorization 自动附加到任意 URL；不通过全局关闭 TLS/ATS/CSP、扩大 Tauri 权限为全部资源来修复联调。

## 5. 契约治理：先还原现状，再建立唯一编辑源

不要先凭空写一份理想 OpenAPI，然后迫使旧客户端和服务器全部改变。

第一步盘点实际 Controller/DTO、前端 API 和 WS 实现，记录现有 `/api/v1`、响应包装 `{code,msg,data,requestId?}`、实际事件名与 envelope。第二步做人工可审阅的 v1 契约基线，明确哪些接口尚未纳入。第三步选择生成工具与 schema 版本，验证真实模型中的 optional/null、枚举、文件上传、错误响应等。[R08–R09]

本方案建议：基线确认后，以 `contracts/rest/openapi.yaml` 为 REST 的唯一手工编辑契约。Java Controller 可继续手写，但需运行与该契约对照的集成/契约测试；生成模型不是授权实现，不需要重新生成全部 Controller/Entity。

TS 客户端可采用 OpenAPI Generator 的 typescript-fetch，Dart 可采用 dart-dio；两者官方提供相应生成器。[O06–O07] 必须固定生成器版本和配置，验证能处理本项目使用的 schema 特性。REST 生成器不负责 WS 重连、离线同步、权限或数据库逻辑。

若本地已存在可靠的 code-first 导出流程，可通过 ADR 改选“服务器定义→只读导出规范→SDK”，但同一接口不能同时维护两份可手写的权威 schema。归属和生成方向必须写明。

WS 契约包括：版本、方向、事件字段、关联 ID、认证、确认/失败、游标、同步完成语义、未知事件和重连行为。类型可生成，业务状态机仍需实现。

生成物建议提交到版本库，禁止人工修改；在固定环境中重新生成并检查无 diff。保持确定性：移除无意义时间戳、固定排序/格式及换行。正式实现前列出“源文件→产物→消费者”清单，避免同一 ChatMessage 在 REST、WS、types.ts 和 Dart 中各有定义。即便为工具兼容生成镜像，也要标记只读并自动检查一致性。

已有协议变更以增量兼容为先。不可在架构迁移中悄悄改 v1 枚举/字段/事件名。新增字段也需兼容性测试；旧客户端对新增 enum 的处理不能假定安全。需要不兼容变更时，应有版本协商、旧/新客户端与旧/新服务器测试及回滚计划。

## 6. 用同一组测试向量统一 TS/Dart 行为

`contracts/test-vectors/` 存可审查的输入、初始状态、预期输出与解释，不放真实用户消息和敏感日志。TS/Dart 分别运行同一组向量，必要时 Java 也验证网络编解码。

首批应覆盖：

1. AUTH_OK 后先同步，再允许正常发送；等待 SYNC_RESPONSE 不导致入站串行队列死锁。
2. 同一客户端消息被重试、确认和历史同步多次返回，界面最终只保留一条；先验证现有 clientMsgId 幂等语义，不能仅在 UI 层去重。
3. 断线、过期 token、重连、游标缺口、旧连接晚到事件、重复/乱序事件。
4. outbox 暂存、补发、失败、应用重启恢复，以及服务器/账号隔离。
5. optional 与 null、未知字段/事件、时间戳单位、排序和超大序列值。现有数字字段不能直接改成字符串而破坏 v1；先明确安全范围，对无法安全解析的值应检测而非静默损坏，确需迁移则版本化。
6. 上传取消、断点续传、重复块、校验失败、权限撤销，以及当前直传/回退流程实际存在的边界。

测试期望来自已确认业务行为或明确批准的新需求，不从“当前错误输出”自动生成正确答案。用测试固定行为不等于把已知缺陷永久固化：缺陷应有复现、修复任务和单独变更记录。

## 7. 平台适配设计

接口按业务所需的能力拆分，而不是一个万能 NativeService。不要一次实现下表所有适配，随用例逐步引入。

| 能力 | Web | Tauri | Flutter Android / iOS |
|---|---|---|---|
| Endpoint/AuthSession | 同源及浏览器会话适配 | 受信任节点与桌面存储 | 受信任节点与移动安全存储 |
| MessageStore/Outbox | 现有 IndexedDB 适配 | 首期复用现有 WebView 存储 | 本地数据库适配 |
| Discovery | 现有 `/node/discoveries` + 手动输入 | 按需要新增 Rust mDNS/发现 | 插件或 Kotlin NSD / Swift Bonjour |
| Notification | 浏览器可用能力及站内反馈 | 系统通知 | 本地通知、通知权限；推送另建注册能力 |
| FileAccess/Share | 用户选择的浏览器文件能力 | 受限范围系统文件/分享 | 系统文件选择/分享，插件或原生适配 |
| Lifecycle/Resume | 页面可见性/可恢复状态 | 窗口/休眠唤醒 | 前后台/系统调度/恢复补同步 |
| Dialog/Feedback | 保留可用交互 | 从现有 NativeBridge 演进 | 平台化交互，避免强搬 Web Toast |

统一返回类型应区分：可用、需授权、已拒绝、不支持、暂时不可用、用户取消、真实失败。发现结果为空不是“权限被拒绝”。接口需要取消/超时/释放订阅，避免页面销毁后继续扫描和发送事件。权限请求由合理的用户操作触发，不启动即弹全部弹窗。

Flutter 自定义原生能力优先使用 Pigeon 或清晰的 platform channel 协议；业务语义的 port 与通信桥接的 DTO 分开。Pigeon 生成代码是进程内 Dart↔Swift/Kotlin 桥接，不等于生成后端 REST 或 WS 协议。[O02]

成熟、符合要求的插件可以封装使用，不要求所有系统能力从零写 Swift/Kotlin。引入前检查维护、许可证、最低系统版本、原生依赖、权限、Google 服务依赖和真机测试。插件缺失不应污染 core。

Tauri 的业务 TS 仅通过适配访问命令；Rust 使用明确的 capabilities/permissions/scopes 和输入校验。前端不得任意运行 shell 或读写任意路径。[O03]

### 特别约束：LAN-first 不等于手机后台永远在线

iOS 普通应用的后台执行受系统限制，Swift 也不能提供任意常驻能力。Flutter/Platform Adapter 无法消除这一限制。[O08]

产品策略应为：前台局域网通信；后台按系统允许能力运行；可接入公网时推送作为提醒且不作为消息事实来源；恢复前台按服务器游标补齐。纯隔离局域网、iOS 进程挂起时，不保证即时收到新消息通知。禁止用音频/定位/VoIP 假用途维持聊天进程。

本地通知不等于远程推送。不要把 FCM 写成全体 Android 的硬依赖；推送通道与核心聊天分离。Android 前台服务、通知和电池策略必须按目标 SDK 和 OEM 真机验证，不能承诺每台设备锁屏后永久联网。

发现到一个节点/用户不等于自动建立好友或信任。至少区分发现、身份确认、加好友授权和企业策略；未来企业自动发现作为独立产品需求，不混入本次基础设施迁移。

## 8. 设计系统：复用已有视觉，不在重构中改版

从 `main.css` 与 `useTheme.ts` 的实际主题提取 primitive 和 semantic token。现有颜色/圆角是首轮视觉基线，不采用以前示例中的随机颜色与半径。[R11、R14]

`design/tokens/` 唯一编辑源 → 固定工具生成 CSS 变量和 Dart Theme/常量；旧 CSS 变量可保留别名逐页迁移。非平凡 CSS 渐变、阴影、模糊效果不能盲目转为一个数字：允许平台专用、显式记录的渲染映射。

统一品牌色、语义色、间距、圆角、图标、状态与文字层级。Web CSS 像素和 Flutter 逻辑像素的映射、系统字体回退、动态字体、暗色和高对比策略必须明确。不得复制分发无授权的 Apple 字体；使用平台系统字体/合法资源。

首批只做六类共享设计规范及两套实现：Button、Input、Avatar、MessageBubble、ConversationItem、ConnectionStatus。规范涵盖 loading/empty/error/disabled/focus/selected 等适用状态。页面可以不同布局：桌面双/三栏，手机单栏，平板自适应。统一产品语义，不强求跨端逐像素一致。

验收：同样的测试数据，各自固定渲染环境进行 Web 截图基线与 Flutter golden test；token 源到两端输出一致性检查；亮/暗主题；长中文、长 URL、失败状态、大字体、屏幕阅读标签、键盘焦点、触摸目标、安全区与键盘遮挡。截图与 golden 不替代真机手势/输入法/通知测试。

不要求每次修改 token 读取全部历史设计文档；读取组件规范与相关 token 即可。视觉变更需要明确批准，不能让生成工具自动接受全部新截图。

## 9. Flutter 首版：做一条完整真实路径

第一阶段移动端不做“全页面占位”，而是完成：

`手动配置可信服务器 → 登录 → 获取会话/历史 → 建立 WS/认证/同步 → 发送与接收文字 → 断线恢复 → 退出/换账号`

手机与现有 Web/桌面使用同一后端互发消息；重复/补发/排序测试通过。这条路径不依赖 LAN discovery、推送或全部群管理页面完成。

之后增加文件、发现、通知和生命周期适配，再逐项迁移好友、群组、广播、临时房间等真实存在的产品能力。业务新需求另开任务；不能把新增群管理规则或广播流程等业务新功能与架构迁移绑在同一个 PR。

Flutter 和 Dart 工具链必须固定具体版本，提交应用 lock；Android/iOS 最低版本采用明确产品支持政策并在 CI 校验，不能依赖随 Flutter 升级自动抬高的默认值。当前官方支持页列出的基线可作为选型起点：Android API 24、iOS 15；实际采用值由锁定 SDK 与插件联合验证，不把这张官方表当 MeshX 的测试结果。[O05]

Android compile/target SDK、Gradle、Kotlin 和 Xcode/iOS SDK 根据实施时的锁定工具链及发行要求核实，不在根 AGENTS 写永久“最新版”。后端 Java 17 与 Android 构建所需 JDK 要分别确认。Flutter 升级与最低系统版本变更需要独立 ADR 和回归，不混入功能任务。

## 10. Codex 工作区与按需上下文

根 AGENTS 建议约 40–80 行，团队初始预算约 4 KiB；这不是 Codex 的 token 限制。只写任务入口、代码边界、验证入口、不可破坏项与安全规则。模块文件只补充该范围独有的信息，不重复根规则。

当前 Codex 文档说明，指令发现沿项目根到工作目录的路径进行，并存在默认合并大小限制。不要假设从根启动的会话会预载每个子目录的 AGENTS；修改前应显式定位并读取目标路径适用的根/祖先/模块指令，包括 override。[O01]

新会话最小读取：适用指令 → `docs/ai/INDEX.md` → 当前任务卡/交接 → 相关接口和源文件。必要时读相关 ADR/规范，不默认全文扫描 README、docs、历史任务、锁文件和生成 SDK。无法定位时允许渐进扩大搜索，不能以节省 token 为由跳过必要安全检查。

`docs/ai/INDEX.md` 应告诉 Agent：认证查哪里、实时状态查哪里、存储查哪里、token查哪里、各模块如何验证。repo-map 仅列入口与依赖，不复制代码，也不记录每次都变的全仓库行数/时间戳导致无意义 diff。

任务状态单独写 `docs/tasks/active/<task-id>.md`，完成后归档。交接记录分支/HEAD、已改文件、关键决策、下一步、精确测试命令/结果、已知阻塞，不能保存原始聊天流水、密钥或几十万行日志。

不把“文档优先”当作忽略实际代码的理由。代码、契约、ADR 冲突时记录证据并修正差异；不能因为旧文档有一句话就擅自破坏当前行为。

### 并行策略与 token 衡量

一个任务一个分支/独立 worktree。先冻结契约和 token 再并行 UI；root lock、契约源、生成配置、共享 token 的写入有单一协调任务，避免两个 Agent 同时覆盖。不要在同一工作目录启动多个写代码的 Agent。

默认单任务执行，必要时另一个会话评审 diff。评审者不重新扫描整个仓库。是否调用更强模型由风险决定，不把模型商品名写入架构永久规则。

节约效果用可观测指标衡量：完成同类任务时的可用 token/成本统计、读取文件范围、重复探索、返工次数、验证失败和回归。没有真实数据不承诺节省百分比；短文档也可能因重复/过时而增加成本。

`.gitignore` 不是读取权限隔离，AGENTS 也不是安全沙箱。真实 secrets、APNs 凭据、签名证书、生产库访问放在安全凭据机制中。不要自创 `.codexignore`/token_budget 等配置键或关闭审批来“提效”；工具配置变更需核对当时官方 schema。

## 11. 统一命令和 CI

下列是 Codex 需要在 MX-A01 及后续阶段实现的**目标命令**，不是本包中已实现、已运行的脚本：

```sh
node tooling/meshx.mjs doctor
node tooling/meshx.mjs verify --scope web
node tooling/meshx.mjs verify --scope server
node tooling/meshx.mjs verify --scope desktop
node tooling/meshx.mjs verify --scope contracts
node tooling/meshx.mjs verify --scope mobile
node tooling/meshx.mjs verify --changed --base <已确认的基线SHA>
```

Node 包装器只做定位、调度、日志和汇总，不取代 Maven/Cargo/Flutter。使用 argv 和明确 cwd，避免把用户输入拼进 shell；支持 Windows 的 mvnw.cmd、工具路径中的空格、错误码和信号。doctor 只检测，不自动全局安装/升级。

当前已确认的 Web 命令为 `npm --prefix frontend test`、`npm --prefix frontend run typecheck`、`npm --prefix frontend run build`、`npm --prefix frontend run build:desktop`；Maven 候选 `./mvnw test`（Windows 为 mvnw.cmd），实际所需环境由 MX-A00 验证。桌面 npm scripts 已有 dev/build。[R03–R04]

Web 构建会写后端静态目录，运行前后记录这些生成差异；不要因此对用户未提交文件执行全局 clean/reset。[R06]

`verify --changed` 必须沿依赖图向消费者传播，而不只按目录过滤；考虑删除/重命名，工作树模式考虑未追踪文件。契约变更至少验证 Java/TS/Dart 消费者；token 变更验证两套 UI；共享核心变更验证使用该核心的所有入口。无法判定影响范围时保守扩大验证，不默默跳过。

矩阵建议：

| 执行环境 | 检查 |
|---|---|
| Linux | npm 安装/TS/单测/Web build、Java 单元/契约/必要服务集成、生成一致性、边界与 hygiene |
| Android SDK 环境 | Flutter analyze/test、Android debug build、按阶段添加 API 最低与主力版本 smoke |
| macOS + Xcode | iOS simulator build/test；阶段性 Tauri macOS build；签名分发单独受控 |
| Windows | Tauri Windows build、统一脚本 Windows 路径验证 |
| 实机/受控局域网 | 发现、通知、前后台、休眠/恢复、文件和 OEM 行为 |

不要要求一个 Linux 会话证明 iOS 构建通过。缺少必要 SDK 或真机时输出 BLOCKED/NOT_RUN，状态不得伪装成 PASS。依赖下载受限也不能直接把检查禁用。现有 Gitleaks/hygiene 必须保留。[R12]

## 12. 兼容、发布与回滚

保留已有安装标识、数据目录、`lanchat_local_v2` 数据库、服务地址/协议、生产密钥和发布设置。品牌显示名可以后续独立变更，不能趁架构迁移更改 `com.atti20.lanchat` 导致旧安装升级/存储行为改变。[R05、R10]

数据库迁移一旦涉及已有部署，应有备份、升级测试和支持范围，使用兼容的扩展再收缩策略。不能为了回滚而自动删除用户数据。重构 PR 与数据格式变更拆开；回滚代码不等于安全回滚数据。

旧客户端与新服务端、新客户端与兼容旧服务端的组合应按明确策略测试。对旧客户端不能支持的能力，返回明确能力缺失/升级提示，不静默失效。前端静态资源保留/清理策略必须保持可审阅，避免部署时旧 index 指向已删资源。

生产签名、APNs 凭据、Google/厂商推送 credentials、自动更新私钥不得提交仓库。发布要单独批准，不因 CI 绿了就让 Codex 自动推送/合并/发版。

## 13. 实施顺序与退出条件

见 TASK_INDEX.md。每项可以拆成数个小 PR，生成文件单列；不设机械的总行数上限来掩盖真实复杂性。

第一轮只完成 MX-A00，下一轮 MX-A01。基础契约和 core 之后，才能开始 TS/Dart 平行实现。CI 随模块逐步增加，不等全部功能写完再补测试。

整个架构完成至少要看到：原有 Web/桌面回归通过；同源契约能生成两种 SDK；core 无非法依赖；Flutter 与现有客户端真实互通且能重连补同步；token 改动可一致生成两端输出；平台能力拒绝/不支持被正确处理；新会话从短入口定位任务；变更影响范围检查覆盖所有消费者。空目录、Hello World 和未执行的测试脚本不满足这些条件。

## 14. 明确不做

不一次重写后端；不切换 Vue 到 React；不为移动端引入 Capacitor；不把所有 Vue composable 机械搬入 core；不同时引入多个 Dart 状态管理框架；不创建没有消费者的包；不把全部原生能力从零实现；不为了一个插件擅自提高 minSdk/iOS Deployment Target；不把自动发现等同于自动授权；不承诺纯局域网 iOS 后台必达；不把 HarmonyOS 写成 Flutter 官方自动支持；不把业务新需求与基础架构重构一起交付。
