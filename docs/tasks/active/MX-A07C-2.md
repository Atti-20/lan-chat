# MX-A07C-2 — Flutter C08 冷进程与真实OS恢复

开始/阶段日期：2026-09-13。状态：**C08_PARTIAL。iPhone 16 Pro Max / iOS 26.6.1 真机 Profile 的5轮恢复型冷进程、双时钟冷启上界及5轮真实Settings前后台恢复 PASS；G08/G14/G15仍不关闭。**

## 基线与范围

- 分支 `feature/v0.3.1`，基线HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`，保留已有dirty workspace；承接 [MX-A07C-1](MX-A07C-1.md) 的帧/输入切片。
- 本卡新增独立持久Profile探针、严格证据校验器与受限iOS控制脚本。没有修改生产`lib/`、协议、应用标识、版本、最低OS、安全规则或分发配置。
- 工件为现有独立ID `com.meshx.meshxFlutterProbe`、0.1.0、Apple Development签名，仅安装到测试iPhone；不是批准身份、TestFlight/App Store或正式分发。
- 探针复用生产ChatController、MeshXApp、FileChatStore、Keychain与原生适配器，但入口为`integration_test/c08_process_probe.dart`，通过忽略配置使用隔离HTTP fixture并显式允许本地HTTP。因此不是正式`lib/main.dart`或生产HTTPS证据。

## 实现

- 设备容器中的`c08-process-probe.json`按`PROBE_RUN_ID`分区并原子追加，不覆盖失败轮次。每个进程只记录Dart首帧、缓存可操作、ONLINE/ready、2050条消息数量/唯一性、RSS、连接打开/认证/同步与峰值；不记录token、密码、消息正文、用户/设备标识或文件路径。
- 首次运行完成专用账号登录与2050条真实Spring消息落盘。之后每轮明确终止同一Runner进程再公开API启动，从Keychain与账号快照恢复；验证器只取5条`restoredCredentials=true`的冷进程，不把fixture准备轮算入。
- `c08_process_control.py`从设备公开app data container拉取证据，只终止可执行路径严格以`/Runner.app/Runner`结尾的PID；`--count`限制1–10。第二轮冷启同步记录主机发起`devicectl launch`到设备报告ready的端到端上界，补足Dart内时钟不含原生启动阶段的不足。
- 真实OS恢复通过公开API启动系统Settings使Runner进入background并观察连接offline，再重新启动Runner到foreground并等待ONLINE；共5轮，不调用假的lifecycle注入。
- `check_c08_process_evidence.py`强制5个恢复型冷进程的双时钟均<=5秒、2050条唯一、peak connection=1，并要求5个真实background/offline/foreground/ONLINE行<=5秒。通过结论固定为`PASS_PROCESS_LIFECYCLE_SLICE`、`c08GateClosed=false`。

## 结果

证据位于 [output/mx-a07c2-2026-09-13](../../../output/mx-a07c2-2026-09-13/)。最终设备JSON SHA-256为`ae1833e489603a9f51ccfb02440a3c941e3c26da657e521f9c279ff2cf8af433`，主机冷启摘要为`1bdcc2b69caf63ba611a6fb7446c8525b41f2a5cec6b8ac18126491f8b949269`；Profile Runner可执行文件为`dcc0ee34a4f5e6d029a947bd20a91a59ca3bf3b1487793547167860c489f0fa4`。

| 轮次 | Dart首帧 | 缓存可操作 | ONLINE | 2050条ready | 主机launch→ready上界 | RSS ready |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 15ms | 32ms | 330ms | 397ms | 1093ms | 130.95MiB |
| 2 | 15ms | 33ms | 304ms | 365ms | 1010ms | 123.86MiB |
| 3 | 16ms | 34ms | 317ms | 377ms | 1007ms | 134.66MiB |
| 4 | 17ms | 34ms | 337ms | 400ms | 1034ms | 130.69MiB |
| 5 | 14ms | 32ms | 211ms | 349ms | 1082ms | 123.69MiB |

5轮RSS在123.69–134.66MiB间波动，无逐轮单调增长；每轮2050条消息ID唯一、连接峰值1。真实OS恢复的foreground→ONLINE依次为213/297/208/301/353ms，每轮均先记录background与offline，测试进程未被OS杀死。校验器返回`PASS_PROCESS_LIFECYCLE_SLICE`。

## 验证边界与后续

1. 双时钟证明的是开发签名Profile测试入口；正式main/生产HTTPS、批准身份与签名仍NOT_RUN，因此不关闭G08/G13/G16。
2. 本轮没有执行锁屏、长挂起、Wi-Fi切换或OS杀进程恢复，这些属于C05；Settings前后台只覆盖C08恢复预算。
3. Android真机仍BLOCKED；Android 17虚拟机的性能FAIL由A07C-1保留，虚拟机可以继续辅助开发但不替代G01/G08实体证据。
4. iOS 13与代表中间OS、VoiceOver/TalkBack和完整G15矩阵仍NOT_RUN，G14/G15不关闭。

## 仓库验证

| Scope / 检查 | 结果 |
|---|---|
| `./tooling/verify flutter` | PASS：8个C08证据脚本测试、边界/向量/token检查、`flutter analyze`、117个Flutter测试 |
| `./tooling/verify flutter-candidate` | PASS：候选审计仍为`PREPARED_NOT_APPROVED`，5项发布阻塞未改变 |
| `./tooling/verify tooling` | PASS：58个runner测试 |
| `./tooling/verify workspace` | PASS |
| `./tooling/verify hygiene` | PASS |
| 本卡12个源码/工具/文档文件定向Gitleaks | PASS |
| `./tooling/verify security`全历史扫描 | FAIL：63个提交中仍有1个既有历史发现；本卡未新增发现 |
| 正式main/HTTPS、Android实体机、旧/中间OS、远程CI/签名分发 | NOT_RUN |

## 停止边界

已停止`meshx-c08-process-*`专用容器/18409 Spring并终止当前Runner测试进程；设备上的开发包保留用于复现，不称正式安装。没有commit、push、merge、远程CI或发布动作。
