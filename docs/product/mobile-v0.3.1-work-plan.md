# Mobile v0.3.1 — A07B 精确开发清单与 A07C 验证清单

广播续验：[MX-A07C-3](../tasks/active/MX-A07C-3.md)完成真实Web发布→Flutter无凭证办理→Web自动更新1/1确认和接收人已执行明细，Android模拟器及125项回归PASS；图片/定位及失效/拒绝矩阵仍未关闭。

2026-09-13续验：[MX-A07C-3](../tasks/active/MX-A07C-3.md)已取得新主导航下真实Vue与Android模拟器双向文本、联系人私聊、群成员、资料和退出证据，统一Flutter125项回归PASS。C01_PARTIAL，完整业务/拒绝路径与设备矩阵仍未关闭。

2026-09-09；计划由 [MX-A07A](../tasks/active/MX-A07A.md)冻结。后续已授权的 [MX-A07B-1](../tasks/active/MX-A07B-1.md)因真实消息变更恢复缺口以 **BLOCKED_BY_CONTRACT** 结束，未修改运行时代码/协议。[MX-A07B-2](../tasks/active/MX-A07B-2.md) B04、[MX-A07B-3](../tasks/active/MX-A07B-3.md) B09、[MX-A07B-4](../tasks/active/MX-A07B-4.md) B05 与 [MX-A07B-5](../tasks/active/MX-A07B-5.md) B06 已完成本阶段实现、本地回归、双平台模拟器构建及真实 Spring 闭环；[MX-A07B-6](../tasks/active/MX-A07B-6.md) B07 已完成本阶段实现与规则验证；[MX-A07B-7](../tasks/active/MX-A07B-7.md) B08 已完成本地实现、115项回归、双平台模拟器及iPhone Settings三轮公开API回调；[MX-A07B-8](../tasks/active/MX-A07B-8.md) B10 已完成最小脱敏支持信息、本地117项回归、双平台模拟器构建与iPhone Profile原生版本读取；[MX-A07B-9](../tasks/active/MX-A07B-9.md) B11已完成候选清单、统一verify、CI准备和双平台unsigned release-mode本地工件验证，但正式身份/版本/签名/升级与远程CI仍未批准/NOT_RUN。[MX-A07B-10](../tasks/active/MX-A07B-10.md) 补做 B02 产品层 Design System 与移动导航整合，119项Flutter回归及Android 17虚拟机四页面视觉PASS，真机/跨端仍另验。[MX-A07C-1](../tasks/active/MX-A07C-1.md) 已开始 C08：iPhone 真机 Profile 的独立 220/2050 条滚动与1000字输入分段预算 PASS，Android 17 虚拟机同流程性能 FAIL，仅作诊断；[MX-A07C-2](../tasks/active/MX-A07C-2.md) 又完成iPhone真机5轮冷进程、主机端到端上界与5轮真实Settings前后台恢复，均在5秒预算内且单连接/2050条唯一。两卡仍是开发签名测试HTTP入口，不是正式main/HTTPS；Android真机、读屏及旧OS仍未验，故 A07C 为 **IN_PROGRESS**，G08/G14/G15不关闭。下列计划不是其他阶段授权。

范围权威：[Scope](mobile-release-scope-v0.3.1.md)；事实来源：[Matrix](product-parity-matrix-v0.3.1.md)；关闭条件：[Gates](mobile-release-gates-v0.3.1.md)。各条按依赖顺序单独验收；发现新需求不扩大整张A07。

## A07B — 开发切片

当前实现继续位于 `apps/flutter-prototype`；以下新文件名是计划建议，不是已经存在或本轮创建的代码。保留现有ChangeNotifier、纯Dart Core/Ports、Flutter Adapter/Kotlin/Swift方向，复用A04 Token；不直接执行TS。API方法以 [当前OpenAPI](../../contracts/rest/openapi.json) 和实际Service权限为准。

### B01 契约消费与发布连接前置（先于新功能）

- **责任路径**：`lib/data/meshx_api.dart`、现有生成Dart操作切片、相关DTO解码与 `test/http_isolation_test.dart`；按需形成小型 `lib/data/*_api.dart`。只有后续任务明确允许时才增选 `tooling/contracts/generation.json` 中既有操作，不修改REST/WS schema。
- **工作**：列清新增API的真实method/path/Result和二进制差异；错误先检查HTTP再检查Result.code。为动态Map做显式解码和负向样例，不能把生成可选字段当服务端接受缺省。
- **连接决策**：复现正式main对HTTP候选拒绝，准备受信HTTPS LAN fixture、证书名称/SAN匹配与mDNS候选方案；检查v2 TXT缺advertisedHost对DNS证书的影响。如要补服务器TXT/身份策略，先记录单独变更边界与授权，不由Flutter偷偷改服务端契约或降级TLS。
- **完成条件**：所需操作均来自既有契约；坏URL、错误账号、401单次刷新、403、二进制错误/取消明确；R01/R03/R04有可执行验收输入。没有证据时G11/G12继续未通过，不妨碍独立好友/资料开发。
- **验证**：contracts、dart-core、flutter；如果增选生成操作还跑tooling/workspace；对实际改到的服务层再加server，不能空更新快照掩盖行为差异。

### B02 移动导航与会话整合

- **责任路径**：`lib/ui/app.dart`、`lib/chat_controller.dart`；建议拆 `lib/ui/conversations/` 与功能入口，入口保持组合职责；不重写A05消息管线。
- **工作**：P0消息/联系人/广播/个人入口；加载/空/失败状态、返回导航、首次建群/加好友后可进入空会话，未实现房间/高级操作明确受限。保留私聊/群聊ID、当前选择、账号与节点归属。
- **完成条件**：没有靠seed才出现的必要入口；不可用的temporary和定位任务不出现可成功操作的假按钮；窄屏/宽屏/返回行为一致。
- **验证**：Flutter widget/导航测试；A07C再做真实P0点击互通。

### B03 消息可见已读、撤回与授权失效（不以增加新页面代替）

- **责任路径**：`lib/chat_controller.dart`、`lib/core/models.dart`、`lib/core/store.dart`、`lib/ui`消息视图及对应reliability/recovery测试。
- **工作**：接入现有 `PUT /chat/conversation/read`，仅在前台、正确会话且内容确实可见时更新游标。消费已有 `CHAT_READ / CHAT_RECALL / CHAT_BURN / CONVERSATION_CHANGED / CONVERSATION_REMOVED`；合并乱序与迟到消息时不可恢复已撤回正文。按账号/节点清理通知、待发与已失去访问权的缓存展示。
- **先做复现**：已缓存消息→离线→另一端撤回/销毁/移除会话→恢复；分别检验实时事件缺失和旧sequence前变更，不能只检验新消息gap。当前SYNC只含messages/latestPositions/deniedConversationIds/hasMore；不能凭空消费新字段。
- **完成条件**：现有history/目录重校验与缓存失效能证明本P0范围；若需持久变更游标/墓碑等新契约才能安全完成，标记该条BLOCKED并提交独立契约缺口，不改schema、不绕过权限。主动高级发起功能仍延期，但安全接收必须达标。
- **验证**：针对可见性、乱序、刷新竞态、移除/重加和进程恢复的Flutter行为测试；共享向量/contracts；真实服务器的跨端测试由C01/C05执行。

### B04 好友/申请/搜索

- **责任路径**：建议 `lib/data/friends_api.dart`、`lib/application/friends_controller.dart`、`lib/ui/friends/`，接现有会话编排。
- **既有API**：`GET /friend/list`、`GET /user/search?keyword=`、`POST /friend/request`、`GET /friend/requests`、`POST /friend/handle`、`DELETE /friend/{friendId}`、`PUT /friend/{friendId}/remark?remark=`。请求字段按FriendRequestDTO/FriendHandleDTO解码，不新造好友状态。
- **完成条件**：搜索→申请→另一账号接受/拒绝→刷新列表→私聊；重复申请/重复处理、非本人待办、删除后发送失败、备注同步均有明确状态；消费既有FRIEND_CHANGED并在恢复时重拉，不假设WS永久在后台。
- **验证**：API负向/账号切换与controller/widget测试；服务端现有权限不足的定向测试在后续授权范围内补，保持业务语义。

### B05 基本群聊

- **责任路径**：建议 `lib/data/groups_api.dart`、`lib/application/groups_controller.dart`、`lib/ui/groups/`。
- **既有API**：`POST /group`（groupName/memberIds）、`GET /group/my`、`GET /group/{groupId}`、`GET /group/{groupId}/members`、`POST /group/{groupId}/leave`。
- **完成条件**：从好友选择初始成员、建群、查看成员、普通成员退出；群名2–20字符等规则由实际Service决定，客户端提示一致。群主普通退出按服务器规则拒绝；不添加未冻结的转让/管理员功能。被移除/群解散使用B03失效逻辑。
- **验证**：请求/重复点击/坏成员/权限失败/空群导航；C01做实际多账号闭环。已有GroupServiceReceiptCleanupTest只证明解散回执清理，不能当建群/退出完整证据。

### B06 基础文件/图片消息

- **责任路径**：`lib/core/platform_ports.dart`、`lib/platform` 与 Kotlin/Swift `MobileCapabilities` 的受限文件适配；建议 `lib/data/attachments_api.dart`、`lib/application/attachment_controller.dart`、`lib/ui/attachments/`。
- **既有API/事件**：有界前台 `POST /file/upload`、授权内容/签名预览接口、`CHAT_SEND` 的现有file/image内容结构；参考Web `useChat.sendFile/uploadThroughNode` 和FileController。可复用既有分片API实现细节，但本版本不宣称跨进程续传。
- **工作**：选中opaque handle后增加受限流式读取/上传能力，Core不能获得任意路径读取入口；认证和取消仍由应用适配负责。下载只接受当前节点授权文件引用，验证字节/哈希，不能跟随不可信内容携带token跨origin。完成上传再以同clientMsgId进入消息ACK流程。
- **完成条件**：文字outbox语义不回退；小文件/图片双向可用，25MiB及服务端更小限制生效、取消不等于失败、上传失败不产生已发送附件；退出/账号切换清理缓存。Web直传超时的既有中转回退仍可向Flutter送达。
- **验证**：取消/超限/删除/不可读/失去权限/账号切换/上传完成与ACK丢失的不同失败阶段；C06做真实文件提供方、字节和UI证据。

### B07 广播接收与回执

- **责任路径**：建议 `lib/data/broadcasts_api.dart`、`lib/application/broadcasts_controller.dart`、`lib/ui/broadcasts/`，接入消息卡/通知导航。
- **既有API**：`GET /broadcast`、`/pending`、`/{id}`，`POST /{id}/view`、`/confirm`、`/complete`，`POST /file/broadcast-image`；真实前缀均在`/api/v1`。消费既有BROADCAST/BROADCAST_UPDATED与技术账号持久消息，不另造消息事实来源。
- **完成条件**：收到→查看→按服务器允许值确认；无定位任务能完成，要求图片则先上传合法证据。重复点击/超时重试仍幂等；已取消/过期/被移除不能提交。`requireLocationProof=true` 明确只读、提示使用既有Web办理，不调用EXECUTED替代complete；其他合法确认状态仍按服务器规则。
- **边界**：不实现创建、目标编辑、管理统计或新定位Port。技术通知账号不是好友，不尝试认证该账号；历史补拉不会重复弹几十条通知。未授权详情不能靠卡片缓存绕过。
- **验证**：现有BroadcastService规则、Dart状态与消息路由测试，C01/C06验证真实Web发起与手机回执、失去目标资格、重复/过期等。

### B08 权限恢复与通知路由

- **责任路径**：`lib/application/platform_coordinator.dart`、`lib/platform/system_capabilities.dart`、`lib/ui/capabilities_page.dart`、`ios/Runner/MobileCapabilities.swift`、对应Android适配与tests。
- **工作**：IOS-SETTINGS-01最小诊断/fix或明确fallback；保留能力status/reason。通知点击要等待正确会话恢复、校验owner/origin、导航普通会话或广播详情，过期目标回安全列表。
- **完成条件**：G02标准全部满足；账号退出后旧通知不可导航/重现，权限恢复不自动重复弹窗或重复连接；标准App调用与测试探针路径一致。只用公开API，不修改identifier。
- **验证**：自动状态/导航/账号竞态测试，C04单列真实OS开关/横幅/点击证据，show成功不算点击通过。

### B09 个人资料/基础设置

- **责任路径**：`lib/data/meshx_api.dart`或小型 `profile_api.dart`，建议 `lib/ui/profile/` 与普通偏好存储；敏感信息仍只走Keychain/Keystore。
- **既有API**：`GET /user/info`、`PUT /user/profile`（nickname/avatar）、`POST /file/avatar`、`PUT /user/password`。复用Web当前文字头像表示与文件头像引用，不新增头像格式。
- **完成条件**：查看/修改后与Web一致，保存失败不假显示成功，重启保留；密码变更后的既有会话撤销按服务器规则处理。主题/通知/节点状态可理解；系统/浅/深主题与偏好保存不进入Core平台依赖。
- **验证**：不合法头像/过大文件/403/账号切换/保存后重启、昵称长字、小屏布局；密码测试不得把真实口令输出日志。

### B10 平台可用性与最小诊断

- **责任路径**：P0 Flutter UI/主题、现有platform结果呈现；不创建管理员日志控制台。
- **工作**：P0操作触控、基础语义标签、系统字号、键盘/返回、安全区、权限拒绝可达；最小支持信息只含应用/OS版本、当前节点标识/连接阶段和脱敏错误码。用户复制支持信息前可预览；不得包含token、Cookie、凭据或文件路径。
- **完成条件**：基础流程可用，不以大规模动效/性能重写代替实测；A05 Debug输入ANR有明确C08复现场景，未证明修复前保留风险。
- **验证**：widget/Token回归、C02/C08实体设备场景；只在量化复现后做针对性性能修复。

### B11 候选构建与CI准备（没有发布动作）

- **责任路径**：Flutter模块构建/现有 `.github/workflows/flutter-prototype.yml`、统一verify声明、发布说明；具体修改仍须A07B授权包括此条。
- **工作**：列清候选正式main、版本/build number、应用身份、依赖锁、无fixture和debug网络例外；补足现有CI只analyze/test而没有双平台候选构建的缺口方案。未经独立批准不选择新的应用ID/签名身份或渠道，不把原型改名安装到正式用户数据上。
- **完成条件**：可复现构建/检查命令可review；远程CI要等明确远程候选与触发授权，当前禁止commit/push意味着不能靠本地工作流文件关闭G10。
- **验证**：tooling/workspace/hygiene与受影响构建；签名/升级/远程结果由C07/C10验证，D最后审查。

## A07C — 精确环境验证清单

每项记录目标候选工件及源码哈希；先用 `./tooling/verify <scope> --dry-run`，再执行真实验证。日志放当轮 `output/mx-a07c-<date>/<candidate>/`；通过/失败/硬件阻塞/未运行分别标PASS/FAIL/BLOCKED/NOT_RUN。不得覆盖A05/A06失败或只保留最好的一次。

| ID / Gate | 环境与操作 | 必须收集的证据与通过边界 |
|---|---|---|
| C01 / G00/G03/G06/G12 | 独立Spring fixture、真实Vue Web、候选Android/iOS；三个普通账号及管理发起者；逐项跑登录退出、好友申请接受/拒绝、建群/退出、文字/文件、广播回执、资料/密码 | 实际请求/服务端ACK/持久结果与客户端UI对应；不共享假API；同ID重试单次写入；非成员/过期/删除/权限失败不得被忽略。服务端与UI自动化分开标记 |
| C02 / G01/G14/G15 | 至少一台真实Android；记录品牌/型号/API/ROM/补丁/网络/电池策略、候选mode/签名；每个声明支持OEM安排代表设备；iPhone型号/OS同样登记 | 当前无Android硬件即BLOCKED，不拿Pixel emulator补格。现有工程minSdk26/iOS13、代表中间OS/当前OS的原生API分支另测；无法覆盖声明范围则保持Gate未关闭 |
| C03 / G04/G11 | 真实Wi-Fi、独立HTTPS节点；首次允许/拒绝/设置恢复、无节点→上线→下线、扫描中stop/页面销毁、前后台重扫、Wi-Fi A→B、两个独立身份节点/多接口 | 服务端实际对端IP证明LAN；USB169.254、localhost、10.0.2.2单列；结果无界增长/过期/旧回调均失败。正式证书+hostname/SAN匹配、不含测试ATS；两节点不能是同fixture的两个origin |
| C04 / G02/G05 | iPhone与Android真实系统通知/本地网络开关；首次提示拒绝、再次请求、手动/按钮恢复；真实新消息横幅/通知中心；点击前台、后台、进程终止、账号已换、目标已失效 | 实际OS画面与回调status/reason分开；执行IOS-SETTINGS-01至少3轮；正确等待AUTH/SYNC与账号归属，历史/重复/当前会话不重复通知；关闭权限后应用内仍可查消息 |
| C05 / G03/G04 | 稳定LAN下真实Home切后台、锁屏/解锁、长挂起、断网恢复、Wi-Fi切换、OS杀进程；Android正常电池策略/合理Doze、Activity重建；两套独立服务器各自DB/存储/controlId | 每次后台期由真实对端发消息；服务端ACK sequence、恢复cursor和消息ID唯一；连接/heartbeat/refresh/flush的owner与峰值计数。退出/移除会话/旧缓存撤回分别检验；不得为测试关闭系统电池策略 |
| C06 / G06 | 仅测试文件：普通文本、图片、25MiB边界/超限、零字节/不支持格式、文件提供方拒绝、选择后删除/不可读、取消、分享撤销；前后台/账号切换穿插 | 实际服务端文件字节/哈希/授权，picker/source访问释放与缓存清理；分享面板与接收完成分开。广播图片证明/定位必需只读/过期或取消不能伪完成；Web直传失败能中转给Flutter |
| C07 / G07/G13/G16 | 清洁安装、同身份同签名候选N→N+1覆盖升级、安装失败恢复、进程重启、卸载重装；保留测试中的普通缓存与outbox | 逐项比对origin+userId、凭据策略、消息/clientMsgId/游标/主题；签名或ID不同不算升级。旧Capacitor→Flutter默认不承诺透明迁移；没有经批准迁移方案不得覆盖正式应用 |
| C08 / G08/G14/G15 | 真机Profile或Release-like正式main；5次冷启动/5次恢复；>=220和2000条测试消息滚动各30秒；持续输入1000字、键盘切换/长字/主题/字号/读屏，发现扫描与重连并行 | 记录所有轮次首帧/可交互/ONLINE、帧分布、卡顿/内存趋势/ANR/崩溃。以下工程预算先锁定再测；Debug JIT ANR不能推断release，但要保留针对性复现结果 |
| C09 / G09 | 单独批准SEC-FP-01处置后，原工具/规则下全历史+候选变化目录+合成负向检测；检查运行/构建日志脱敏 | 完整fingerprint变化审计、默认generic-api-key仍命中有效合成样例；不能把新真泄漏归入历史误报；规则配置版本与报告哈希一并存档 |
| C10 / G10/G13/G14/G16 | 批准的候选revision/构建输入，远程CI双平台编译+所需scopes，批准签名/分发渠道的安装测试 | CI run ID、源码/锁/工件hash、签名/版本/OS与安装启动结果；无探针、fixture账号、生产凭据泄漏。未获远程操作/签名身份授权则BLOCKED，不自动commit/push/upload |

### C08 工程预算与解释

以下是本次首发验收预算，不是A06已达标的事实；仅适用于记录过的稳定测试LAN与声明支持设备。未来调整需在测量前记录理由，不能跑完后改阈值迎合结果。

- 不出现ANR/崩溃、持续内存无界增长或无法恢复的输入卡死。
- 已有本地会话的冷启动到可操作缓存界面每轮不超过5秒；正常Wi-Fi恢复到ONLINE每轮不超过5秒（认证失效/网络不可达另按错误与恢复场景验收，不把等待人工输入计入）。
- 稳定滚动/输入窗口记录真实刷新周期，p95帧耗时不超过2个刷新周期；超过250ms的应用内停顿必须定位并消除P0原因。OS权限面板动画、加载阶段与稳态窗口分开测，不能把空闲帧混入掩盖卡顿。
- 明确采集区间与设备热状态；全样本和失败轮次保留。A06混合540帧p95=3.455ms、max=53.332ms，以及恢复/登录532ms只作为观测，不直接对照上述完整预算宣布PASS。

### Android 最小实体机验收卡（当前全卡 BLOCKED）

1. 记录实际型号/ROM/API/应用mode/签名哈希；先安装正式main候选，不能用Debug探针代替全部功能。
2. 本地网络按该API实际权限模型执行允许、拒绝、设置恢复、无节点/上下线/停止；不为没有该运行时权限的旧API伪造开关。
3. 真实文字双向收发、>=220分页、断网outbox、后台/锁屏/网络恢复、杀进程保留数据；对端ACK与本地唯一性同时证明。
4. 通知权限关闭/开启、横幅/点击/冷启动路由、重复/当前会话/历史静默；不依赖GMS/FCM。
5. SAF提供方选择/取消/拒绝/超限/不可读，上传/接收/分享与缓存释放；只使用专用测试文件。
6. 键盘/IME、系统返回（支持时Predictive Back）、主题、字号/读屏、Activity重建/正常电池限制；Profile滚动/输入与A05 ANR风险复现。
7. 相同批准包身份覆盖升级后的凭据、消息、outbox、游标；安装、签名、发布与可用性分别留证。

## A07C统一回归命令范围

在仓库根对 `contracts dart-core flutter core design server web tooling workspace hygiene security flutter-android flutter-ios` 各自先dry-run，再运行 `./tooling/verify <scope> --report <evidence>/<scope>.json`。server涉及真实数据库的场景使用独立fixture；不占用或清理用户既有服务。单项失败后的后续项按运行器标NOT_RUN，必要时独立运行可归因范围，不能改全量结论。

集成测试从Flutter模块使用当前 `test_driver/integration_driver.dart`、实际设备ID与忽略的测试账号配置；保留数据恢复用`--keep-app-running`并显式终止/启动，不让driver自动卸载造假。模拟器/真实设备、Debug/Profile/Release、LAN/TLS、签名/分发分别记录。没有Tauri业务改动，不为数字好看重复所有桌面真机；实际Web/Tauri互通风险按改变范围加测。

## 进入A07D的前置条件

仅当已获后续阶段授权、对应平台全部BLOCKING Gate有关闭证据、NON_BLOCKING/DEFERRED限制明确、候选工件可追溯时，才具备RC就绪审查输入。审查也不等于正式发布授权。当前有多个未关闭Gate，A07D **NOT_STARTED**，不得自动进入A08。
