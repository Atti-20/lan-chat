# MeshX 协作入口

MeshX 是 LAN-first 私有协作系统。后端 Spring Boot；Web 使用 Vue；桌面使用 Vue + Tauri/Rust；移动端目标 Flutter，不采用 Capacitor。现有旧移动壳保留为兼容工程，Flutter 原型不等于正式迁移；HarmonyOS 尚无正式客户端。

## 修改前

1. 先 `git status --short`，保留已有未提交/未追踪文件；以本地实现为准，不回到方案中的旧远程快照。
2. 目标入口不明确时读 [AI 索引](docs/ai/INDEX.md)，选择任务路线或 `python3 tooling/workspace.py context <模块>`，主动读取目标路径适用的 AGENTS/override。根启动的会话不会自动加载所有子目录指令。
3. 只读相关入口和测试；跨模块再读 [架构地图](ARCHITECTURE.md) 与相关 ADR；UI 先读 [设计入口](docs/design/README.md) 和 `apps/web/DESIGN.md`。

## 仓库地图

| 范围 | 指令 / 入口 | 验证 scope |
|---|---|---|
| 服务端、权限、数据库、实时消息 | `services/server/AGENTS.md`；SQL 在 `sql/` | server、contracts |
| Vue 页面与共享业务 | `apps/web/AGENTS.md`（原 frontend） | web |
| Tauri 原生能力 | `apps/desktop/AGENTS.md` | desktop |
| Flutter 移动切片 | `apps/flutter-prototype/AGENTS.md` | flutter |
| 旧 Android/iOS 壳 | `apps/android/AGENTS.md` / `apps/ios/AGENTS.md` | android / ios，仅旧壳 |
| 协议、模型、接口、设计值 | `contracts/AGENTS.md`、`packages/<模块>/AGENTS.md` | contracts、受影响客户端 |
| 文档、工具链 | `docs/AGENTS.md`、`tooling/AGENTS.md` | workspace、tooling |

## 边界与禁止事项

- 依赖方向：domain-ts → protocol；platform-ports → domain-ts/protocol；应用适配 → ports。protocol 不依赖应用；共享核心不导入 Vue、DOM、原生 SDK 或具体存储。
- 复用 `apps/web/src/platform/nativeBridge.ts`、现有平台适配和共享包，不新建竞争入口。设计值来自 `packages/design-tokens/tokens.json`，复用 main.css/UiIcon，保留浅深色、响应式和平台交互。
- 协议源和覆盖边界见 [contracts](contracts/README.md)、[ADR 0005](docs/adr/0005-repository-context-and-contract-authority.md)；结构类型不能代替服务端权限、事务、ACK/幂等和连续序列测试。
- 保持当前目录、构建关系和锁文件；不为治理自动搬工程、重写业务、升级主要依赖或引入重型工作区工具。API/WS、数据库、应用 ID、最低系统版本、签名和 Cookie/令牌语义变更必须有对应任务授权。
- 不用跳过测试、空验证、假数据成功或放宽安全检查代替验收；保留 repository hygiene/Gitleaks。不得覆盖用户改动或自动 push、merge、release。

## 验证与交接

有文件修改时按影响选择 `./tooling/verify <scope>`；只读咨询不运行构建。命令不明确或运行器变化时先用 `--dry-run`，缺前提用 `python3 tooling/workspace.py doctor <scope>` 定位。[验证说明](docs/ai/VALIDATION.md) 定义 PASS / FAIL / BLOCKED / NOT_RUN；测试、构建、设备/LAN、签名发布分别报告。

文档事实变化时更新相关文档；有用户指定任务卡时维护其状态，普通小修改不新建任务卡。报告归档遵循 `docs/AGENTS.md`。默认单代理，同一时刻最多一个子代理；分工、交接与停止规则见 [工作流程](docs/ai/WORKFLOW.md)。

完成当前授权范围的实现及相关验证后停止；已有授权覆盖的本地修复与复验无需重复确认。

不默认扫描整个 docs、历史报告、已完成计划、PRD/TODO 或 output/target/node_modules；查询限定到相关模块，必要时再展开。
