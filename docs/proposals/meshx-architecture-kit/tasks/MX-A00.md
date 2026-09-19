# MX-A00 — 本地事实审查与可复现基线

## 目标与边界

以当前本地工作树为事实来源，不假设远程 d7571ded 就是最新代码。只新增/更新事实文档和任务交接，不改业务逻辑、目录、依赖、schema、签名或数据。

## 必须读取

已有适用 AGENTS/override；本包 AUDIT_BASELINE.md；根 pom.xml；frontend/package.json 与 vite.config.ts；apps/desktop 的 package.json/tauri.conf.json；已有 CI；与本任务有关的运行说明。先定位本地是否已有 Flutter、contracts、ADR 和 workspace，存在则优先复用。

## 操作

记录分支、HEAD、git status 与未提交/未追踪文件，不能覆盖用户改动。记录 Node/npm、Java、Maven Wrapper、Rust/Cargo、Flutter/Dart、Android SDK、Xcode 的实际可用版本。缺失项留为 BLOCKED，不全局安装或自动升级。

确认前后端、数据库、Redis、MinIO 等测试环境要求，不把单元测试误认成端到端测试。先列出可执行命令，再在环境允许时运行；需要本地依赖安装时只用受信任的已提交 lock，不自动 audit fix。检查构建是否会修改静态生成目录，并记录前后差异，不删除用户文件。

最少尝试已有 Web test/typecheck；可行时 Web/desktop 模式构建、Maven test、桌面 cargo check/test。实际命令从本地脚本核实，不能声称本包已经验证可运行。

建立产品回归清单：登录/刷新/退出、会话与文本、消息同步/outbox、好友群组、广播、文件与断点续传/当前直传流程、节点发现、亮暗主题、Web 静态部署、桌面发布构建。仅记录实际发现的功能，不把此前聊天愿望当成现有实现。

## 交付

`docs/architecture/current-state.md`：入口、工具链、协议来源、关键耦合、当前目标与事实差异。
`docs/runbooks/baseline.md`：命令、环境、退出码、PASS/FAIL/BLOCKED/NOT_RUN、必要日志摘要。
`docs/tasks/active/MX-A00.md`：任务状态、基线 HEAD、下一步 A01。

## 验收

审查 diff：无业务/依赖/目录迁移；未提交改动得到保护；每条“通过”有真实执行证据；未审查的认证/数据/生产联网问题明确列为待核查。基线失败分清既有失败与环境问题，不能默认是此次任务造成，也不能忽略。

## 回滚与停止

仅回滚本任务新增文档，不碰运行数据和用户文件。本轮到此结束。关键基线不能运行或存在未分类失败时，不开始后续代码迁移；可在下一任务先解决环境或建回归测试。
