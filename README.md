# MeshX — LAN-first 私有协作系统

目标路线为 Spring Boot 后端、Vue Web、Vue + Tauri 桌面与 Flutter 移动端。现有 Capacitor 客户端在完整迁移验收前保留，Flutter 目前已落地独立切片原型。当前开发版本为 **v0.3.0**；根 `VERSION` 为发布版本基线。签名、设备、网络与发布状态以对应验收证据为准。

## 开始工作

- AI 任务从 [AGENTS.md](AGENTS.md) → [AI 索引](docs/ai/INDEX.md) → 目标模块指令/任务路由进入；跨模块再查 [架构地图](ARCHITECTURE.md)。
- 人工开发查看 [本地开发](docs/runbooks/local-dev.md)、[验证说明](docs/runbooks/testing.md) 和 [完整配置/功能手册](docs/runbooks/project-guide.md)。
- 接口与数据入口见 [contracts](contracts/README.md)；产品边界见 [产品范围](docs/product/overview.md)。
- 文档、决策、计划与报告统一从 [docs 索引](docs/README.md) 进入。

## 工作区

| 目录 | 用途 |
|---|---|
| `apps/web` | Web / 桌面共享 Vue UI；迁移期间仍供旧移动壳使用 |
| `apps/desktop` | Tauri / Rust 桌面能力 |
| `apps/android`、`apps/ios` | 迁移期间保留的 Capacitor 原生桥接与打包 |
| `apps/flutter-prototype` | 目标移动端的 Flutter 验证切片 |
| `apps/website` | 独立产品官网 |
| `services/server` | Java 服务；根 Maven POM 聚合 |
| `packages` | 协议类型、模型/规则、平台接口、设计变量 |
| `contracts` | 已落地的机器契约及其覆盖边界 |
| `sql`、`deploy`、`scripts` | 数据库、部署与发布工具 |
| `tooling` | 模块地图、协议/设计生成工具与统一验证入口 |
| `docs`、`archive` | 当前规范与历史材料/代码分开保存 |

## 常用命令

```sh
npm ci --prefix apps/web
./mvnw -pl services/server spring-boot:run
npm --prefix apps/web run dev
```

后端需要事先配置 MySQL/Redis 等依赖，参见本地开发手册。

```sh
python3 tooling/workspace.py context web
python3 tooling/workspace.py context chat
python3 tooling/workspace.py doctor web
./tooling/verify workspace
./tooling/verify web
./tooling/verify server
```

完整 scope、PASS/FAIL/BLOCKED/NOT_RUN、dry-run 和 JSON 报告见 [统一验证](docs/ai/VALIDATION.md)；治理阶段进度与 handoff 见 [任务索引](docs/tasks/README.md)。

前端原 `frontend/` 已迁入 `apps/web/`，服务端原 `src/` 已迁入 `services/server/src/`。现有根 `./mvnw test/package` 命令保留。完整迁移影响见 [路径说明](docs/runbooks/local-dev.md)。

目标路线与旧客户端退出条件见 [ADR 0004](docs/adr/0004-client-target-and-contracts.md)。协议入口见 [contracts](contracts/README.md)，设计入口见 [跨端设计体系](docs/design/README.md)。

本地证据统一放 `output/`；历史 PRD/TODO 在 `docs/archive/local/`。`logs/`、`uploads/` 是应用运行数据，编辑器/凭据目录是本地配置，均不作为业务源码提交。旧根 `out / target / test-results / outputs / PRD / website / spikes` 已整理到对应目录，见 [归档说明](docs/archive/README.md)。
