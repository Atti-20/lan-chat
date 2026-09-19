# 统一验证入口

唯一命令清单是 [tooling/workspace.json](../../tooling/workspace.json)。[workspace.py](../../tooling/workspace.py) 提供入口，[verification.py](../../tooling/verification.py) 处理执行和结果；不另建 Node runner、根 package.json 或第二套依赖管理。现有 package.json、Maven、Cargo、各 lock 与 CI 安全门禁保持职责。

Flutter候选准备使用 `flutter-candidate`、`flutter-android-candidate`、`flutter-ios-candidate`。后两者只生成并审计未签名prototype release-mode工件；PASS不代表正式身份、版本、设备安装、升级、签名、远程CI或发布通过，边界见[候选准备清单](../product/flutter-candidate-readiness-v0.3.1.md)。

## 常用命令

在仓库根执行；在其他目录可用工作区脚本的绝对路径，命令仍在清单指定的仓库目录执行。Windows 使用 `python tooling/workspace.py verify <scope>`，不要求 sh。

```sh
python3 tooling/workspace.py context chat
python3 tooling/workspace.py doctor web
./tooling/verify web --dry-run
./tooling/verify web
./tooling/verify desktop
./tooling/verify server
./tooling/verify contracts
./tooling/verify workspace
./tooling/verify tooling
./tooling/verify hygiene
./tooling/verify security
./tooling/verify all
# 可选：写入本次调用的机器可读结果，路径相对调用时的 cwd
./tooling/verify web --report output/verification/web.json
./tooling/verify server -- -Dtest=FileServiceSecurityTest
```

| scope | 实际包装 | 边界 |
|---|---|---|
| workspace | 现有入口/链接/字节预算、生成漂移、契约和共享依赖检查 | AI 任务路由和 docs/ai、docs/tasks 也纳入检查 |
| tooling | Python unittest discover | 运行器的失败/阻塞、argv、dry-run、报告和导航回归 |
| web | npm test；npm run build（内含 vue-tsc） | Vite 写入服务端 static/app，保留旧哈希资源 |
| server | 根 `./mvnw -B test` | 默认单元/MVC/H2等测试，不是生产数据库/升级或 E2E |
| desktop | npm build:desktop；cargo test --locked | dist-desktop 会清空重建；保留原有系统凭据库 ignored 项，另记 NOT_RUN；不生成签名发布包 |
| contracts | 现有静态检查、npm check、RestContractTest | 结构契约与行为覆盖边界仍以 contracts/README 为准 |
| dart-core | Core 依赖边界；独立 Dart VM 共享向量 | 不启动 Flutter engine |
| flutter-android | flutter build apk --debug --no-pub | 编译不代表设备运行或发布 |
| flutter-ios | flutter build ios --simulator --debug --no-pub | macOS/Xcode；不验证真机签名 |
| flutter | Core 依赖边界/向量、tokens --check；flutter analyze/test --no-pub | 复用已安装依赖，不自动 pub get；原型切片，不是完整移动 App |
| hygiene | 跟踪文件策略、图标一致性、版本一致性、工作树和暂存区 diff --check | 跟踪文件检查不扫描未追踪文件内容，不等于凭据扫描 |
| security | Gitleaks git，全本地历史、现有配置、输出完全脱敏 | 拒绝浅克隆；不自动 fetch。扫描所有本地 refs，不证明未获取的远程分支、未提交/未追踪内容无敏感数据 |
| android / ios | 保留原来的 Gradle test/lint/APK、Vue 同步/Xcode 模拟器命令 | **仅旧 Capacitor 壳**，不作为选定移动路线；SDK、签名和真机另验 |
| all | 顺序执行上述全部 scope（包含旧壳），首次失败/阻塞即停止 | 后续每条命令显示 NOT_RUN；运行前用 --dry-run 确认；不会自动安装服务、启动 E2E 或发布 |

`all` 的确切顺序在清单 `verificationGroups.all`；包括安全门禁，不能将它误解为只运行 Web/Java。按任务选择范围通常更合适；只跑部分 scope 时仍需说明未验证项。

## 状态与退出码

- **PASS**：对应子命令执行完成且退出 0；不扩展到未覆盖的运行层次。存在原有 ignored 测试时保留原始计数，单独记 NOT_RUN。
- **FAIL**：子进程失败，保留其非零退出码与原始输出；后续步骤 NOT_RUN。信号终止转为 `128 + signal` 的入口退出码，JSON 保留子进程原始码。
- **BLOCKED**：可执行文件、声明的依赖/输入、平台或完整本地历史缺失，或 OS 无法启动命令；入口返回 3。没有启动子进程时 `exitCode=null`。
- **NOT_RUN**：dry-run、之前步骤失败/阻塞，或被中断而未完成。dry-run 返回 0 只表示命令计划成功，**验证汇总仍是 NOT_RUN**；中断返回 130。

未知 scope/非法参数返回 2 并报告 FAIL；空 scope/空命令清单不能返回成功。JSON `steps` 保存 scope、命令名、cwd、argv、状态、退出码、耗时和原因；`--report` 写入失败同样非零。

`doctor [scope]` 默认 all，只核对可执行文件与清单中声明的依赖/输入、平台及浅克隆状态，不启动构建、自动安装或接受 SDK 许可证。doctor 的 PASS 只表示这些前提存在，所有验证步骤仍是 NOT_RUN；它不检查 SDK 许可证、工具版本兼容、数据库健康、Xcode provisioning 或浏览器/真机能力。相关任务再执行真实验证及平台诊断，例如 `flutter doctor -v`，不能用 doctor 返回 0 代替构建或设备证据。

## 环境、锁文件和安全

不引入根 npm workspace：现有共享包按源码消费，各工程 lock 已能定义依赖，统一导航/验证不需要改变安装策略。依赖缺失时先记录 BLOCKED，再按 [本地开发](../runbooks/local-dev.md) 和当前用户授权恢复受信任 lock；不运行 audit fix、切包管理器或升级主要依赖。

Python 子命令使用当前解释器，Windows npm/flutter/Maven/Gradle 使用对应 .cmd/.bat；argv 数组保留带空格的路径和参数，不拼接 shell。`MESHX_MAVEN_REPO` 沿用现有缓存覆盖；`MESHX_GITLEAKS_BIN` 可指定已经验证的二进制路径，例如 CI 下载并校验 SHA-256 的工具。默认从 PATH 取 Gitleaks，不下载安装。

现有 `.github/workflows/repository-hygiene.yml` 的固定 checkout、完整历史、下载校验和 Gitleaks 参数继续保留；统一入口不是安全检查的豁免。现有 CI 的 Node/Java 版本与本机可能不同，应分别报告。

## 证据与交付

每次修改按影响执行真实命令，在任务卡记录日期、branch/HEAD、环境、退出码、PASS/FAIL/BLOCKED/NOT_RUN、原始日志、已处理/遗留项和下一张卡的启动条件。报告按 [docs 指令](../AGENTS.md) 归档；输出放 output，不提交日志、凭据或安装包。

编译/测试、浏览器/已安装应用、模拟器/真机、LAN/后台、签名/发布分别记录。Web/desktop 构建前检查目标输出是否包含用户文件，保存必要前后差异；不得 clean 掉已有工程来证明“干净”。A00 的历史证据在 [baseline](../runbooks/baseline.md)，当前任务结果在 [任务索引](../tasks/README.md)。

移动进程恢复需保留应用数据：`flutter drive --keep-app-running` 后用 adb force-stop / simctl terminate，再启动恢复阶段。默认 drive 卸载不可作为进程重启证据。真实 Web/native 互通与独立两服务器隔离、真机/LAN、后台/签名应分别记录。
