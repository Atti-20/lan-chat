# MX-A07B-5 — Flutter 基础文件与图片消息

开始/完成日期：2026-09-13。状态：**B06 本阶段交付完成；实现、本地回归、Android/iOS 模拟器构建及真实 Spring 节点中转闭环 PASS。** Android/iPhone 真实文件提供方、系统保存/分享面板和发布仍 NOT_RUN/BLOCKED；安全门禁保留既有 FAIL。跨进程续传与 WebRTC 直传不属于本切片。

## 基线与范围

- 分支 `feature/v0.3.1`；HEAD `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；起点仍为用户已有的大规模 dirty workspace，全部保留。以 `docs/tasks` 当前进度、A07A Scope/Matrix/Gates/Work Plan 及 A07B-1R 停止边界为准，不重做 A00–A07B-4。
- 本切片只消费既有 `POST /api/v1/file/upload`、授权 `GET /api/v1/file/content/{fileName}` 及现有 `CHAT_SEND/CHAT_ACK` 的 `file/image` JSON 结构。没有修改 REST/WS schema、Java 服务端、数据库、应用标识、最低系统版本、签名或发布配置。
- P0 只做最多 25 MiB 的有界前台节点中转。服务端若配置更小限制，实际上传失败原样可见；不宣称后台上传、跨进程分片续传、WebRTC/GMS 直传或视频编辑。

## 已实现

- 新增严格 `AttachmentData` / `FileUploadResult`：只接受当前节点 `/api/v1/file/content/<storedName>` 相对引用、1–25 MiB、合法 MIME、64 位 SHA-256 与 `NODE_RELAY`；外部绝对 URL、直传但无本机副本、元数据不一致均 fail closed。
- FilePicker Port 增加最多 1 MiB 的 opaque handle 分块读取；Kotlin 使用 `RandomAccessFile`，Swift 使用兼容最低系统版本的 `FileHandle.readData(ofLength:)`。Core 与 MethodChannel 均不接收任意路径/URI；原头像完整读取仍保持 5 MiB 调用上限。
- `MeshXApi` 使用已知长度 multipart 边读边上传，支持进度和取消，401 仅刷新一次；只有上传完成并严格解析响应后，`ChatController` 才以 `file/image` 加入原有持久 outbox、同一 `clientMsgId` ACK/超时/重试管线。上传、选择、读取失败均不会产生附件消息，文字离线 outbox 未改变。
- 接收侧禁用重定向，只为同节点授权内容附带 Bearer；按声明大小和 SHA-256 校验完整字节。图片可在会话内基础预览，验证后的文件写成新的 app-owned 临时 handle 再交给 Android/iOS 系统保存/分享面板。
- 取消不显示为失败；切换会话会阻止迟到上传进入错误会话；退出/账号切换会取消旧操作、清空内存预览并释放 native 临时副本。Web `NODE_RELAY` 消息可被同一严格结构接收；`PEER_TO_PEER` 无本地副本时明确拒绝而不伪装下载成功。
- 新增真实 Spring 探针 `tool/a07_attachments_live_test.dart`，覆盖群内文本文件和 PNG 上传、双方 `CHAT_ACK/CHAT_DELIVER`、接收方字节/哈希、同 ID 重发 duplicated ACK，以及退群后的下载授权拒绝。

## 验证

证据位于 [output/mx-a07b5-2026-09-13](../../../output/mx-a07b5-2026-09-13/)；统一 scope 均按仓库验证入口执行。

| 范围 / 命令 | 状态 | 结果 |
|---|---|---|
| `./tooling/verify flutter --report output/mx-a07b5-2026-09-13/flutter-final.json` | PASS / 0 | 纯 Dart 边界、14 向量、Token drift、analyze、102 项测试；新增 6 个附件 DTO/API/controller 用例、2 个 widget 用例及平台分块/cache codec 覆盖 |
| `./tooling/verify flutter-android --report output/mx-a07b5-2026-09-13/flutter-android-final.json` | PASS / 0 | Kotlin 分块读/缓存写编译并生成 debug APK；不代表 Android 真机、系统面板、签名或发布通过 |
| `./tooling/verify flutter-ios --report output/mx-a07b5-2026-09-13/flutter-ios-final.json` | PASS / 0 | Swift 分块读/缓存写编译并生成 iOS Simulator app；不代表 iPhone 真机、系统面板、签名或发布通过 |
| `flutter test --no-pub --machine tool/a07_attachments_live_test.dart --dart-define=MESHX_NODE=http://127.0.0.1:18432` | PASS / 0 | 隔离 Spring/MySQL/Redis 完成文本/PNG 节点中转、ACK/重复ACK、双向授权下载/哈希及退群后拒绝；证据 `attachments-live.json` |
| `./tooling/verify contracts --report output/mx-a07b5-2026-09-13/contracts.json` | PASS / 0 | 契约来源/生成无漂移，Node 9、Java 7、兼容回归 3 全部 PASS |
| `./tooling/verify workspace --report output/mx-a07b5-2026-09-13/workspace-final.json` | PASS / 0 | 正式 generate 后新增附件入口与 repo-map 一致 |
| `./tooling/verify hygiene --report output/mx-a07b5-2026-09-13/hygiene-final.json` | PASS / 0 | tracked/native icon/version/工作树与 index whitespace 共 5 步 |
| `./tooling/verify security --report output/mx-a07b5-2026-09-13/security-final.json` | FAIL / 1 | 全历史 63 commits 仍为同一既有 finding；规则未修改或放宽 |
| `gitleaks dir` 定向扫描 Flutter `lib/test/tool`、Kotlin/Swift 与本任务卡 | PASS / 0 | 六个变化范围均无 finding；JSON 报告保存在本轮 output |

首次 iOS 模拟器构建因 `FileHandle.read(upToCount:)` 要求 iOS 13.4 而 FAIL；改用现有 deployment target 可用的读取 API 后重跑 PASS，没有提高最低系统版本。首次真实 fixture 启动漏传隔离组织 ID，注册返回 500；补齐 `MESHX_ORGANIZATION_ID=org-a07b5` 后全链路 PASS。另一次在仓库根误运行模块相对 Flutter 命令，分析器误扫历史 `output/` 快照并产生无效失败；正式统一 scope 与模块内复验均 PASS。这些失败未被伪装成首次成功。

## 未完成与下一步

1. Android/iPhone 真实文件选择器、25 MiB 边界、删除/权限撤销、上传取消、图片预览、系统保存/分享面板、返回/字号/读屏点击属于 A07C/C06，当前 NOT_RUN；模拟器构建和 fake adapter 不能关闭设备/发布 Gate。
2. 专用 fixture 已按所有权停止；`meshx-a07b5-*` 容器与 18432/13352/16432 监听均已清除。真实 Spring 探针是服务端/API/WS 证据，不是假称手机 UI 实测。
3. 跨进程分片续传仍为 F23/P1，WebRTC 直传仍为 F24/P2；本卡只确保 Web 直传回退形成的 `NODE_RELAY` 内容可被 Flutter 解析和下载。
4. B03 通用消息变更/授权恢复仍由 A07B-1R `READY_FOR_APPROVAL` 候选控制；B07/B08/B10/B11 与 A07C/A07D 保持其当前状态。

## 回滚与停止边界

本卡只改 Flutter 附件模型/API/controller/UI、平台 opaque 分块读与临时缓存、依赖锁、测试/探针和状态文档。回滚需逐文件核对后续编辑，不回滚整个 dirty tree。没有 commit、push、merge、release、签名或远程操作。
