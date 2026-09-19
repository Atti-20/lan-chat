# MX-A03 — TypeScript Shared Core & Platform Adapter Extraction

开始/完成日期：2026-09-09。状态：**交付完成；限定 Core/Port/Adapter 回归与 clean snapshot / controlled rebuild PASS，安全扫描仍 FAIL — known false positive**。本轮已停止，未启动 MX-A04。

## 目标与边界

在现有 `apps/web`、`packages/domain-ts`、`packages/platform-ports` 内逐步抽出真实聊天规则与必要副作用接口，保留 Vue orchestration、nativeBridge 和网络兼容入口。至少一条实际 outbox 链消费 Core → Port → Adapter；复用 A02 契约和向量，建立静态/动态 import 与无 DOM 类型检查。

不开发 Flutter、UI、Design System、LAN、通知/后台；不修改 REST/WS、数据库 schema、框架/锁、应用标识/签名/最低版本；不搬整工程，不更改安全规则，不提交/推送/合并/发布。完成后停止，不自动进入 A04。

## 当前事实与保护

- 分支 `feature/v0.3.1`，HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`。
- 起点 1459 路径：966 存在，493 既有缺失。已有未提交目录迁移、A00/A01/A02 交付全部保留。
- SHA-256、起点副本、Git/index 状态和原 Web/desktop/mobile 产物清单：`output/mx-a03-2026-09-09/`。
- A02 安全状态 **FAIL — known false positive**；Android/CocoaPods 环境债、设备/LAN、远程 CI、发布未通过，本轮不隐式关闭。

## 验收完成

- [x] 先在旧实现固定消息 normalize/merge/sort 与 outbox 行为，再将相同断言接到 `packages/domain-ts`；既有 sequence/conversation 规则保持。浏览器 Blob 缓存类型移回 Web adapter。
- [x] 真实离线链：`useChat → useOutbox → domain-ts/outbox + OutboxPort → web/outboxStore → localChatDb`。Tauri WebView 继续共用原 IndexedDB，库名/版本/owner/条目结构保持。
- [x] `RealtimePort` 提供 Web WebSocket / Tauri Channel 两种 adapter；原 `nativeTransport` 导出仍可用，HTTP 行为及 nativeBridge 保持。
- [x] 覆盖重复 ACK/投递、gap/补同步、SENDING 恢复、重试/clientMsgId、AUTH/SYNCING/ONLINE、旧连接事件、logout/账号/节点切换。
- [x] 新 AST 依赖规则和无 DOM 编译接入 `check:core`、Web typecheck/build 及 `./tooling/verify core`；违规 import、浏览器类型和动态访问有真实 CLI 非零负例。
- [x] 当前可用 Java/Web/Dart/Rust、浏览器 IndexedDB 与隔离重建完成；主线程只读审查及 scout 对迁移前后传输实现的一致性核对完成。
- [x] 报告、命令日志、变更 patch、生成/锁/既有产物保护证据及 handoff 已归档。

## 新边界及保留职责

Core：`messages.ts`、`outbox.ts`、`realtime.ts`；已有 conversation/sequence 仍在原共享包。Ports 仅新增 Outbox 的 load/save/delete 与 Realtime 的 open/socket；没有无消费方的 ServerConfig/Session/Clock 全套抽象。

Vue 保留 ref/computed、AUTH、入站串行队列、同步 I/O、ACK 等待、outbox 发送编排、生命周期与 UI 提示；localChatDb、认证/节点服务继续负责实际副作用。依赖为 Core → protocol、Ports → Core/protocol、应用编排 → Core/Ports、adapter → Ports，不引入 domain/ports 循环。详见 [ADR 0007](../../adr/0007-typescript-shared-core-and-narrow-ports.md)。

发现并修复两处既有竞态：TOKEN_EXPIRED 的异步刷新在 disconnect 后重开连接；旧刷新阻塞新连接 AUTH_OK。用已有 socketEpoch 隔离刷新生命周期和结果，仅将刷新 I/O 移出入站等待链，普通业务/存储处理继续串行。修复前 FAIL、修复后及全量回归 PASS 的日志均保留。令牌/REST/WS 策略未改变。

## 实际验证

完整 [报告](../../reports/仓库审查/MX-A03TypeScript共享Core与平台适配报告_2026-09-09.md)、[全部执行命令及结果](../../../output/mx-a03-2026-09-09/verification-runs.json)、[保护核对](../../../output/mx-a03-2026-09-09/preservation.json)。下列数量有重叠，不相加。

| 命令/范围 | 结果 |
|---|---|
| 隔离 `./tooling/verify core` | PASS：AST/无 DOM 编译，29 项 Core/Port/实时契约用例 |
| 隔离 `./tooling/verify web` | PASS：108 项、typecheck、生产构建 |
| 隔离 `./tooling/verify desktop` | PASS：桌面 UI 构建、Rust 52；系统凭据库 1 项 ignored，仍未执行 |
| 隔离 `cargo test ... --release --locked native_transport::tests::native_` | PASS：2 项实际原生 HTTP/WS 回环；无 WebView/dev proxy，不等于安装包网络验收 |
| 隔离 `./tooling/verify contracts` | PASS：Node 9、Java MVC/Handler/HTTP 7、兼容门禁 3；A02 输入/产物不变 |
| 隔离 `./mvnw ... -B clean test` | PASS：313，0 失败/错误/跳过 |
| 隔离 `./tooling/verify flutter` | PASS：现有 analyze、30 项；未开发 Flutter 产品能力 |
| 隔离 `npm run test:storage` / E2E `typecheck` | PASS：真实 Chromium/IndexedDB 4 项；认证和原生调用为替身 |
| 隔离 `npm --prefix apps/web run build:mobile` | PASS：共享移动 Web 构建，不是 Android/iOS 原生构建 |
| `./tooling/verify tooling` / `workspace` / `hygiene` | PASS：43 项工具测试、生成/链接/目录规则与最终文档核对 |
| `./tooling/verify security` | **FAIL — known false positive**：62 提交、1 个缓存键命中；原规则未变 |
| `verify all`、远程 CI、安装包/真机/LAN/签名发布 | NOT_RUN；不能从分范围 PASS 推导全门禁通过 |

Clean snapshot 包含当前工作树 986 份非忽略输入，npm/Maven wrapper/M2/Pub 从空缓存恢复，复用已安装 SDK、Rust crate cache 与锁定版本浏览器，Rust 使用全新 target。全部生成/测试后快照 SHA-256 无变化，8 份锁不变。原目录 Web 106、desktop 15、mobile 15 份产物保留。随后只回填 5 份状态/索引/验证说明文档；最终工作区检查另行通过，已验证生产源码与快照一致。

## 遗留验证债与 A04 条件

- 安全扫描误报尚未处置，状态保持 FAIL；不是全安全门禁通过。当前 Gitleaks 只扫描 Git 历史，未提交源码不在该历史扫描范围。
- Android cmdline-tools/许可证、CocoaPods 属 A00/A02 环境债，本轮未重测或修复；原生壳构建、系统凭据库、安装后桌面网络、真机/LAN/后台、签名发布及远程 CI 仍未完成。
- 浏览器测试证明真实 IndexedDB 与实际 TS 编排；未连接真实业务后端/数据库/外部服务，不代表完整聊天 E2E 或多窗口并发隔离。
- 原 IndexedDB 是单 owner、切换时清空；多 Tab/不同账号同时运行和 outbox 并发 claim 未解决。生成 REST Blob、旧 NativeBridge AbortSignal 保留在 Core 依赖图外；不得导回 Core。
- A02 开放 Map/复杂 WS 分支/大整数、Flutter 实时解析与串行状态机差距仍保留；没有以本轮 TS 抽取关闭这些边界。

**A04 的受控技术前置具备；未启动。** A04 需用户明确开始，再读取现有设计入口、tokens/生成工具、真实主题与组件，按实际已有成果核定增量和视觉基线，不重新建立第二套 Design System；当前安全/设备/发布债不会自动消失。

回滚仅按本轮 `a03.patch`、SHA 清单与 `source-before/` 撤回明确变更，并先核对后续编辑；禁止整树 reset/checkout/clean。没有提交、推送、合并或发布。完成 MX-A03 后停止。
