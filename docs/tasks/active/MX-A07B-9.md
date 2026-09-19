# MX-A07B-9 — Flutter 候选构建与 CI 准备

开始/完成日期：2026-09-13。状态：**B11 本地准备完成，PREPARED_NOT_APPROVED。** 候选输入审计、Android unsigned release-mode APK 与 iOS unsigned release-mode iphoneos bundle 本地 PASS；远程 CI、正式身份/版本、签名、安装升级和发布仍 NOT_RUN/未批准，G10/G13/G16 不关闭。

## 基线与范围

- 分支 `feature/v0.3.1`；HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；保留用户已有大规模 dirty workspace。以 A07A Gates、A07B-8 与当前 Flutter/CI 文件为准。
- 只修改 Flutter 候选清单/检查器、统一 verify、现有 Flutter workflow 与状态文档。没有选择或修改应用 ID、版本、最低 OS、签名身份、证书、渠道；没有 commit、push、workflow dispatch、远程 CI 或分发。

## 已实现

- 新增 `candidate-readiness.json`：固定正式 `lib/main.dart`、Flutter/Dart、pubspec版本与 lock hash、Android/iOS身份和最低版本、无 dart-define/fixture 的构建参数，并显式保持 `releaseEligible=false` 与五个审批 blockers。
- 新增标准库检查器及单测，核对实际 pubspec/lock、Gradle、Xcode、Info.plist、debug-only网络例外、工作流 SDK pin 与统一构建入口。Android APK审计结构和无签名块；iOS审计 bundle身份/版本/可执行文件与未签名状态。
- `tooling/workspace.json` 新增 `flutter-candidate`、`flutter-android-candidate`、`flutter-ios-candidate`。Android构建前只会删除带三重生成标记的 ignored stale registrant，拒绝删除任何无法识别的 Java 文件。
- Flutter workflow 从单个 Ubuntu job 扩为 quality、Android release-mode prototype、iOS unsigned iphoneos 三个 job，工件名包含 commit SHA、保留14天，并在 summary 明示身份/签名/设备/升级/发布未验证。
- 详细准备清单见 [Flutter候选构建准备](../../product/flutter-candidate-readiness-v0.3.1.md)。

## 验证

证据位于 [output/mx-a07b9-2026-09-13](../../../output/mx-a07b9-2026-09-13/)。

| 范围 / 命令 | 状态 | 结果 |
|---|---|---|
| `./tooling/verify flutter-candidate --report .../candidate-source-final.json` | PASS / 0 | 3项检查器测试、7类source audit；状态保持PREPARED_NOT_APPROVED与5个blockers |
| 首次 `flutter-android-candidate` | FAIL / NOT_RUN | Profile真机测试遗留ignored生成registrant引用仅测试插件，release Java编译FAIL；工件审计正确NOT_RUN |
| `./tooling/verify flutter-android-candidate --report .../android-candidate-final-2.json` | PASS / 0 | 4步通过；unsigned release APK结构、无签名块、53,095,128 bytes与SHA-256审计 |
| 首次 `flutter-ios-candidate` | FAIL / NOT_RUN | Flutter明确拒绝`--release --simulator`；工件审计正确NOT_RUN，未把debug Simulator冒充候选 |
| `./tooling/verify flutter-ios-candidate --report .../ios-candidate-final-2.json` | PASS / 0 | 3步通过；release iphoneos + `--no-codesign`，bundle身份/版本/可执行文件及完全未签名审计 |
| `actionlint .github/workflows/flutter-prototype.yml` | PASS / 0 | 工作流语法/表达式检查通过；这不是远程执行证据 |
| `aapt dump badging` / `plutil` 本地工件核对 | PASS | Android `com.meshx.meshx_flutter_probe` `0.1.0(1)` min26/target37；iOS `com.meshx.meshxFlutterProbe` `0.1.0(1)` |
| `./tooling/verify flutter` / `tooling` / `workspace` / `hygiene` | PASS / 0 | Flutter 117项、runner 58项、workspace 1步、hygiene 5步通过；repo-map由官方generate更新 |
| `./tooling/verify security --report .../security.json` | FAIL / 1 | 全历史63 commits仍为同一既有finding；规则未修改或放宽 |
| `gitleaks dir` 定向扫描候选清单/检查器、workflow、workspace配置与文档 | PASS / 0 | 八个变化范围均无finding |
| 远程 CI / 签名 / 安装 / 升级 / 发布 | NOT_RUN | 本卡禁止远程触发与发布，且身份/版本/升级策略未批准 |

## 未完成与下一步

1. C10 需对批准的具体 revision 触发远程 workflow，保存 run ID、clean checkout日志与远程工件hash；本地 actionlint/构建不能关闭 G10。
2. C07/G13/G16 需先批准正式身份、版本/build映射、签名渠道和旧 Capacitor → Flutter 升级/数据策略，再做清洁安装、覆盖升级、失败恢复与分发审查。
3. 当前本地工件均故意未签名且使用prototype身份，不可安装分发，也不称 RC。
4. A07C/A07D 与 A07B-1R 后续实现未在本卡启动。

## 回滚与停止边界

本卡只改候选准备文件、现有 Flutter workflow、统一验证清单和文档。回滚需逐文件核对后续编辑，不回滚整个 dirty tree。没有 commit、push、merge、workflow dispatch、签名、release或上传分发。


## Flutter 原生品牌资源补齐（2026-09-13）

本次接续完整Flutter迁移目标，发现Flutter Android仍使用默认Flutter图标且无round/adaptive配置。现将既有MeshX桌面规范导出逐文件复制到Flutter Android五档普通/round/foreground资源及v26配置，manifest加入roundIcon；iOS全部19个Contents槽位复用相同桌面iOS导出。源图与用户指定PNG哈希一致；没有重绘品牌、修改Web页面资源或应用身份。

现有 `scripts/ci/check-native-icon-parity.py` 从16个扩为50个栅格引用，并检查Flutter/旧Android的manifest启动器入口及adaptive配置。Flutter workflow quality调用同一检查器，路径触发覆盖其资源依赖；没有触发远程CI。标准库检查器负例证明错误PNG、缺失roundIcon均FAIL，恢复后PASS。

证据目录 `output/mx-flutter-icons-2026-09-13/`：hygiene五组PASS（`hygiene.json`）；checker负例PASS（`checker-negative.json`）；actionlint与py_compile PASS；Android debug main构建PASS（`android-build.json`），APK内15个PNG逐像素与规范导出相同（`apk-icon-audit.json`）。五档前景白色标记最大半径31.18–32.26dp，均在33dp安全半径内（`safe-area.json`，阈值与方法在JSON中明示）。浏览器实际查看`preview.html`的72dp圆形遮罩预览，白色标记完整；该HTML是静态资源预览，**不是Pixel截图**。

iOS Simulator main构建PASS（`ios-build.json`），包内120px/152px两张AppIcon逐像素与规范导出一致（`ios-icon-audit.json`）；Android编译后manifest含icon/roundIcon（`apk-manifest.txt`）。Pixel桌面安装/显示、OEM、iPhone桌面、最终签名/升级/分发仍NOT_RUN；现有桌面/旧壳资源未变，因此未重建这些客户端。此图标交付不关闭G10/G13/G16或完整迁移目标。


## 跨端本地工件接续（2026-09-14）

Flutter最新导航/到期刷新代码已通过176项测试并生成Android debug与iOS Simulator debug工件，见 `output/mx-navigation-deadline-2026-09-14/artifacts.json`，构建结果及边界在B10卡。

Web执行现有`npm --prefix apps/web run build`成功，归档到 `output/mx-desktop-package-2026-09-14/MeshX-Web.zip`；源为服务端`static/app`，部署基路径`/app/`，保留旧哈希资源。首轮按通用dist路径归档失败，查阅当前vite.config后改用实际目录成功。哈希/大小见同目录`web-artifact.json`。未部署或重启服务端。macOS app构建PASS（同目录`build-app.log`），完整bundle的`codesign --verify --deep --strict`通过；包内icon.icns与规范导出逐字节一致。CUA启动本次构建路径，进程路径/PID及tauri://localhost登录界面、原生扫描结果已核对，随后退出测试应用。截图仅本轮工具输出，未声称已归档PNG。应用ZIP与哈希/版本见`macos-artifact.json`。使用现有0.3.0 / com.atti20.lanchat及ad-hoc签名，未公证、未安装到Applications、未生成DMG、未发布；本轮启动验证不等于完整登录聊天回归。Windows/Linux应用未在本轮构建，完整跨端交付保持开放。


Windows本地构建环境探测（2026-09-14）：Parallels发现已有Windows 11，初始suspended，恢复成功；恢复期间的第一次工具查询被拒绝，恢复后的Node/Rust/Git/磁盘只读查询持续无响应，CUA窗口读取也timeout。已取消该查询（prlctl退出255），没有开始编译、安装依赖或修改guest源码/签名。环境预检BLOCKED，工具链UNKNOWN，Windows构建NOT_RUN；证据为 `output/mx-windows-build-preflight-2026-09-14/preflight.json`。已成功恢复原挂起状态（同目录`vm-state-final.txt`）；未强制关机。


## 2026-09-20 当前代码整批构建与Pixel启动器验收

用户明确批准Flutter首发最低iOS15。候选清单、Xcode项目与Runner/RunnerTests全部9处部署目标已固定15.0；产品支持文档同步，不再依赖随Xcode变化的RECOMMENDED值。首次候选source检查正确发现13.0与推荐值混用并FAIL，获得授权后3项检查器测试和7类source审计PASS；未绕过审计或更换应用ID。

构建与证据统一在output/mx-delivery-2026-09-20。Web当前构建PASS并归档MeshX-Web.zip（/app/基路径，保留既有哈希资源，不部署）。macOS build:app及完整签名校验PASS，继而正常build:dmg完成，无需CI跳过Finder布局；DMG校验PASS，实际只读挂载后验证包签名和icon.icns与规范源逐字节相同，并通过LaunchServices启动本次镜像内应用。CUA看到tauri://localhost登录界面及原生发现入口，进程路径属于本次挂载卷；退出并卸载镜像。保持0.3.0/com.atti20.lanchat/ad-hoc签名，未经公证或正式发布。打DMG后Tauri清理了原app-only输出，第一次尝试原路径启动失败；改为检查实际交付DMG中的应用，不将失败记成功。

Android release正式lib/main.dart、无注入输入构建及unsigned审计PASS；保留无签名APK，并以本机既有Android开发密钥签署独立preview副本。preview安装到Pixel_10_Pro模拟器并启动正常登录页，未卸载/清除原账号数据。pixel-drawer.png为实际Pixel启动器画面，MeshX体验版圆形图标白色标记完整；不是HTML遮罩预览。现有两个其他MeshX安装项不作为本包图标证据。50栅格/自适应层源一致性检查PASS。Android preview仍为release-mode网络策略，HTTPS要求保留，不放宽本地HTTP保护。

现场发现浅色登录页状态栏仍为白色文字：先加应用级AnnotatedRegion后，截图pixel-final-light-rendered.png仍失败，原因是透明AppBar自动亮度覆盖外层。随后统一设置AppBarTheme.systemOverlayStyle，同时保留无AppBar页面的应用级规则。最终pixel-appbar-final-light.png实际登录页的时间、电量、信号已为黑色；暗色切换规则保留，但此次没有追加暗色设备截图，不虚报双主题设备验收。最终Flutter analyze及完整251项测试PASS（flutter-analyze-final.log、flutter-tests-final.log）。修改后Android及iOS工件均重新构建，第二次构建日志以second-开头，最终哈希以mobile-final-artifacts.json为准。

iOS无签名release正式入口构建、候选工件审计PASS；另使用用户已登录的原开发账号和体验版身份构建正常lib/main.dart的Profile开发签名包，用于当前已登记iPhone。该包不是容量integration_test入口；正式发布和无签名候选的属性保持分离。原生身份未改，仍不是RC。最终安装/启动结果、IPA哈希与开发描述文件期限见本目录记录；不能把安装启动命令成功当作完整聊天、通知或iOS视觉验收。

Windows重新探测有进展：VM从原suspended恢复成功，--current-user可运行真实Windows命令。此前默认exec在SYSTEM账号，不能据其PATH缺失推断用户环境；随后确认登录用户atti也无Node/cargo/git，但有winget。工具链尚未安装，Windows构建NOT_RUN；已恢复原suspended状态。第一次读取中文Windows版本时宿主UTF-8解码失败，改用gb18030读取后证实Windows 11 10.0.26200.9168，不误标为VM不可用。Linux本批NOT_RUN。完整目标仍需Windows/Linux构建、真实HTTP/WS与设备故障矩阵、正式身份/签名/升级/发布门禁；本批产物不能把整体迁移标记完成。


## 2026-09-20 构建环境与 GitHub 接续

用户批准安装 Windows 工具链后，Node.js LTS 已安装，Git 下载校验通过但停留在 UAC；用户随后要求停止 Windows/Linux 本地构建、上传最新代码并改用 GitHub 构建，优先验收 macOS/iOS/Android/Web 闭环。已取消本次 winget/Git 安装进程，Rust/MSVC 未进入安装，不自动重启。Windows 构建副本使用官方便携 Python，签名验证有效；原设计检查脚本读取默认 GBK 失败，修复显式 UTF-8 后 Windows Vue 构建 PASS，工具测试 59 项 PASS。

停止指令前 Linux ARM64 本地测试 53 PASS/1 既有 ignored，Debian 包和 AppImage 已生成（output/mx-delivery-2026-09-20/linux）。首轮漏拷贝 contracts/fixtures/core-v1.json，补齐后测试通过；AppImage 首轮缺 xdg-utils，补齐后打包通过。未执行 Linux GUI 安装启动验收。用户要求后不再追加本地 Linux 构建。

现有 Desktop Build 工作流新增 Windows NSIS/MSI、Linux deb/AppImage 的未签名体验安装包上传，保留原 Rust tests/clippy 与版本检查；Linux 安装依赖补 xdg-utils/patchelf。通知模块仅在 macOS/Windows 编译其专用回调，消除 Linux 无调用代码的严格 lint 失败，未改变 Linux 的不支持返回语义。macOS all-targets strict clippy PASS。同步前 staged Gitleaks PASS；暂存全部源码后发现四处历史末尾空行，修复原文件和 API 文档生成器，不跳过 hygiene。GitHub 上传与四端真实闭环结果待后续证据，不把本地构建视作通过。
