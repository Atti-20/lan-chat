# MeshX 当前架构事实清单

核查日期：2026-09-08。任务：[MX-A00](../tasks/active/MX-A00.md)。本页描述本地工作树；执行证据见 [回归基线](../runbooks/baseline.md)。

## 基线与适用规则

- 分支：`feature/v0.3.1`；HEAD：`16efe67dcaae432afd62e4fa10e1d6abbf55c419`，提交时间 `2026-09-06 21:42:39 +0800`。
- 初始 `git status --short` 有 616 条折叠记录；展开未追踪文件后共 1279 条：91 个已修改、493 个已删除、695 个未追踪文件；暂存区为空。旧 `frontend/`、`src/` 的删除和新工程目录均是进入本轮前的状态。
- 实施包对照提交 `d7571ded11fa4d6e66fd0c05e37e0541c2004b23` 已存在于本地对象库；与 HEAD 互不为祖先，`HEAD...对照提交` 独有提交数为 `33 / 4`。不能将当前树简单称为该提交的更新版本。本轮没有 fetch、reset、checkout、提交、推送、合并或发布；本地远程跟踪引用不代表服务器实时状态。
- 已读根、docs、tooling、server、web、desktop、Flutter、现有 Android/iOS、contracts 与四个 packages 模块的 AGENTS；在相关目录及祖先链未发现适用的 `AGENTS.override.md`。实施包 `templates/` 是候选资料，没有覆盖现有指令。
- 本轮用户确定的路线是 **Spring Boot 后端、Vue Web、Vue + Tauri 桌面、Flutter Android/iOS，不采用 Capacitor**。旧壳的存在属于现状记录；本轮只审查，不删除、不扩建或改造旧壳。既有文件中与目标相冲突的表述见下文。

原始状态、逐文件 SHA-256、产物备份及命令日志保存在仓库本地 `output/mx-a00-2026-09-08/`；它们按既有规则忽略，不作为新克隆的必备文件。

## 实际入口与复用

| 范围 | 当前事实 / 权威入口 | 与实施包检查时的差异、复用边界 |
|---|---|---|
| 服务端 | 根 [pom.xml](../../pom.xml) 是 `meshx-workspace:0.3.0` 聚合器；业务 POM / Java 在 [services/server](../../services/server/AGENTS.md) | 不恢复根 `src/`，不再次迁移；服务 POM 为 Spring Boot 3.5.0、Java 17、应用 0.3.0，并非包内旧版本 2.3.0 |
| Web | [package.json](../../apps/web/package.json)、[vite.config.ts](../../apps/web/vite.config.ts)；入口 `src/main.ts / App.vue` | 原 `frontend/` 已变成 `apps/web/`；Vue 3.5.39、Vite 8.1.3、TypeScript 5.9.3，Node 声明 `>=20.19.0`；未声明 Pinia |
| 桌面 | [package.json](../../apps/desktop/package.json)、[tauri.conf.json](../../apps/desktop/src-tauri/tauri.conf.json)、`src-tauri/src/lib.rs` | Tauri CLI 2.11.4；前置命令和 frontendDist 已指向 `apps/web`；复用 Rust auth、transport、discovery、device_identity 和 node_runtime |
| Flutter | [apps/flutter-prototype](../../apps/flutter-prototype/AGENTS.md)；`lib/chat_controller.dart`、`lib/data/meshx_api.dart`、`lib/platform/discovery.dart`、`lib/ui/` | 已有真实后端接入代码、Kotlin NSD / Swift Bonjour、WS 生成模型及测试。保持现名和身份；它仍是切片原型，尚不能认定正式移动端替换完成 |
| 旧移动客户端 | [apps/android](../../apps/android/AGENTS.md)、[apps/ios](../../apps/ios/AGENTS.md) | 现存 Capacitor 8 工程和原生桥接；本轮保留用户文件。`apps/mobile-legacy` 目录也存在，未作为正式入口、未展开审查 |
| 协议 | [contracts/README.md](../../contracts/README.md)、`packages/protocol` | REST 结构、WS schema、TS/Dart 生成及检查链已存在，不新建竞争契约 |
| TS 共享层 | [domain-ts](../../packages/domain-ts/AGENTS.md)、[platform-ports](../../packages/platform-ports/AGENTS.md) | 已有模型、会话 ID、连续序列和 NativeBridge 接口；协议无应用依赖，domain 只依赖 protocol，ports 只依赖 domain/protocol |
| 设计 | [docs/design](../design/README.md)、[apps/web/DESIGN.md](../../apps/web/DESIGN.md)、`packages/design-tokens` | JSON tokens / 组件语义、CSS/Dart 生成已存在；本轮无 UI 变更，未做视觉验收 |
| 工作区 | [tooling/workspace.json](../../tooling/workspace.json)、[workspace.py](../../tooling/workspace.py)、`tooling/verify` | 已有 context、generate/check、verify、模块映射及错误退出行为；没有根 package.json/npm workspace/根 package-lock。各 npm 工程保留子 lock，不重复新建第二套运行器 |
| 架构与交接 | [ARCHITECTURE.md](../../ARCHITECTURE.md)、[ADR 0004](../adr/0004-client-target-and-contracts.md)、[ADR 0005](../adr/0005-repository-context-and-contract-authority.md)、[执行计划](../exec-plans/README.md) | 已有目标路线、契约权威、按需上下文和跨会话规范；本次按指定路径交付 `docs/tasks/active/MX-A00.md`，不复制一份竞争任务状态 |

上述目录迁移、contracts、Flutter 和工具链代码均是**本轮前已有实现**，不能据此直接把 MX-A01～A08 标记为已完成。

## 本机工具链

版本由本轮命令读取，未安装、升级全局工具或重建依赖。

| 工具 | 本机观察 | 限制 |
|---|---|---|
| 系统 / Python | macOS 26.6.2、arm64；Python 3.14.6 | 不代表 Linux/Windows 环境 |
| Node / npm | Node 26.3.1、npm 11.16.0 | CI Web 为 Node 20；本轮不是 Node 20 / 全新 npm ci 证明 |
| Java | 默认 Homebrew 21.0.12；另有 Microsoft 17.0.19 | 本轮 Maven 实际使用 Java 21；源码目标仍为 17，CI Java 17 未在本轮重跑 |
| Maven | 根 Wrapper 3.3.4 配置，实际 Maven 3.9.16 可运行；全局 `mvn` 不在 PATH | 采用根 `./mvnw`，默认本地缓存可用，不需要临时缓存替换 |
| Rust / Cargo | rustc / cargo 1.97.1，stable-aarch64-apple-darwin | Cargo.toml 最低 Rust 声明 1.77.2 未改；测试不等于 release 包 |
| Flutter / Dart | Flutter 3.44.8、Dart 3.12.2；已有 package_config 与 pubspec.lock | 使用 `analyze/test --no-pub` 复用现有依赖，不触发自动解析或安装 |
| Android | SDK 位于 `/Users/atti/Library/Android/sdk`；platforms 有 36、36.1、37.0；build-tools 35.0.0/36.0.0；NDK 28.2.13676358；绝对路径 adb 37.0.0 可运行 | ANDROID_HOME/ANDROID_SDK_ROOT 未设置，但 local.properties 已指向 SDK；adb 不在 PATH。doctor 报 cmdline-tools 缺失、许可证状态未知；不能声明 Android 构建环境就绪 |
| Xcode | 26.6（17F113）；所选路径 `/Applications/Xcode.app/Contents/Developer`；模拟器 SDK 26.5 | doctor 报 CocoaPods 未安装；原型 SPM/插件构建链未验证，不能由该告警推断所有 iOS 构建必定失败 |
| 其他 | Docker CLI 29.6.2 可运行；doctor 检测到 iPhone、iOS 模拟器和 macOS | 未启动/管理设备或容器；Docker daemon、业务服务及设备可安装性未核验。doctor 的 Chrome 默认路径不可用，不等于 MeshX Web 构建不可用 |

`apps/web`、desktop、contracts 的本地 npm 工具和 Flutter 缓存均存在。Web、Flutter、contracts 的锁文件在当前树尚未追踪；desktop npm/Cargo lock 已追踪。故本轮不执行新的 npm ci/pub get；已有缓存上的通过不证明未提交工程能由新克隆重建。

## 协议、认证与生产联网

REST 当前快照有 101 个路径、109 个操作。来源是 [RestContractTest](../../services/server/src/test/java/com/lanchat/contract/RestContractTest.java) 对 MVC Controller/DTO 的导出和普通测试比较，生成 `packages/protocol/src/rest.ts`。业务权限、事务、幂等和错误触发仍查服务实现与行为测试。该结构测试原有 MVC slice 使用服务替身，且设置 `addFilters=false`，不能用它证明安全过滤器通过；本轮未修改这一配置，完整 server 范围另包含已有安全相关测试。

WS 端点 `/ws/chat`，信封为 `version / event / requestId / clientMsgId / conversationId / timestamp / payload`。来源是 [WS 规则](../../contracts/websocket/README.md)、schema、Java Handler / Service 与行为测试；TS 和 Flutter WS DTO 已同源生成。`x-events.coverage=direction` 的广播、文件信令等仍是字段覆盖缺口；REST 的开放 Map、业务必填和完整 Dart REST SDK 也未全部完成。

| 场景 | 已核对的实现路径与语义 | 本轮未验证 |
|---|---|---|
| 服务端登录/刷新/退出 | [AuthController](../../services/server/src/main/java/com/lanchat/controller/AuthController.java) 写入 `lanchat_refresh` HttpOnly Cookie，SameSite=Strict、path=/api/v1/auth，Secure 来自配置；LoginVO 对 refreshToken 使用 JsonIgnore。刷新接受请求体或 Cookie，Service 识别 web/desktop/android/ios | 目标部署的 Secure、反向代理/Cookie 实际配置；实际会话库与全量授权审计 |
| Web | [api.ts](../../apps/web/src/services/api.ts) 使用 nodeFetch、节点键和 NodeRefreshCoordinator；刷新带 same-origin credentials；[storage.ts](../../apps/web/src/utils/storage.ts) 使用 sessionStorage 保存应用会话 | 真实浏览器登录、旋转刷新、跨节点切换、过期/退出行为与静态部署 |
| Tauri | `nativeBridge` 调用 Rust `native_auth.rs`；reqwest Cookie session 按 Origin 隔离，native_transport 校验握手许可的 Origin 和 API/WS 路径；HTTP/WS 经 IPC 到原生网络，不依赖 Vite 开发代理 | 已安装发布包的节点握手、TLS、认证恢复、Cookie 隔离与文件网络路径。系统凭据库测试原有 ignore，本轮未执行 |
| Flutter | [meshx_api.dart](../../apps/flutter-prototype/lib/data/meshx_api.dart) 使用 Dart HttpClient/WebSocket，设备类型 android/ios；内存 access token / refresh Cookie、单飞刷新和失败后清理；局域网 HTTP 为调试策略，release 要求 HTTPS | Keychain/Keystore 登录保留、磁盘 outbox、升级/数据迁移、真实设备生命周期及完整功能；生成模型不等于这些能力已实现 |

## 数据与关键耦合

- 数据库配置目标是 MySQL；Compose 声明 `mysql:8.4`、`redis:7.4-alpine` 和 MinIO 固定 RELEASE 镜像。这些是部署配置版本，**不是本机或线上数据库的运行版本**。
- [sql/](../../sql) 有初始化文件及 16 个迁移文件，末项为 `migration-v3.1-broadcast-notice-outbox.sql`。`deploy/mysql-init.sh` 为新库执行 init.sql；服务 POM 未配置 Flyway/Liquibase，既有手册要求已有库按顺序迁移。本轮未连接具体数据库，实际 MySQL 版本、已应用迁移/表结构、备份恢复均为 NOT_RUN，不推断已升级到 v3.1。
- [localChatDb.ts](../../apps/web/src/services/localChatDb.ts) 仍依赖 Vue `toRaw` 与 IndexedDB，库名 `lanchat_local_v2`、版本 **5**（实施包对照为 4），含 outbox/messages/positions 等 store；不能直接搬成纯 core，不能改名清库。
- [useWebSocket.ts](../../apps/web/src/composables/useWebSocket.ts) 仍含 Vue 状态、window 生命周期和串行入站队列；AUTH_OK 后进入 SYNCING，补同步完成后才 ONLINE；重构需要保留顺序、连续游标和 clientMsgId 重试语义。
- api、nodeContext、nativeBridge、IndexedDB 和 Vue 编排仍在应用层；packages 类型抽取不表示网络/存储已平台无关。Flutter 当前是独立 Dart 实现。
- Web base `/app/`，输出 `services/server/src/main/resources/static/app/`，`emptyOutDir=false` 保留旧哈希资源。桌面 base `./`、输出 `apps/web/dist-desktop/`、`emptyOutDir=true`。构建前后逐文件校验：Web 106 文件、桌面 15 文件，内容均无差异；缓存/target 中的测试产物有正常刷新。
- 应用身份保持：桌面 `com.atti20.lanchat`；旧 Android `com.meshx.android`（LAN 变体后缀 `.lan`），最低 26、compile/target 37；旧 iOS `com.atti20.lanchat`，最低 15.0；Flutter Android `com.meshx.meshx_flutter_probe`，最低 26、compile/target 37；Flutter iOS `com.meshx.meshxFlutterProbe`，Xcode 配置最低 13.0。桌面 tauri.conf 未显式写最低 macOS 版本，本轮未推断工具默认值。所有配置均未修改。

## 现有 CI 与目标差距

`.github/workflows/ci.yml` 已有 Maven、Web、契约、版本、工具单测、Compose 配置及空白检查。`repository-hygiene.yml` 保留跟踪文件策略、图标一致性、全历史 Gitleaks 与下载校验。E2E 使用隔离 MySQL/Redis/MinIO、两个 Spring Boot 实例和 Nginx；真实 mDNS 依赖专用多播 runner，不能由 hosted E2E 证明。桌面有跨平台构建和独立签名发布门禁；本轮只读检查这些定义，未运行 GitHub Actions、安全扫描或发布。

需要下一任务处理的明确差距：

1. 根和部分模块 AGENTS、Flutter README、完整手册、Android/iOS CI 仍将 Capacitor 称为正式移动路线；与本轮目标及 ADR 0004 不一致。先统一目标/现状措辞和入口责任，不机械覆盖模板或删除现有应用。
2. A01 的 context、verify、地图和 ADR 大部分基础设施已存在，应复用；根 npm workspace/lock 尚无，不能把 Python 模块清单当 npm workspace，也不必照模板增加第二套 Node 运行器。
3. Flutter 原型与检查工作流可复用，但没有正式 Android/iOS 功能对齐、原生构建及真机发布基线。原型 README 的 token 来源/手写契约说明也落后于现有 JSON→Dart、WS DTO 生成实现。
4. 大量当前工程和锁文件未提交，当前 HEAD 不能复现这个工作树。后续干净 worktree/CI 应使用经审阅的完整当前快照；不能直接 checkout HEAD 后将工程缺失误判为本轮删除。

下一任务及停止条件见 [MX-A00 交接](../tasks/active/MX-A00.md)。本页不将计划、接口定义、单元测试或模拟器枚举结果写成已完成迁移。
