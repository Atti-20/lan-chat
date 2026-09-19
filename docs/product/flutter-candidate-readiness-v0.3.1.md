# Flutter 候选构建准备 — v0.3.1

更新：2026-09-13（MX-A07B-9）。状态：**PREPARED_NOT_APPROVED**。本文和机器清单只证明候选输入与无签名构建可审查，不批准正式应用身份、版本、签名、升级策略、远程 CI 或发布。

## 当前输入快照

| 项目 | 当前值 | 候选边界 |
|---|---|---|
| 正式入口 | `apps/flutter-prototype/lib/main.dart` | 不使用 `integration_test`、probe target 或 fixture main |
| Flutter / Dart | `3.44.8` / `3.12.2` | 工作流固定 Flutter tag；Dart 使用该 SDK 自带版本 |
| 依赖锁 | `pubspec.lock` SHA-256 `85c4b5f05d12a2a35b801b823394354fcb619eb8820d793bb3853ac7e3ba41fe` | CI 使用 `flutter pub get --enforce-lockfile`；hash 漂移使 source audit 失败 |
| 应用版本 | `0.1.0+1` | 与目标 v0.3.1 不一致，`VERSION_UNAPPROVED`；本卡不改版本 |
| Android | `com.meshx.meshx_flutter_probe`，minSdk 26 / targetSdk 37 | prototype ID；release-mode APK 必须无签名、无 dart-define/fixture 输入 |
| iOS | `com.meshx.meshxFlutterProbe`，iOS 15 | prototype ID；构建 release-mode iphoneos bundle，并显式 `--no-codesign` |
| 网络策略 | 正式 main 仅 HTTPS | Android cleartext 例外只在 `src/debug`；iOS `NSAllowsLocalNetworking` 只在 `Info-Debug.plist` |

机器权威为 [candidate-readiness.json](../../apps/flutter-prototype/candidate-readiness.json)，由 [check_candidate.py](../../apps/flutter-prototype/tool/check_candidate.py) 对实际 pubspec/lock、Gradle、Xcode、Info.plist、入口和工作流逐项核对。清单固定 `dartDefines=[]`、`fixtureFiles=[]`；支持测试的 fixture 与 debug 网络例外可以继续存在于测试/debug 路径，但不能进入候选命令或正式配置。

## 统一命令与工件

```sh
./tooling/verify flutter-candidate
./tooling/verify flutter-android-candidate
./tooling/verify flutter-ios-candidate
```

- `flutter-candidate` 运行检查器单测和七类 source audit。
- Android scope 先安全移除 Flutter drive 可能遗留的、带生成文件标记的 ignored `GeneratedPluginRegistrant.java`，再运行 `flutter build apk --release --no-pub`。审计要求 APK 结构完整且没有 v1/v2/v3 签名块。
- iOS scope 运行 `flutter build ios --release --no-codesign --no-pub`，核对 bundle ID、版本/build number、可执行文件和完全未签名状态。Flutter 不支持 release-mode Simulator；该失败历史保留在 A07B-9。

2026-09-13 本地结果：Android APK `fd92f7c9290021febe21179ad65924617baa962848d4e586aa78dd4dbdb756b2`（53,095,128 bytes，UNSIGNED）；iOS Runner 可执行文件 `7039ee68bcb4091d78ca8d8ada6367b7eaaf80808369922691ccb21722097b05`（479,480 bytes，UNSIGNED）。这些 hash 只标识本轮本地工件，不声称跨 runner bit-for-bit 可复现，也不是批准分发的 RC。

## CI 准备

`.github/workflows/flutter-prototype.yml` 保留 Ubuntu analyze/test，并增加：

| Job | Runner | 可证明 | 不能证明 |
|---|---|---|---|
| quality | Ubuntu 22.04 | 锁依赖、生成一致、Flutter 回归、候选 source audit | 原生工件、真机、签名 |
| android-candidate | Ubuntu 22.04 / Java 21 / Android API 37 | 无注入输入的 unsigned release-mode prototype APK，结构/hash审计 | 可安装签名包、升级、OEM/真机、商店 |
| ios-candidate | macOS 26 / Xcode | unsigned release-mode iphoneos prototype bundle，身份/版本/hash审计 | provisioning、设备安装、TestFlight/App Store |

两个工件保留 14 天，名称含 commit SHA。工作流只声明且在本地通过语法检查；本卡没有 commit/push/workflow_dispatch，因此 G10 远程 run ID、干净 checkout 结果与远端 artifact hash 仍 **NOT_RUN**。

## 仍需独立批准

机器清单必须继续报告以下 blockers，全部关闭前 `releaseEligible=false`：

1. `APP_IDENTITY_UNAPPROVED`：正式 Android applicationId / iOS bundle ID 未选择；不得覆盖现有 Capacitor 正式用户数据。
2. `VERSION_UNAPPROVED`：目标 v0.3.1 与当前 prototype `0.1.0+1` 尚未形成批准映射。
3. `SIGNING_NOT_CONFIGURED`：Android keystore、Apple Team/provisioning/channel 均未配置，也不得提交仓库。
4. `UPGRADE_POLICY_UNAPPROVED`：旧 Capacitor → Flutter 不承诺透明数据迁移，需 C07 的身份与升级方案。
5. `REMOTE_CI_NOT_RUN`：需 C10 明确授权对具体 revision 触发并保存 run ID 与远程工件证据。

发布、签名、版本/应用 ID 决策、远程触发和分发属于后续 C07/C10/D 审查，不由 B11 的本地 PASS 推导。

2026-09-20：用户明确批准 Flutter 首发最低 iOS 15；工程各配置固定为15.0，不随Xcode推荐值自动漂移。iOS 13/14不在本次Flutter首发支持范围；应用身份、正式签名及升级策略另按既有门禁。
