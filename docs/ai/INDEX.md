# AI 上下文入口

先读根 [AGENTS.md](../../AGENTS.md) 和用户指定的任务卡，执行 `git status --short`。本地目录以 [模块地图](../generated/repo-map.md) 为准：共享 Vue 在 `apps/web`，后端在 `services/server`；不因旧方案写 frontend/root src 就移动工程。

## 按任务选择最小上下文

| 我要修改 | 导航命令 | 读取顺序 / 验证 |
|---|---|---|
| 聊天逻辑、ACK、重连、同步 | `python3 tooling/workspace.py context chat` | Web、server、domain 的适用指令 → 输出中相关入口/测试 → WS 规则；web/server/contracts |
| Tauri 能力、原生网络或节点发现 | `python3 tooling/workspace.py context tauri` | Web、desktop、ports 指令 → nativeBridge/nativeTransport → Rust 对应模块；web/desktop |
| 后端接口、DTO、权限 | `python3 tooling/workspace.py context api` | server/contracts 指令 → API 导航中对应 Controller → DTO/Service/Security 与现有测试；server/contracts，消费者受影响再跑 web |
| 认证刷新、节点隔离 | `python3 tooling/workspace.py context web` | [api.ts](../../apps/web/src/services/api.ts)、[authRetry.ts](../../apps/web/src/services/authRetry.ts)、[nodeRefreshCoordinator.ts](../../apps/web/src/services/nodeRefreshCoordinator.ts)；原生认证再展开 tauri/API 路线 |
| 缓存、outbox | `python3 tooling/workspace.py context chat` | [useOutbox.ts](../../apps/web/src/composables/useOutbox.ts) → [纯队列规则](../../packages/domain-ts/src/outbox.ts) / [OutboxPort](../../packages/platform-ports/src/outbox.ts) → [现有数据库](../../apps/web/src/services/localChatDb.ts)；不把 IndexedDB 实现搬成纯 core |
| 局部 Vue 界面 | `python3 tooling/workspace.py context web` | [Vue 规范](../../apps/web/DESIGN.md) → 相关组件规范 / main.css/UiIcon；web 与受影响页面的浅深色、宽窄屏渲染 |
| 共享设计值与组件规范 | `python3 tooling/workspace.py context design` | [设计规范](../design/README.md) → [Token source](../../packages/design-tokens/README.md) → [相关组件规范](../design/component-specs/README.md)；design 与受影响消费者；仅协议或核心变化时增加 contracts/core |
| 工具、AI 导航、任务交接 | `python3 tooling/workspace.py context workspace` | tooling/docs 指令 → workspace.json → 对应工具与测试；workspace/tooling |
| Flutter 原型的授权后续任务 | `python3 tooling/workspace.py context flutter` | Flutter 指令 → 用户授权的任务卡与目标切片；dart-core/flutter/contracts；原生另用 flutter-android/flutter-ios |

源码与测试的短路由由 [REPO_MAP](REPO_MAP.md) 从 `tooling/workspace.json` 生成，避免再维护一份全仓文件列表。`context <模块或任务> --json` 给出同一导航、仓库指令路径/字节数和验证 scope，不加载源码，不执行验证。

## 继续工作

- 先查 [任务记录](../tasks/README.md)，读被用户指定的任务及 handoff；不要自动继续下一张卡。
- 命令与状态看 [VALIDATION](VALIDATION.md)，实际工具/服务版本以现场检查为准；历史数字只代表对应轮次。
- 跨模块决策再读 [ARCHITECTURE](../../ARCHITECTURE.md) 和关联 ADR；协议、授权、数据库状态按 [ADR 0005](../adr/0005-repository-context-and-contract-authority.md) 分别核验。
- 指令发现规则见 [上下文说明](../architecture/repository-context.md)；context 只列仓库内标准 AGENTS/override，不替代会话全局规则或用户授权。

目标路线是 Spring Boot、Vue/Tauri、Flutter；现有 Android/iOS 命令描述旧 Capacitor 工程，不能当成 Flutter 已完成。禁止用生成索引、接口声明或构建成功代替业务/设备验收。
