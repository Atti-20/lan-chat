# MX-A07B-1R — Message Mutation Recovery Contract Review & Freeze

2026-09-09；结果 **READY_FOR_APPROVAL**。设计冻结候选已交付，实施批准尚未取得；**非IMPLEMENTED**。本轮仅契约评审、目标期望和迁移/回滚设计，1I/B-2/C均未启动。

## 基线和输入核对

branch `feature/v0.3.1`，HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`，dirty。先保存[状态](../../../output/mx-a07b1r-2026-09-09/git-status-before.txt)和[基线](../../../output/mx-a07b1r-2026-09-09/baseline.json)。根及目标路径无AGENTS.override，使用根、contracts/docs/tooling、server/Web/Flutter指令；AI入口、A07B-1 task/handoff、提案/报告、旧向量、REST/WS README、ADR0006/0008均已核对。

只读当前ChatMessageServiceImpl/ConversationServiceImpl/ChatWebSocketHandler与Dart ChatController/FileChatStore、Web useChat/localChatDb的相关恢复边界。A07B-1真实失败证据沿用已验证文件，没有重新启动fixture/真实复现/设备测试。没有用历史313/119/68测试数冒充本轮验收。

## 11项交付

| 要求 | 冻结材料 |
|---|---|
| 最终contract design | [design.md](../../proposals/mutation-recovery-v1/design.md)，D01–D08 |
| REST/WS capability proposal | [wire.md](../../proposals/mutation-recovery-v1/wire.md)，W01–W05 |
| mutation record schema proposal | [proposal JSON Schema](../../proposals/mutation-recovery-v1/mutation-record.schema.json)，独立于生产schema |
| cursor semantics | design D01/D04，per-user epoch+decimal cursor，独立message cursor |
| snapshot/recovery state machine | design D03，S/H manifest→分页→固定F→原子提交→ready→ONLINE_SAFE |
| compatibility matrix | design D08，四个old/new组合及最低能力政策 |
| retention/expiration policy | design D04，至少30天、floor包含边界、15分钟session pin及明确rebuild |
| migration plan | [implementation.md](../../proposals/mutation-recovery-v1/implementation.md)，加法迁移/全实例写屏障/先服务端再客户端 |
| rollback plan | 同文档，保留日志和dual-write；降级/备份恢复换epoch与quarantine |
| approved-target test vectors | [38项目标期望](../../../contracts/test-vectors/mutation-recovery-v1-targets.json)，状态TARGET_PENDING_APPROVAL；批准后执行；旧characterization不改 |
| 1I implementation checklist | implementation I01–I12，事务、两端Core/持久化、权限、已读、通知、真实集成与兼容验收 |

## 冻结决策摘要

per-user而非per-conversation/device流，确保失权后仍收到最小失效通知及多设备独立恢复。类型包含recalled/burned/unavailable/access revoked/access changed，READ保持本人私有位点不入普通流。好友删除可读不可发，群移除读发拒绝并失效缓存/队列。

对象终态单调，accessVersion允许显式重入但旧outbox不复活；旧原文/快照不能越过墓碑。未知协商安全状态隔离，不能跳cursor。

manifest边界H由同一DB一致读视图取得，记录ID/版本而不复制正文；分页读当前状态并验权，H后mutation重放到固定F，完整原子持久化后ready确认才ONLINE_SAFE。当前v1正文仅候选，必须经带版本恢复快照确认。新会话/消息的局部manifest流程明确定义，性能待1I容量门禁；不假定可直接复用无版本正文覆盖安全缓存。

默认不启用旧server full-rebuild fallback，未认证时选择明确阻断策略。需要整体审批的产品取舍：断开/启动先隔离旧正文、至少30天日志、300ms实际可见并按连续已读前缀推进、旧队列权限变化后禁止自动复活。这些不是现有行为已达到的结论。

## 验证、范围审计和安全

[本轮报告](../../reports/仓库审查/MX-A07B-1R_消息变更恢复契约冻结_2026-09-09.md)保存精确命令、状态和限制；[证据目录](../../../output/mx-a07b1r-2026-09-09/)仅本地验证产物。

设计artifact验证：`node docs/proposals/mutation-recovery-v1/validate.mjs`；验证5个合法/8个非法记录（含BigInt范围）、38项唯一目标/22项必需覆盖以及安全期望约束。**不是执行目标业务状态机**，TS/Dart/Java目标行为全部NOT_RUN，留1I实现。

各scope先`./tooling/verify <scope> --dry-run`，再运行workspace/tooling/hygiene/contracts/security并保存JSON；workspace/tooling（58测试）/contracts/hygiene、提案artifact校验及变化设计Gitleaks均PASS，187本地链接无断链，范围审计无越界/新增丢失。本轮不修改安全扫描配置；全历史保留FAIL已核实历史误报，变化设计/向量独立扫描。运行时代码、生产REST/WS schema、SQL、依赖锁、旧characterization及B-1证据保持原样，以[preservation.json](../../../output/mx-a07b1r-2026-09-09/preservation.json)为准。

## Handoff / 停止

无生产migration文件/SQL执行，无消息运行时或Flutter/Web逻辑改动，无commit/push/merge/release。新提案schema未接入生产生成器或接受兼容基线。现有B-1安全问题仍未修复，R02/G03不关闭。

下一步是审批整个候选（特别是严格离线隔离和旧服务器阻断政策），批准后再单独授权1I。准备好进入审批不等于准许执行实现或数据库操作。本轮在 **READY_FOR_APPROVAL** 停止；A07B-1I、A07B-2、A07C均NOT_STARTED。


## 完整移动迁移续接（2026-09-13）

已再次核对冻结候选、实施清单和产品取舍，并向用户请求整体批准及启动本地1I实现（服务端/契约/Web/Flutter，不含生产数据库执行或发布）。当前尚未收到答复，保持READY_FOR_APPROVAL，不能把自动目标续接消息视为审批。独立功能、图标和本地构建仍可推进。


## 实施审批已收到（2026-09-14）

用户明确回复“批准”，授权冻结候选及1I本地服务端/契约/Web/Flutter实现与验证；不包含生产数据库执行、发布或应用身份/签名变更。此前READY_FOR_APPROVAL记录保留为历史；当前设计状态APPROVED_FOR_LOCAL_IMPLEMENTATION，实施进度见[MX-A07B-1I](MX-A07B-1I.md)。
