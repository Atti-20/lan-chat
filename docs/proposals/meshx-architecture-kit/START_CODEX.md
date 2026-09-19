# 首轮交给 Codex 的任务

将以下内容粘贴到以 MeshX 仓库为工作目录的新 Codex 会话：

```text
请在当前 MeshX 仓库实施轻量多端架构治理。
技术路线已确定：Spring Boot + Vue/Tauri + Flutter Android/iOS，不采用 Capacitor。

先遵循当前仓库已有且适用的 AGENTS.md / AGENTS.override.md。
读取 docs/proposals/meshx-architecture-kit/README.md、AUDIT_BASELINE.md、
TASK_INDEX.md 以及 tasks/MX-A00.md。本轮只执行 MX-A00。

检查基线 d7571ded11fa4d6e66fd0c05e37e0541c2004b23 仅供对照，
不得 checkout/reset 到该提交，不得覆盖我本地的未提交修改。
先记录当前分支、HEAD、git status、工具链和已有运行命令。
检查已有 AGENTS、ADR、contracts、Flutter 工程，存在时复用，不能另建第二套。

本轮只生成事实清单、回归基线与下一阶段任务，不修改业务逻辑、
API/WS 协议、数据库、应用标识、包管理器或现有目录结构。
可以运行已识别的本地检查；缺少依赖/服务/SDK 时记录 BLOCKED，
不得用跳过测试、关闭安全检查或模拟成功替代。

完成后给出：实际修改文件、运行命令和结果、未验证项、
风险与建议的下一任务 MX-A01，并写入 docs/tasks/active/MX-A00.md。
不要自动执行后续阶段、推送、合并或发布。
```

## 后续会话

完成 MX-A00 后，后续可使用：

```text
执行 docs/proposals/meshx-architecture-kit/tasks/MX-A01.md。
先读取适用 AGENTS.md、docs/ai/INDEX.md，以及该任务现有交接。
只执行这一任务，按验收条件验证并更新交接，不自动推进后续阶段。
```

将任务号替换为当前阶段即可。索引与交接文件尚未建立时，先定位本任务前置交付物，不要假称已读取。

## 交付的判定

不要以“项目成功改成 Monorepo”作为泛化汇报。应说明，例如：
“已建立 npm workspaces；原 Vue 测试通过；桌面 Rust 检查通过；打包缺少 macOS 环境未运行；Flutter 尚未开始。”
