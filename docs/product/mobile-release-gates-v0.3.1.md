# Mobile v0.3.1 — Release Blocking Gates

日期：2026-09-09；[A07A冻结范围](mobile-release-scope-v0.3.1.md)的验收规则。当前 Android **NOT_RELEASE_READY**，iOS **NOT_RELEASE_READY**。本文件制定规则，不执行A07B/C/D，不修改扫描规则、签名、标识或协议。

后续证据更新：[A07B-1](../tasks/active/MX-A07B-1.md)已真实复现旧撤回/焚毁在离线增量恢复及Flutter磁盘重启后仍残留，状态 **BLOCKED_BY_CONTRACT**。G03保持BLOCKING且未关闭；测试复现PASS不等于安全行为PASS。处置候选见[独立提案](../proposals/message-mutation-reconciliation-v1-supplement.md)，其余门禁分类不变。

## 分类与关闭规则

- **BLOCKING**：必须有对应目标平台/版本的验收证据才能宣布Release Ready；未执行、环境受阻、仅代码存在都不能关闭。
- **NON_BLOCKING**：允许在明确支持范围内首发，必须记录影响、适用限制、负责人和后续项；不能用单元测试数量将真实设备门禁降级。
- **DEFERRED**：本版本明确不承诺该功能；已承诺的安全接收/权限恢复不能借“功能延期”逃避验收。

表内执行状态保留PASS/FAIL/BLOCKED/NOT_RUN。A05/A06有界PASS只关闭其证据范围，不自动关闭整个Gate。每条Gate关闭记录至少包含：候选源码标识及dirty差异摘要、构建哈希、机型/OS/网络、执行步骤、预期与实际、日志/录屏或截图、操作者及日期。Android/iOS分别判定；不使用整行笼统“移动端通过”。

## BLOCKING

| Gate | 平台 / 当前证据状态 | 必须满足的关闭条件 | 后续负责切片 |
|---|---|---|---|
| G00 P0产品闭环 | 两端 NOT_RUN：完整P0尚缺实现，Gate未满足 | Scope全部Included可达，成功/取消/拒绝/失败/恢复均有测试；延期入口明确，不伪完成；真实Web互通 | B02–B10，C01 |
| G01 Android real-device validation | Android BLOCKED：无真实手机 | 至少一台明确列入首发支持范围的实体手机完成P0/系统矩阵；如果目标首发声明包含某OEM，该OEM代表设备必须通过。emulator不可替代 | C02–C08 |
| G02 iOS Settings recovery failure | iOS FAIL：A06真实请求FAILED，手动恢复PASS | 关闭下方缺陷的fix或fallback验收；保留失败历史，不能仅一次重试成功 | B08，C04 |
| G03 认证/一致性/授权失效 | 两端 NOT_RUN完整发布矩阵；A05/A06文字切片PASS | AUTH/SYNC/outbox唯一归属；账号/设备撤销、并发刷新、乱序/ACK重试、已读/撤回/移除、两独立真实服务器隔离；不从附加字段兼容推断权限正确 | B01–B05，C01/C05 |
| G04 真OS lifecycle/network | Android BLOCKED；iOS完整lock/suspend/network NOT_RUN | 后台/前台、锁屏/解锁、真实网络断开/恢复/切换、进程终止、Android activity重建/合理Doze后恢复，均无重复WS/heartbeat/refresh/flush/通知；恢复补齐sequence | C03/C05 |
| G05 notification visual/click routing | 两端 NOT_RUN；iPhone权限/show/cancel有界PASS | 实际系统横幅/通知中心与点击前后台/冷启动路由；正确账号/节点/目标、过期/移除目标安全落地、重复/历史静默；明确无远程Push的后台限制 | B07/B08，C04 |
| G06 文件/广播端到端与异常 | 两端 NOT_RUN产品闭环；iPhone本地picker/share有界PASS | 真实Web↔手机的文件字节/哈希/授权、取消/超限/不可读/删除/退出清理；广播图片证据与回执幂等、取消/失去目标资格后不误完成；Web直传不可用能中转 | B06/B07，C01/C06 |
| G07 upgrade/install preservation | 两端 NOT_RUN；A06覆盖安装观测不够 | 相同批准身份与签名的旧候选→新候选升级，凭据/消息/outbox/游标/主题按策略保留；失败升级/重启不损坏；清洁安装与卸载重装边界单列；不把bundle ID不同当升级 | C07 |
| G08 release/profile performance | C08_PARTIAL：iPhone真机Profile分段性能、5轮冷进程及5轮真实OS恢复测试入口PASS；Android真机BLOCKED，API37虚拟机性能FAIL；正式main/HTTPS NOT_RUN | 真机Profile/Release-like测冷启动、列表滚动、输入/键盘、长消息、发现并行、重连；零ANR/崩溃/无界阻塞，记录帧/耗时和回归。A07C-1/2证明开发签名测试HTTP入口下iPhone预算，冷启含主机launch→ready上界，但不关闭正式main/生产传输Gate；Android Lavapipe退化与A05 Debug ANR均保留到真机正式main实证 | B10，C08 |
| G09 security full-history + changed-source | FAIL：仅已确认历史指纹；A06 changed PASS | 先单独批准并执行下方最小处置；完整历史与全部候选变化文件扫描按既有规则通过，真实generic-api-key探针仍被发现；新命中逐条核查，不能批量豁免 | C09（安全处置子任务须明确授权） |
| G10 remote CI & reproducibility | B11 LOCAL_PREPARED；远程仍NOT_RUN | 明确候选revision触发远程CI，contracts/core/design/server/web/flutter/tooling/workspace/hygiene/security所需检查通过；平台构建覆盖Android/iOS，保存run ID与工件哈希。A07B-9已声明quality/Android/iOS job并本地验证unsigned工件，但无远程run ID/clean checkout证据 | B11（准备），C10（授权触发） |
| G11 LAN real device with production transport | Android BLOCKED；iPhone测试HTTP LAN PASS，正式HTTPS NOT_RUN | 正式main/配置在真实LAN从发现候选完成受信HTTPS登录/WS；证书名称/SAN、节点TXT secure/端口/可达性匹配；无全局ATS/cleartext/TLS豁免；空网/节点上下线/双服务去重/网络切换分别记录 | B01/B08，C03 |
| G12 节点/控制面策略兼容 | NOT_RUN真实目标部署 | 说明支持的普通v1会话与部署策略；针对强制v2设备身份场景确认批准路线或明确不支持并可理解地失败，不得静默绕过策略。10个Dart REST常量不等于v2客户端就绪 | B01，C01/C05 |
| G13 signing/distribution | 两端 NOT_RUN；B11工件故意UNSIGNED | 经批准的身份、渠道、签名/配置与可安装工件、版本/build number可追溯；先验证安装/启动再发布审查。开发profile或unsigned本地工件不是TestFlight/App Store/Android正式分发证据 | C07/C10，D审查 |
| G14 old OS compatibility | NOT_RUN：目前主要为新API环境 | 当前声明Android minSdk26/iOS13维持；核对锁定Flutter引擎实际兼容范围与原生API可用性。最低受支持OS与代表中间版本跑关键链，必要设备/设备农场缺失时保持BLOCKED；不得静默提高最低版本或只看工程设置 | C02/C08/C10 |
| G15 基础UI/accessibility | NOT_RUN完整矩阵；A07B-8新增支持页320×568/1.5倍字号/语义局部PASS | P0屏幕小屏/长字/浅深色/系统字号/键盘/返回/安全区可用，主要控件读屏可识别，权限拒绝可恢复，无不可达操作；Flutter语义独立验收 | B09/B10，C02/C08 |
| G16 正式候选入口/版本/数据身份 | B11 PREPARED_NOT_APPROVED：正式main/无fixture注入已审计，仍为prototype 0.1.0+1与独立ID | 候选来自正式main，不含探针命令、fixture账号、测试HTTP例外或debug凭据；批准身份与版本对应工件、可重复构建。旧Capacitor与Flutter应用ID不同则不得承诺自动数据继承，先确认产品迁移政策 | B11（清单），C07/C10 |

A07C是验证阶段；它可以暴露缺陷，但不隐式授权改协议、数据库、最低OS、签名身份或安全规则。缺陷修复回到明确授权的小任务，重跑受影响Gate。

## NON_BLOCKING 与 DEFERRED

| ID / 分类 | 事项 | 首发约束 / 负责人 |
|---|---|---|
| N01 NON_BLOCKING | 已声明支持设备之外的更多Android OEM/更多iPhone外形与OS组合 | C阶段设备矩阵维护者记录为未验证；不得宣传“所有Android都支持”。G01代表实体机和声明支持的OEM不在此豁免内 |
| N02 NON_BLOCKING | 与移动P0无关的桌面凭据库/窗口/更新器旧债 | 桌面维护者继续保留；若影响Web/Tauri与手机互通、账号安全或正式联合发布，转相应BLOCKING，不能全局豁免 |
| N03 NON_BLOCKING | 性能微优化、非关键动效、深度无障碍增强 | 仅在G08/G15基础可用通过后；ANR、不可操作、读屏关键路径缺失仍阻塞 |
| D01 DEFERRED | APNs/FCM/OEM远程Push与永久后台即时收信 | 不进入本版本承诺；产品说明必须如实说明恢复补拉 |
| D02 DEFERRED | 移动直传、完整跨进程分片上传、定位证明/复杂群管理/临时房间等P1/P2 | [Scope](mobile-release-scope-v0.3.1.md)为唯一范围；已有功能兼容与安全降级仍受G00/G06约束 |
| D03 DEFERRED | 手机管理后台、广播创建/统计/导出、HarmonyOS | 保留Server/Web/桌面现有功能，不启动新端 |

## IOS-SETTINGS-01 — 真实产品缺陷

**Severity：S2 / Major**：用户拒绝通知或本地网络权限后，常见恢复入口打不开。手动进入设置可恢复，尚未观察到数据损坏；但仅显示失败不构成完整产品恢复，因此当前为G02 BLOCKING。

当前调用链已核对：`CapabilitiesPage` 的“打开系统设置” → `PlatformCoordinator.openSettings` → `NativeSystemCapabilities` / MethodChannel `openSettings` → Swift `MobileCapabilities.handle` → `UIApplication.openSettingsURLString` + 活动 `UIWindowScene.open`。Swift将完成回调 `ok=false` 映射为 `FAILED/settingsUnavailable`，缺scene为`TEMPORARILY_UNAVAILABLE/background`。Dart保存并显示失败状态，不吞掉。

A06 [第一次结果](../../output/mx-a06-2026-09-09/iphone-settings-result.json)和[scene路径结果](../../output/mx-a06-2026-09-09/iphone-settings-scene-result.json)都记录 `op=openSettings, status=failed`；设备为真实iPhone16 Pro Max/iOS26.6.1、Profile运行。原交接将其记为公开OS API返回失败。本轮确认保留该FAILED事实，不重新操作设备刷成功率。**现存JSON只保存能力status，没有独立记录原始回调Bool和reason；不能据它再细分是OS拒绝、scene选择还是通道异常。** 后续最小诊断需要补这个证据字段，不对“镜像环境造成”作无依据归因。

用户受影响路径：通知权限拒绝 → 开启提醒不能再次弹提示 → 打开设置失败；LAN权限拒绝后的恢复同样依赖正确引导。A06手动系统设置恢复成功只证明权限可恢复，不证明按钮修好。

2026-09-13 的 [MX-A07B-7](../tasks/active/MX-A07B-7.md) 已把候选路径改为公开 `UIApplication.shared.open`，补齐前台/URL检查、细分reason、单飞与人工恢复/复查。iPhone 16 Pro Max / iOS 26.6.1 Profile 三轮均返回 `settingsOpenAccepted`，并各记录一次前台→后台转换；当前工具未捕获手机设置页画面，因此这是 **MITIGATED / C04_VISUAL_NOT_RUN**，不覆盖上方历史 FAIL，也不关闭 G02。

Fix/fallback验收：

1. 在正式main的已授权测试构建中，确认主线程、当前foreground scene/presenter、系统URL及完成回调的status/reason，日志仅记录场景状态，不记账号/URL凭据。用公开API；不使用`app-prefs`等私有scheme，不改bundle identifier。
2. 修复路径：分别在通知拒绝和LAN拒绝后，从冷启动/恢复前台进入，至少3次独立进入流程，确认实际到达应用设置；不是仅回调true。后台调用应报告temporarilyUnavailable。重复点击不重复打开、不阻塞连接恢复。
3. Fallback路径：OS仍拒绝时，页面提供匹配受支持OS的人工导航文字和返回后“重新检查”，明确当前应用显示名；例如已验证新iOS上的“设置→App→MeshX体验版→通知/本地网络”。旧OS路线单独验收。不能只显示“稍后重试”，不能以打开通用设置页充当直达成功。
4. 两条路径均须在用户返回后无提示刷新通知状态/重新执行LAN发现；拒绝、仍关闭、已开启都有准确结果。由真机证据证明，不注入假权限成功。
5. 采用经过验证的fallback可以关闭“用户无法恢复”Gate；原生直达缺陷仍保留为已缓解、未根治，并记录支持OS范围。一次偶然成功不删除历史记录。

## SEC-FP-01 — 全历史误报正式处置方案（仅计划）

本轮使用本机Gitleaks **8.30.1**、原 `.gitleaks.toml` 与 `--log-opts=--all` 重跑，退出码1；新报告与A06逐项比较相同且只有1条。证据：[security.json](../../output/mx-a07a-2026-09-09/security.json)、[findings](../../output/mx-a07a-2026-09-09/security-findings.json)、[comparison](../../output/mx-a07a-2026-09-09/security-comparison.json)。

| 字段 | 精确值 |
|---|---|
| RuleID | `generic-api-key` |
| Historical file / line | `frontend/src/platform/notificationDeliveryDeduper.ts`，12 |
| Commit | `16efe67dcaae432afd62e4fa10e1d6abbf55c419` |
| Fingerprint | `16efe67dcaae432afd62e4fa10e1d6abbf55c419:frontend/src/platform/notificationDeliveryDeduper.ts:generic-api-key:12` |
| 当前位置 | [notificationDeliveryDeduper.ts](../../apps/web/src/platform/notificationDeliveryDeduper.ts)，12 |
| 非secret依据 | 值为 `meshx_notification_delivery_v1` 的固定localStorage命名空间。已核对历史blob与当前构造器/read/write：用于保存通知去重标识与时间，不是认证token/签名密钥，没有送入Authorization或服务端凭据链 |

最小处置建议：后续经明确批准，只在 `.gitleaksignore` 登记这一条含commit的完整fingerprint并附审查引用；不修改默认generic-api-key规则、不豁免目录/文件/整类STORAGE_KEY、不降低熵阈值、不批量用baseline吞掉全部发现、不改历史。按发现指纹抑制是当前版本支持的机制，依据 [Gitleaks v8.30.1官方说明](https://github.com/gitleaks/gitleaks/blob/v8.30.1/README.md#gitleaksignore)。该机制需锁定版本并以实际命令回归确认。

**A07A没有新增`.gitleaksignore`条目，也没有修改任何安全规则。** 已知误报的原始扫描仍FAIL，不能提前把方案写成修复完成。

后续处置验收必须同时完成：

- 保存无例外的原始报告，确认唯一被移出的finding是上述五元组；对别的新命中保持失败并单独分类。
- 使用原配置执行完整历史 `gitleaks git . --log-opts=--all --config=.gitleaks.toml --redact=100 --no-banner`，并运行 `./tooling/verify security`；不能只扫HEAD或浅克隆。
- 对A07B/C全部新增/修改且含未跟踪的源码建立独立目录快照执行 `gitleaks dir <snapshot> --config=.gitleaks.toml --redact=100 --no-banner`；核对配置、生成物、工作流和准备分发的工件日志。历史fingerprint不自动适用于新路径/新提交/dir扫描。
- 在隔离临时测试材料中放置**合成、无效、非真实凭据**：同一历史路径的新提交/同一行的generic-api-key、当前移动后路径、其他目录、相似常量名；确保规则仍报告并返回非零。先证明合成样例在无例外时能被该规则命中，再与最小例外对比，避免选一个本来就检测不到的样例假PASS。
- 将无害命名空间和真实凭据形态分开验证；未来该常量再次在新提交/dir扫描命中须重新核对，不追加通配豁免。原有两条FileControllerTest确定性测试值allowlist保持原范围。
- 运行tooling/workspace/hygiene及相关安全回归，把G09关闭记录关联新扫描工件与review，不覆盖旧FAIL。
