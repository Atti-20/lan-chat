# ADR 0001：分层 Monorepo 与按需上下文

状态：接受。日期：2026-09-08。

## 背景

服务源码原在根 `src/`，共享 UI 原在 `frontend/`，原生壳已在 apps。根 README 较长，历史 PRD/TODO/报告包含不同时间的实现状态。新任务难以快速判断入口、依赖方向与验证范围。

## 决策

1. Web 应用进入 `apps/web`，服务进入 `services/server`。根 Maven wrapper 与聚合 POM 保留，SQL 和部署上下文仍在根。
2. 抽出 protocol、domain-ts、platform-ports、design-tokens 的真实实现；以应用内转导出兼容已有 import，不复制定义。
3. 保留各应用的 npm lockfile；共享源码通过相对 import 消费，暂不引入根 npm workspace、pnpm 或发布包。多语言 Monorepo 不要求统一所有包管理器。
4. 根 AGENTS 只放路由、稳定约束和验证入口；模块指令各自维护。架构/决策/运行手册/短期计划分开保存。
5. `tooling/workspace.json` 声明模块入口和验证命令；标准库脚本生成地图并检查依赖方向、入口链接和协议字段漂移。
6. 仅对已完成的契约生成声明单一事实来源：WS 信封从 schema 生成 TS，Java 字段形状由 CI 核对。REST 先给真实代码索引，不创建空 OpenAPI 冒充完成。

## 后果

新任务可按模块读取小入口；目录/接口/生成物漂移能在 CI 暴露。迁移引入了构建路径调整与少量兼容导出；CI、Docker、Tauri、Capacitor 和 Android 增量构建都必须覆盖共享 packages。

完整 API SDK、独立 UI 包以及另一种 UI 技术的主题生成，只在出现明确消费者并完成契约/构建验证时添加。Flutter/Harmony 客户端不能直接复用 TS 运行时代码，需从协议生成各自模型并执行一致性测试。

## Codex 依据

官方说明：Codex 启动时沿项目根到当前目录建立指令链，靠近当前目录的指令更具体；默认总大小有限。因而根指令要求主动读取目标模块文件，不能假定在根启动会自动包含所有子目录。[AGENTS.md 官方文档](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
