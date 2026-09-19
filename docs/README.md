# 文档与报告

- [MX-A07C-3 新主导航真实跨端验收](tasks/active/MX-A07C-3.md)：真实Vue与Android模拟器文本互通、联系人私聊、群成员、资料和退出PASS，C01仍为部分覆盖。

所有报告统一按 `reports/<分类>/<报告名称>_YYYY-MM-DD.<扩展名>` 保存。日期使用报告实际日期，放在名称末尾、扩展名前；迁移历史报告不改变原日期。

- [AGENTS 与 Skills 按需执行优化](reports/仓库审查/AGENTS与Skills按需执行优化_2026-09-12.md)：按需验证、技能触发与等待边界；含检查和恢复证据。
- [MX-A07B-5 Flutter 基础文件与图片消息](tasks/active/MX-A07B-5.md)：25 MiB 有界分块上传、附件 ACK outbox、授权下载/哈希、图片预览与系统保存/分享实现，102 项回归、双平台模拟器构建及真实 Spring 闭环 PASS；设备/发布另验。
- [MX-A07B-6 Flutter 广播接收与回执](tasks/active/MX-A07B-6.md)：待办/详情/查看/确认、图片凭证完成、技术账号卡片和定位只读边界，110 项回归、双平台模拟器构建及 57 项服务规则 PASS；真实 Web/手机闭环另验。
- [MX-A07B-7 Flutter 权限恢复与通知路由](tasks/active/MX-A07B-7.md)：普通/广播通知安全路由、账号竞态、iOS Settings公开API修复候选与人工fallback，115项回归、双平台模拟器及iPhone三轮回调PASS；C04视觉/点击另验。
- [MX-A07B-8 Flutter 平台可用性与最小诊断](tasks/active/MX-A07B-8.md)：用户预览后复制的脱敏支持信息、双端版本端口、连接阶段与稳定错误码，117项回归、双平台模拟器构建与iPhone Profile原生读取PASS；真机完整UI/性能/旧OS矩阵另验。
- [MX-A07B-9 Flutter 候选构建与 CI 准备](tasks/active/MX-A07B-9.md)：PREPARED_NOT_APPROVED候选清单、统一verify和三job工作流；Android/iOS unsigned release-mode工件本地验证，远程CI、正式身份/版本/签名/升级/发布另审。
- [MX-A07C-1 Flutter C08 Profile 性能切片](reports/实机验证/MX-A07C-1_Flutter性能切片验收_2026-09-13.md)：iPhone 16 Pro Max真机Profile分段预算PASS；Android 17虚拟机流程PASS但性能FAIL；正式main、真实冷启/OS恢复、Android真机及旧OS/读屏仍未验，Gate不关闭。
- [MX-A07C-2 Flutter冷进程与真实OS恢复](reports/实机验证/MX-A07C-2_Flutter冷进程与OS恢复验收_2026-09-13.md)：iPhone真机Profile 5次恢复型冷进程、主机launch→ready上界与5次真实Settings前后台恢复PASS；仍为开发签名测试HTTP入口，正式main/HTTPS及Android真机未验。
- [MX-A07B-10 Flutter 移动导航与会话整合](tasks/active/MX-A07B-10.md)：补做B02产品层Design System，普通用户“消息 / 联系人 / 群聊 / 广播”改为常驻移动主导航，119项Flutter回归PASS；模拟器视觉与真机门禁分别记录。
- [MX-A07B-4 Flutter 基本群聊切片](tasks/active/MX-A07B-4.md)：建群、成员查看、群聊与普通成员退群实现、94 项回归、双平台模拟器构建及真实 Spring/WS 闭环 PASS；设备/发布另验。
- [MX-A07B-3 Flutter 资料与设置切片](tasks/active/MX-A07B-3.md)：资料/头像/密码/主题偏好实现、87 项回归、Android/iOS 模拟器构建及真实 Spring 密码撤销闭环 PASS；设备/发布另验。
- [MX-A07B-2 Flutter 好友切片](tasks/active/MX-A07B-2.md)：好友/申请/搜索实现、回归、Android/iOS 模拟器构建及真实 Spring/WS 闭环 PASS；设备/发布另验。
- [MX-A07B-1R 契约冻结候选](reports/仓库审查/MX-A07B-1R_消息变更恢复契约冻结_2026-09-09.md)：READY_FOR_APPROVAL，[设计入口](proposals/mutation-recovery-v1/design.md)，未实施。
- [MX-A07B-1 消息变更与缓存一致性](reports/仓库审查/MX-A07B-1_消息变更与缓存一致性复现_2026-09-09.md)：真实Server/Dart离线变更与重启复现，BLOCKED_BY_CONTRACT，[契约补充提案](proposals/message-mutation-reconciliation-v1-supplement.md)待评审。
- [MX-A07A 功能矩阵与首发冻结](reports/仓库审查/MX-A07A_跨端功能矩阵与移动首发范围冻结_2026-09-09.md)：36项跨端事实、移动v0.3.1范围、阻塞门禁及后续清单。
- [MX-A06 Flutter 系统能力与真机验收](reports/实机验证/MX-A06_Flutter系统能力与真机验收_2026-09-09.md)：iPhone真实Wi-Fi发现、前后台补拉与权限/文件证据，保留Android真机及完整矩阵边界。
- [MX-A05 Flutter 移动文字聊天验收](reports/实机验证/MX-A05_Flutter移动文字聊天验收_2026-09-09.md)：双模拟器真实 Web 互通、持久恢复、安全存储与明确边界。

## 工程文档

| 入口 | 用途 |
|---|---|
| [AI 索引](ai/INDEX.md) / [任务源码路由](ai/REPO_MAP.md) / [统一验证](ai/VALIDATION.md) | 从修改意图找到指令、实现、测试和状态语义 |
| [治理任务与 handoff](tasks/README.md) | 用户指定阶段的实际进度、证据与下一阶段启动条件 |
| [架构地图](../ARCHITECTURE.md) / [生成模块地图](generated/repo-map.md) | 当前模块、入口和依赖方向 |
| [产品范围](product/overview.md) | 功能边界与术语 |
| [跨端功能矩阵](product/product-parity-matrix-v0.3.1.md) / [移动v0.3.1范围](product/mobile-release-scope-v0.3.1.md) | 当前实现与移动Included/Deferred/平台差异，A07A冻结目标不等于发布就绪 |
| [移动Release Gates](product/mobile-release-gates-v0.3.1.md) / [A07B与A07C清单](product/mobile-v0.3.1-work-plan.md) | 阻塞门禁、Settings/安全处置计划、精确开发和真机验证 |
| [Flutter候选构建准备](product/flutter-candidate-readiness-v0.3.1.md) | B11候选输入、统一命令、CI工件边界与仍需批准的身份/版本/签名/升级事项 |
| [平台适配](architecture/platform-adapters.md) | 共享接口与原生实现 |
| [Flutter 对照原型](../apps/flutter-prototype/README.md) / [验证决策](adr/0003-flutter-prototype.md) | 实际运行方式、移动端选型门槛 |
| [工作区 ADR](adr/0001-workspace.md) / [客户端选型 ADR](adr/0002-client-runtime.md) | 为什么这样组织、何时重新选型 |
| [协议入口](../contracts/README.md) / [REST API 导航](generated/api-summary.md) / [SQL 索引](generated/sql-map.md) | 协议覆盖边界与代码定位 |
| [本地开发](runbooks/local-dev.md) / [验证说明](runbooks/testing.md) / [完整手册](runbooks/project-guide.md) | 安装、运行、测试与部署 |
| [跨端设计体系](design/README.md) / [Vue 细则](../apps/web/DESIGN.md) | 中立 token、组件状态、平台适配与验证 |
| [目标路线 ADR](adr/0004-client-target-and-contracts.md) / [归档说明](archive/README.md) | Flutter 迁移目标与历史材料位置 |
| [跨会话计划](exec-plans/README.md) | 进行中工作、模板与交接 |
| [按需上下文](architecture/repository-context.md) / [事实来源 ADR](adr/0005-repository-context-and-contract-authority.md) | 模块导航、字节预算、证据与说明的区别 |
| [契约盘点](../contracts/inventory.md) / [端点认证 ADR](adr/0006-endpoints-auth-and-contract-generation.md) / [兼容门禁](../contracts/compatibility/README.md) | REST/WS 跨语言生成、共享样例、来源和行为边界 |
| [TS Core 与小型 Ports ADR](adr/0007-typescript-shared-core-and-narrow-ports.md) / [MX-A03 交接](tasks/active/MX-A03.md) | 聊天规则、平台接口、兼容入口和架构依赖检查 |
| [Design Token source](../packages/design-tokens/README.md) / [六类组件规范](design/component-specs/README.md) / [MX-A04 交接](tasks/active/MX-A04.md) | 三层 Token、CSS/Dart 生成、视觉基线与设计债 |
| [当前事实](architecture/current-state.md) / [本地回归基线](runbooks/baseline.md) / [MX-A00 交接](tasks/active/MX-A00.md) | 2026-09-08 工作树审查、命令证据和下一阶段边界 |

持续维护的工程文档随源码提交；`archive/local` 下历史 PRD/TODO 和 reports 是本地资料，不能作为新克隆必备依赖。

## 报告索引

| 分类 | 报告 | 报告日期 |
|---|---|---|
| 实机验证 | [MX-A07C-2 Flutter冷进程与真实OS恢复验收](reports/实机验证/MX-A07C-2_Flutter冷进程与OS恢复验收_2026-09-13.md) | 2026-09-13 |
| 实机验证 | [MX-A07C-1 Flutter Profile 性能切片验收](reports/实机验证/MX-A07C-1_Flutter性能切片验收_2026-09-13.md) | 2026-09-13 |
| 界面设计 | [MX-A07B-10 跨端信息架构与 Flutter 主导航](reports/界面设计/MX-A07B-10_跨端信息架构与Flutter主导航_2026-09-13.md) | 2026-09-13 |
| 界面设计 | [MX-A04 Design System Foundation 报告](reports/界面设计/MX-A04设计基础与视觉回归报告_2026-09-09.md) | 2026-09-09 |
| 仓库审查 | [MX-A03 TypeScript 共享 Core 与平台适配报告](reports/仓库审查/MX-A03TypeScript共享Core与平台适配报告_2026-09-09.md) | 2026-09-09 |
| 仓库审查 | [MX-A02 契约治理与跨端基础报告](reports/仓库审查/MX-A02契约治理与跨端基础报告_2026-09-09.md) | 2026-09-09 |
| 仓库审查 | [MX-A01 AI 上下文与统一验证报告](reports/仓库审查/MX-A01AI上下文与统一验证报告_2026-09-08.md) | 2026-09-08 |
| 仓库审查 | [MX-A00 本地事实与回归基线报告](reports/仓库审查/MX-A00本地事实与回归基线报告_2026-09-08.md) | 2026-09-08 |
| 商业分析 | [MeshX 变现分析报告](reports/商业分析/MeshX变现分析报告_2026-09-05.md) | 2026-09-05 |
| 实机验证 | [Mac 与浏览器实机验证报告](reports/实机验证/Mac与浏览器实机验证报告_2026-09-05.md) | 2026-09-05 |
| 实机验证 | [多端验证报告](reports/实机验证/多端验证报告_2026-09-05.md) | 2026-09-05 |
| 实机验证 | [跨端界面与消息广播修复验收报告](reports/实机验证/跨端界面与消息广播修复验收报告_2026-09-05.md) | 2026-09-05 |
| 实机验证 | [Android 导航徽标运行态复验](reports/实机验证/Android导航徽标运行态复验_2026-09-06.md) | 2026-09-06 |
| 实机验证 | [Android 移动广播完成流复验](reports/实机验证/Android移动广播完成流复验_2026-09-06.md) | 2026-09-06 |
| 实机验证 | [Android 广播系统通知点击链路复验](reports/实机验证/Android广播系统通知点击链路复验_2026-09-06.md) | 2026-09-06 |
| 实机验证 | [桌面运行时桥接与全屏复验](reports/实机验证/桌面运行时桥接与全屏复验_2026-09-06.md) | 2026-09-06 |
| 实机验证 | [iOS 模拟器移动登录与壳同步复验](reports/实机验证/iOS模拟器移动登录与壳同步复验_2026-09-06.md) | 2026-09-06 |
| 实机验证 | [Android 最新包键盘发送与群聊回执复验](reports/实机验证/Android最新包键盘发送与群聊回执复验_2026-09-06.md) | 2026-09-06 |
| 实机验证 | [隔离栈广播通知耐久投递与回执复验](reports/实机验证/隔离栈迁移与广播通知账户复验_2026-09-06.md) | 2026-09-06 |
| 实机验证 | [跨端续验与实体设备阻断](reports/实机验证/跨端续验与实体设备阻断_2026-09-06.md) | 2026-09-06 |
| 实机验证 | [iPhone 实体机与 Windows 虚拟机重试](reports/实机验证/iPhone实体机与Windows虚拟机重试_2026-09-06.md) | 2026-09-06 |
| 实机验证 | [原生应用图标模拟器与虚拟机验证](reports/实机验证/原生应用图标模拟器与虚拟机验证_2026-09-08.md) | 2026-09-08 |
| 仓库审查 | [GitHub 仓库审查与清理报告](reports/仓库审查/GitHub仓库审查与清理报告_2026-09-05.md) | 2026-09-05 |
| 仓库审查 | [Flutter移动端对照原型验收报告](reports/仓库审查/Flutter移动端对照原型验收报告_2026-09-08.md) | 2026-09-08 |
| 仓库审查 | [工作区架构重构验收报告](reports/仓库审查/工作区架构重构验收报告_2026-09-08.md) | 2026-09-08 |
| 仓库审查 | [协议与设计体系及工作区整理验收报告](reports/仓库审查/协议与设计体系及工作区整理验收报告_2026-09-08.md) | 2026-09-08 |
| 仓库审查 | [仓库分层上下文与建议采纳报告](reports/仓库审查/仓库分层上下文与建议采纳报告_2026-09-08.md) | 2026-09-08 |
| 界面设计 | [界面设计审查报告](reports/界面设计/界面设计审查报告_2026-09-05.md) | 2026-09-05 |

## 保存约定

- 新报告按主题进入对应分类，并更新此索引；新主题可增加分类。
- README、开发规范等持续维护的说明文档不属于报告，无需附加报告日期。
- `reports/` 为本地报告归档，沿用 Git 忽略规则；截图、日志和安装包保留在原证据目录，报告内使用相对链接引用。
- 报告描述的是正文日期对应的状态；查看开发进度时同时参考更新日期更晚的报告与当前代码。
