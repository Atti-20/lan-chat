# 工作区工具

- `workspace.json` 是模块、taskRoutes 与统一验证命令的配置；`workspace.py` 和内部 `verification.py` 仅使用 Python 标准库，不增加竞争入口/根依赖管理。
- `generate` 生成地图、REST 导航/操作与 Dart 切片、SQL 索引、设计值与 WS Java/TS/Dart；REST TS 模型单独执行 `npm --prefix tooling/contracts run generate`。`check` 校验入口、链接、生成物、组件引用和依赖方向。兼容基线不由 generate 更新，见 contracts/compatibility/README.md。
- `context` 只列目标模块/任务的指令链、相关入口/测试和验证范围；新增 routes/relatedDocs/verificationScopes 时同步检查断链和摘要字节预算。根 AGENTS 上限 4096 字节，模块 4000，摘要 4096。
- `verify` 调用真实工具并输出 PASS/FAIL/BLOCKED/NOT_RUN；失败后所有后续命令 NOT_RUN，保留子进程退出码。缺前提返回 3，dry-run 只能显示 NOT_RUN。doctor 只检查命令/声明输入，不证明 SDK/服务就绪；不自动安装或升级。
- 保留现有 hygiene/Gitleaks 语义与配置、全历史/脱敏参数；安全扫描拒绝浅克隆。--report 用于本地 JSON 证据，不把日志写入源码目录。
- Windows 直接用 `python tooling/workspace.py`；保留空格路径与非根工作目录支持。命令用 argv 数组传给 subprocess，不拼接 shell。
- 修改运行器后执行 `python3 -m unittest discover -s tooling/tests` 和 `./tooling/verify workspace`。
