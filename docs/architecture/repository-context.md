# 按需加载仓库上下文

目标是让新任务快速定位相关代码与验证方式。模块导航由 `tooling/workspace.json` 维护；不把路线图、历史报告和所有平台实现放进每次任务的入口。

## 使用

```sh
python3 tooling/workspace.py context flutter
python3 tooling/workspace.py context contracts --json
python3 tooling/workspace.py context chat
python3 tooling/workspace.py context tauri
python3 tooling/workspace.py context api
./tooling/verify contracts --dry-run
```

从 [AI 索引](../ai/INDEX.md) 选择任务；context 只输出目标模块/任务的指令路径、源码/测试入口、相关规范和验证范围，不加载源码或执行验证。Android / iOS 指保留的旧 Capacitor 壳；Flutter 使用 `flutter`，不存在代表迁移完成的 `mobile` 模块。chat/tauri/api 三条路线按清单组合所需模块，不需要逐项扫描仓库。

先读输出的仓库指令，再选与任务有关的文件。完整 REST JSON 较大，先读 [API 导航](../generated/api-summary.md)，再展开该 Controller 的路由和具体 schema。SQL 索引描述脚本位置，数据库当前结构与迁移是否执行仍须按任务核实。

## 指令加载与预算

Codex 启动时按仓库根到当前工作目录的路径发现指令；不能假定从根启动时所有子目录指令已自动加载。修改目标目录前主动读取对应 AGENTS。官方规则还包含覆盖文件与可配置的大小上限，见 [官方说明](https://learn.chatgpt.com/docs/agent-configuration/agents-md)。

context 仅列出仓库内标准 `AGENTS.override.md / AGENTS.md` 的祖先链，优先非空 override；它不读取用户全局配置、个性化 fallback 文件名或会话状态，也不替代 Codex 自己的指令加载。

本仓库检查根 AGENTS 不超过 4,096 UTF-8 字节、模块指令不超过 4,000 字节；单次 context 的文本与 JSON 摘要均不超过 4,096 字节。这些是仓库约束，**不是 token 统计，也不证明实际会话开销下降多少**。输出中的 instructionBytes 只累计所列仓库指令文件，不含源码、规范、全局规则或历史对话。规则核对日期 2026-09-08；官方文档的会话上限与这里的仓库预算分别解释。

新增模块必须填写可访问的源码入口、relatedDocs 及其适用场景、verificationScopes。workspace 检查会拒绝断链、未知验证范围、过长摘要和过期生成物。

## 展开规则

- 日常修改：根及模块指令、相关实现和现有测试。
- 跨模块或架构变更：再读 ARCHITECTURE、相关 ADR 和平台接口。
- 协议变更：结构、Handler / Service 行为与兼容测试一起核对。
- 多轮工作：维护用户指定的活动计划。编号治理任务在 tasks/active 保留状态/handoff，其他 exec-plans 任务完成后移入 completed；见 [任务机制](../ai/WORKFLOW.md)。

历史报告、completed 计划和其他平台仅在任务确实需要时读取；这不阻止为定位跨端故障扩大调查。验证范围同样按影响选择，涉及安装、键盘、通知、后台或 LAN 时补充实际运行证据。

项目事实冲突处理见 [ADR 0005](../adr/0005-repository-context-and-contract-authority.md)。
