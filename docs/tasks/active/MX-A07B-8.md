# MX-A07B-8 — Flutter 平台可用性与最小诊断

开始/完成日期：2026-09-13。状态：**B10 本地实现阶段完成；117 项 Flutter 回归、Android debug APK、iOS Simulator 构建与 iPhone Profile 原生版本读取 PASS。** 产品支持页真机导航/复制、完整读屏/Dynamic Type/返回/键盘/安全区矩阵和 C08 Profile/Release-like 性能仍 NOT_RUN；A05 Debug 输入 ANR 风险保持未关闭。

## 基线与范围

- 分支 `feature/v0.3.1`；HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；保留用户已有大规模 dirty workspace。基于 A06、A07B-3 与 A07B-7 的主题、权限和生命周期实现继续。
- 本卡只增加 Flutter 用户可见的最小支持信息和对应 Android/iOS 公开版本读取。没有创建管理员日志控制台，没有改应用标识、版本号、最低系统版本、签名、协议、服务端或依赖。

## 已实现

- 个人资料与设置新增“查看支持信息”入口。页面先展示再允许复制，复制内容与可见预览完全一致；失败时只显示稳定 reason 并可重试。
- 原生 `RuntimeInfoPort` 只返回应用版本/build number、OS 名称/版本。Dart 适配器对每个字段做类型、字符和 64 字符上限校验；契约不包含设备名称、设备 ID、文件路径或任意日志。
- 支持信息再组合当前节点名称及 host/port、`ONLINE / CONNECTING / BACKGROUND / OFFLINE / SIGNED_OUT` 连接阶段和稳定脱敏错误码。节点 URI 不带 user-info、query、fragment 或 path；错误码按 HTTP、格式、平台、超时、网络/TLS和未知类别生成，不复制原始异常。
- 页面使用 `SafeArea`、可滚动布局、系统文字缩放、可选择预览、明确按钮文字与语义标签。新增 320×568、1.5 倍字号 widget 回归，验证内容可达、无溢出，且令牌、原始错误和路径不会进入预览或剪贴板。

## 验证

证据位于 [output/mx-a07b8-2026-09-13](../../../output/mx-a07b8-2026-09-13/)。

| 范围 / 命令 | 状态 | 结果 |
|---|---|---|
| `./tooling/verify flutter --report output/mx-a07b8-2026-09-13/flutter-final-2.json` | PASS / 0 | 纯 Dart边界、14向量、Token drift、analyze与117项测试全部通过；新增真机探针也进入 analyze |
| 新增支持信息测试 | PASS | 原生返回值白名单/恶意路径拒绝；320×568、1.5倍字号；预览/复制一致；token、原始错误和路径均不出现 |
| `./tooling/verify flutter-android --report .../flutter-android.json` | PASS / 0 | Kotlin `PackageInfo` / `Build.VERSION` 编译并生成 debug APK；Android 实机读取 NOT_RUN |
| `./tooling/verify flutter-ios --report .../flutter-ios.json` | PASS / 0 | Swift `Bundle` / `UIDevice` 编译并生成 Simulator app；产品页真机导航/复制 NOT_RUN |
| `flutter drive --profile ... a07b8_runtime_info_test.dart -d 00008140-000039280E08801C` | PASS / 0 | iPhone 16 Pro Max / iOS 26.6.1 完成 Profile 签名安装启动；真实通道只返回 app `0.1.0 (1)`、OS `iOS 26.6.1` 四字段，见 `ios-runtime-info-integration.json` |
| C02 / C08 实体设备可用性与性能 | NOT_RUN | 当前只有一台无线 iPhone 且本卡未执行完整交互/性能脚本；Android 设备仍缺失。A05 Debug 输入 ANR 不据静态修复或模拟器构建关闭 |
| `./tooling/verify workspace --report .../workspace-final-3.json` / `hygiene --report .../hygiene-final-2.json` | PASS / 0 | 新文件使首次 workspace 检查正确报告 repo-map stale；经官方 generate 后 workspace 1 步与 hygiene 5 步通过 |
| `./tooling/verify security --report .../security.json` | FAIL / 1 | 全历史 63 commits 仍为同一既有 finding；规则未修改或放宽 |
| `gitleaks dir` 定向扫描 Flutter lib、Kotlin、Swift、单元/集成测试与本任务卡 | PASS / 0 | 六个变化范围均无 finding |

实现中第一次正式 Flutter 检查因新增页面的一处单行 `if` lint 而 FAIL，测试被正确标为 NOT_RUN；修正后完整复验 PASS。另一次在 Flutter 子目录误执行根相对 `./tooling/verify` 返回 127，立即回到仓库根重跑；没有把这两次操作错误隐藏成通过。

## 未完成与下一步

1. G15 仍需 Android/iPhone 实体设备跑小屏/长字、浅深色、系统字号、键盘、返回、安全区、主要控件读屏与权限拒绝恢复；当前自动 widget 与 iPhone 原生版本读取只关闭新增支持页的局部代码/端口回归，产品页导航和复制未在手机上操作。
2. G08 仍需 Profile/Release-like 真机量化冷启动、列表滚动、输入/键盘、长消息、发现并行和重连。A05 Debug 输入 ANR 保持风险，只有量化复现后才授权针对性修复。
3. G14 最低 Android 26 / iOS 13 的实际关键链仍需代表设备或设备农场；本卡 API 有版本分支且双端编译通过，但不等于旧 OS 运行证据。
4. B11、A07C/A07D 与 A07B-1R 后续实现均未在本卡启动。

## 回滚与停止边界

本卡只改 Flutter support UI/port/controller、Kotlin/Swift版本桥、测试和状态文档。回滚需逐文件核对后续编辑，不回滚整个 dirty tree。没有 commit、push、merge、release或上传分发。
