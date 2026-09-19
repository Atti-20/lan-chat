# MX-A01 — 短上下文、轻量 workspace 和验证入口

前置：A00 已完成；本任务会影响的基线检查可执行，或已有明确先修任务。

## 目标

让下一次 Codex 会话可以从短入口找到代码、规则和命令，且不会改变现有产品行为。

## 范围

合并本包 AGENTS 模板与已有规则；新增根/相关模块指令、ARCHITECTURE、docs/ai/INDEX、短 repo-map、runbooks。保留 frontend、root src/pom 和 apps/desktop 位置；不重写 UI/核心、不创建 Flutter 占位项目。

## 操作

根 AGENTS 只提供地图、边界和验证；版本信息引用实际配置，不复制一长串随时间失效的版本号。模块指令只写独有规则。现有 override/全局规则不能被模板抹去。

以 npm workspaces 纳入当前 frontend 和 apps/desktop；只在共享包真实创建时纳入 packages/ts。锁定本任务验证过的 npm/Node 组合，保持现有主要依赖版本。形成根 lock 前保留旧锁文件作为对照；完成安装和构建验证后在同一可审阅变更中移除已失效的子 lock，更新 Docker/CI 安装步骤。禁止顺便引入 pnpm/Nx/Turborepo。

实现 `node tooling/meshx.mjs doctor` 和 `verify --scope web|server|desktop`，包装真实现有命令。不能存在总是返回 0 的空验证任务；未实现的 scope 必须明确报错。支持 Windows/Mac 路径、argv、退出码、命令缺失，不拼接用户输入进 shell。

建立任务模板与交接规范、初始范围依赖映射。保留现有 hygiene/Gitleaks，新增实际可用的 Web/Java CI 检查；不要创建一排空 job 冒充覆盖。

## 验收

干净工作树/临时 worktree 中根 npm ci 可重复；Web 测试、类型检查和两种构建不劣化；桌面构建路径仍正确。doctor 不修改工具链；错误 scope、缺少工具、测试失败返回非零且可诊断。新会话按 INDEX 能定位认证、WS、存储、平台和主题入口，无须读完整 README。

不强求本机不具备的原生平台编译通过；报告真实覆盖并安排相应 runner。构建产物不得污染正式源码提交。

## 回滚

根 package/lock/workspace 和路径相关调整作为一个可回滚单元；恢复原安装流程，不修改业务或用户数据。只撤回本任务改动。结束后写 A01 交接，不自动执行 A02。
