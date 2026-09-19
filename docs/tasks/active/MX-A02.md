# MX-A02 — Contract Governance & Cross-platform Contract Foundation

开始日期：2026-09-08。完成日期：2026-09-09。状态：**交付完成；限定契约/回归与 clean snapshot reproduction PASS，安全扫描仍 FAIL**。本轮停止，未启动 MX-A03。

## 基线与事实来源

分支 `feature/v0.3.1`，HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`。起点保护清单 1438 路径：945 存在、493 既有缺失。保留 A00/A01 未提交迁移；没有回到旧 HEAD 重建项目。

REST：实际 Java Controller/DTO/Entity、SecurityConfig、Service → MVC 测试导出 OpenAPI 3.0.1 → TS 模型/操作与最小 Dart 切片。OpenAPI 是导出产物。WS：envelope/event schema → Java envelope / TS / Dart；鉴权、幂等和同步语义由真实 Handler/Service 与应用测试证明。完整 [盘点](../../../contracts/inventory.md)、[ADR 0006](../../adr/0006-endpoints-auth-and-contract-generation.md)、[兼容门禁](../../../contracts/compatibility/README.md)。

## 验收完成

- [x] 盘点 REST 101 路径/109 操作/108 模型与 WS 40 事件/49 方向，认证、设备、错误、地址和 sequence 边界。
- [x] 核心 nullable/optional、登录/注册必填由真实 Java/Jackson/Service 证据恢复；设备类型为归一集合，不误作拒绝未知值的 enum。
- [x] TS 消费生成 ApiResult、核心方法/路径/输入、WS 版本/事件；Dart 9 个核心 REST 操作与关联模型可生成/解析，并接入原型路径/方法和登录 DTO。
- [x] 共用 24 帧、20 REST 样例、3 组序列场景；Java Handler、真实 HTTP、Web composable、TS 与 Dart 使用实际实现验证。
- [x] 漂移和保守不兼容门禁、故意破坏的负例与真实 CLI 非零检查；生成不自动更新兼容基线。
- [x] 相关 Web/Server/桌面和 Dart 回归完成；当前工作树与原锁的隔离冷恢复完成。
- [x] 一次只读审查的两个 P2 均修复；安全误报与平台/发布边界准确保留。

## 实际命令与结果

全部 argv/cwd/时间/退出码与日志位于 [本地证据](../../../output/mx-a02-2026-09-08/)。完整修改文件、生成文件、兼容发现和风险见 [A02 报告](../../reports/仓库审查/MX-A02契约治理与跨端基础报告_2026-09-09.md)。

| 命令/范围 | 结果 |
|---|---|
| `./tooling/verify contracts` | PASS：源/生成、Node 9 + TS 编译、Java MVC/Handler/HTTP 7、compatibility 3 |
| 隔离 `./mvnw ... clean test` | PASS：313，0 失败/错误/跳过；真实 loopback 登录/会话/Cookie/401/退出，服务依赖替身 |
| 隔离 `./tooling/verify web` | PASS：81 项、vue-tsc、生产构建 |
| 隔离 `./tooling/verify desktop` | PASS：桌面 UI、Rust 50；系统凭据库 1 项 ignored 仍未执行 |
| 隔离 `./tooling/verify flutter` | PASS：analyze 无问题、30 项 |
| `./tooling/verify tooling` / `workspace` / `hygiene` | PASS：43 项工具测试、生成/链接/边界、5 项 hygiene；收尾文档复核 |
| `./tooling/verify security` | **FAIL**：62 提交，1 个已核实存储键误报；不放宽配置/规则 |
| `verify all`、远程 CI、Android/iOS、设备/LAN、签名发布 | NOT_RUN；不能由上述分范围结果推导通过 |

Clean snapshot 从当前 tracked/untracked 非忽略输入复制 965 文件，8 份锁文件不变；npm、Maven wrapper/M2、Pub 使用空缓存，实际下载恢复。复用已安装 Node 26.3.1、Java 21（release 17）、Flutter 3.44.8/Dart 3.12.2；Rust crate 缓存复用并使用新 target，单独说明。生成/测试后快照 SHA-256 零差异，原目录 Web 106/desktop 15 个构建产物内容保持不变。

修复了首次复制误排源码 logs 组件、A01 对本地 output 证据的强制链接要求；保留失败日志并在完整最终快照重跑。测试后仅回填本卡与文档索引，源码验证结果不因收尾文字变化失效。

## 已知边界及 A00/A01 债务

- Gitleaks generic-api-key 命中旧 `frontend/src/platform/notificationDeliveryDeduper.ts:12`，实际为 `meshx_notification_delivery_v1` 缓存键。误报处置未关闭，扫描仍 FAIL；不宣称全安全门禁通过。
- 当前工作树的可用范围 clean reproduction 已关闭；远程 clone/CI Java 17/Node 20、其他 OS/SDK 安装仍未证明，许多模块/lock 仍未追踪。
- A00 Android cmdline-tools/许可证、CocoaPods 阻塞未修；旧原生壳、系统凭据库、安装后桌面/release 网络、真机/LAN/后台、真实 DB/外部服务/E2E、签名/发布仍待独立验收。
- Flutter 的新 WsFrame 只完成独立解析链路；RealtimeConnection 仍为旧解析入口，未有 Web 的 WS SYNCING/连续游标/同等入站串行状态机。未开发 Flutter 页面或实时业务。
- 开放 Map、35 个方向级 WS 分支、复杂业务条件与超出 JS 安全整数的 long 表示仍有边界；生成成功不代表完整 SDK 或授权校验。

## Handoff / A03 条件

**具备范围受控的 A03 TS Core/平台适配工作技术前置；不等于全门禁、移动功能或发布就绪。** 用户另行启动后读取适用 AGENTS → 本卡/报告 → AI INDEX → A03 原卡和相关模块。复用本轮生成 DTO、兼容 facade 与状态机向量；一次迁移一个用例，保护 Cookie、账号/服务器隔离、缓存/outbox、clientMsgId、入站顺序与补同步。

没有修改数据库 schema、主要框架/lock、应用 ID/最低系统版本、生产 Controller/Service 业务或原生 SDK；没有 LAN/通知/后台开发、目录迁移、提交/推送/合并/发布。A00/A01 历史卡保留原内容。

只按本轮 `a02.patch`、SHA 清单和 `source-before/` 撤回明确变更，先核对后续用户编辑；禁止整树 reset/checkout/clean。完成 MX-A02 后停止，不自动进入 MX-A03。
