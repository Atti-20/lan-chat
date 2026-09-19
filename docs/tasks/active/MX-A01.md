# MX-A01 — AI Workspace & Unified Verification

日期：2026-09-08。状态：**交付完成；导航与运行器验收 PASS，全量验证 FAIL（已核实的 Gitleaks 误报）**。本轮停止，未启动 MX-A02。

## 基线与范围

分支 `feature/v0.3.1`；HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`。初始展开 status 1282 条：修改 91、删除 493、未追踪 698，暂存区为空。以 [A00 交接](MX-A00.md) 和当前工作树为准，保留已有迁移；共享 Vue 在 `apps/web`，后端在 `services/server`。

没有搬目录、改业务/协议/数据库/应用身份/签名/最低版本、开发 Flutter 功能或新采纳 Capacitor。Android/iOS scope 只描述已有旧壳。未提交、推送、合并或发布。

## 完成项与交付

- [x] 根 AGENTS 从 5008 缩至 3375 字节，完善现有局部指令；报告/分工/交接细节下沉。
- [x] 建立 AI 入口、生成任务路由、验证说明和协作流程；聊天/Tauri/API 路由对应真实指令、源码、测试和 scope。
- [x] 复用 Python workspace 和 `./tooling/verify`，增加 doctor、四状态、JSON 报告、真实退出码/前提检查；保留 hygiene/Gitleaks 和现有构建。
- [x] 复用现有任务模板，编号任务保持单一状态/handoff。
- [x] 实际验证可用范围，记录失败/忽略/未运行；完成源码、lock、协议和构建产物保护核对。

本轮 **32 个交付文件：23 个原文件修改、8 个非忽略新文件、1 个本地忽略报告**。完整逐文件清单、命令、退出码、证据与八项交付内容见 [A01 报告](../../reports/仓库审查/MX-A01AI上下文与统一验证报告_2026-09-08.md)。本轮 diff 和原始日志在 [本地证据目录](../../../output/mx-a01-2026-09-08/)，不能把工作树原有迁移计入本轮修改。

不新增根 npm workspace/package.json：用户本轮明确允许按必要性省略；现有子工程 lock 与源码共享已满足任务，不需改变安装流程。保留已有 Web/Java CI 与安全门禁，没有新建空 job。`all` 包括全部现有平台范围，首次失败后余项 NOT_RUN。

## 新会话读取路径与命令

根适用 AGENTS/override → 本卡与 A00 handoff → [INDEX](../../ai/INDEX.md) → `python3 tooling/workspace.py context chat|tauri|api` 给出的局部指令 → 相关实现/测试；跨模块再读架构/ADR。认证、缓存/outbox、主题另有短入口。

三条 JSON 导航从非根 cwd 实际运行通过，输出 880 / 770 / 925 字节，根指令去重，入口存在；这是导航工具验证，独立新 Codex 会话演练仍 NOT_RUN。

统一命令：`./tooling/verify <scope>`；可选 `--report output/<文件>.json`、`--dry-run`。前提探测：`python3 tooling/workspace.py doctor <scope>`。Windows 使用 `python tooling/workspace.py verify <scope>`。语义见 [VALIDATION](../../ai/VALIDATION.md)。

## 实际结果

默认 cwd 是仓库根；完整 argv/cwd/时间/退出码由证据目录各 `.json` 和 `.log` 保存。`all` 失败后又分别执行了相关模块；这些结果不会覆盖原始全量失败。

| scope / 命令 | 退出码 / 状态 | 结果 |
|---|---|---|
| `verify workspace` | 0 / PASS | 入口、链接、字节预算、生成漂移、依赖边界，交付后复核 |
| `verify tooling` | 0 / PASS | 38 项；真实子进程失败/阻塞/参数/cwd、dry-run、报告与导航 |
| `verify hygiene` | 0 / PASS | `all` 内 5 步实际执行：跟踪文件、图标、版本、工作树/暂存区空白 |
| `verify security` | 1 / FAIL | `all` 内执行同一 scope；62 个本地提交、1 个已核实误报 |
| `verify all` | 1 / FAIL | 7 PASS、1 FAIL、0 BLOCKED、14 NOT_RUN |
| `verify web` | 0 / PASS | 79 项、vue-tsc、Web 构建 |
| `verify server` | 0 / PASS | 309 项，失败/错误/跳过均 0；Java 21 增量构建 |
| `verify desktop` | 0 / PASS（默认范围） | 桌面 UI、Rust 50 通过；原有系统凭据库 1 项 ignored 仍 NOT_RUN |
| `verify contracts` | 0 / PASS | 静态契约、Node 6 项、生成/TS 编译、MVC 3 项；MVC 已含于 server 总数 |
| `verify flutter` | 0 / PASS | tokens、analyze 无问题、27 项；保留 --no-pub |
| `doctor all` | 0 / 前提 PASS，验证 NOT_RUN | 22 步命令/声明输入存在，不证明 SDK/许可证/服务就绪 |
| `verify all --dry-run` | 0 / NOT_RUN | 从非根 cwd 调用绝对脚本，22 步均未执行 |
| `verify android` / `ios` | — / NOT_RUN | `all` 提前停止，未单独运行旧壳；本轮没有原生工程改动 |

Gitleaks 8.30.1 命中 HEAD 的 `frontend/src/platform/notificationDeliveryDeduper.ts:12`。已对照历史与当前源码，确认是普通 localStorage 命名空间，不是认证凭据。配置/历史不变，扫描仍 FAIL；证据仅保存规则、路径、行号、提交和分类。

## A00 未处理项与 A01 遗留项

- 当前新模块/lock 仍大量未追踪；缓存上的通过不等于干净克隆或 CI Java 17 / Node 20 通过。根 npm install/ci 不适用，子工程全新安装仍 NOT_RUN。
- A00 的 Android cmdline-tools/许可证和 CocoaPods 完整环境问题未处理；本轮未重跑 Flutter doctor，引用 A00 的 BLOCKED 记录。旧壳 CI 保留，正式 Flutter 移动建设未开展。
- REST 必填/开放 Map、WS 方向字段、Dart REST SDK 等缺口仍在；没有提前生成或冻结新的 A02 契约。
- 真实数据库/迁移、外部服务、浏览器/E2E、安装后桌面、系统凭据库、设备/LAN/后台、签名发布仍 NOT_RUN；既有 Vite/JDK 告警保留。
- A01 遗留：精确处置 Gitleaks 误报并复跑安全门禁；Windows 仅测试 launcher 参数映射，未原生执行；独立新会话演练未做。

A00 的目标路线措辞、统一安全入口和四状态缺口已在导航/工具范围处理；旧原生 CI 未转成 Flutter CI。早期测试的 macOS 临时目录别名断言和新增文件后的生成地图漂移已修复，原始失败日志保留，最终相关验证通过。

## Handoff 与 A02 启动条件

**具备启动 A02 契约治理工作的条件；尚不具备全门禁、干净快照或全平台验收通过的条件。** A00/A01 交付和相关 server/web/desktop/contracts/flutter 验证已具备，全量失败已分类为固定存储键误报。

用户另行启动后，重新核对 Git 状态、本卡和 A02 原卡；先确定完整工作树/lock 的隔离复现方式，安排精确误报处置和安全复跑。复用 `contracts/README.md`、ADR 0005、MVC code-first REST、WS schema、生成器、platform-ports/nativeBridge 与既有测试；不能照搬 openapi.yaml 模板形成第二权威源。按 A02 范围验证真实登录/会话与认证地址边界。

## 保护与回滚

source-before 记录 1430 个路径：937 个现存文件 SHA-256、493 个既有缺失状态。最终仅本轮文档/工具改动；原有缺失状态、业务源码、package/POM/Cargo/lock、SQL、协议生成物、CI/安全配置保留。Web static/app 106 文件、desktop dist 15 文件构建前后内容一致；缓存/测试报告正常更新。

只按本轮 diff 和 `output/mx-a01-2026-09-08/source-before/` 撤回明确变更，先核对后续用户编辑；禁止整个工作树 reset/checkout/clean。A00 历史卡和基线保持原样。本轮在 MX-A01 停止。
