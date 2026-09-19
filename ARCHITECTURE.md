# MeshX 架构地图

本文件描述现有结构，功能状态以实现及相应验证为准。首次定位通常只需根 `AGENTS.md` 和目标模块指令。

```text
                         services/server (Spring Boot)
                                    │
                         contracts (REST / WebSocket)
                     ┌──────────────┴──────────────┐
                 TS 协议类型                    Dart 协议类型
                     │                             │
           apps/web (Vue)              apps/flutter-prototype (Flutter)
             ├─ Web                            目标 Android / iOS
             ├─ apps/desktop (Tauri)           完整业务迁移尚未完成
             └─ apps/android / apps/ios
                迁移期保留的 Capacitor 壳
                     │                             │
                     └──── packages/design-tokens ─┘
                              JSON → CSS / Dart
```

目标路线见 [ADR 0004](docs/adr/0004-client-target-and-contracts.md)。图中的原生壳共享 Vue 构建结果；Flutter 使用独立 UI 与状态逻辑，不能直接运行 TS 核心。具体入口在 [生成地图](docs/generated/repo-map.md)。

## 职责与依赖

| 层 | 实际位置 | 规则 |
|---|---|---|
| 控制面、业务与 Relay | `services/server/src/main/java/com/lanchat` | 权限、事务、消息持久化与序列在服务端保证 |
| 协议 | `contracts/rest`、`contracts/websocket`、`packages/protocol` | MVC 导出 REST，WS schema 生成 TS/Dart；字段、方向和生成物受检查 |
| 共享模型与规则 | `packages/domain-ts` | 模型、会话标识、序列、消息合并/排序、outbox 恢复与重连规则；无 UI 或平台 SDK |
| 平台接口 | `packages/platform-ports` | 小型 Outbox/Realtime Port 与既有 `NativeBridge` 类型；不包含实现 |
| 平台实现 | `apps/web/src/platform` 与原生壳 | `webBridge / capacitorBridge / tauriBridge` 实现同一接口 |
| 应用服务与编排 | `apps/web/src/services`、`composables` | API、IndexedDB、WebSocket、状态与生命周期仍在应用层 |
| 设计 | `packages/design-tokens/tokens.json`、`components.json` | 中立值和组件状态；分别生成 CSS/Dart，Vue 与 Flutter 各自实现组件 |
| 初始化与迁移 | `sql/` | 与部署包共享；不自动执行升级迁移 |
| 运行及发布 | `deploy/`、`scripts/`、`.github/workflows/` | Docker build context 保留仓库根 |

`apps/web/src/types.ts`、`utils/conversation.ts`、`utils/sequence.ts` 及原平台模块中的类型导出是兼容入口，定义只在 packages 保存。新共享规则直接在 packages 中实现。
REST TS 和 WS TS/Dart 已生成，但 API client、令牌恢复和 IndexedDB 仍依赖应用状态，不能将类型生成包装成完整 SDK。MX-A03 由 composable 消费纯 Core 与小型 Port，保留 ACK/同步/认证编排，见 [ADR 0007](docs/adr/0007-typescript-shared-core-and-narrow-ports.md)。Vue 基础组件由正式客户端壳直接复用。`apps/flutter-prototype` 是独立 Dart UI 验证，设计值从中立 JSON 生成，直接调用同一服务端；它不执行 TS 核心，也不意味着移动端正式迁移完成。

## 关键事实

- 单逻辑节点可以由多个 Spring Boot 实例共享 MySQL、Redis 和对象存储；这不等于独立数据库节点互相复制。
- 可靠消息使用 `clientMsgId` 幂等、事务提交后 ACK、会话序列、连续接收游标与重连补拉。原生长期后台收信仍需平台机制和设备证据。
- Web、Desktop、Mobile 是 Vite 输出模式。运行时桥接由真实宿主能力选择，不能以构建模式判断是否可用 IPC。
- Web 构建进入 `services/server/src/main/resources/static/app`；桌面/移动构建分别进入 `apps/web/dist-desktop`、`dist-mobile`。
- 根 Maven POM 仅聚合。`./mvnw test/package` 覆盖服务模块；启动用 `./mvnw -pl services/server spring-boot:run`。此启动方式的本地上传/日志工作目录保持仓库根。
- HarmonyOS 没有正式实现；Flutter 当前也不是本仓库的生产客户端。后续非 TS 客户端共享协议与设计定义，而不是假定能够直接执行 TS 核心。

当前官网在 `apps/website`，退出路线的 Compose 原型在 `archive/compose-multiplatform`。历史本地 PRD/TODO 在 `docs/archive/local`，旧构建与证据在 `output/legacy-build`、`output/history`。根保留 Maven、部署配置、版本与导航入口，应用构建回到各自模块。

## 按需展开

模块定位使用 `python3 tooling/workspace.py context <模块>`；[上下文规则](docs/architecture/repository-context.md) 与 [事实来源 ADR](docs/adr/0005-repository-context-and-contract-authority.md) 约束按需读取和冲突处理。

- [客户端和平台边界](docs/architecture/platform-adapters.md)
- [协议事实入口](contracts/README.md) 与 [跨端设计体系](docs/design/README.md)
- [产品范围](docs/product/overview.md)
- [ADR 0001：工作区](docs/adr/0001-workspace.md)、[ADR 0002：客户端选型](docs/adr/0002-client-runtime.md)
- [Flutter 对照原型](apps/flutter-prototype/README.md) 与 [验证决策](docs/adr/0003-flutter-prototype.md)
- [开发命令](docs/runbooks/local-dev.md)、[验证与发布边界](docs/runbooks/testing.md)

更新目录或入口后运行 `python3 tooling/workspace.py generate`，并通过 `./tooling/verify workspace`。历史 PRD/TODO 和本地报告只用于查决策背景，不能覆盖现行代码事实。
