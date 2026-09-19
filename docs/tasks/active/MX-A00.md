# MX-A00 — 本地事实审查与回归基线

日期：2026-09-08。任务状态：**PASS（MX-A00 文档审查与基线记录范围）**；原生平台完整环境有 BLOCKED，用户流程/设备/发布有 NOT_RUN。MX-A01 **未开始**，本轮在此停止。

## 基线与边界

- 分支：`feature/v0.3.1`；HEAD：`16efe67dcaae432afd62e4fa10e1d6abbf55c419`。
- 初始状态：`git status --short` 616 条；展开未追踪后 1279 条（修改 91 / 删除 493 / 未追踪 695），暂存区为空。大量目录迁移与新模块为已有用户改动。
- 实施包对照提交 `d7571ded11fa4d6e66fd0c05e37e0541c2004b23` 与 HEAD 分叉（33 / 4 个独有提交）；没有切换、重置或联网更新到该提交。
- 目标：Spring Boot；Vue Web / Vue + Tauri 桌面；Flutter Android/iOS；不采用 Capacitor。本轮记录旧壳及指令/CI 差异，保留用户工程，不把原型标为正式移动迁移完成。
- 已按现有适用指令读取实施包 README、AUDIT_BASELINE、TASK_INDEX、MX-A00；MX-A01 卡仅用于交接范围核对。未应用 templates、未修改 AGENTS/override。

## 完成项与交付

- [x] 记录分支、HEAD、分叉关系、未提交/未追踪状态及实际目录，保存源文件 SHA-256。
- [x] 核对并复用已有 Flutter、contracts、packages、架构 ADR、context/verify 入口。
- [x] 记录本机工具链、数据库/外部服务前提、API/WS 权威来源、认证/存储/生产联网边界。
- [x] 执行现有可用 Web、Maven、桌面 Rust、契约、工具及 Flutter 分析/测试；分类 ignored/doctor 问题，未用跳过测试代替通过。
- [x] 保存 Vite 产物备份与前后清单；构建后源码内容无变化、两处静态输出内容无差异。
- [x] 建立产品回归清单、未验证项、风险及下阶段任务，更新 docs 索引。
- [x] 最终文档校验和交付 diff 核对：workspace、空白与链接检查通过；新报告证据路径首次检查失败，已修正并复核。

| 本轮文件 | 变更 |
|---|---|
| [current-state.md](../../architecture/current-state.md) | 新增：事实、工具链、目录映射、协议/认证/数据、目标差异与复用边界 |
| [baseline.md](../../runbooks/baseline.md) | 新增：完整命令/退出码、结果、环境、告警、NOT_RUN 与产品回归矩阵 |
| 本任务卡 `docs/tasks/active/MX-A00.md` | 新增并更新：范围、状态、交接与停止条件 |
| [归档报告](../../reports/仓库审查/MX-A00本地事实与回归基线报告_2026-09-08.md) | 新增：按仓库规则本地归档的交付摘要与证据链接 |
| [docs/README.md](../../README.md) | 仅增加事实/基线/任务/报告导航，保留原有改动 |

## 命令与结果摘要

全部 argv、cwd、退出码、时间和日志见 baseline；下面不重复列独立命令已覆盖的测试。

| 检查 | 状态 |
|---|---|
| `./tooling/verify web` | PASS，79 项，类型检查、Web 构建通过，无跳过 |
| `./tooling/verify server` | PASS，309 项，失败/错误/跳过均 0；Java 21、增量构建 |
| `./tooling/verify desktop` | PASS（默认范围），桌面前端通过、Rust 50 项通过；原有凭据库测试 1 项 NOT_RUN |
| `./tooling/verify contracts` | PASS，Node 6 项、MVC 3 项及生成/编译；MVC 3 项也含于 server 总数 |
| `python3 -m unittest discover -s tooling/tests` | PASS，20 项 |
| Flutter token `--check`、`analyze --no-pub`、`test --no-pub` | PASS，分析无问题、测试 27 项 |
| `flutter doctor -v` | BLOCKED（完整环境）：Android cmdline-tools/许可证，CocoaPods 缺失；退出 0 不能等同平台就绪 |
| `./tooling/verify workspace`、交付 diff/链接检查 | PASS；链接检查首次发现本轮报告相对路径错误，修正后通过，原始失败日志保留 |

## 未验证项与风险

1. **可复现快照风险**：当前 HEAD 不含大量新模块和 lock；本地缓存上的通过不等于干净克隆/CI 通过。不得自动提交或 reset 来“整理”工作树。
2. **目标措辞与门禁冲突**：根/模块 AGENTS、部分手册及 Android/iOS CI 仍以 Capacitor 为正式路线；Flutter 当前仅有原型检查。需要下阶段治理，未在 A00 越界修改。
3. **原生环境边界**：Android SDK 存在，但命令行工具缺失、license 状态未知；CocoaPods 缺失，iOS SPM/插件构建适用性尚未核查。doctor 枚举到设备不代表实机验收。
4. **协议/客户端缺口**：REST 业务必填/开放 Map、WS 仅方向事件、Dart REST SDK 未完整；Flutter 无安全持久登录、磁盘 outbox、完整文件/广播等功能对齐。不能直接把 TS 应用代码搬给 Dart。
5. **运行/数据/发布边界**：真实数据库版本与已应用迁移、MySQL/Redis/MinIO 栈、浏览器/E2E、安装后桌面网络、系统凭据库、设备/LAN/后台、签名与发布全部未验。未确认凭据或服务缺失时只标 NOT_RUN。
6. **构建告警**：现有 Web 大分块/无效动态导入、JDK agent 警告保留；未调整阈值、依赖或安全检查。

## 下一阶段：MX-A01（仅交接，未执行）

执行前重读本卡、现有适用指令和 [MX-A01 原卡](../../proposals/meshx-architecture-kit/tasks/MX-A01.md)，重新 `git status --short`。按当前工作树映射旧路径，不恢复 frontend/root src，不重复迁目录。

| 顺序 | 待办与复用 | 验收/先修 |
|---|---|---|
| 1 | 审阅已有 AGENTS/ADR/context/verify，统一 Flutter 目标与旧壳现状措辞；复用 ARCHITECTURE、docs 索引和工作区清单 | 保留现有约束、API/应用身份；模板不整文件覆盖；避免两个竞争索引/运行器 |
| 2 | 列出未提交迁移和 lock 的完整快照边界，设计隔离复现方式 | 不能只拿 HEAD 建 worktree 后声称当前工程丢失；没有完整输入先记 BLOCKED，不自动推送/合并 |
| 3 | 按原卡评估根 npm workspace/lock 的必要整合，纳入实际 apps/web/desktop；当前已有共享包按实际消费核对 | 不升级主要依赖；先保留各 lock 作对照，再在可审阅变更中同步 CI/Docker 安装和验证；不切 pnpm/Nx/Turbo |
| 4 | 优先扩展现有 Python context/verify 的只读 doctor 和导航缺口，而非照抄 Node 脚本名新增一套 | 缺命令、失败、未知 scope 都真实非零；支持空格路径/Windows argv；继续保留 hygiene/Gitleaks |
| 5 | 在完整快照上验证干净安装、Web test/typecheck/双模式构建，按影响验证 server/desktop | 本机 Java 21/Node 26 基线与 CI Java 17/Node 20 区分；关键命令不能运行或失败未分类时先修，不开始代码迁移 |
| 6 | 为移动后续任务列出 SDK/许可证、CocoaPods或SPM核对及设备验收负责人/环境 | A01 不抢跑 Flutter 功能重写、协议修改或旧壳删除；A02～A08 保持待执行 |

## 回滚与停止

只撤销上述本轮新文档及 docs/README 新增导航；报告/日志按本地证据策略保留或单独处理。不得用整个工作树 `git checkout/reset/clean` 回滚，因为用户已有大量改动。`output/mx-a00-2026-09-08/docs-README-before.md` 保存本轮编辑前的索引；恢复也需先核对没有后续用户编辑。

本轮不自动进入 MX-A01；不提交、推送、合并、签名或发布。
