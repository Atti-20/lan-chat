# MX-A07C-3 — 新主导航真实跨端流程验收


## 附件等待响应取消与重试（2026-09-13）

状态：**生产修复与本地回归PASS；Android原生新分支NOT_RUN，C01/C06未关闭**。接续上轮最后分块取消修复，覆盖文件写完后等待响应头、已收到部分响应正文两种卡住场景。

- `lib/data/meshx_api.dart`：现有cancelled回调在上传存活期间每100ms检查，取消时中止请求；若响应流已开始则取消订阅并结束等待。finally移除检查器和订阅，正文读取增加45秒超时；正常错误仍抛出，不把取消报为发送成功。用户取消不保证撤回服务端此前已接收的文件，消息仍须上传成功后才进入ACK outbox。
- `test/attachments_feature_test.dart`：真实loopback HttpServer持有响应；两种场景分别先FAIL（2秒超时），修复后及时抛AttachmentCancelledException。追加同一API在旧服务端handler仍等待时上传不同文件并校验新文件hash；此项是网络回归，不冒充Spring或系统picker证据。
- `tool/c06_upload_fault_proxy.mjs`：增加可选stall模式，只挂起首次上传且不转发；记录客户端关闭事件，之后恢复真实HTTP/WS中继。默认503行为保留，CLI仍核验flutter-probe与loopback。4项Node测试PASS，包含挂起/取消/重试和原双向升级连接。
- `integration_test/c01_cross_client_smoke_test.dart`：可选C01_CANCEL_UPLOAD（须有附件名且不能与错误分支同时使用），原生读完后点击既有“取消文件操作”，断言句柄释放、入口恢复、无错误banner、客户端与Spring零新增消息，再重选并沿原流程等ACK。scope与uploadCancellationUi单独记录。新分支**尚未运行**。
- 当前adb确认emulator-5554在线，18386 Java监听；CUA无法识别qemu原生窗口（Invalid app），没有用既有截图或旧设备日志冒充本轮系统选图。未启动新的故障代理或交互drive，原fixture保持运行。
- 本轮统一Flutter六组PASS（analyze与164项测试）：`output/mx-a07c6-upload-wait-cancel/flutter-final.json`；首次`flutter.json`保留两处多余非空断言lint FAIL和测试NOT_RUN。最终同API重试断言追加后定向复验及analyze分别存`attachments-final.log`、`analyze-final.log`。Android正式main入口debug构建PASS（93.5秒），见`android-build.json`；APK路径/大小/SHA-256存`android-artifact.json`，构建不关闭设备或发布Gate。

下一步仍为新Android原生上传取消→重选→ACK→Web哈希，以及真实连接中断；消息恢复契约、iOS/实体机、正式身份/HTTPS/签名与最终各端图标/工件均保持未完成。完整迁移目标没有缩小为附件修复。


## 附件最后分块取消缺陷修复（2026-09-13）

状态：**本次修复与本地验证PASS；C01/C06保持未关闭**。本次接续用户完整Flutter迁移目标，保留全部已有未提交迁移文件。读取当前任务索引、A07A范围及C03最新交接后，推进附件上传中取消的实际网络边界。

- `lib/data/meshx_api.dart`：原生分块读取返回后、最后一块之后提交multipart结束符前，再检查操作取消/账号失效。原实现只在读取前检查，最后一次读取中取消仍向HTTP服务提交完整文件；新增本机HTTP回归先FAIL，收到服务端的unexpected upload错误。
- 中止未完整写入的HttpClientRequest会让done产生异步错误；提前观察该Future，正常路径依然await close报告网络错误。首轮修复测试捕获未处理Content size below specified contentLength异常，补齐观察后定向13项PASS。
- `test/attachments_feature_test.dart`：再扩大为读取期间取消与最后一次progress回调取消两种情况，断言AttachmentCancelledException、进度调用次数和服务端零完整multipart请求。使用真实loopback HttpServer，不把fake API成功当作网络取消证据。
- 此改动维持既有UI、取消入口、主题及ACK发送边界。系统原生取消/大文件中途断网/同页重选→唯一ACK→Web哈希、iOS与实体机仍NOT_RUN；取消发生在服务端已接收之后的撤回不由本修复保证。
- 统一Flutter六组检查PASS（analyze及162项测试），workspace PASS；证据：`output/mx-a07c6-upload-cancellation/flutter.json`、同目录`workspace.json`。曾因根/子目录命令路径不匹配出现FileNotFound和No pubspec/no matching tests，纠正路径后执行；不计为产品验收通过。
- 已核实用户图标文件 `/Users/atti/Documents/MeshX_dark.png` 存在；尚未开展本次图标修改、圆形mask验收或最终各端打包，完整目标保持进行中。

## Android 附件上传失败恢复切片完成（2026-09-13）

状态：**本子任务 PASS；C01 / C06 完整矩阵仍未关闭**。用户要求适当增加每轮工作量，本轮一次覆盖系统取消、HTTP上传失败、可见错误/关闭、资源释放、不同文件重选、重复点击回归以及真实跨端下载校验，完成后停止。Git仍为 `feature/v0.3.1` / `16efe67dcaae432afd62e4fa10e1d6abbf55c419`，保留全部未提交/未追踪迁移内容。

### 实现与对应客户端规则

- 新增 `apps/flutter-prototype/tool/c06_upload_fault_proxy.mjs`：只监听127.0.0.1，只接受loopback HTTP上游，CLI启动核验 `nodeId=flutter-probe`；第一次POST上传读完请求后返回503且不转发，其余HTTP与WebSocket原样送至真实fixture。只记录状态/字节计数，不记录认证头、正文或WS内容，无新增依赖。本次代理18387已在验收后停止，原18386后端、5194 Web保持原状。
- `tool/test_c06_upload_fault_proxy.mjs` 验证非本地目标拒绝、首次失败未抵达上游、后续上传字节/认证/Cookie透传及双向升级连接。
- `integration_test/c01_cross_client_smoke_test.dart` 增加可选 `C01_ATTACHMENT_ERROR`：真实UI断言指定错误可见且可命中、文件句柄释放、发送入口恢复、客户端与Spring历史均无新增发送消息；关闭提示后在同一会话重选并等真实ACK。继续沿用 `C01_ATTACHMENT_ONLY` 在附件完成后结束，不运行其他业务。
- `test/attachments_feature_test.dart` 增加503、SocketException、TimeoutException三类失败恢复：错误/进度/句柄/零消息、关闭后取消不误重试、换新文件及重试期间重复点击只发一次。生产 `AttachmentController` 的现有实现通过，无需修改。
- 对照共享Vue `useChat.sendFile/uploadThroughNode`：上传成功才queueMessage，失败只释放上传态；`MessageComposer.onFileChange` 取消不发事件、每次清空input。Flutter保留现有发送入口、MaterialBanner错误及关闭动作、原主题和文件卡片，未改Web/Tauri或设计值。Web使用可续传上传、Flutter当前multipart分块读取是已有能力差异，本轮不扩大为传输协议改造。

### 真实流程与证据

环境：Pixel 10 Pro / Android 17 AVD，debug integration入口；10.0.2.2:18387故障代理→127.0.0.1:18386既有Spring工件；Edge真实Vue仍直连原fixture。故障是可控代理HTTP 503，不能称为自然断网、服务端自身故障或物理设备验收。

Android先系统Back取消并确认零消息，再选68字节旧测试文件 `C01-not-image.txt`；代理记录 `upload-rejected/status=503/forwarded=false`。设备UI出现“文件上传暂时不可用，请稍后重试”，句柄释放，服务端history及客户端均无新增消息；点击关闭后从同一会话重新选取不同内容的83字节 `C06-retry.txt`。代理仅记录一次后续上传，真实Spring返回200；最终messageId=`1a8715e62de245f99dbec9817fffa473`、sequence=241、delivery=sent。Flutter附件气泡实际构建且句柄释放，Web无需刷新显示83 B节点中转卡片，查看正文与下载均成功。Android原文件、ACK元数据、Web实际下载SHA-256均为 `188cdf1d91aa651c290ff332bf8930894b1cf9a74ebd5f7125d4da2c7d2bdc8e`，与失败文件不同。

本轮证据根：`output/mx-a07c6-upload-recovery/`。配置只留该忽略目录，不输出凭据。 Web预览已由实际snapshot核对；下载后额外调用locator.innerText因弹窗不再匹配而超时，保留 `web-dom-evidence.txt`，该补充采集FAIL不计为新的展示证据。

| 验证 | 状态 | 证据 |
|---|---|---|
| 故障代理自动测试 | PASS | `proxy-test.log`，3项，含真实本机HTTP/升级连接 |
| 附件定向回归 | PASS | `attachments-test.log`，12项 |
| 统一Flutter | PASS | `flutter.json`，6组，静态检查及160项测试 |
| Android原生取消→503→关闭→重选→ACK | PASS | `android-integration.json` / `drive.log`，62秒；`attachmentFailureUi`和`attachmentUi` |
| 首次不转发/后续真实成功 | PASS | `proxy-events.jsonl`，恰好一次503拒绝及一次200转发 |
| Web正文与实际下载一致 | PASS | `cross-client-evidence.json`、`web-downloaded.txt`；两端83字节及SHA-256一致 |
| 原生选择器截图 | 已采集 | `picker-cancel.png`、`picker-after-failure.png`；错误可见性由设备Widget断言证明 |
| workspace | PASS | `workspace.json`，入口/链接/依赖边界检查通过 |
| 实体机/iOS/真实断网/大文件中断/后台/LAN TLS/发布 | NOT_RUN | 503注入不证明TCP中途断开或上述矩阵，既有Gate不关闭 |

复现时先用 `node --test apps/flutter-prototype/tool/test_c06_upload_fault_proxy.mjs`；再以独立fixture启动 `node apps/flutter-prototype/tool/c06_upload_fault_proxy.mjs http://127.0.0.1:18386 18387 <本轮output>/proxy-events.jsonl`。每轮使用新的证据目录并重启代理恢复“一次失败”状态；Android忽略配置的MESHX_NODE指向10.0.2.2:18387，沿用真实账号/群组，Flutter drive额外给出 `C01_ATTACHMENT_ONLY=true`、最终文件名、上述错误文案及本轮唯一Web/Flutter消息标记。首次picker取消、第二次选择失败文件、第三次选择成功文件；故障代理不得用于正式节点。

### 剩余与下一项

本轮已完成并停止。建议下一轮作为一个较完整切片：**Android附件上传中取消/连接中断→资源释放→同会话重选→唯一ACK与Web文件一致性**，使用较大专用测试文件，区分用户取消与网络故障；当前SocketException/TimeoutException仅受控回归，不替代原生真实中断。广播重复图片上传资格仍PENDING_APPROVAL、B03消息恢复契约仍待审批；Android实体机、iOS与发布门禁保持原边界。



## Android 附件取消重试独立切片完成（2026-09-13）

状态：**本子任务 PASS；完整 C01 仍 C01_PARTIAL**。当前 branch/HEAD 仍为 `feature/v0.3.1` / `16efe67dcaae432afd62e4fa10e1d6abbf55c419`；Flutter、Web及任务目录原为未追踪迁移内容，保留全部既有工作，未commit/push。

选择依据：B06为移动P0，附件旧选择修复与9项定向回归已经存在；最新交接将此闭环列为下一项。现场18386隔离Spring、5194真实Vue/Edge和emulator-5554均可用，不依赖待审批的消息恢复契约、广播上传资格或实体Android设备。只对照共享Vue `MessageComposer.onFileChange` 的取消不发送、成功选择才发出文件事件并清空选择规则，以及 `AttachmentBubble` 查看/下载入口；未改其他客户端或设计值。

本轮实现：`integration_test/c01_cross_client_smoke_test.dart` 增加 `C01_ATTACHMENT_ONLY=true`（必须同时提供 `C01_ATTACHMENT_NAME`），附件完成后卸载页面并结束，不运行群成员、资料、广播等无关分支；报告标明scope，未执行附件时显式NOT_RUN。补充取消返回后等待真实AUTH/SYNC ONLINE再重试、重试系统picker实际打开、上传后句柄释放断言，记录url和SHA-256。既有 `attachment_controller.dart` 取消修复复用，本轮未重写生产逻辑。

验收环境为 Pixel 10 Pro / Android 17 AVD，debug integration入口，HTTP隔离fixture；服务端复用现有运行工件，不证明最新全部服务端源码或LAN/TLS。实际系统Back取消后断言CANCELLED、无新增自己消息、无选中句柄；同一会话重新打开SAF并选择68字节 `C01-not-image.txt`，收到 `messageId=f674c896cb5b41e39c9678f085455748` / sequence=237 / delivery=sent，附件气泡实际构建且句柄释放。真实Vue无需刷新显示同名68 B节点中转卡片，点击查看显示专用测试正文，再点击下载取得同一文件；Android原文件、ACK附件元数据及Web实际下载文件SHA-256均为 `d75a9f8d3eab70e4a258995d9fb459200393b8a0daf63bec5cd668d16c2e552c`。

| 验证 | 状态 | 证据 |
|---|---|---|
| 附件定向回归 | PASS | `flutter test --no-pub test/attachments_feature_test.dart`，9项 |
| 统一Flutter | PASS | `output/mx-a07c3-attachment-bounded/flutter.json`，6组含157项测试 |
| 最终探针静态复验 | PASS | 同目录 `analyze-final.log`；仅追加ONLINE等待后不重复无关全套测试 |
| Android取消→重选→真实ACK | PASS | 同目录 `retry/android-integration.json`、`retry/drive.log`，37秒；两张picker截图 |
| Vue查看与实际下载哈希 | PASS | 同目录 `retry/web-downloaded.txt`；真实浏览器DOM与下载结果，哈希与上述一致 |
| workspace | PASS | 同目录 `workspace.json`，入口/链接/边界检查通过 |
| iOS / Android实体机 / LAN HTTPS / 发布 | NOT_RUN | 本轮明确排除；Android硬件缺口与既有发布阻塞不关闭 |

首轮证据保留在同目录 `drive.log`：恢复时过早重试触发现有离线提示，未取得附件ACK；后续driver以 `Service has disappeared` 失败退出，没有生成成功JSON，不计PASS。复验补足ONLINE前置后通过。启动时关闭上轮残留系统picker并启动原型；没有清理其他应用数据。Web截图两轮等待超时不影响已取得的DOM、正文和下载哈希事实，也不将截图失败冒充成功。

历史交接更新：本附件取消重试闭环已关闭；其建议的 **Android附件上传失败后同页重新选择→ACK→Web显示** 已由上方上传失败恢复切片完成（代理503范围）。广播重复图片上传资格继续PENDING_APPROVAL，B03恢复契约继续待审批；本轮不处理这些独立问题，不关闭C01/C06全矩阵或发布门禁。

## 执行调整与短交接（2026-09-13，用户要求节省额度）

停止“持续推进全部Flutter改造”的无界执行。完整迁移范围保留为路线图，不因本调整宣称完成。当前目标控件仍须由用户暂停；助手不能通过将未完成目标标记complete/blocked代替暂停。

该交接指定的 **Android附件取消→重新选择→真实ACK→Web显示同一附件** 已于2026-09-13完成，证据见下节。本轮已停止，不自动继续广播权限、iOS、发布或下一项；下方短交接保留为历史输入。

短交接：

- 已完成：Flutter附件旧选择误发修复；157项Flutter测试、静态与workspace检查PASS。报告 `output/mx-a07c3-timezone/flutter-attachment-native-probe.json`。正文图片在Android真实后端解码/返回/完成已PASS。
- 当前修改：`integration_test/c01_cross_client_smoke_test.dart`新增可选 `C01_ATTACHMENT_NAME`，先等待人工取消系统picker、断言零新消息，再选择同名文件并等待真实ACK/气泡。此分支尚未PASS。
- 首次附件集成在前置旧Web消息不在可见区时FAIL，证据 `output/mx-a07c3-timezone/android-attachment-native/`。随后真实Web发送新标记 `C01 Web native attachment 20260913`；截图 `output/playwright/mx-a07c3-resume/web-attachment-marker.png`。复验使用runId `20260913-attachment-final`、发送文字 `C01 native attachment final 20260913`、文件 `C01-not-image.txt`，因用户要求节省额度主动终止进程；不计PASS，输出目录 `output/mx-a07c3-timezone/android-attachment-native-final/` 不能凭目录存在推断完成。
- 下次先核对运行状态：隔离后端18386、AVD emulator-5554、Vite5194；不凭历史假设仍在线。配置在忽略路径 `output/mx-a07c3-timezone/fixture/integration-android-config.json`，不输出凭据。测试文件为AVD Download内68字节 `C01-not-image.txt`。真实Web账号使用peer；Playwright会话名 `meshx-c01-timezone`。若现有标记已离开可见区，从真实Web发送新唯一标记，不放宽渲染断言。
- 服务端相同图片跨用户上传资格修复仍PENDING_APPROVAL，不能纳入本阶段。原工作树大量未提交迁移文件必须保留，不push/commit/release。

节流规则：默认单代理；仅读取适用AGENTS、本段交接及目标代码，不重新加载全部方案/历史任务；命令输出只取相关片段；长任务优先等待20–30秒，不每秒轮询；先定向测试，代码稳定后一次完整Flutter验证及文档校验，失败仅复验受影响项；最终只交付结果、证据和剩余项。阶段切换建议新会话携带本段，避免继续累积本长会话。

日期：2026-09-13。状态：**C01_PARTIAL**。基线：`feature/v0.3.1` / `16efe67dcaae432afd62e4fa10e1d6abbf55c419`，保留已有未提交/未追踪迁移文件。不关闭完整 C01 或发布门禁。

## 环境与结果

独立 Spring fixture HTTP 18385 / MySQL 13412 / Redis 16412，3个普通账号、220条群消息和24条私聊种子。当前 Vue 经 Vite 5194 在 Edge 运行，Flutter 为 Pixel 10 Pro AVD / Android 17 的 debug integration 入口。凭据只留忽略的本地配置。后端使用已有本地构建工件，本轮未重建服务端，不证明全部最新服务端源码。

| 场景 | 状态 | 证据范围 |
|---|---|---|
| 登录同步 | PASS | UI 登录后真实 AUTH/SYNC 与群列表 |
| Vue → Flutter | PASS | Vue UI 发送，Flutter 实际渲染文本，对端身份及 sequence=221 |
| Flutter → Vue | PASS | Flutter UI 发送，真实 ACK sequence=222；Vue DOM 和截图出现相同群消息 |
| 联系人私聊 | PASS | 底栏进入联系人，好友行打开输入框并发送；Web 会话摘要出现私聊文本 |
| 群成员 | PASS | 底栏群聊进入详情，真实接口及UI均包含3个成员 |
| 资料与退出 | PASS | UI 修改昵称、currentUser回读一致；UI退出后session与原生凭据为空 |

Android 结果：`output/mx-a07c3-resume/android-render-wait/android-integration.json`。真实浏览器截图：`output/playwright/mx-a07c3-resume/web-send.png`、`web-receive.png`，已读取接收截图核对。统一验证另见 `output/mx-a07c3-resume/flutter-verify.json`。

首轮失败留在 `output/mx-a07c3-resume/android/`：模型已收到消息但UI未构建，立即断言失败。`integration_test/c01_cross_client_smoke_test.dart`改为等待真实文本渲染，未放宽成功条件。复验遇到Android附近设备权限弹窗，依据截图允许后测试通过；拒绝分支不算通过。

## 可见返回操作续验

探针现已将两次直接 `controller.leaveConversation()` 替换为点击“返回消息列表”，并断言当前会话清空、四入口导航恢复。Android真实后端复验PASS：`output/mx-a07c3-resume/android-visible-back/android-integration.json` 中 `conversationBackUi=true`，后续群详情/资料/退出仍PASS。该轮发送使用独立标记，不覆盖前轮证据；本轮ACK不单独代替Web显示证据，双向显示仍引用前轮。附近设备权限弹窗经实际允许后继续；只覆盖页面返回按钮，不证明系统手势返回。统一Flutter125项PASS，证据 `output/mx-a07c3-resume/flutter-visible-back.json`。

## 未覆盖与交接

### 附件选取结果归属修复

排查P0附件发送发现广播同类缺陷：PlatformCoordinator.pickFile在忙碌时不重新选择，取消/拒绝/失败时可能保留旧句柄；AttachmentController只判断selectedFile非空，可能误发其他流程的旧文件。三个定向用例先真实FAIL（忙碌入口预期false，实际true并上传），后增加入口忙碌拒绝及本次fileStatus.ok检查，只在本次成功选择后承担清理。取消不上传且不报错误，拒绝/失败保留错误；旧选择不误上传/误清理，重新选择后只上传新文件。服务端权限实现未变。

`test/attachments_feature_test.dart`增加三项回归，包含忙碌、取消/拒绝/失败、空消息/零上传和新文件重试，9项附件定向测试PASS。最初测试草稿使用不存在的denied枚举编译失败，改为实际permissionDenied后才取得上述产品失败证据。统一验证首轮 `output/mx-a07c3-timezone/flutter-attachment-picker-ownership.json` 因测试缺大括号lint FAIL、全量测试NOT_RUN；修正后另存 `output/mx-a07c3-timezone/flutter-attachment-picker-ownership-final.json`。受控系统Port/HTTP证据不替代Android实际SAF选择→上传→消息ACK→Web接收、系统面板取消和iOS验收，这些仍待执行。

### 广播正文图片查看缺口

#### 相同图片跨用户上传：精确复现与授权边界

真实隔离后端复现已补齐：用户3再次完整上传 `assets/meshx.png`，HTTP 200 / code=200、fileId=1、instantUpload=true；数据库当场回读 `(file_id=1,user_id=3,grant_type=UPLOAD_PROOF)`。随后使用该ID发布正文图片，HTTP 200但业务code=403、msg=`只能使用自己上传的照片`，不是网络失败。最终发布权限回读为0。未修改文件归属，也未跳过权限检查。

代码定位：`FileServiceImpl.storeStagedFile`在完整字节、SHA-256及内容检查通过后，向重复上传者记录UPLOAD_PROOF；`BroadcastServiceImpl.validateImageFiles`只比较首位uploadUserId。正文发布与complete共用该方法，故移动端凭证存在同类风险。另发现FileAccessGrantMapper.grant的冲突更新会直接覆盖grant_type；修复需要同时考虑已有上传证明被BROADCAST_CONTENT/BROADCAST_EVIDENCE查看授权覆盖的情况，不能仅用canAccessFile替代上传资格（该方法包含头像、会话和广播查看权限）。

服务端资格修复状态：**PENDING_APPROVAL**，已向用户询问是否授权“首位上传者或完整上传证明可使用；仅查看授权/仅知ID仍拒绝”的边界。当前未修改生产权限实现。需要的回归包括：重复上传者正文/完成成功、仅查看者拒绝、仅知ID拒绝、其他账号证明不能借用、后续只读授权不丢失上传证明、失败不得写入完成状态/证据。此项与图片查看已通过的证据分开，不关闭整体迁移或C01。

Android正文图片续验PASS：隔离fixture通过真实上传/发布接口创建广播8，正文文件2（发布者3所有，1674字节）。Pixel 10 Pro AVD真实登录→广播待办→正文图片入口→RawImage实际解码→页面返回→完成广播，集成32秒PASS；证据 `output/mx-a07c3-timezone/android-content-image-final/android-integration.json`，`contentImageUi.decoded=true`、count=1，广播EXECUTED。统一Flutter154项和6组检查PASS：`output/mx-a07c3-timezone/flutter-content-image-device.json`。这次没有操作Web发布页面，不能升级为完整Web发布→手机闭环；缩放手势、图片页原生系统返回、iOS/实体机仍NOT_RUN。测试中的附近设备权限经截图核对后允许。最初启动参数误写广播7，在构建期间明确终止，再用实际返回ID8重新运行，未把该启动计为通过。

准备阶段发现待排查边界：发布者3上传与用户2已有文件相同的PNG，真实上传返回全局去重文件1，而其upload_user_id仍为2；发布失败，广播7未落库。本地BroadcastServiceImpl.validateImageFiles要求上传者归属，这与观察相符，但首次错误输出漏取msg字段，尚不能作为完整接口错误复现报告。改用另一张仓库现有PNG后得到发布者所有的文件2并成功创建广播8。没有改文件归属或放宽鉴权，临时发布权限已恢复0；下一步应补足相同内容跨用户上传的精确错误和回归。该后端边界不由本次查看成功覆盖。

核对P0接收侧详情发现：Flutter原先仅显示正文图片数量，不提供查看入口。这是正文内容消费缺口，不能以图片完成凭证上传已通过替代。先在既有MeshXApi内复用头像的有界鉴权图片读取实现，新增broadcastImageBytes：只接受当前节点标准文件路径，下载前后复核当前接收人ACTIVE和详情中图片归属，拒绝跨账号、外部地址、重定向及非图片响应，保持5MiB上限。不增加服务端接口/权限或外部图片请求。

六项定向PASS：正常读取、下载期间移除资格、外部URL、非本广播图片、重定向、非图片；图片下载使用本地真实HTTP测试服务器，详情为受控API，不等于真实Spring权限验证。API阶段报告 `output/mx-a07c3-timezone/flutter-broadcast-image-access.json`。

续接UI：详情提供逐张“查看图片”入口，独立图片页沿用既有主题与返回方式，支持缩放、加载反馈和请求失败重试。页面关闭、账号/节点身份或详情资格失效时丢弃迟到响应，并撤下图片、清理MemoryImage缓存。三个widget定向测试PASS，覆盖本地有效PNG实际解码、账号失效撤图/缓存释放、失败重试、详情失效及关闭后迟到响应。完整验证记录 `output/mx-a07c3-timezone/flutter-broadcast-image-viewer.json`。这些是受控API与widget证据；**IN_PROGRESS：真实Web正文图片→Android查看、原生缩放/返回、iOS与实体机验收仍NOT_RUN**。不据此关闭C01。

### 图片办理失败后的资源释放与重试

按钮配色核验：设备错误截图的蓝底文字曾疑似对比不足，但不能据单帧判定稳定态配色错误。新增真实Theme/FilledButton/RenderParagraph回归，浅深主题分别经历可用→禁用→恢复；等待动画稳定后，渲染文字为on-accent、默认按钮背景为action-bg，对比度≥4.5:1，禁用时无点击回调。定向PASS，未修改token或主题，不把中间帧猜测当作已复现缺陷。完整报告 `output/mx-a07c3-timezone/flutter-button-token-state.json`；真实系统动态配色/渲染与读屏仍不由该测试证明。

Android错误文件续验：隔离广播id=6，真实系统选择器选择专用68字节文本C01-not-image.txt；设备测试断言SnackBar出现“请选择图片文件”、选中句柄清空、详情仍可办理。截图 `output/mx-a07c3-timezone/invalid-file-feedback.png` 已读取，显示持久错误卡；该截图在SnackBar消失后采集，瞬时提示由设备Widget断言证明。第二次选择前库回读PENDING、完成时间NULL、凭证数0；同页重新选择C01-proof.png后22:41:46完成并关联一条COMPLETION_IMAGE/file_id=1。设备PASS：`android-invalid-file-retry/android-integration.json`（同一输出根），`imageRejectedWithoutCompletion=请选择图片文件`，后续退出PASS。完整144项PASS：`flutter-invalid-file-probe.json`。该轮不覆盖超限、真实网络失败、长正文或读屏；未重采Web展示。

长详情反馈续修：广播完成/回执操作失败且有明确错误时，在当前位置显示SnackBar，同时保留页内错误卡；成功或用户取消不新增失败提示，异步返回时页面已卸载则不展示。扩展浅深主题回归为16行长正文、320px、1.4倍文字，滚动到底部点击后直接断言提示可见/可命中，再验证页内关闭及重新选图。定向2项PASS，完整报告 `output/mx-a07c3-timezone/flutter-visible-action-error.json`。测试编写时修正了误匹配正文内Scrollable及离屏按钮已卸载的问题；这些首轮失败属于测试定位，不能当作产品缺陷复现证据。原生设备、TalkBack及离开页面时异步返回仍需专门验收。

页面续验：新增320px、1.4倍文字、实际MeshX浅/深主题下的Widget流程，通过点击上传触发原生Port超限结果，滚动查看明确5MB提示，核对完成按钮恢复可用；点击关闭提示后再发起选择并取消，详情仍可办理且无错误。先复现关闭按钮无可读名称，已补充“关闭提示”tooltip。验证 `output/mx-a07c3-timezone/flutter-image-error-widget.json`。这证明布局/可访问名称/页面操作，不证明真实TalkBack朗读或错误自动进入可视区域；当前测试主动滚动到提示，长详情的错误可发现性仍需检查。

失败提示续修：超过5MiB此前只显示“操作失败，请重试”，定向断言先FAIL。广播控制器现同时读取能力结果的reason，fileTooLarge明确提示5MB上限和重新选择；fileMissing提示图片不可用；fileUnreadable/invalidFileContent提示无法读取并重新选择。权限类状态仍使用原权限指引，未知reason不直接展示系统内部文本。补充失效、不可读、内容无效的错误文案与同页重新选择成功回归；完整复验 `output/mx-a07c3-timezone/flutter-image-feedback-final.json`。本次仅为控制器与受控测试证据，设备错误提示渲染未重验。

新增四类受控Port/API回归：选择非图片、读取权限拒绝、读取超过5MiB、上传返回503。每类都断言零确认/零完成、原详情仍PENDING、有错误无成功反馈、busy释放、选中文件清空且调用资源释放；随后同一控制器重新选择合法图片能完成，错误被清除，句柄再次释放。本轮现有实现通过，无需更改生产逻辑。测试桩仅用于故障注入，不能视为真实权限、断网、系统提供方或服务端安全验收。

定向4项PASS；首轮完整验证 `output/mx-a07c3-timezone/flutter-image-failure-retry.json` 保留测试大括号lint FAIL、全量测试NOT_RUN，修正后复验另存 `flutter-image-failure-retry-final.json`。真实系统取消/重试与图片跨端成功见下文，真实错误文件/超限/上传中断、iOS及真机仍待验收。

### 取消选图不能复用旧图片

真实系统续验：广播id=5（API创建隔离测试输入）在Android 17虚拟机点击完成后打开系统选择器，使用系统Back取消。探针确认CANCELLED、详情仍可办理、confirmedAt/completedAt为空且无成功反馈；在第二次选图尚未选择时另行回读库，PENDING、两时间NULL、凭证数0。从同一详情重新点击完成、选中C01-proof.png后，实际写入EXECUTED及22:29:45完成时间，关联一条COMPLETION_IMAGE/file_id=1。未重启或重开详情绕过恢复。

设备PASS：`output/mx-a07c3-timezone/android-image-cancel-retry/android-integration.json`，`imagePickerCancelledWithoutCompletion=true`、图片完成与后续退出通过；选择器前后截图`cancel-picker-before.png`/`cancel-picker-reopened.png`。统一135项PASS：`flutter-native-cancel.json`。本轮未重采Web图片展示，Web闭环仍引用广播4；预先已有其他流程选中文件的复现仍为受控回归，不升级为设备覆盖。共享导航规范补入凭证选择/取消/重试的产品规则，其他端完整反向验收继续留待后续。

受控回归复现：平台协调器保留前一次选中文件时，广播只检查selectedFile非空，忽略本次picker的cancelled结果，因而取消仍尝试上传旧图片。新增测试先FAIL（uploads实际1，期望0）。现广播办理先拒绝协调器已有进行中的操作，并要求本次fileStatus成功且文件非空才读取上传；取消保持安静、不自动提交其他流程的图片，不擅自清除其他流程的原有选择。

同一回归覆盖取消零上传/零完成、平台busy且旧状态SUCCESS仍不上传，以及随后重新选择new.png能办理成功并释放句柄。该证据为受控Port/API测试，真实系统取消后重试尚未复验；上一节真实图片成功截图属于修复前版本，不替代本次设备回归。验证报告：`output/mx-a07c3-timezone/flutter-cancel-stale-selection.json`。

### Android 系统选图到 Web 回执图片闭环

18386 独立 fixture 的广播 id=4 要求 EXECUTED 与图片凭证、不要求定位。本轮通过真实 API 创建测试输入（不是 Web 发布 UI 证据），仅临时赋予已核对归属的测试发布账号权限并在创建后恢复为0。接收账号仍为普通用户。

Flutter 从广播入口进入详情、点击完成，实际打开 Android 系统文件选择器，从 Downloads 选择仓库 `assets/meshx.png` 的专用副本 `C01-proof.png`。复验 PASS：`output/mx-a07c3-timezone/android-image-proof-wait/android-integration.json`，`nativeSystemPorts=true`、`imageProofRequired=true`、EXECUTED、completed=true；真实成功反馈可见、完成按钮与待办消失、选中文件句柄清空，后续资料和退出通过。

真实库回读：广播4/用户2在22:23:09写入confirmed_at及completed_at，COMPLETION_IMAGE关联file_id=1；文件名C01-proof.png、7004字节，file_hash与仓库源文件SHA-256一致：`d1bc99fb097e915c3bde9c3e3d7e2527cc5b86497a15e4f94bf67e06d00837c9`。Web未手动刷新自动更新100%、1/1已确认；通过“已执行→查看回执详情”实际展示相同MeshX图片。已读取截图 `output/playwright/mx-a07c3-resume/web-image-proof-visible.png`；前态 `web-image-before.png`。Android选择器截图 `output/mx-a07c3-timezone/image-retry-picker.png`。

首轮 `android-image-proof/` 保留FAIL：45秒窗口内未完成系统选择后的成功反馈，当时库仍PENDING且无文件记录。图片分支为外部人工选图单独预留120秒，其他完成分支仍45秒，成功断言未放宽；复验实际总时长50秒。统一Flutter134项PASS：`output/mx-a07c3-timezone/flutter-image-wait.json`。这只证明单图片成功闭环，不覆盖取消/错误文件/超限、上传失败重试、iOS或真机矩阵。系统选择器当前允许所有文件、应用随后校验图片类型；图片选择过滤体验仍可继续对齐。

### 原生能力接入后的基础流程复验

此前 C01 测试入口未传入平台协调器，不能用其结果证明系统选图等能力。现按应用入口组合 `NativeSystemCapabilities`、`PlatformCoordinator` 与 `FlutterLifecyclePort`，通过 `MeshXApp` 管理启动和释放；新增 `C01_BROADCAST_IMAGE` 参数，核对真实广播是否要求图片并在办理结束检查选中文件句柄已清空。没有指定广播时仍明确 NOT_RUN，不将基础流程成功计为图片上传成功。

Android 17 虚拟机接入真实原生能力后的基础流程 PASS：`output/mx-a07c3-timezone/android-native-ports/android-integration.json`。使用现有 18386 隔离后端，实际允许附近设备权限，复验登录同步、既有 Web 消息显示、Flutter 发送 ACK、联系人私聊、可见返回、群成员、资料与退出。该轮没有执行广播或系统选图，也没有重新采集 Web 接收截图；图片上传与 Web 凭证展示仍 NOT_RUN。

统一 Flutter 验证 134 项 PASS，6 组检查通过：`output/mx-a07c3-timezone/flutter-native-ports-final.json`。首轮 `flutter-native-ports.json` 保留测试入口大括号 lint FAIL 与测试 NOT_RUN，修正后另存复验。完整 C01、真机与发布门禁仍不关闭。

### 时区对齐复验与待办刷新等待

清理：最终复验后已停止旧18385后端并删除其专用MySQL/Redis临时容器及卷；旧日志、截图、JSON和运行JAR仍保留，测试数据可重建但旧数据库现场不可直接恢复。新18386环境保留供后续C01/C06验收；未触碰用户其他容器。

最终真实复验PASS：`output/mx-a07c3-timezone/android-expiry-recheck/android-integration.json`有passed=true、expiredWhileOpen=true、completionAttempted=false，包含返回待办实际消失与后续资料/退出断言。数据库回读id=3接收人仍ACTIVE/PENDING，confirmed_at/completed_at均NULL，未伪造完成；双方发布权限回读为0。最终APK SHA256 `398527eb8e057e5ba316a652f724bcaca916bf073e71bc8d46dd99e09aba77c0`。仅证明显式上海时区、当前约3秒设备超前、前台debug模拟器场景；不宣称任意时钟偏差、跨时区或后台恢复通过。

进一步复验：`android-expiry-wait/`在等待45秒后仍FAIL，因此撤回“只要等异步刷新就能通过”的推断。现场同一秒采样宿主/数据库epoch=1789308299，Android=1789308302，模拟器约快3秒。单次本地到期刷新早于服务器截止，拿到旧待办后没有再次读取。

控制器现对“本地已到期但权威待办仍返回该项”最多每秒再核对一次、总计5次；只读API，不直接过滤或伪造待办，超过上限保留服务器结果。更换广播/截止时间重新计数，销毁取消计时。增加成功收敛及5次上限回归，统一Flutter134项PASS：`output/mx-a07c3-timezone/flutter-deadline-recheck-final.json`；非final保留测试大括号lint FAIL与全量NOT_RUN。真实复验采用新广播id=3、截止22:08:52，结果另存`android-expiry-recheck/`。

新环境数据库实际NOW=21:54:43、UTC_TIMESTAMP=13:54:43、session time_zone=+08:00，启动成功。`tests/e2e/mobile-specs/serve-web.mjs`新增受限的PROBE_API_ORIGIN，只接受127.0.0.1的HTTP根origin，拒绝外部地址/路径/凭据；语法和3项负向检查PASS。当前Vite5194代理18386，不再指向旧失败环境。

新环境id=1截止21:59:30，Android详情到期正常，但探针仍在返回后的即时待办断言FAIL。现场SQL已确认deadline_at<NOW，真实pending接口返回[]，这次区别于上一轮8小时时区错误，剩下的是探针没有等待异步列表更新。改为等待目标待办widget真正消失（仍使用既有45秒上限，不删断言、不造假列表）。第一轮证据 `output/mx-a07c3-timezone/android-expiry/android-integration.json`保留，下一轮新广播id=2截止22:03:28，结果另存 `android-expiry-wait/`。

Web重新登录后实际渲染`C01 Flutter timezone aligned 20260913`，截图 `output/playwright/mx-a07c3-resume/web-timezone-receive-final.png`；非final截图是被测试播种登录挤下线后的登录页，不算接收证据。时区对齐首轮APK SHA256 `24af1bd7ece244d7ee87d6e4d9f832b5ff13c05faf63a29a04b7d5847809c364`，后端运行工件与旧轮相同。临时广播发布权限已恢复。

### 真实截止时间验收与隔离环境时区缺口

新探针参数 `C01_BROADCAST_EXPIRY`等待真实截止而非点击完成。HTTP创建广播id=3，截止`2026-09-13T21:51:47`，Android/接口按上海时区解释。手机详情保持打开时完成按钮自动消失，接口回读已过期且未确认/完成；但返回列表仍有待办，整轮**FAIL**，证据 `output/mx-a07c3-resume/android-broadcast-expiry/android-integration.json`，不能把其中expiredWhileOpen=true升级成全轮PASS。

现场核对：MySQL `NOW()`=13:52:14，deadline_at=21:51:47，session time_zone=SYSTEM；真实pending接口仍返回id=3。该8小时时区偏差来自测试容器默认UTC与Java/无时区LocalDateTime不一致，并非等一个UI帧即可解决。移动端没有删除权威待办来制造通过。

已修正 `tool/backend_fixture.py`，显式使用MySQL `--default-time-zone=+08:00` 和Java `-Duser.timezone=Asia/Shanghai`，state记录两者；语法编译PASS。新独立环境在 `output/mx-a07c3-timezone/fixture` 启动，端口18386/13413/16413、容器名前缀meshx-c01-timezone；旧失败环境暂保留。新环境的真实SQL时区与到期闭环尚待复验。生产部署跨时区语义未修改/未验证。

本轮Flutter132项PASS：`output/mx-a07c3-resume/flutter-expiry-probe.json`。失败轮Android APK SHA256 `a5094b1e2c97acc13c45c7c27356bee9fe0154d7a5b02a41f6e06855a14af110`；后端运行工件 `9c22ecd7ec1535a95cea97062943683a599ba2acbd14bc4e0b106bf76e9802a3`。测试账号发布权限创建后立即恢复为0。

### 无实时事件时的到期入口更新

最终统一Flutter132项PASS，6组检查均PASS：`output/mx-a07c3-resume/flutter-deadline-final.json`。

新增回归先FAIL：打开带截止时间的详情后，无实时事件时不会再通知UI，测试等待到期通知超时。控制器现按当前详情的截止时间设置一次性计时，到期立即重绘本地办理资格并刷新权威列表/详情；不写回执、不伪造服务端完成状态。更换详情或销毁会取消旧计时，已有提交仍由服务端最后鉴权。

受控API测试覆盖无事件到期拒绝办理、替换详情取消旧计时、销毁后不再发起请求。该证据不替代真实设备时钟偏差、后台冻结恢复或服务端跨时区截止时间验收。首轮统一验证在lint大括号规则FAIL、全量测试NOT_RUN，记录 `output/mx-a07c3-resume/flutter-deadline.json`；修正后复验另存final文件。

### Web移除目标时Android详情实时失效

使用新广播id=2，真实Web发布给测试接收人；Flutter导航到待办详情，先断言完成按钮存在、viewedAt已写入且未确认，再输出协调标记等待Web操作。Web通过“管理通知对象→移出目标→保存目标”移除接收人，没有用SQL替代业务变更。Android同一已挂载详情自动撤下完成按钮并显示失效说明；接口核验无提交资格，返回后待办项消失。未点击完成，也未自动重开详情或手动刷新。

Android集成PASS：`output/mx-a07c3-resume/android-broadcast-removal/android-integration.json`记录`removedWhileOpen=true`、`completionAttempted=false`；真实库回读target_status=REMOVED、confirm_status=PENDING、confirmed_at/completed_at均NULL。移除前手机截图 `output/mx-a07c3-resume/removal-detail-before.png` 已读取；Web移除结果截图 `output/playwright/mx-a07c3-resume/web-broadcast-removed.png`。移除后手机状态由实际widget断言证明，未另存该帧截图。

仅为测试Web账号临时恢复发布权限，验收后已回读双方can_send_broadcast=0。130项统一Flutter回归PASS，`output/mx-a07c3-resume/flutter-removal-probe.json`。本轮证明在线移除资格后的UI收敛及无误写；不证明恶意绕过UI提交会被拒绝，也不替代取消/过期、离线移除或重加矩阵。

### 打开详情后的实时失效修复

统一Flutter130项PASS，静态/生成/向量与测试6组均PASS；最终证据 `output/mx-a07c3-resume/flutter-detail-open-race.json`。先前128/129项报告保留，不覆盖。

对照Web `useBroadcasts.handleRealtimeEvent`发现Flutter只刷新列表、未刷新打开的详情；新回归先真实FAIL（移除目标后canSubmit仍true）。现修改 `lib/application/broadcasts_controller.dart`：实时刷新同时重新读取当前详情，独立深链open也订阅更新；办理/首次打开期间收到更新延后核对，旧请求通过generation和详情身份检查不能覆盖新打开的详情，销毁后不通知。核验失败清空旧详情而非沿用旧提交资格。

`test/broadcasts_feature_test.dart`覆盖移除资格、深链撤销、旧响应/销毁、核验失败、首次打开期间更新，既有选图取消回归也加入办理期间失去资格。以上为受控API/事件回归，不等于真实Web撤销→Android页面闭环；真实失败路径仍待下一轮验收。该改动不涉及服务端或协议。

### 真实广播办理续验

Web真实创建“C01 Flutter 广播办理 20260913”（id=1），指定唯一测试好友、要求EXECUTED、不要求图片/定位。仅在已核对 ownership label 的 `meshx-c01-resume-mysql` 中临时赋予Web测试账号发布权限，接收账号仍为普通用户；验收后已恢复发布权限为0，未改正式数据。

Flutter经底栏广播待办进入详情，真实view接口留下viewedAt；点击“完成广播任务”后接口回读confirmStatus=EXECUTED、confirmedAt/completedAt非空，完成按钮消失，返回列表后待办消失。Android集成测试PASS，结果 `output/mx-a07c3-resume/android-broadcast/android-integration.json` 的 `broadcastUi` 记录实际id和状态。新增参数 `C01_BROADCAST_ID`；未提供时明确记录NOT_RUN，不凭基础smoke升级广播验收。

Web未经手动刷新自动呈现100%、1/1人确认、1已查看、1已执行；点击已执行统计后显示对应接收账号明细。前后截图 `output/playwright/mx-a07c3-resume/web-broadcast-before.png` / `web-broadcast-completed.png`，完成截图已读取。统一Flutter125项PASS，`output/mx-a07c3-resume/flutter-broadcast.json`。这是无凭证单接收人成功路径，不覆盖图片/定位、取消/过期/移除资格、并发/重复办理或OS通知。

- 页面返回按钮已复验；真实系统返回手势/OEM矩阵仍待验。
- 好友申请接受/拒绝、创建/退出群、文件、密码、非成员/过期/删除/权限失败和同ID重试仍未完整覆盖。广播无凭证成功路径已完成上述跨端闭环，图片凭证与失效/拒绝路径继续验收。
- iOS、Android实体机/OEM/读屏、正式main/HTTPS、签名与发布均NOT_RUN。独立原型应用、开发HTTP、模拟器、测试入口不能替代这些门禁；`c01GateClosed=false`。
