# 文档维护

- 持续规范按 `product/ architecture/ adr/ runbooks/` 保存；首次定位优先 README 索引，不读取全部文档。
- ADR 记录决策和替代方案。编号治理任务按用户指定路径 `tasks/active/MX-*.md` 维护唯一状态/handoff；其他跨会话任务使用 `exec-plans/active/`，完成后归入 `completed/`。共同复用 exec-plans/template.md，不在根 AGENTS 写短期进度。
- `generated/` 由根 `tooling/workspace.py generate` 更新，不手改。
- 所有报告在 `reports/<分类>/<名称>_YYYY-MM-DD.<扩展名>`，日期置于扩展名前并与正文一致；优先复用商业分析/实机验证/仓库审查/界面设计分类。新增或迁移同步更新 README 和引用；迁移历史报告保留原日期。
- 截图、日志、安装包留在各自本地输出目录，报告用相对链接引用；reports/output 维持原 Git 忽略策略。
- `ai/INDEX.md` 是人工维护的任务入口；`ai/REPO_MAP.md` 从 workspace.json 生成，不能手改或复制一份全仓文件列表。验证语义统一写在 ai/VALIDATION.md。
- 历史 PRD/TODO 和旧验证数字不自动升级成当前结论。验收范围明确区分静态检查、构建、设备、LAN、签名与发布。
- 文档/目录变更后在根运行 `./tooling/verify workspace`。
