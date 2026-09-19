# MX-A07B-1I — 消息变更恢复实现

开始日期：2026-09-14；状态：IN_PROGRESS。用户明确批准1R冻结方案及1I本地实施验证；不执行生产数据库迁移、发布、应用身份/签名变更。保留当前feature/v0.3.1大规模dirty工作区，不切换到旧远程快照。

## 当前里程碑清单（以本节为准；下方早期数字保留为历史证据）

当前交付批次：恢复链路完整闭环。M1 后端一致性收口、M2恢复服务、M3两端Core与持久化均 IN_PROGRESS。后续两批为跨端体验/验收、最终应用交付；不再把每个入口拆成交接切片。

最新整批证据（2026-09-20）：预览/未读与时间排序已在两端对齐；Web178项、Flutter完整250项及后续存储11项专项通过。真实MySQL95项零skip，覆盖200k/100目录全分页、READY、超限拒绝、500名群成员撤回原子性及两个独立JVM日志写入。最终Pixel模拟器Profile两次200k恢复/文件替换与重载通过，总106.2秒，RSS664MB、峰值869MB，最大事件间隔548ms；仍不能认定真机性能达标。iPhone真机Profile前台两轮200k恢复与文件重载通过，总39.4秒、峰值721MB、最大事件间隔283ms；仍有短暂停顿且HTTP为注入传输；完整多服务实例/分页、设备验收与最终应用交付仍待。详见本卡末尾各批次证据。

M3当前状态：Web/Tauri `useChat` 与 Flutter `ChatController` 均已接入候选恢复链路，默认构建开关仍关闭，已迁移格式2禁止退回旧缓存。当前大批次持续包含持久存储、控制器、真实服务端集成、统一页面反馈和已读/通知接线，不以单个helper或测试结束一轮。M1–M4尚未整体关闭，不能用测试数量换算整体完成率。

| 客户端环节 | 当前实现 | 尚欠验收/边界 |
|---|---|---|
| Core与快照 | 两端共用冻结向量、连续游标、整页回滚、终态/权限单调；完整目录与全部保留ID；正常details与同次持锁投影一致；200k/100会话客户端探针已完成 | 仍需移动设备容量/内存验收、局部重建优化与多设备故障矩阵；Flutter proof已使用不可变分桶结构避免逐页全量复制 |
| HTTP与恢复协调 | 实际API层7操作；快照→固定cut日志追赶→原子commit→READY；在线已提交状态可resume，无额外快照页；grant/过期/epoch变化回退完整重建；5秒检查 | 重启仍完整重建，不从磁盘proof直接resume；实时提示触发重建，尚非完整性能方案 |
| 格式2与控制器 | Web实际IndexedDB、Flutter实际FileChatStore；迁移清旧副本、账号/generation/迟到回调隔离；新待发先commit后发帧、并发入队不丢项；失权清历史/队列引用/活动会话草稿 | Flutter跨进程协作写入锁已有宿主机真实进程验证；断电目录同步、正式升级降级与实际设备故障仍待 |
| 页面与交互 | 两端统一恢复/存储失败/需升级文案，READY前隐藏正文和禁发；三类body-free终态占位，不补造发送人/时间；已接回验证后预览/未读计算，阅后即焚预览不含正文 | 完整多端导航/入口与设备回归仍在后续大批次；未读依本人摘要刷新，不将发送READ当作服务端已确认 |
| 已读 | 两端加入相同300ms可见性Core与共享向量；页面收集视口，连续前缀、后台/遮挡/切换取消，恢复先读取本人已读摘要 | Web真实useChat+Workspace+IndexedDB已观察到可见300ms后的CHAT_READ；八组共同向量及Flutter控制器门禁已通过；物理设备/复杂遮挡仍待验 |
| 通知 | 两端body-free通知路由/effect与快照同次提交；仅新live候选通过权威恢复后SHOW，历史静默；失效CANCEL持久重试、迟到SHOW再检查；点击验证当前账号/消息/访问版本 | OS实机显示/撤销/冷启动仍待验；Linux取消未支持；Windows原生构建环境阻塞；iOS签名构建已通过，但不能据此关闭通知验收 |

2026-09-20 通知整批进展：实际Web session/sink与Flutter ChatController/FileChatStore/PlatformCoordinator已接入持久通知日志。迁移先CANCEL_ALL；SHOW完成后提交ACK，失败保留effect；撤回/失权清路由并排入CANCEL。原生标识按账号作用域生成，显示完成再次核对当前状态。Web/Tauri点击在登录前只保留无正文目标，登录后消费重验owner；恢复未就绪暂缓跳转；同会话不同消息不互相去重。明确退出同时使通知owner失效。Flutter真实文件存储专项验证取消失败重试及显示等待期间撤回，系统通知边界仍为注入FakeSystem，不冒充系统中心或设备证据。

本批证据仍在上述统一目录：`notification-web-final.log`为174项测试及类型/构建PASS；`notification-flutter-full.log`为分析与237项PASS，随后新增迟到SHOW用例由`notification-flutter-integration-final.log`两项专项PASS；`notification-desktop-final.log`为桌面前端构建及Rust 53 PASS/1 ignored（原有忽略项未修改），使用进程级CommandLineTools环境，无全局工具链切换；`notification-android-compile.log`为真实Kotlin编译PASS。macOS原生取消/点击代码已编译，尚未实际系统通知验收。Windows交叉检查缺少目标C头文件assert.h；默认Xcode工具链要求用户接受更新后的许可，未代用户接受，iOS未补报构建成功。Linux明确返回取消不支持，effect保留且界面提示重试。桌面冷启动激活、浏览器实际Notification权限/点击、真机/容量/多JVM及最终应用包仍未完成；默认能力门禁不变。继续当前对话的大批次，不因上述单项结果新开任务或要求重新批准。

通知浏览器追加验收：`web-notification-browser-final3.log`在实际Vue/useChat/IndexedDB及原生桥Web路径执行，API/WS/Notification为边界替身，验证durable-before-SHOW、历史静默、系统payload无正文、owner点击、撤回取消/路由失效、退出拒绝旧点击。最终补充异步点击模块加载后再次校验owner，PendingNavigationStore按messageId/owner/notification来源一起匹配，防止迟到事件消费较新目标；`notification-navigation-final.log`17项专项与`notification-web-typecheck-final.log`PASS。早期probe路径跳转、残留sessionStorage、Vite HMR产生模块副本的失败日志保留；隔离fixture存储并重启自有Vite后复验通过，没有放宽断言。`notification-flutter-analyze-final.log`、workspace-final2、hygiene-final五组及29文件限定源码Gitleaks扫描PASS；最初workspace提示生成索引过期，已用官方generate更新后复验。桌面唯一ignored是原有系统凭据存储写入测试，不能报告零skip。全历史Gitleaks仍未豁免。

真实服务端集成已PASS：拥有独立端口的嵌入Tomcat、实际Spring恢复Controller/事务服务与独立MySQL；Node加载实际Web API/协调器/sink，Flutter使用实际MeshXApi/协调器/FileChatStore。205条消息跨页恢复，第一次cut前撤回并清正文，第二次cut前物理删除产生UNAVAILABLE，两端从C=1 resume到C=2且不新增快照请求。fixture以隔离认证过滤器提供测试用户，并由测试transport显式覆盖空能力声明；**不是JWT登录、真实WS、设备或生产门禁验收**。服务端能力仍versions=[]，候选接口双开关约束保留；没有业务库DDL。Web该HTTP专项使用注入原子storage，实际IndexedDB另由浏览器真实useChat专项验证；Flutter使用真实文件存储。独立容器已清理。

控制器浏览器专项PASS：真实Vue useChat与IndexedDB，注入API/实时端口，验证旧正文隔离、commit先于发送、候选消息不展示、撤权清正文与当前入口。Web recoveryChatSession覆盖并发新队列写入不互相覆盖、提交失败、旧owner迟到提交；Flutter真实ChatController/FileChatStore覆盖重启迁移、磁盘临时文件故障、登出隔离和禁发。构建参数分别为`VITE_MESHX_MUTATION_RECOVERY`与`MESHX_MUTATION_RECOVERY`，默认false，迁移标记优先。

统一验证证据目录：`output/mx-a07b1i-controller-2026-09-19/`。`live-clients-mysql.json`及web/flutter-live-http为真实HTTP链；server-final为328项零skip PASS；terminal-flutter-final为分析与223项PASS；core-final及terminal-web为Core/Web测试类型构建PASS；web-terminal-browser为实际四组主题/尺寸和三类终态渲染PASS。新增已读后的`read-web-final`为166项测试及类型/构建PASS，`read-flutter-final`为分析与232项PASS；最后补充待发Widget唯一标识及遮挡即时取消后，`read-flutter-analyze-final`分析及`read-flutter-targeted-final`21项专项复验PASS。`web-read-controller-browser-verified`使用同一Vue运行时和真实MessageThread视口观察，记录AUTH→CHAT_READ→CHAT_SEND，并继续断言失权清理；最新浏览器/Vite自有实例已停止。早期fixture日期序列化、MySQL启动探测、Vue双运行时/HMR模块副本及Widget假时钟卡住的失败日志保留，修正fixture后重跑；未放宽业务断言。

历史证据仍保留在 `output/mx-a07b1i-client-core-2026-09-14/`、`output/mx-a07b1i-snapshot-details-2026-09-14/`；后者真实MySQL92项PASS。新HTTP fixture认证/序列化配置仅在测试中，匹配已安装Spring Boot实际默认值。contracts-final四组、workspace-final、hygiene-final五组、source-scan-final变更源码扫描PASS；原生图标50个导出和adaptive层一致性PASS，未补报Pixel真机圆形桌面截图。全历史Gitleaks原FAIL未豁免，变更源码扫描不能代替全历史发布门禁。I01–I12没有整项关闭，最终应用尚未重新生成。

| 项目 | 当前实现与证据 | 尚欠验收 |
|---|---|---|
| HTTP/WS 普通发送、撤回、焚毁 | 现有ChatMessageService同源事务；创建NORMAL版本1、终态单调版本、权限先于幂等 | 全链与故障矩阵随恢复接口一起验 |
| 广播技术卡 | 保存/清理调用同源消息版本与日志；无正文变更事实 | 多接收者外层事务锁序与死锁重试 |
| 群、好友、临时房间权限 | 创建/加入/移除/重入、禁言/到期、好友删除/拉黑、临时房间生命周期已接入 | 多JVM和恢复快照时钟边界 |
| 新私聊目录 | 首次成员目录写GRANTED重建，重复确保不追加 | 实际跨端新目录闭环 |
| 群物理删除与附件 | 消息UNAVAILABLE墓碑与失权同事务；共享MessageAttachmentCleanup接入撤回/焚毁/群删除，独立引用保护及失败回滚 | 真实对象存储故障与并发新引用创建验收 |
| 文件下载引用 | 已焚毁消息不再授予下载；其他有效引用保留 | 原有上传者/显式授权/头像权限仍独立，不声称全部物理销毁 |
| mutation/outbox | 原子连续游标、幂等、溢出/故障回滚；retention同事务清理过期日志前缀、提示outbox与floor，不清对象墓碑 | 多节点故障与容量 |
| 投递租约与订阅 | MutationDispatchQueue短事务领取；MutationHintDispatcher在事务外接RealtimeRouter，路由失败退避；AUTH后RECOVERY_SUBSCRIBE、ACK失败撤订、发送公共出口过滤订阅epoch；REST/WS共用关闭的广告门禁 | 真实多节点Redis故障/重启与端到端延迟；现有验证为实际DB租约+注入路由故障及Handler测试，不冒充集群实测 |
| 历史初始化 | MutationRecoveryBootstrap：显式维护事务，当前消息与访问元数据初始化、拒绝非法/不一致数据、不改epoch/cursor、不伪造历史mutation | 生产维护/备份/恢复/屏障工具和容量；没有执行业务库初始化 |
| 恢复读取与resume会话 | MutationStreamReader固定REPEATABLE READ读视图；持久会话、canonical origin/账号隔离、固定cut/ready、幂等重试与15分钟TTL；单账号最多20个活动会话；RecoveryController接入完整/局部rebuild及resume、分页/cut/mutations/ready/release，no-store及HTTP错误码 | 真实投递与能力门禁；api-enabled默认false，候选测试须同时启用dual-write；capabilities仍固定空versions，未广告版本1 |
| 日志保留与会话pin | MutationRetention按UTC保留至少30天，仅删除连续前缀，同事务清过期提示并推进floor；新pin与清理共享stream锁；每轮最多100账号、每账号2000条、清1000个过期会话；完整/局部manifest同事务pin | 多JVM容量与实际部署验收 |
| 一致快照清单与分页 | RecoverySnapshotManifest独立REPEATABLE READ建立S/H与完整/局部清单、同事务pin、rebuild幂等；RecoverySnapshotPages当前会话权限/终态投影、连续已服务位置与末页证明；READY同时检查客户端声明和持久分页完成 | 单实例200000条/100目录清单及全分页已通过；到期权限边界、多JVM、500接收者与实际客户端/设备验收仍待；当前读取超时30秒，创建60秒 |

局部重建与提示批次：`output/mx-a07b1i-local-rebuild-2026-09-14/`。真实MySQL最终91项PASS（含过期提示清理失败回滚），服务端总回归328项PASS，Flutter分析及176项测试PASS，Web测试/类型检查/构建PASS，contracts四组和tooling 59项PASS；本次自有MySQL容器已清理。总回归后追加的retention清理在最终MySQL专项复验，不重复全量Java测试。rebuild-conversations校验1..100个规范cid，排序去重参与幂等身份；只生成指定会话清单，但cut/mutation仍覆盖整个账号流。持久失权版本在目录/会话实体消失后仍可投影最小失效项。缺少持久accessVersion时采用D03允许的整账号重建回退，响应mode=rebuild；两端必须按响应mode选择整个generation替换，不能按原请求cid局部merge。没有编造版本或截断成功。

订阅与提示接线：新增RECOVERY_SUBSCRIBE/RECOVERY_SUBSCRIBED/MUTATION_AVAILABLE三个可选事件，生成Java/TS/Dart契约；RecoveryProtocolAvailability当前固定不广告，订阅与dispatcher生产路径同样禁用。hint-dispatch-enabled默认未配置，实际启用还要求dual-write。测试通过显式候选门禁执行，不修改业务库/生产配置。兼容审阅确认旧REST/WS结构、信封、生成配置及原向量hash全部不变，仅增加3事件与7个恢复REST path；证据compatibility-review.json，按兼容README的受审阅命令更新基线。来源盘点新增实际MutationHintDispatcher.setEvent调用，外部动态事件漂移仍拒绝，工具59项回归通过。

对照冻结mutation-record.schema.json补齐实际记录的recordVersion=1及严格三位毫秒UTC committedAt，验证eventId/streamEpoch规范UUID。新增真实序列化测试读取冻结schema，断言必填字段、禁止附加字段、const与pattern（不声称替代全部JSON Schema/跨端行为验证）。局部会话、整账号回退、选择集合幂等及非法输入专项与原有HTTP恢复测试一起运行。

分页与HTTP批次证据：`output/mx-a07b1i-snapshot-pages-2026-09-14/`，真实MySQL 84项、服务端全量324项、contracts四组、workspace、hygiene五组及本批源码Gitleaks均PASS；测试零skip，自有MySQL容器已清理。HTTP数据库流程使用MockMvc和真实MySQL/事务服务，不冒充设备/LAN/多实例验证。投影使用会话锁及当前读取，仍可读消息保留同ID并允许更高终态替代正文；物理缺行必须有持久UNAVAILABLE。失权则按wire最小CONVERSATION失效项撤销整会话，重复项幂等，不捏造全局消息终态。随机token绑定session；不能跳过未服务位置，分页失败不推进进度；空manifest也须读取空页。snapshot已丢失的rebuild不能走resume READY。HTTP的owner只来自认证上下文，origin来自servlet服务地址而非客户端Origin头；默认不启用候选API、不广告能力、没有生产配置修改。

契约导出发现两个内部Page同名导致OpenAPI错误合并；已改为MutationPage/SnapshotPage并加入真实契约字段断言，重新生成REST快照与TS协议模型。原ResponseEntity导出器假定全部是文件流，遇204无content报错，已对恢复JSON响应单独保留成功结构并列出400/401/403/404/409/410/503错误。相关失败保留response-shape-fail-*；测试时钟错误保留fixture-clock-fail-*（将fixture NOW改为业务使用的Java LocalDateTime，不改变2分钟撤回规则）。

快照证据在`output/mx-a07b1i-snapshot-2026-09-14/`，mysql-tests.json为真实MySQL 77项PASS、零skip；本批按影响执行专项，未重复运行320项全量服务端测试。实现先持stream锁防止retention越过尚未发布的pin，再用普通SELECT建立S并读H；后续目录、last_sequence与消息清单均为同一一致读，禁止INSERT…SELECT混入当前读。新账号空目录建立真实epoch和H=0。目录缺access版本、业务消息缺状态版本/序列不一致、NORMAL元数据孤立均拒绝并回滚。最多100个目录/200000条消息，超限显式RECOVERY_CAPACITY，不返回截断成功；此上限不是性能通过。清单页token为256bit随机值，表内不存正文；此为清单阶段历史证据；当前投影与READY进度见上方分页HTTP批次。原有73项恢复测试继续保留，新增跨200条完整性、隔离/空流、回填失败回滚及正常消息在H之后提交时的同视图验证。

恢复服务批次证据目录：`output/mx-a07b1i-recovery-service-2026-09-14/`。resume-*为68项MySQL通过记录，retention-*为71项通过记录；最终mysql-tests.json为73项PASS、零skip，server.json为全量320项PASS；workspace、hygiene五组与本批源码Gitleaks PASS，自有MySQL容器已清理。恢复读取要求显式REPEATABLE READ事务，counter与记录不混用当前读；缺号/未知事实拒绝成功返回。cut重试保持本轮F，ready发现L>F后才允许下一轮cut。会话请求仅接受canonical origin（默认端口省略、无路径/凭据/query/fragment）；idempotency-key只保存hash，epoch重置后旧重试不能返回旧会话。会话数20是本地资源上限，不是容量验收声明。

清理事务使用READ COMMITTED并先锁stream，再非锁定读取活动pin，避免与cut的session→stream形成反向锁；创建pin先持同一stream锁，清理能看到此前提交的pin。清理只推进连续旧前缀，遇到较新事件停止，遇缺号回滚；不清message/access墓碑。轮转userId避免前100个长期失败或被pin账号饿死后续账号。未启用生产开关、未执行业务库SQL；M1/M2均未关闭。

本组证据集中在 `output/mx-a07b1i-entry-audit-2026-09-14/`。最初文件测试用了不符合32位存储名规则的fixture，filename-fail-*保留；已改为合法fixture。首次并发领取测试错误假设两次SKIP LOCKED一定取满24条，实际返回21；skip-locked-fail-*保留。修正为并发无重复且后续领取无遗漏，仍要求最终24条唯一租约并全部成功确认，不放宽原子性/所有权断言。foundation-*及created-*为阶段验证，最终结果以mysql-tests.json为准。

本组统一验证：服务端320项、真实MySQL61项PASS且零skip；workspace、hygiene五组及变更源码Gitleaks PASS。所有自有fixture已清理，无业务库初始化或生产配置变更。

本组MySQL专项覆盖：原有生命周期/并发用例全部保留，新增新消息版本、私聊目录、租约、初始化，以及9类附件保护/清理失败/群删除/焚毁验证。文件清理复用FileService既有持久意图；测试用真实SQL验证元数据与正文/日志原子性，在FileService边界注入失败，不冒充实际对象存储删除。新清理只接受服务端file_path和上传者本人，保护他人文件、文字URL、所有者自己的头像、群头像、他人的UPLOAD_PROOF、广播凭证、文件传输、上传会话与其他存活消息。旧方法吞掉清理失败且按正文正则提取任意URL的路径已移除。独立引用不存在时调用既有清理outbox；不是新建对象删除通道。

初始化不伪造历史mutation：当前NORMAL映射版本1、当前已撤回/已焚毁映射版本2，已有版本/墓碑必须与现存行一致且不覆盖；非法sequence/孤立cid/冲突终态使整批回滚。初始化没有HTTP/调度入口，不修改epoch或游标，不代表部署屏障/历史实例清退已经完成。

后续里程碑：M2 恢复REST/manifest/pin与投递接线；M3 Web/Flutter Core、format2与队列闭环；M4 已读/通知及跨端设备验收；M5 各端最终构建、图标、安装升级与授权发布门禁。I01–I12没有整项关闭，不能用测试数量推算总体完成率。全历史security原FAIL未豁免。

## 实施范围与顺序

按[冻结实施清单](../../proposals/mutation-recovery-v1/implementation.md)I01–I12执行：事务入口与日志→恢复REST/manifest/pin/outbox→两端Core及持久generation→读位点和通知→真实集成/兼容/容量验收。能力声明在所有写入口、回填屏障及恢复验收完成前保持不可用。

## 当前代码

- 加法DDL `sql/migration-v3.2-mutation-journal.sql`：user stream、无正文mutation、dispatch outbox，未自动应用或连接业务库。完整message/access版本、墓碑、manifest/session及部署屏障仍待接续。
- `MutationFact`按冻结schema约束消息终态和访问变化字段；`MutationJournal`要求已有写事务，按userId升序锁定完整接收者集合，稳定eventId去重，同事务分配连续游标、记录及投递意图。尚未接入业务写入口，不宣称消息安全问题已修复。
- 专用`MutationJournalMySqlIT`需要显式独立MySQL配置，缺失时失败而非跳过；覆盖回滚、重复、冲突全扇出回滚、双服务实例并发、十进制精度/溢出、访问记录。最终7项真实MySQL测试PASS，零skip；首次DDL使用MySQL保留字cursor失败，改为mutation_cursor。随后新增旧MVCC读视图回归复现重复事件漏查/唯一键错误，幂等查询改用FOR UPDATE后通过；两轮FAIL日志分别保留为first-*和mvcc-fail-*。并发证据为同进程两个service对象、六线程与独立MySQL连接，不冒充多JVM部署或全业务锁验收。

## I01 已定位入口

ChatMessageServiceImpl.saveReliableMessage当前幂等命中早于权限解析；recallMessage重复终态判断早于操作者校验；markAsBurned尚无事务注解。群解散GroupServiceImpl.dissolveGroup直接删除chat_message，广播技术卡redactSystemBroadcastCards另有状态写入口。上述均需并入共同事务/锁/终态事实，不能只改HTTP而漏WS与内部调用。后续本卡“消息终态事务与重试鉴权”已修复部分入口；其余覆盖继续推进。

## 验证与边界

证据目录：`output/mx-a07b1i-journal-2026-09-14/`。独立MySQL8.4容器仅使用mx_recovery_test，结束删除本次拥有的容器/卷及临时认证配置；业务库和现有fixture不变。当前目标向量仍需由实际Java/TS/Dart实现逐项执行，结构校验不等于行为通过。最终1I、设备/HTTPS/容量/发布门禁均未关闭。


## 本轮验证结果

- PASS：`./tooling/verify server`，315项、零失败/跳过；另用显式`-Dtest=MutationJournalMySqlIT`执行7项真实MySQL集成PASS，默认单测不自动连接数据库。
- PASS：workspace、hygiene五组；官方generate更新repo-map和SQL索引。本轮新增Java/测试/SQL及相关任务/生成文档独立快照Gitleaks PASS。
- FAIL：全历史security仍唯一命中`16efe67dcaae432afd62e4fa10e1d6abbf55c419:frontend/src/platform/notificationDeliveryDeduper.ts:generic-api-key:12`，详见security-findings.json；未新增例外或放宽规则。
- NOT_RUN：业务终态/访问写入口接入、真实REST/WS恢复、TS/Dart Core及format2、38目标向量全链、容量/多JVM/设备/上线演练。当前七项IT只证明日志基础事务，并不关闭I01–I12或G03。

下一步沿I01/I02补消息与访问版本/墓碑和全写入口共同锁，并在同一业务事务调用日志；其后再实现manifest和服务能力协商。审批已取得，不再将1R/1I列为等待用户批准；生产部署/身份/签名边界保持不变。


## 消息终态事务与重试鉴权（2026-09-14）

- `saveReliableMessage`先解析目标并校验当前发送权限，再查幂等键；相同键不能返回另一个会话的旧消息。拒绝失权重试不会分配sequence或返回成功ACK结果。
- `recallMessage`使用MyBatis当前行锁读取；先验证发送者与当前访问权再接受重复撤回。保留2分钟限制，已焚毁不能再转撤回。
- `markAsBurned`加入Spring事务并复用相同消息行锁；已撤回拒绝，重复焚毁仍鉴权且不重复写入。HTTP与WS均调用这两个现有业务方法，没有新建竞争入口。
- 新增5项受控回归。首次全量320项有1项FAIL：冲突用例mock了未被调用的selectOne重载，改用既有getByClientMsgId spy后复验；该轮日志保留为server.log/server.json。
- 显式MySQL IT从7项扩至10项，PASS、零skip。新增测试通过实际MyBatis mapper与按真实@Transactional注解创建的Spring代理执行ChatMessageServiceImpl，验证recall/burn并发单一终态、真实唯一键日志故障回滚正文/状态、成功后重复请求仍校验owner/访问。权限来源在本轮IT使用受控ConversationService，并非真实群成员变更事务；不能据此关闭remove/send或多JVM全链门禁。

本轮证据为 `output/mx-a07b1i-terminal-2026-09-14/`。全量服务端320项复验PASS（server-final.json），workspace及hygiene PASS；新增源码独立Gitleaks PASS。未执行生产SQL、未增加能力声明。对象/access版本、持久日志接入、成员变化共同锁及文件清理提交后语义仍待实现；当前不声称I01/I02完成或G03关闭。


## 消息版本、墓碑与三处终态双写（2026-09-14）

`MessageMutationRecorder`与V3.2加法DDL新增无正文`recovery_message_state`，保存不可重用messageId、cid、原message sequence、单调objectVersion及NORMAL/RECALLED/BURNED/UNAVAILABLE状态。新的NORMAL消息在当前锁定业务行上建立版本1，实际终态转换到版本2；已有终态只能进一步UNAVAILABLE。历史终态缺少版本时要求回填，不能猜历史版本或静默当作NORMAL。保留元数据独立于业务行，物理删除不会自动删除墓碑。

ChatMessageServiceImpl的撤回、焚毁、广播技术卡redact现已调用记录器；技术卡筛选改为按messageId有序FOR UPDATE当前读取，再在同事务更新正文与每用户日志/outbox。私聊接收者使用cid两端固定参与者，删除好友后仍可读历史的一方不会漏终态；群/临时房间使用活动会话成员，成员变更共同锁和失权事件仍须接续。

这是冻结迁移方案中的加法双写阶段：`meshx.recovery.dual-write-enabled`默认false，本轮只在独立MySQL测试明确启用。该开关不广告capability、不批准部署，也不提供安全聊天fallback；正式启用必须先完成所有写入口、版本回填/epoch屏障及权限并发验收。未对当前业务库执行DDL或改运行配置。

真实MySQL专项最终15项PASS（`output/mx-a07b1i-dualwrite-2026-09-14/mysql-tests.json`）：实际业务终态/版本2/两用户日志/outbox同提交，第二接收者游标溢出使正文、撤回日志、版本和首接收者事实一起回滚；重复请求不新增版本/事实；私聊双方终态；技术卡重试；disabled加法阶段；物理删除与更高版本UNAVAILABLE同事务留存。物理删除用例调用实际记录器并在同一事务执行SQL，尚未接入群解散等正式删除入口，不冒充那些入口已验收。前两次扩展分别13项和14项PASS，结果保留为terminal-*及cards-*。

文件清理调用链核对：现有FileServiceImpl.deleteStoredObjects已经调用FileObjectCleanupService.enqueue，持久意图与业务同事务并在afterCommit处理，不新增竞争清理通道；本轮只读核对不冒充文件故障全矩阵通过。

继续项：成员/关系共同锁与accessVersion/revoke/grant事实、所有物理删除入口、全量回填/屏障、恢复manifest/session/REST、dispatch重试/retention、两端Core及原子存储。1I仍IN_PROGRESS；未广告版本1能力，也未关闭G03。

## 会话写锁、当前权限读取与成员失权（2026-09-14）

新增`ConversationWriteGuard`统一会话行锁→当前成员/关系权限→消息行锁顺序。发送、撤回/焚毁、技术卡写入/清理，以及群成员与好友关系修改接入共同会话锁。写事务内群角色、群主、成员数和管理员数使用当前锁定读取，避免旧REPEATABLE READ视图绕过权限或人数限制；普通页面读取保持既有路径。私聊锁可创建无成员的会话元数据，不为不存在的关系创建可见聊天入口。

真实MySQL专项先完成17项（`output/mx-a07b1i-write-lock-2026-09-14/mysql-tests.json`）：实际GroupService/ConversationService/ChatMessageService及MyBatis执行两个提交顺序。移除先提交时，已有旧读视图的事务仍拒绝发送；发送先持锁时，移除等待提交，随后相同clientMsgId重试被拒绝。会话成员未读/通知维护使用mock，不将本用例称为完整通知链或多JVM验证。该轮全量服务端320项PASS。

加法DDL增加`recovery_access_state`，`AccessMutationRecorder`在默认关闭的同一dual-write开关下运行。实际移除成员与主动退群先捕获当前权限，删除后确认失权，再同事务保存accessVersion和目标用户mutation/outbox。已读权限从版本1变为失权版本2；失权事实不携带rebuildConversation。已有元数据与当前权限不符时拒绝并要求协调回填，不默默覆盖。重复移除/退群不追加事实；日志溢出整体回滚成员删除和访问元数据。新增3项后初次20项MySQL专项PASS，零skip，证据`output/mx-a07b1i-access-2026-09-14/initial-mysql-tests.json`。随后针对群元数据当前读取补强复验，最终结果见同目录mysql-tests.json及server.json。

当前仍仅完成移除/退群两处访问双写；重新加入、禁言变化及到期、好友删除/拉黑、临时房间、群解散/物理删除、所有入口回填屏障仍待接入。暂不能启用能力声明或正式dual-write，更不能关闭恢复安全门禁。1I保持IN_PROGRESS，不等待重复审批；所有SQL只在本次拥有的独立测试库执行。

本切片最终复验：服务端320项、独立MySQL20项均PASS、零skip；workspace及hygiene五组PASS（含native-icons）；变更源码/SQL/文档独立Gitleaks扫描PASS。一次将两个scope放在同一verify命令导致参数错误，日志保留workspace-hygiene.log；改为两个合法命令分别执行通过，未跳过检查。全历史security沿用前述FAIL证据，本切片没有修改例外。fixture运行器已删除本次自有MySQL容器/卷，未触碰业务库。本切片只交付服务端恢复基础；客户端恢复、设备验收和最终各端重新打包仍未完成。

## 群访问重新授权与新目录事件（2026-09-14，接续）

`AccessMutationRecorder.grant`确认同一会话锁下“不可读→可读”，增加持久accessVersion并写入ACCESS_CHANGED/GRANTED，强制rebuildConversation=true。`addMembers`对实际新加入且有效的成员捕获之前权限，完成全部成员写入/会话目录同步后按userId升序写授权日志；已在群中或输入重复的用户不追加事件。`createGroup`先建立空会话并持锁，然后为群主与有效成员写相同授权事实，避免新目录条目没有恢复事件。

`ensureGroupConversation`在写事务内改为按用户有序的当前成员读取，避免旧MVCC快照把刚移除的人恢复到conversation_member。测试fixture新增真实chat_group和conversation_member及MyBatis mapper，移除/重新加入现在验证真实left_time和目录更新；用户有效状态仍用受控UserMapper，事件订阅与WS/通知投递不在该证据范围。

首次编译失败来自JUnit assertTrue重载与TransactionTemplate泛型推断冲突，改为显式Boolean断言；compile-fail-*日志保留。重新加入专项22项真实MySQL测试PASS（output/mx-a07b1i-grant-2026-09-14/rejoin-mysql-tests.json），覆盖2→3→4访问版本、GRANTED重建、重复加入无事实、批量后续用户日志失败整体回滚。新建群专项及最终全量/治理复验继续记录在同目录。

该接续切片最终PASS：真实MySQL23项及服务端全量320项，零失败/跳过；workspace、hygiene五组及本切片源码独立Gitleaks扫描通过。证据`output/mx-a07b1i-grant-2026-09-14/{mysql-tests,server,workspace,hygiene}.json`。本次拥有的MySQL容器/卷已清理；未执行业务库迁移。全历史security前述FAIL未豁免。仍待禁言及自动到期、好友/临时房间访问变化、群解散墓碑、全写入口回填屏障及客户端恢复链路；1I保持IN_PROGRESS。

## 群解散物理删除与墓碑（2026-09-14，接续）

`dissolveGroup`在共同会话锁和当前群主校验后捕获当前成员权限，按messageId有序锁定群消息。先为每条消息保存更高版本UNAVAILABLE墓碑和接收者日志，再执行既有回执、撤回记录、消息与群成员/群元数据删除，保留DESTROYED会话；最后为全部原当前成员保存GROUP_REMOVED失权事实。所有操作共享Spring业务事务，消息元数据不随chat_message物理删除，未使用级联清除墓碑。再次解散保持既有“群不存在”错误，无重复事实。

真实MySQL专项扩至25项并PASS、零skip，证据`output/mx-a07b1i-dissolve-2026-09-14/mysql-tests.json`。两个新增用例调用实际GroupService/ConversationService、实际消息/群/成员/会话/回执mapper：非群主拒绝；正常解散后正文/成员/回执删除，原sequence=62墓碑版本2持久存在，两用户各收到消息终态和失权事实；最后用户失权日志溢出发生在物理删除之后，仍整体回滚正文、回执、群、成员、会话状态、墓碑、访问元数据和先前mutation/outbox。用户元数据和事件订阅仍有mock，未证明实际WS/客户端回收或对象存储文件清理。群解散附件对象回收及其他清理旁路仍须审计接续。

本次自有MySQL容器/卷已清理，无业务库DDL或能力声明变更。全量服务端及治理结果记录同目录；1I仍IN_PROGRESS，客户端恢复及最终打包未关闭。

本切片最终复验：服务端320项、MySQL25项PASS且零skip；workspace、hygiene五组、本切片源码Gitleaks均PASS。全历史security已记录的FAIL未豁免。下一步继续审计附件清理与其余访问变化/到期入口，再推进恢复REST、manifest及Web/Flutter恢复存储。

## 好友访问变化与提交后通知（2026-09-14，接续）

`AccessMutationRecorder.refreshReadable`用于历史仍可读的发送权限变化，不清历史、不伪造失权；相同有效权限不追加版本。FriendService实际删除双方关系写FRIEND_DELETED，拉黑使双方发送权限禁用并写SEND_DENIED，解除拉黑写UPDATED；重新接受好友申请恢复发送权限时写更高版本GRANTED并强制重建。双方状态在共同私聊会话锁下捕获，日志按userId升序提交。dual-write仍默认关闭，不广告恢复能力。

真实MySQLfixture扩展friendship/friend_request并使用实际Mapper和FriendService事务代理。28项专项初次PASS，零skip（output/mx-a07b1i-friends-2026-09-14/access-mysql-tests.json）：删除后正文保留、重复删除无新事实、重新接受版本3且重建、拉黑/解除拉黑双方权限、后一接收者日志溢出回滚双方关系和所有前置事实。有效用户元数据使用mock，WS投递不属于该证据。

接受好友的既有成功通知移到关系与访问日志写入之后，并注册afterCommit；外层事务回滚不发送成功通知。新增实际事务外层回滚用例及成功提交通知断言，最终证据见同目录mysql-tests.json。该通知仍是现有WS通知调用，不冒充durable mutation outbox跨进程投递；进程崩溃/网络投递重试仍待dispatch阶段验证。

本切片最终：服务端320项、独立MySQL29项PASS且零skip；workspace、hygiene五组及变更源码Gitleaks PASS。自有MySQL容器/卷已清理；全历史security前述FAIL未豁免。未连接业务库执行迁移，未生成本轮最终应用包；1I及整体Flutter改造仍在推进。后续重点：禁言显式变化及自动到期、临时房间、附件清理/其他旁路、全量回填与恢复服务/两端状态机。

## 显式禁言与解除（2026-09-14，接续）

管理员`muteMember`在当前权限校验之后捕获受影响成员的访问状态，期限更新与SEND_DENIED/UPDATED访问事件同事务提交；延长尚未到期的禁言不改变读/发布尔权限，因此不重复增加访问版本。保留历史、rebuildConversation=false。解除禁言改为LambdaUpdateWrapper显式SET mute_until=NULL，避免默认非空字段策略忽略null而遗留禁言。

独立MySQL专项31项PASS、零skip，证据`output/mx-a07b1i-mute-2026-09-14/mysql-tests.json`。新增实际业务用例覆盖禁言→延长→解除的版本2/3、真实数据库NULL清除、历史保留；无权限操作者拒绝，以及日志溢出回滚期限/访问元数据/日志。自有fixture已清理，无业务库DDL。

自动到期尚未实现，且仍是启用dual-write/恢复能力前的明确门禁：当前guard按时钟推导sendAllowed，而持久accessVersion不能因时间流逝自动递增；必须将到期协调、管理员延长/解除/移除竞态、写入/恢复快照边界一起验证。不能在before()里直接追加单用户日志后再执行多接收者业务，因为那会破坏接收者全序锁；不能只靠一条定时WS通知当持久状态同步。后续应先形成可复现的到期事务与快照一致性证据，再关闭该缺口。

最终复验：服务端320项、真实MySQL31项PASS且零skip；workspace、hygiene五组及本轮源码Gitleaks PASS。全历史security前述FAIL未豁免。1I继续IN_PROGRESS；自动到期、临时房间和附件旁路，以及REST/manifest/Web/Flutter恢复仍未完成，不作完整迁移或最终应用交付声明。

## 禁言到期协调与管理员竞态（2026-09-14，接续）

`ConversationWriteGuard.Permission`增加仅服务端内部使用的expiredMuteDeadline：来自共同会话锁下的当前group_member期限，不进入wire。`before()`继续只捕获权限，不提前获取用户stream锁。`AccessMutationRecorder`在实际日志阶段识别“持久禁发→已到期可发”的差异，先写更高版本UPDATED；若同事务正在重新禁言/移除/解散，再按顺序写该业务变化。两条事实与业务更新同事务，后一步失败会回滚前一步到期事实。重复协调不会递增版本。

新增`GroupMuteExpiryWorker`，仅dual-write明确启用时注册，默认关闭。每轮最多读取100个有持久禁发元数据的到期候选，每个候选在独立事务中重新锁定会话、检查当前成员及期限；不直接发WS通知、不清期限或正文，失败保留候选重试。管理员延长期限后，即使旧候选被再次执行也不会恢复发送。候选比较使用Java LocalDateTime，与现有期限写入和guard一致，不依赖数据库NOW的时区；未改现有业务日期存储语义。

首轮35项MySQL专项PASS（initial-*），加入并发/第二事实失败后37项PASS（concurrent-*）。用例采用真实MySQL、实际事务/mapper/记录器；fixture将期限设为Java当前时间前一分钟来触发边界，没有等待10分钟或宣称真实时长/device实测。覆盖重复协调、到期后重新禁言/移除、旧候选、日志失败保持版本、两个线程独立连接/记录器只提交一次，以及第二事件游标溢出整体回滚到期事实与新期限。统一时间基准后的最终复验保存在`output/mx-a07b1i-expiry-2026-09-14/mysql-tests.json`。

该实现补齐后台与管理员事务的到期日志，尚不证明未来恢复manifest与时钟边界已一致：恢复会话仍需在冻结S/H之前协调到期并验证页/写入边界，容量、公平重试/永久故障候选、真实调度与多JVM执行也待专项。不得因此提前广告版本1或启用生产dual-write。

本切片最终PASS：服务端320项、真实MySQL37项且零skip；workspace、hygiene五组、变更源码Gitleaks通过。所有测试fixture已清理；未执行业务库迁移或改变恢复能力开关。全历史security前述FAIL未豁免。1I仍IN_PROGRESS；剩余临时房间/附件旁路、恢复服务与两端存储、设备及最终包验收继续执行。

## 临时房间加入/退出与会话锁（2026-09-14，接续）

TemporaryRoomService创建房间后持有会话锁，再创建所有者成员并写GRANTED；按房间码加入先只定位不可变ID，再锁会话、当前锁定房间并复核ID，消除原先房间→会话与发送会话→房间的反向锁序。退出也先锁会话。实际加入/退出分别写GRANTED重建/REMOVED失权，重复加入不追加；角色、容量与写事务返回视图使用当前成员读取，避免旧MVCC视图绕过容量或复活角色。生命周期处理在变更房间状态前同样先取会话锁，完整到期事件仍在后续接入。

真实MySQLfixture增加完整temporary_room与实际mapper/事务服务。39项专项PASS、零skip（output/mx-a07b1i-temporary-2026-09-14/mysql-tests.json）。新增创建→加入→退出→重入版本2/3/4、重复加入无事实、真实容量限制、所有者退出拒绝；加入或退出日志溢出回滚真实conversation_member和访问版本。故障用例只在独立fixture修复人为注入的计数器，不引入业务游标重置接口。事件发布器仍受控，不将本测试称为真实WS/设备广播验证。

临时房间FREEZE/ARCHIVE/DESTROY尚未写完整访问事实，当前到期有效禁发与持久版本的时间边界需按冻结方案补齐；批量生命周期处理还需避免跨房间用户stream锁逆序，不能简单在原全批事务里逐房间追加。没有物理删除的DESTROY入口不能凭状态名宣称附件/消息清理完成。1I保持IN_PROGRESS，能力声明/生产dual-write仍未启用。

最终验证：服务端320项、独立MySQL39项PASS且零skip；workspace、hygiene五组、变更源码Gitleaks PASS。自有数据库fixture已清理，没有业务库迁移或最终安装包产出。全历史security原FAIL未豁免；下一步接续临时房间生命周期、清理旁路及恢复API/两端核心。

## 临时房间生命周期事务（2026-09-14，接续）

新增TemporaryRoomExpiryService：每个房间用REQUIRES_NEW独立事务处理，会话锁→当前房间及成员→业务状态→按用户升序日志，批量调度不跨房间保留用户stream锁。扫描只是候选，锁后重新验证ACTIVE及expiresAt；FREEZE/ARCHIVE保留read并禁发，DESTROY撤销read并标记全部成员离开，复用现有会话状态和事件。失败房间全部回滚，先前成功房间保留提交，下一次调度继续处理ACTIVE候选。既有批量入口不再以一笔外层事务包住所有房间。

内部权限快照携带锁定房间到期证据，持久send=true而实际已经到期时，先记录SEND_DENIED，再记录必要的DESTROYED失权；冻结/归档不重复写相同权限。该证据不进入客户端wire。临时房间DESTROY仍是现有逻辑销毁/失权，不是物理删除正文或附件，清理旁路仍待处理。

MySQL专项41项PASS、零skip（output/mx-a07b1i-lifecycle-2026-09-14/mysql-tests.json）：三种到期策略及重复执行；后一房间第二条日志失败，验证它的房间/成员/版本/日志全部回滚，前一房间独立提交不受影响。fixture用已过去的Java期限触发，不宣称真实调度/真机验证。全量服务端及治理结果见同目录。

进度口径：I01–I12尚无整个验收项完全关闭；当前前3项实现较多，其余恢复API、manifest、双端Core/原子存储、读位点/通知、真实链路及发布仍需完成。用户要求扩大切片，后续按后端收口、恢复服务、双端实现、读位点通知、跨端验收、最终交付六块汇报，避免把测试数字当整体进度。

本轮最终服务端320项、真实MySQL41项PASS且零skip；workspace、hygiene五组及变更源码Gitleaks PASS。独立fixture已清理，无生产迁移、能力开关或发布变化。全历史security既有FAIL未豁免；整体任务保持进行中。

## 2026-09-20 会话摘要与容量整批验证

仍在同一对话连续执行恢复链路大批次。Web/Flutter会话列表从当前已验证image按消息sequence计算最新预览，从本人已读位置计算仍正常且非本人的未读消息；缺少本人基线时不虚构未读。终态使用统一占位，阅后即焚只显示“阅后即焚消息”，媒体按一致类型文案显示；隔离阶段不显示旧预览，终态不补造时间。原有旧协议路径保持原实现。Web基线改为响应式替换，摘要完成后列表会更新；Flutter在恢复publish时生成会话摘要。

实际浏览器useChat/IndexedDB/API与WS边界替身验证预览、未读1→2→撤回后1、通知及登出隔离：`web-summary-browser.log` PASS。实际ConversationSidebar浅深色390/1280四组渲染与未读徽标检查：`web-summary-render-stable.log` PASS；截图使用现有主题变量并禁用主题过渡后截取，已目视检查暗色窄屏。Flutter真实控制器/FileChatStore新增相同预览/未读断言。Core、Web178项/类型/构建、Flutter分析/241项、contracts四组、workspace、hygiene五组与12文件限定源码Gitleaks PASS；日志前缀`summary-*`。首次Flutter analyze提示新工具print及条件缺大括号，已修正后完整复验，未忽略规则。

Web sink此前每条正常消息都filter整个历史，实际10k约715ms、20k约2747ms。现改按messageId索引更新，终态按页统一施加，消息/权限/提交门禁保持。新增显式容量工具`apps/web/tool/recovery_capacity_probe.mjs`、`apps/flutter-prototype/tool/recovery_capacity_probe.dart`：均处理200000消息/100会话/1001页；Web实际Core/协调器/sink与JSON序列化约31249ms、进程RSS816791552字节，HTTP/磁盘为替身；Flutter实际Core/协调器/sink/FileChatStore提交并由新store实例重载约107123ms、进程RSS1348485120字节，HTTP为替身。两者均在宿主机，**不能证明移动设备容量通过**，内存占用及逐页proof复制仍需处理。证据为`capacity-web-200k.log`与`capacity-flutter-200k.log`，临时Flutter目录已由tearDown删除。

新增显式隔离MySQL容量IT，目标为200k/100目录、全分页READY和200001条拒绝、不产生截断清单。初次测试编译误用了旧fixture私有辅助方法，已改为直接断言RecoveryFault；第一轮实际执行在NORMAL孤立元数据联接查询触发60秒超时。改为同一S内有界读取两侧header并按精确ID/sequence/终态比对后，原92项MySQL通过，但容量仍在200k逐条驱动batch写入处触发原60秒事务超时。现改为每次最多500行、全部参数化的multi-row INSERT，不改驱动配置、事务隔离或60秒上限。原失败记录为`first-*`、`timeout-*`、`batch-timeout-*capacity-server-mysql.*`；最终容量专项已PASS：清单创建45053ms（保持60秒上限），1001页及READY总计285039ms；200001条明确RECOVERY_CAPACITY，检查仍只有原完整清单且无截断新增清单。最终组合93项（原92项加容量IT）零失败/零skip PASS，见`capacity-server-mysql.log/json`；runner确认本次拥有的MySQL容器/卷及临时凭据文件已清理。全过程只操作本次独立mx_recovery_test容器，未修改已有业务容器/业务库或生产门禁。

Flutter列表追加与Web一致的最新已验证消息时间排序，终态/空列表timestamp为空、相同时间保留原目录顺序。`summary-flutter-final2.log`分析PASS，测试发现空摘要预期漏写新增timestamp字段；补全精确预期后`summary-flutter-tests-final.log`完整241项PASS。该失败是测试record形状变化，不是放宽断言；原日志保留。


## 2026-09-20 Flutter容量与存储整批进展

仍属于恢复链路大批次，不新开对话或为单个测试新增交接。Flutter不可变proof采用分桶写时复制，阶段切换共享只读集合；失败页不修改旧快照。JSON编码按需生成行，校验保持格式2原字节合同；外层文件流式解码，覆盖旧缓存时验证全部JSON语法和根类型但不保留旧正文对象图。文件flush/原子rename、owner/revision和校验码拒绝规则不变。

PASS：最终Flutter分析与247项测试（memory-stream-flutter-final2.log），包括正确校验码但非法JSON/根类型拒绝覆盖、Unicode跨文件分块、旧快照不可变及失败回滚。首轮分析发现测试字符串多余转义，修正后复验，原失败日志保留。workspace生成索引更新后PASS，hygiene PASS。

容量证据：宿主机单次200k由历史107123ms下降至分桶版22141ms（memory-flutter-buckets-200k.log），不是控制变量的真机基准。Pixel_10_Pro模拟器Profile最终连续两次200k/100会话、2002页，首次提交21881ms，已有缓存替换26679ms，重载3378ms，总51938ms；最终RSS631914496字节，系统报告峰值831799296字节。证据capacity-android-profile-stream/android-emulator-integration.json与memory-android-profile-stream.log。HTTP为注入边界，实际协调器/sink/Android文件提交和重载；两次完整数量与revision=2断言通过。之前lazy JSON单次约916MB未显著改善，未将其宣称成功降内存。

仍未通过性能验收：日志出现约21秒长帧，测试传输微任务连续完成与实际网络不同，但同步CPU工作风险仍需处理；不声称流畅、真机/LAN或C08通过。该Profile APK为验证入口，不是最终应用交付。跨进程/断电、物理设备、多JVM、500接收者、跨端完整体验与最终构建仍待，能力默认关闭不变。


## 2026-09-20 恢复响应性与跨进程存储

恢复协调器每4页或连续工作8ms后让出事件循环，并立即重验owner/轮次；事件循环取消测试证明立即完成的分页不会饿死取消，提交和READY均不发生。格式2编码/校验/读取及写入使用短生命周期Dart isolate；请求对象方法引用只携带数据，序列化函数改为顶层函数，避免携带控制器/原生通道闭包。调用时捕获图像，主isolate全局队列仍决定写入顺序；隔离worker不能绕过revision比较、flush与原子rename。非法JSON、校验失败、临时文件故障、旧缓存拒绝回退与调用后修改输入的原有测试继续通过。

新增稳定账号.lock文件：主isolate队列保护同进程调用，OS文件锁保护协作进程；不删除锁inode，避免等待者锁到旧inode。恢复和旧格式写入均在持锁后重读版本。真实独立Dart进程测试验证持锁更新后旧revision拒绝、进程强杀后锁自动释放、旧格式写入不能绕过迁移。该证据来自宿主机真实进程，不冒充Android/iOS跨进程或断电持久化证明；Dart的Unix文件锁按进程生效，仍要求应用持久写入从主isolate统一排队。

PASS：responsive-process-flutter-final.log为分析与250项完整回归；随后新增4096个分桶proof值的跨isolate编码逐项比较，responsive-storage-transfer-final.log共11项PASS。首次分页取消fixture未填满非末页而被协议拒绝，修正为合法单条页后通过，未放宽生产分页检查；首次分析花括号提示已修正，失败日志保留。

首轮响应性Profile（capacity-android-profile-responsive）：Pixel_10_Pro模拟器，两次200k/100会话、2002页、真实文件提交/替换/重载PASS；106660ms，RSS664305664、系统峰值840515584字节；5030次16ms心跳，最大间隔532ms，2次超过250ms；1939帧，最大build109730us/raster226243us。UI测试含持续进度动画，HTTP仍为注入传输，不能直接与无动画旧基准当作受控性能比较。未重现旧日志的约21秒长帧，但仍有明显短暂停顿及总耗时增加，C08/真机性能不关闭。

任务索引已记录用户当前的大批次持续授权：卡片是证据记录，不再以完成单卡为新开对话或重复批准条件。旧卡历史阶段停止规则不替代当前授权；生产发布/签名/应用身份边界不变。

最终含文件锁Android模拟器Profile复验：capacity-android-profile-final/android-emulator-integration.json与responsive-process-android-final.log，200k两轮PASS，总106234ms，首次34522ms、替换59466ms、重载12246ms；RSS663830528、峰值868630528字节，最大事件间隔548ms，2次超过250ms，1946帧。此为最终运行代码证据，仍不关闭真机性能。自有Android模拟器已停止。用户随后明确可接iOS真机，已检测iOS27.0和Xcode27.0；许可阻塞已消失，首次Flutter误报开发模式不可用，CoreDevice确认enabled且DDI启用后isUsable=true，真机测试继续。


## 2026-09-20 500接收者与独立JVM验证

fanout-final-server-mysql.log/json：实际MySQL95项、零失败/跳过PASS，两个子进程均输出PASS worker。新增实际ChatMessageService撤回方法对500名群成员清正文、每人一条mutation/outbox、重试不重复；最后一名（按锁序）cursor溢出时，正文/撤回记录/版本与前499名日志及流均回滚。两条新增业务用例复用已有真实事务/MyBatis/写锁fixture，权限服务边界仍委托实际ConversationWriteGuard，不冒充完整登录HTTP。

另启两个独立JVM（独立连接和TransactionTemplate），同步起跑对同500条用户流写入：共享source重试仅产生500条，另外各3个独立事件；最终500条流每条连续1..7，3500条唯一mutation及outbox。此为日志原语跨JVM与500扇出证据，不等同两个完整应用实例并发变更/分页或WS投递；该余项保持开放。测试子进程在finally退出并保留日志于主报告；自有MySQL容器与临时认证配置已清理。

首轮95项中1项fixture断言错将撤回空字符串期待为null，生产实现与原有契约本就是空字符串；修正为严格空字符串后整组复验通过。未修改生产业务实现或清空规则，原FAIL日志fanout-server-mysql保留。客户端200k上限和服务端默认能力门禁不变。

iOS真机连接后的新阻塞：有效开发证书存在，但本机无可用开发描述文件，旧体验版embedded.mobileprovision于2026-09-16过期；Xcode报告No Accounts/No profiles。已请求用户在Xcode登录原账号，不代登录。新Xcode27要求开发构建目标至少iOS15；本次iOS27真机探针通过独立xcconfig覆盖target15，不据此修改首发最低OS承诺。现场Xcode操作/构建后项目级13仍在，目标级出现RECOMMENDED_IPHONEOS_DEPLOYMENT_TARGET；工作区原为未跟踪工程，不能据此盲目覆盖现场改动，正式候选的最低OS仍须核定。

用户已完成Xcode账号登录。responsive-iphone-profile-signed.log记录Profile构建43.6秒成功，保留体验版bundleId；iphone-profile-artifact.json记录可执行文件SHA-256、构建最低OS15、有效描述文件到期时间及包含目标设备。当前Xcode界面已确认正在复制iOS27系统调试符号（首次观察6%），手机未锁定且无Runner进程；此为可验证准备等待，不是性能通过。先前签名失败与直接xcodebuild缺Flutter头文件的日志保留，改用Flutter正规构建流程后编译通过；没有修改SDK或应用身份。


## 2026-09-20 iPhone真机前台容量实测

用户确认原“主界面”为iPhone桌面，不能把此前启动请求成功当作前台运行。直接无start-paused启动能执行分页，但Flutter调试连接未稳定建立，旧运行没有完整指标，不计PASS。测试入口改为写入带唯一runId的设备本地RUNNING/PASS/FAIL记录，经CoreDevice读取，避免结果依赖VM调试连接；产物记录Runner、App.framework/App（Dart AOT代码）及Flutter framework的SHA-256。仍使用已授权的体验版应用身份和现有开发签名，不是正式应用包。

首次持久结果iphone-persistent-result.json为FAIL：首帧时生命周期仍inactive。增加最多30秒的前台等待，等待时间不计入性能；进入resumed后才开始，过程中任何inactive/hidden/paused及结束非resumed均拒绝前台证据，且要求实际渲染帧。没有取消或放宽全程前台断言。分析iphone-persistent-probe-analyze2.log PASS，最终签名Profile构建iphone-persistent-profile-build2.log PASS，安装PASS。

最终设备结果iphone-persistent-result2.json：runId=iphone-capacity-20260920-persist2，iPhone真机/iOS27.0/Profile，foregroundThroughout=true；200000消息、100会话，两轮恢复共2002页，实际协调器/sink/FileChatStore提交、替换与新store实例重载，数量及revision断言PASS。首次14255ms、替换22116ms、重载3071ms，总39442ms；结束RSS586399744、系统报告峰值720650240字节；2279次16ms心跳，最大间隔283ms、1次超过250ms；2002帧，最大build45030us、raster19278us。构建与runId对应关系见iphone-persistent-artifact2.json，原始分页日志iphone-persistent-console2.log。

该结果证明此真机上的前台容量完整性及本次测量，不代表无卡顿：约283ms停顿、峰值约721MB仍须考虑产品预算。HTTP仍为注入传输，不能关闭真实LAN/HTTPS、完整恢复故障矩阵或C08正式main性能门禁。没有把测试入口作为最终应用交付。先前的符号复制、调试连接失败和inactive失败证据全部保留；本次自有mDNS查询已停止，未改用户网络、Xcode全局设置或清除应用数据。


## 2026-09-20 独立进程分页与Flutter进程重启

RecoveryProcessesMySqlIT新增两个独立JVM服务进程，共用显式隔离MySQL。一个进程创建405条消息的恢复会话/首批分页，另一个持有实际会话事务锁；测试从performance_schema.data_lock_waits确认分页已经发生真实InnoDB等待后才释放写事务。实际ChatMessageService撤回成功后，重试页保留原token并返回RECALLED/version2且无content/details；两个进程交替取完406个目录/消息条目。同一会话在另一进程cut保持固定F=1；新撤回提交后READY(1)为false，重新cut=2、连续两条mutation及READY(2)通过。最终恰好2条mutation/outbox，进程退出后清理自有临时目录。复用测试fixture的terminalService仅放宽到包内可见，生产实现未改。

该专项从单进程并发推进到真实独立JVM的事务服务及共享恢复会话，仍不是两个完整部署应用的HTTP/JWT/WS/调度验收。没有用文件屏障本身证明数据库并发；屏障仅保持事务，锁等待另由MySQL权威视图断言。初次recovery-processes-server-mysql为1项PASS；后续与真实客户端HTTP合跑的recovery-client-restart-server-mysql.log/json为2项PASS、零skip。

MutationRecoveryClientsMySqlIT同时扩展Flutter实际进程重启：第一进程经真实Tomcat/Spring恢复API/MySQL完成快照→撤回cursor1→物理删除cursor2，退出后第二个Flutter测试进程使用同一FileChatStore目录。第二进程无旧Core/控制器内存，先核验revision2与cursor2，再重新全分页恢复（大于1页），新文件revision3/cursor2、204条正常消息及UNAVAILABLE墓碑保持，敏感旧正文不复活，发布只发生于恢复通过后。原Web实际API/Core/sink HTTP路径继续PASS，其磁盘仍为注入边界，不能标成真实浏览器重启；认证仍为隔离测试过滤器，不宣称JWT/真实WS/LAN。临时缓存由父测试在所有子进程退出后删除。

证据为web-live-http-initial.log、flutter-live-http-initial.log、flutter-live-http-restart.log及组合MySQL日志。Flutter analyze通过（client-restart-analyze.log），新增测试引起生成repo-map过期，已用官方generate更新；workspace复验及源码扫描另附同目录结果。测试完成后独立MySQL容器及凭据文件已由runner清理，无业务库操作、能力开关或应用身份变化。恢复链路仍需完整HTTP/WS离线变更与部署矩阵，未关闭整个1I或最终交付。
