# MX-A05 — Flutter Android/iOS 真实文字聊天闭环

开始日期：2026-09-09。状态：**已完成本阶段模拟器文字闭环并交接；安全门禁仍 FAIL，真机/LAN/发布未验收；未启动 MX-A06**。

## 核对后的起点

- 当前工作区与 Git 根均为 `/Volumes/External Disk/Project/java/lan-chat-server`；分支 `feature/v0.3.1`，HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`。
- A02/A03/A04 任务卡、REST/WS 生成链、TS Core/Ports、Dart 向量、Token source/CSS/Dart、A04 报告与验证清单已现场核对存在。历史测试数量不当作本轮结果。
- 起点 1500 个跟踪/未跟踪路径的 SHA-256/缺失状态及 Git 状态：[baseline](../../../output/mx-a05-2026-09-09/baseline.json)。保存受影响 Flutter 源文件起点副本；大量未跟踪成果继续保留，不 reset/clean/stash/commit/push。
- 复用 `apps/flutter-prototype` 的现有 Android/iOS 工程、标识与锁。没有另建 apps/mobile 或使用初始化命令覆盖配置。

## 本轮实现

- `lib/core` 纯 Dart 模型、合并、连续游标、重连规则、窄存储接口；旧 `lib/data/models.dart` 为兼容导出。Core 不依赖 Flutter、Theme 或平台插件，独立 Dart VM 执行共享向量。
- Flutter `ChangeNotifier` 保持唯一状态编排。AUTH 成功后使用真实分页 SYNC，完成前不可发网络业务帧；入站队列、连接代次与账号代次隔离。
- 文件原子快照同时存消息/outbox/游标，按 origin + userId 分区。先持久化再发送；SENDING 重启恢复为 queued，复用 ID/内容。长期凭据通过 Android Keystore AES-GCM / iOS Keychain 存储；平台失败真实报错。
- REST 从既有导出快照增选 `getCurrentUserInfo` 到 Dart 生成切片；未改 Controller、OpenAPI、WS schema 或旧 Web/Tauri 协议语义。
- 双主题继续消费 A04 生成 Token；支持离线文字排队、失败重试、生命周期恢复。开发 HTTP 例外限调试配置，release 保持证书与 HTTPS 验证。
- 新增独立测试后端参数、真实 TCP 断网代理、Web 页面协调测试与 Flutter 原生分阶段重启测试。协调器不伪造业务响应。

## 验证记录

本轮原始日志/JSON/截图位于 [A05 evidence](../../../output/mx-a05-2026-09-09/)，Web 截图位于 [Playwright evidence](../../../output/playwright/mx-a05/)。完整环境、命令、失败记录与边界见 [A05 验收报告](../../reports/实机验证/MX-A05_Flutter移动文字聊天验收_2026-09-09.md)。

本轮重跑：Dart Core 14、Flutter 44 + analyze、Java 313、Web 110、TS Core 30、Design 12、Tooling 58 均 PASS；A02 契约、workspace/hygiene 门禁 PASS。空 PUB_CACHE 锁定恢复与 44 测试 PASS；8 份锁未变，起点 1500 路径没有新增缺失。Android debug / iOS simulator 最终统一构建均 PASS。

Android API 37（emulator-5554）与 iOS 26.5（iPhone 17 Pro simulator）分别通过真实 Vue 互发、>=220 消息 AUTH/分页 SYNC、实际 TCP 断网补发、45 秒令牌过期单次刷新、原生安全存储及保留数据进程重启。Android ID `189d0b29bf31422727f7e76160c86192`、iOS ID `04a7fa688826a20b6806beb4c98f449f` 各在重启前后保持不变并服务端单次提交。截图来自真实原生系统，Web 协调测试最终 PASS。

换账号及另一 origin 登录两端 PASS；另一 origin 仍是同一实际后端，不声称独立两服务器串号验收。独立 HTTP 双端点负向测试覆盖凭据不能跨 origin 复用和 401 刷新有界。真实双服务器、真机、跨设备 LAN、远程 CI、完整 OS 前后台/锁屏矩阵、签名发布 NOT_RUN。Tauri 无源码/桥接变化，本轮未重跑 Rust 52；旧数不冒充新结果。

全历史 Gitleaks FAIL：1 条已核实 STORAGE_KEY 误报；新增/改变源码另扫 PASS，规则保持。Android 初次 debug/JIT 输入 ANR 保留为性能债，后续运行无新 ANR。完整错误/重跑经过见报告。

## 继承边界与停止

全历史 Gitleaks 既有误报、真机/LAN、原生正式签名/发布、远程 CI、旧壳环境和桌面凭据库债不由本轮模拟器通过隐式关闭。新增安全命中须单独核查。本轮已完成 handoff，停止于 A05，不进入 A06。


## 下一会话最小读取集

根 AGENTS → docs/ai/INDEX → 本卡及报告 → ADR 0008 → apps/flutter-prototype/AGENTS、README；按需要读 lib/core、chat_controller、platform/storage、data/meshx_api、recovery/mobile_reliability/http_isolation 测试及 a05_mobile_test。契约来源用 contracts/README / ADR 0006，设计用 docs/design/README。保留当前 dirty/untracked 工作，不重建 A02/A03/A04、不自动提交。

## 剩余任务

在后续单独授权下补两独立真实节点隔离、Android 完整底部键盘/多 IME、实体 Android/iPhone 和 profile/release 性能、安全存储故障及完整生命周期矩阵。附件、推送、后台常驻、HarmonyOS、正式发布均未扩展。最低版本、应用标识、旧工程布局保持。
