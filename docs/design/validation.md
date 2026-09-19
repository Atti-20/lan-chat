# 跨端设计验证矩阵

日期：2026-09-09（MX-A04）。

## 机器检查

- `./tooling/verify design`：严格 source 结构、双主题、引用/循环/类型/单位、CSS/Dart 生成确定性和 CLI 只读漂移负例；组件状态与图标引用。
- `./tooling/verify core`：AST 导入边界（含样式/Dart/design-tokens 负例）、无 DOM 编译和 A03 核心测试。
- `python3 tooling/workspace.py generate --check`：中立 token 到 CSS / Dart、WS 类型及模块索引不得漂移。
- `./tooling/verify contracts`：Java 信封、WS 事件/关键 payload 字段、组件引用和消息状态事件检查。
- `npm --prefix tooling/contracts run check`：REST 生成类型与 WS 正反例校验。
- `./tooling/verify web`：基础文字及实心按钮 4.5:1 对比度、未定义变量、行为与生产构建。
- `./tooling/verify flutter`：生成值、Dart 分析、消息序列/重试、权限拒绝、长列表与键盘组件验证。

这些检查不替代实际浏览器、系统字体、透明叠层和原生窗口渲染。

## 渲染与行为

| 维度 | 最小场景 | 通过条件 |
|---|---|---|
| 主题与宽度 | 浅/深色；320、390、768、1440 宽 | 无横向溢出、截断主操作或无法辨认的状态 |
| 消息 | 发送中、ACK、重复 ACK、失败重试、撤回/焚毁 | 同一消息不重复插入，状态与协议含义一致 |
| 断线 | 重连、会话失效、无权限会话 | 草稿保留，禁用原因清楚；失效会话不继续假发送 |
| 输入 | 长文本、多行、中文组合输入、键盘弹出 | 输入内容不丢失，发送与取消可触达 |
| 列表 | 空、加载、失败、1200 条消息 | 明确下一步；滚动稳定；只构建必要可见项 |
| 辅助 | Tab、系统返回、文字放大、减少动态效果 | 焦点可见，语义标签完整，不依赖动画完成业务状态 |

实际应用未实现的状态要记录为边界，不以规范预览或模拟数据冒充业务完成。报告按 `docs/reports/界面设计/` 或本轮综合主题归档，截图与日志在 `output/`。

## A04 轻量浏览器回归

复用 tests/e2e 已锁定的 Playwright，无新增依赖。`npm --prefix tests/e2e run test:design` 自动启动 Vite test-only fixture，16 项 DOM 检查涵盖主题持久化/无效偏好回退、320/1440、两倍根字号、长中英文本/昵称、失败重试原 ID、撤回/焚毁、禁用/hover/focus/错误描述、头像回退、选中语义、减少透明。数据不连接真实账号，测试页面不进入生产路由。

`npm --prefix tests/e2e run test:design:visual` 检查 16 张浅深色 × 320/1440 × 普通/两倍字号 × Web/Tauri CSS 上下文截图。基线位于本地 `output/playwright/mx-a04/baseline-v2/`，可用 `MESHX_DESIGN_BASELINE_DIR` 指定绝对目录。最大允许像素差为 0。缺基线直接失败，不自动创建或更新。

仅首次、人工复查当前 UI 时运行 `MESHX_DESIGN_CAPTURE_BASELINE=1 npm --prefix tests/e2e run test:design:visual`，使用全新目录；已有基线禁止覆盖。应固定 Chromium/Playwright、系统字体和平台；跨机器/引擎变更先评审再建立新基线。截图在本地输出，未提供给远程 CI 的基线不得声称已验证。

Tauri 样式上下文只设置 data-runtime，不能等同安装应用/WebKit 渲染。附件真实传输、平台辅助技术和 Dynamic Type 设备测试仍 NOT_RUN。保留 [设计债](debt/README.md) 与 A00–A03 handoff 的环境/发布边界。
