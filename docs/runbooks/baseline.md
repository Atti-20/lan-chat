# MX-A00 本地测试与构建基线

日期：2026-09-08（Asia/Shanghai）。分支 `feature/v0.3.1`，HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`，对象是当时包含未提交修改的工作树。事实入口见 [current-state](../architecture/current-state.md)，交接见 [MX-A00](../tasks/active/MX-A00.md)。

## 复现约束与状态

所有命令先从本地 package/POM/workspace 脚本核对，默认 cwd 为仓库根 `/Volumes/External Disk/Project/java/lan-chat-server`；Flutter 命令另标 cwd。先执行 `git status --short`，再以 `--untracked-files=all` 留完整状态和文件 SHA-256。未安装依赖、切换分支、重置到远程对照提交、修改测试或关闭安全检查。

- PASS：所列命令实际完成且在声明范围内通过；不是端到端、全平台或发布承诺。
- FAIL：实际执行失败且归为工程/测试失败。本轮已执行测试没有此类失败。
- BLOCKED：已知环境或前提不满足；退出码 0 不能覆盖工具输出里的缺失诊断。
- NOT_RUN：没有执行，退出码记 `—`；不得从已有报告、已安装 SDK 或其他测试外推通过。

证据目录为本地 `output/mx-a00-2026-09-08/`。每项 `.log` 含 cwd、完整 argv、时间和输出，相邻 `.json` 含退出码及耗时；日志引用使用代码路径以避免让受 Git 管理的运行说明依赖本地忽略文件。

## 已执行命令与结果

执行窗口约 22:39–22:43；最终文档检查时间以日志为准。下表不是要求新会话无差别重跑所有命令。

| 命令（根目录，除另标） | 退出码 | 状态与实际结果 | 日志文件（证据目录内） |
|---|---:|---|---|
| `./tooling/verify workspace --dry-run` 以及 web/server/desktop/flutter/contracts 的相同 dry-run | 各 0 | PASS：只验证/展示命令入口；不计作测试通过 | `planned-commands.log` |
| `./tooling/verify workspace`（文档前） | 0 | PASS：上下文、依赖方向、生成物与静态契约检查 | `workspace-before.log` |
| `./tooling/verify web` → `npm --prefix apps/web test`、`npm --prefix apps/web run build` | 0 | PASS：Node 79 项，失败/跳过均 0；build 内 `vue-tsc --noEmit` 与 Web Vite 构建通过 | `web.log` |
| `./tooling/verify server` → `./mvnw -B test` | 0 | PASS：309 项，Failures 0 / Errors 0 / Skipped 0；实际 JVM 21.0.12。Maven 增量编译命中缓存，不是 clean package 证明 | `server.log` |
| `./tooling/verify desktop` → Web `build:desktop`、`cargo test --locked --manifest-path apps/desktop/src-tauri/Cargo.toml` | 0 | PASS：桌面前端类型检查/构建，Rust 50 项通过；**1 项原有 ignored，见下文 NOT_RUN**；不包含 release app/dmg | `desktop.log` |
| `./tooling/verify contracts` | 0 | PASS：静态契约、REST TS 生成检查、Node JSON 正反例 6 项、TS 编译、RestContractTest 3 项（亦属于 server 的 309 项，不重复累计） | `contracts.log` |
| `python3 -m unittest discover -s tooling/tests` | 0 | PASS：20 项；含路径/参数、缺失工具和运行器行为测试 | `tooling-tests.log` |
| `python3 apps/flutter-prototype/tool/generate_tokens.py --check` | 0 | PASS：CSS / Dart 与 tokens.json 一致 | `flutter-tokens.log` |
| cwd `apps/flutter-prototype`：`flutter analyze --no-pub` | 0 | PASS：No issues found | `flutter-analyze.log` |
| 同上：`flutter test --no-pub` | 0 | PASS：27 项核心/契约/恢复/组件测试；不包含 integration_test | `flutter-test.log` |
| 同上：`flutter doctor -v` | 0 | **BLOCKED（完整平台环境）**：Android cmdline-tools 缺失、license 状态未知；CocoaPods 缺失；Chrome 默认路径不存在。分析/普通测试可运行，但不能称原生环境全部就绪 | `flutter-doctor.log` |
| `./tooling/verify workspace`（交付文档后） | 0 | PASS：最终工作区检查；回填状态后再复核 | `workspace-final.log`、`workspace-delivery.log` |
| `git diff --check`、`git diff --cached --check` | 各 0 | PASS：工作树/暂存空白检查 | `documentation-check.log` |
| 本轮文档空白/链接检查 | 首次 1；修复后 0 | 首次 FAIL：新报告证据相对路径多一层；仅修正文档链接，最终 PASS | `documentation-check.log`、`documentation-check-final.log` |

Flutter 统一 scope 的三个步骤均已执行；analyze/test 添加 `--no-pub` 只阻止自动安装/依赖解析，不跳过分析或测试。现有缓存可用，当前新工程的 lock 尚未追踪，不符合“从受信任已提交 lock 新装”的前提，故没有运行 npm ci/pub get。未使用 `-DskipTests`、忽略 lint、安全豁免或修改 schema 快照来获得成功。

工具版本命令包括 `uname -sm`、`sw_vers`、`python3/node/npm --version`、`java -version`、`/usr/libexec/java_home -V`、`./mvnw --version`、`mvn --version`、`rustc/cargo --version`、`rustup show active-toolchain`、`flutter/dart --version`、`xcode-select -p`、`xcodebuild -version`、`xcrun --sdk iphonesimulator --show-sdk-version`、`adb version`、`sdkmanager --version`、`pod --version`、`docker --version`。详见 `toolchain.log`：已找到命令均返回 0；全局 mvn/adb/sdkmanager/pod 查找失败，记录 FileNotFoundError，没有实际进程退出码。绝对路径 `/Users/atti/Library/Android/sdk/platform-tools/adb version` 返回 0，见 `sdk-details.log`。探测脚本汇总返回 0 不表示每个工具存在。

Git 比较命令：`git branch --show-current`、`git rev-parse HEAD`、`git branch -vv`、`git log -1`、`git cat-file -t d7571ded11fa4d6e66fd0c05e37e0541c2004b23` 成功；双向 `git merge-base --is-ancestor` 均返回 1（分叉判定，不是工具错误）；`git rev-list --left-right --count HEAD...d7571ded11fa4d6e66fd0c05e37e0541c2004b23` 返回 0，结果 `33 4`。没有联网刷新远程引用。

## 告警及未运行项的归类

| 事项 | 状态 | 证据、影响与后续条件 |
|---|---|---|
| Web/desktop 构建告警 | PASS（有告警） | Tauri、Capacitor 模块同时静态/动态导入，动态导入未形成独立 chunk；主 JS 约 859.52 kB、gzip 267.96 kB，超过 500 kB 提醒。本轮记录既有构建行为，不调高阈值消音 |
| Maven WARN/ERROR | PASS（已分类） | `redis down`、`database unavailable`、文件清理失败、调度扫描异常来自已有 mock 故障用例；已核对 RedisRealtimeRouterTest、BroadcastNoticeOutboxServiceImplTest、FileTransferLifecycleSchedulerTest 等。管理员初始化日志来自 mock 测试，不能视为本轮修改真实管理员/数据库；springdoc 是 test 依赖；JDK 动态 agent 警告保留 |
| Rust 系统凭据库测试 | NOT_RUN | `device_identity.rs::operating_system_credential_store_round_trip_is_stable` 原有 `#[ignore]`，原因是写入并清理系统凭据库。本轮未添加 ignore，也未用 50 项通过声称该项通过 |
| Android 平台环境就绪 | BLOCKED | doctor 证实缺 cmdline-tools、license 未知；没有安装工具或代用户接受许可证 |
| iOS 原生构建 / 插件链 | NOT_RUN | Xcode 可用但 CocoaPods 未装，SPM/具体插件适用性待核对；尚未执行构建，不把 doctor 告警臆断为构建失败 |
| 全新依赖安装、干净工作树构建、CI Java 17/Node 20 | NOT_RUN | 当前基线使用既有依赖、Java 21 / Node 26；需先审阅并纳入完整当前工程与 lock 后在隔离快照复现 |
| 真实 MySQL 版本/表结构/已应用迁移、Redis、MinIO | NOT_RUN | 未选定或连接业务数据库，不从 Compose 镜像/SQL 文件推断运行状态；需指定隔离测试实例或只读目标及连接上下文 |
| Compose E2E / Playwright / Flutter integration_test | NOT_RUN | 本轮未启动隔离栈、seed 数据、浏览器或应用；既有 E2E 要求 Docker daemon、依赖/浏览器、双服务实例、MySQL/Redis/MinIO。未将未知服务状态标为故障 |
| 桌面 release 编译、app/dmg、安装后生产网络与更新 | NOT_RUN | 当前 scope 只做桌面前端与 Rust test；不证明 release 配置、已安装包或真实 TLS/节点握手 |
| Android/iOS 旧壳构建、Flutter APK/IPA/模拟器构建 | NOT_RUN | A00 未修改原生工程；旧 Capacitor 不作为选定路线继续建设；Flutter 平台先修与验证另立任务 |
| 真机、LAN/mDNS、后台/锁屏、文件直传/中转 | NOT_RUN | doctor 枚举设备不代表安装、交互或 LAN 验收；需真实设备/网络流程，尤其 mDNS 不能用手动地址连接替代 |
| Gitleaks/跟踪文件策略/图标检查、Compose config、远程 CI | NOT_RUN | 已核对现有门禁定义但没有执行完整仓库安全/发布流水线；没有删改或放宽它们 |
| 签名、公证、发布、线上升级/恢复 | NOT_RUN | 未核验发布凭据，未构建/上传/发布；不写占位签名，不推断凭据一定缺失 |

## 产品回归清单

表中“代码存在”仅代表本轮定位到入口；完整用户流程本轮均 NOT_RUN。局部测试结果只能按上表解释。

| 产品流程 | 现有代码/测试入口 | 后续验收动作 |
|---|---|---|
| 登录、刷新、退出、强制下线 | AuthController、UserServiceImpl；Web useAuth/api/authRetry；Rust native_auth；Flutter MeshXApi/recovery_test | 真后端下验证 Cookie/令牌旋转、跨节点隔离、失效清理、退出和重开应用 |
| 会话与文本 | ChatController、ChatMessageServiceImpl；useChat；Flutter chat_controller | Web/Tauri/Flutter 两端互发、历史分页、私聊/群聊和重复发送 |
| 同步 / outbox | ChatWebSocketHandler、可靠消息测试；useWebSocket/useOutbox/localChatDb；Flutter recovery_test | 断网→重连→补拉，clientMsgId 幂等、ACK、连续游标；Flutter 磁盘 outbox 尚未实现 |
| 好友 / 群组 / 临时房间 | Friend/Group/TemporaryRoom Controller/Service；对应 Web composable | 加好友、成员权限变化、退群/房间过期后的消息与列表变化；Flutter 完整管理未对齐 |
| 广播与回执 | BroadcastService、BroadcastNoticeOutbox、Web BroadcastWorkspace | 通知投递、确认/完成、修改接收人、撤销与附件证据的真实流程 |
| 文件、分片/续传与当前直传 | File/ResumableUpload/FileTransfer Service；useResumableUpload/usePeerFileTransfer | 上传中断恢复、哈希/权限校验、当前 WebRTC 直传与 relay 降级、跨实例存储 |
| 节点发现 / 切换 | LanNodeDiscoveryService、Web nodeDiscovery/nodeSwitch；Rust discovery；Flutter Kotlin NSD / Swift Bonjour | 真 LAN 多播、权限拒绝/恢复、手动节点和旧节点请求返回后的隔离 |
| 亮暗主题、响应式、键盘 | 既有 main.css/UiIcon、设计 tokens；Flutter theme/UI tests | 真实渲染浅/深、宽/窄屏、系统键盘、安全区与返回；组件测试不能代替真机 |
| Web 静态部署 | vite.config.ts → Spring static/app；WebMvcConfig/部署配置 | /app/、刷新导航、旧哈希资源、反向代理 API/WS、实际 Cookie |
| 桌面发布构建 | Tauri beforeBuild/frontendDist、nativeTransport、release-desktop CI | release 包、安装并重启新 bundle，真实节点/TLS/文件网络，再做签名/更新 |

## 产物与用户改动保护

构建前保存 `source-before.json`（1427 个被追踪或未忽略源路径）、完整 status、工作树/暂存 diff，以及两个 Vite 输出目录的内容清单和 tar.gz 备份。此操作不搬动原工程文件。

`preservation-after-build-summary.json` 显示：文档写入前已有源码变更 0、新增源码 0；`artifacts-diff.json` 显示 Web static/app 的 106 文件和 desktop dist 的 15 文件均无内容新增、删除或修改。构建可能更新这些文件的时间戳，Maven/Cargo/Flutter 缓存和测试报告按正常流程刷新；未执行 clean 或删除用户产物。

交付后的逐文件核对只允许 `docs/README.md` 与本轮新文档发生变化。最终证据为 `preservation-final-summary.json` 和本轮独立文档 diff。报告正文在 [归档报告](../reports/仓库审查/MX-A00本地事实与回归基线报告_2026-09-08.md)，沿用 reports 与 output 的 Git 忽略策略。
