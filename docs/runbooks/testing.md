# 验证范围

统一入口可在任意工作目录调用；Windows 使用 `python tooling/workspace.py verify <scope>`。
命令执行、doctor、JSON 报告和 PASS/FAIL/BLOCKED/NOT_RUN 的唯一说明见 [统一验证](../ai/VALIDATION.md)。doctor 只检查命令/声明输入，dry-run 的所有验证步骤均是 NOT_RUN。
用 `./tooling/verify <scope> --dry-run` 查看真实命令，配置来源为 `tooling/workspace.json`。`contracts --dry-run` 同时列出静态检查、Node 与 MVC 命令，不执行它们。

| scope | 实际检查 | 不能证明 |
|---|---|---|
| `workspace` | 模块/规范入口、指令与导航摘要大小、验证范围、共享依赖方向、上下文链接、生成物、WS 字段 | 业务/设备行为 |
| `tooling` | Python 工具与运行器回归 | 业务行为、Windows 真机执行 |
| `hygiene` | 跟踪文件策略、图标/版本一致性、工作树/暂存 diff 检查 | 未追踪敏感内容、全历史秘密扫描 |
| `security` | 现有 Gitleaks 参数扫描完整本地 Git 历史 | 未获取远程 refs、未提交/未追踪内容；浅克隆明确 BLOCKED |
| `contracts` | WS 方向/关键字段、JSON 正反例、REST TS 生成物、MVC 快照、设计引用 | 所有业务授权条件、所有事件运行时兼容性 |
| `design` | Token v2 结构/类型/引用/双主题、CSS/Dart 确定性/漂移负例、组件状态/图标映射 | 全产品 UI/原生材质与辅助技术 |
| `core` | TS AST 依赖方向、Core 无 DOM 编译、纯聊天规则、Outbox/Realtime Port 与实际处理器回归 | 实际数据库/设备和多窗口隔离 |
| `flutter` | Dart 生成值、分析、组件/消息恢复测试；--no-pub 复用已有依赖 | 正式移动迁移、真机后台与发布性能 |
| `server` | Maven 测试 | 真实数据库初始化/升级、LAN、多实例运行 |
| `web` | Node 测试、Core 边界/类型检查、Vue 类型检查、生产 Web 构建 | 浏览器渲染、真实壳行为 |
| `desktop` | 桌面前端构建、Rust 测试（含原生 HTTP/WS loopback） | Tauri WebView IPC、安装包、签名、安装后表现 |
| `android` | 旧壳 JVM 单测、Secure Debug lint/APK（含 Vue 同步） | Flutter、真机后台、多播、正式签名 |
| `ios` | 旧壳 UI 同步、无签名模拟器构建 | Flutter、真机、TestFlight/App Store |
| `all` | 工作区检查及以上所有构建/测试范围 | 完整实机验收；缺工具会失败，不静默跳过 |

文档/目录改动至少执行 `./tooling/verify workspace`。共享接口/模型/样式改动执行 web 和受影响原生 scope。仅针对改动编写必要回归，不用静态字符串断言代替业务证明。

```sh
python3 -m unittest discover -s tooling/tests
./tooling/verify workspace
./tooling/verify server -- -Dtest=FileServiceSecurityTest
npm --prefix tests/e2e run typecheck
```

`tests/e2e` 的默认产品 E2E 依赖隔离 Compose 栈，执行说明见 [完整手册](project-guide.md)。不把已有常驻开发数据库用于破坏性 E2E。

`npm --prefix tests/e2e run test:storage` 是独立 Playwright 配置：需恢复 Web/E2E 原锁依赖并安装该锁对应的 Chromium，自动启动独立 localhost Vite、使用新浏览器上下文，只访问测试 origin 的 IndexedDB；认证 HTTP 与原生宿主调用是替身。它验证原 adapter、重新加载恢复、重复记录、账号/节点清理，不启动产品 UI 或真实后端。可用 `E2E_STORAGE_PORT` 选择空闲端口、`PLAYWRIGHT_BROWSERS_PATH` 指定已安装浏览器路径；证据默认在 `output/playwright/storage-contracts/`。这项不自动并入需要 Compose 的默认 E2E，不冒充设备/发布验证。
CI 沿用各平台独立工作流；目录迁移后缓存与触发路径覆盖 `apps/web`、`services/server` 和 `packages`。新增模块/路径后运行 `python3 tooling/workspace.py generate`。

报告保存 `docs/reports/<分类>/` 并加实际日期；截图、日志与包放 `output/` 等原证据目录。报告明确说明是否真实运行浏览器、安装应用、使用设备或连接 LAN。

`npm --prefix tests/e2e run test:design` 与 `test:design:visual` 复用现有 Playwright，详细基线政策/环境要求见 [设计验证](../design/validation.md)。真实组件 fixture 的 16 项 DOM 与 16 张本地截图是有限范围；Tauri CSS 上下文不是 WebKit/已安装应用。视觉基线缺失必须失败，不自动更新。本轮交付与遗留债见 [MX-A04](../tasks/active/MX-A04.md)。
