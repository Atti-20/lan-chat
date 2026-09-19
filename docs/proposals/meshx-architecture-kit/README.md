# MeshX 多端架构与 Codex 实施包

编制日期：2026-09-08。
检查基线：`Atti-20/lan-chat`，`master`，提交 `d7571ded11fa4d6e66fd0c05e37e0541c2004b23`。
确定路线：Spring Boot 服务端；Vue + Tauri 用于 Web/桌面；Flutter 用于 Android/iOS；HarmonyOS 单独评估，不承诺直接编译。

## 这个包是什么

这是根据远程仓库关键文件整理的**实施规范、任务卡和指令模板**，不是已经完成的重构补丁，不是可直接编译的 MeshX 工程。没有修改、推送你的仓库，也没有在本环境运行 MeshX 的测试或构建。当前本地分支与检查基线可能不同，Codex 必须先核对，不能重置到这个提交。

把本目录整体放入仓库的 `docs/proposals/meshx-architecture-kit/`。不要把 `templates/` 直接覆盖进项目。新任务先打开 `START_CODEX.md`，再按 `TASK_INDEX.md` 一次执行一张任务卡。除首次负责总体规划的会话外，不要求每次读取整个实施包。

## 内容入口

- `START_CODEX.md`：可以粘贴给 Codex 的首轮任务。
- `AUDIT_BASELINE.md`：本次实际核对的事实、风险与未验证范围。
- `IMPLEMENTATION_PLAN.md`：目标架构、迁移边界、协议、适配、设计、测试与发布规则。
- `TASK_INDEX.md` 和 `tasks/`：分阶段任务卡，含范围、非目标、验收和回滚。
- `templates/`：根/模块 AGENTS、架构图、任务交接、ADR 的候选模板。
- `SOURCES.md`：固定提交的源码位置及官方资料；源码事实与架构建议分开。

## 实施纪律

**先保存基线，再添加边界，再迁移使用者，最后考虑搬目录。**

当前仓库已经是同一 Git 仓库内包含多个工程，不必为了“Monorepo”把 Maven 服务端立刻搬家。首轮保留 `frontend/`、根 `pom.xml`/`src/` 和 `apps/desktop/`。不切换包管理器、不升级主要依赖、不重写业务、不修改应用标识、不删除现有客户端。

每个阶段必须输出 PASS / FAIL / BLOCKED / NOT_RUN；只有符合验收的阶段才能标记完成。文档写完、空目录建完、模拟器编译成功，都不等于整套多端架构已经交付。
