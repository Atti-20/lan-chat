# MX-A07A — Product Parity Matrix & Release Scope Freeze

日期：2026-09-09。状态：**A07A已完成并停止**。只执行A07A；A07B/A07C/A07D/A08未启动。无发布、commit、push、merge。

## 起点与授权

分支 `feature/v0.3.1`，HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；dirty。已在读取源码前保存 [git status](../../../output/mx-a07a-2026-09-09/git-status-before.txt) 和 [1530路径基线](../../../output/mx-a07a-2026-09-09/baseline.json)，其中493路径是起点就已删除，不是本轮删除。保留A05/A06卡片、ADR与原始输出。

已核对A06任务/handoff与原始JSON：真实iPhone Wi-Fi LAN、权限拒绝/恢复、停止、后台222→223补同步、Keychain、本地文件及OS通知请求的有限PASS；Settings请求FAILED、Android无真机BLOCKED、横幅/点击/完整OS/独立服务器/升级/签名/CI未验收仍保留。没有重新操作设备或把历史证据升级为A07A新测试。

## 正式交付（事实/目标/门禁分别维护）

1. [Product Parity Matrix](../../product/product-parity-matrix-v0.3.1.md)：36能力、五端状态、源码/契约/测试、模拟器与真机证据、缺口和优先级。
2. [Mobile Release Scope v0.3.1](../../product/mobile-release-scope-v0.3.1.md)：Included / Deferred / Platform-specific / Blocked，移动接收侧广播与基础文件纳入P0，管理/定位证明等明确拆分。
3. [Release Gates](../../product/mobile-release-gates-v0.3.1.md)：17个BLOCKING Gate、允许带限制首发和延期项；iOS Settings缺陷/fix-fallback标准、安全精确指纹处置计划。
4. [A07B/A07C精确清单](../../product/mobile-v0.3.1-work-plan.md)：B01–B11开发责任/接口/完成条件与C01–C10真实验证、Android最小验收卡。仅计划，不构成执行授权。

## 关键冻结结论

- 现有Server/Web产品确有好友、群创建、文件、临时房间、广播回执与管理；Tauri复用Vue并有独立原生边界。Web并没有群改名/成员增删/管理员入口，不能声称全端早已对齐。
- Flutter已验证的是可靠文字与部分系统能力；好友目录补名、成员名字、生成设备API常量或文件picker不等于完整对应产品功能。
- 移动P0包括已有账号、会话/可靠文字、基本好友/群聊、安全消息接收、LAN、节点文件/图片、广播接收/基本回执、通知、profile/settings与基础平台可用性。Web/桌面保留组织管理角色；不以完全相同页面为目标。
- Android/iOS目前均NOT_RELEASE_READY。真实Android、Settings恢复、完整OS/升级/通知点击/性能/旧OS、生产HTTPS LAN、目标身份策略、远程CI、签名/候选工件及安全门禁不能靠单测豁免。
- 没有要求推翻技术路线或阻止所有A07B切片的架构缺陷；生产HTTPS发现匹配、旧缓存变更/授权失效、v1登录与v2身份策略是具体前置风险。若现有契约不能支撑安全接收，只阻塞相关切片并另行提案，不在A07A修改schema。

## 验证与安全

本轮文档结构/链接审计、workspace、tooling（58测试）、hygiene、变化文档独立Gitleaks均 **PASS**。源码/规则/锁与原有文件保留审计PASS，没有新增丢失路径。业务测试、模拟器、真机、远程CI和发布均 **NOT_RUN**（本阶段不实施这些动作）。

命令均从仓库根执行，先运行对应`--dry-run`：

| 命令/检查 | 状态 / exit | 本轮证据 |
|---|---|---|
| `python3 tooling/workspace.py generate` | PASS / 0；生成导航无新增差异 | `output/mx-a07a-2026-09-09/generate.log` |
| `./tooling/verify workspace --report output/mx-a07a-2026-09-09/workspace.json` | PASS / 0 | 同名JSON/log |
| `./tooling/verify tooling --report output/mx-a07a-2026-09-09/tooling.json` | PASS / 0；58测试 | 同名JSON/log |
| `./tooling/verify hygiene --report output/mx-a07a-2026-09-09/hygiene.json` | PASS / 0 | 同名JSON/log |
| 文档本地链接/36唯一能力/五端枚举/证据字段/改动范围检查 | PASS / 0 | [document-audit](../../../output/mx-a07a-2026-09-09/document-audit.json) |
| `gitleaks dir output/mx-a07a-2026-09-09/changed-docs --config .gitleaks.toml --redact=100 --no-banner --report-format json --report-path output/mx-a07a-2026-09-09/changed-docs-gitleaks.json` | PASS / 0；无命中 | 同名JSON/log |
| `./tooling/verify security --report output/mx-a07a-2026-09-09/security.json` | FAIL / 1；原有精确指纹 | 同名JSON/log |

修改清单仅为4份新的product文档、本卡，以及`docs/README.md`、`docs/product/overview.md`、`docs/tasks/README.md`三处索引；另存本地忽略的 [审查报告](../../reports/仓库审查/MX-A07A_跨端功能矩阵与移动首发范围冻结_2026-09-09.md)。

已重跑全历史security：**FAIL**，Gitleaks8.30.1、原配置、完整历史，1条finding与A06的Rule/File/Line/Commit/Fingerprint完全一致，见 [本轮对比](../../../output/mx-a07a-2026-09-09/security-comparison.json)。实际规则/ignore未修改；只制定后续精确指纹处置和负向检测验收。

## Handoff与停止

下一阶段须由用户明确授权。A07B先读本卡→四份正式交付→目标模块AGENTS与对应源码/测试；不要重做A00–A06。B01/B03先处理契约消费和一致性前提；独立好友/资料切片可继续，不等待Android硬件。A07C无法得到Android真机时继续BLOCKED；不同平台的Release Ready单独判定。

本轮仅修改文档/索引及必要生成导航；回滚只针对本轮新增文档和索引行，不恢复整个dirty树。安全规则、业务源码、数据库、依赖锁、应用ID/最低OS/签名保持起点。最终改动与验证证据见 [A07A输出目录](../../../output/mx-a07a-2026-09-09/)。

## 完成条件逐项回答

| 问题 | 结论 |
|---|---|
| 1. 每个主要功能在哪些端存在？ | Matrix的36行给出Server/Web/Tauri/Flutter Android/iOS与实际入口；群管理也包含Web缺口，不仅检查Flutter |
| 2. 移动哪些已验证？ | A05双模拟器可靠文字/恢复；A06 iPhone真实Wi-Fi LAN/前后台及有限权限、文件、通知请求；逐项范围在E05/E06，不包含未跑的发布矩阵 |
| 3. 首发包含什么？ | Scope Included：已有账号、会话/可靠文字、安全接收、好友/基本群聊、LAN、基础文件/图片、广播接收/基本回执、通知、profile/settings/基础质量 |
| 4. 哪些延期？ | P1注册/复杂群管理/房间/设备管理/完整续传/定位证明/高级消息；P2手机管理台/广播发起管理/直传/远程Push等；不删除现有Web/Server能力 |
| 5. Android还缺什么？ | P0代码与全部目标Gate，特别是实体Android BLOCKED；模拟器不替代OEM/生命周期/签名/性能 |
| 6. iOS还缺什么？ | P0代码、Settings fix/fallback、通知视觉/点击、完整OS/升级/旧OS/性能、正式HTTPS/签名/CI；现有Profile探针不是正式RC |
| 7. 真正阻塞Gate？ | Gates的G00–G16；Android/iOS分别关闭，单测数量不能豁免 |
| 8. A07B写哪些代码？ | B01–B11明确路径、已有API、依赖、非目标与完成条件；不自动实施 |
| 9. A07C做哪些验证？ | C01–C10及Android最小卡：真实后端/设备/网络、权限/文件/通知、OS、升级、性能、安全、远程CI与工件 |
| 10. 契约/架构是否阻止开发？ | 没有必须全线停工/推翻架构的证据；R01生产TLS发现、R02旧变更与缓存/授权、R04身份策略会阻塞相关验收。不能安全用现有契约解决时另提受控任务，其余独立功能可开发 |
