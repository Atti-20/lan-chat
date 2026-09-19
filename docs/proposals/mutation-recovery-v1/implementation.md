# 迁移、回滚与 MX-A07B-1I 清单

2026-09-09；READY_FOR_APPROVAL，**仅计划，1I未授权/未启动**。规范入口：[design](design.md) / [wire](wire.md)。不执行SQL、不生成生产migration。

## Additive migration与上线顺序

1. 在独立1I分支任务中设计并评审加法DDL：message状态版本和无正文tombstone、user+conversation access版本、user stream epoch/counter、mutation记录、dispatch outbox、临时recovery manifest/session。仅新增表/列/索引，不重用message.sequence，不改原message排序。索引至少唯一(user,epoch,cursor)、唯一(user,epoch,eventId)、日志createdAt/清理前缀、outbox幂等键；访问索引按user/cid。
2. 旧消息版本回填与新写入口切换必须有可证明的部署屏障。先部署不广告能力的新代码和加法表；所有实例/HTTP/WS/清理入口均具备原子dual-write后，再在短暂业务写入维护窗口完成最后回填/检查并建立新streamEpoch。回填旧终态不伪造原message.sequence或把历史mutation排序猜出来；首次新客户端全部rebuild。
3. 旧实例仍能绕过日志写入时**禁止广告能力**。不能滚动到一半就让客户端safe。多实例版本/DB约束/部署检查有一项不满足则capability暂不可用；只有全覆盖自检、事务/并发/恢复集成通过后再开版本1。
4. 新服务端先上线，保留旧v1路由。先Web/Tauri/Flutter受控候选验证，再发新客户端消费；各平台独立迁移旧缓存format2，应用ID、签名、最低OS与本轮无关，不得借迁移改它们。
5. migration必须给备份/恢复、行数与约束核验、dry-run、运行时间、磁盘空间/锁等待预算；由后续明确部署授权执行。本任务没有SQL文件或生产操作。

## Retention / 容量门禁

默认至少30天持久日志；15分钟session pin、60秒manifest创建上限、每页200上限是冻结工程参数。对当前小团队产品，首版容量验收至少覆盖：单账号200,000条保留消息、100个会话，单群500个历史可读接收者，两个实例并发变更/分页；这不是硬编码删除上限或已经达到的能力承诺。

超过预算时明确RECOVERY_CAPACITY并保持隔离。不得无日志异步扇出、截断manifest、跳最新cursor或延长无期限数据库事务来假通过。1I若达不到预算或部署实际规模更大，应提交容量设计调整；发布门禁继续BLOCKED。重复grant导致持续rebuild、活跃会话不停新消息、30天前缀清理与session pin并发都必须测，防止恢复饥饿和磁盘无界。

## Rollback冻结

- 首选回滚新客户端/关闭新capability广告，保留dual-write、mutation log、outbox和所有新表。不能“回滚”成仍广告能力但停写日志。
- 若必须退回不会dual-write的旧server：先关能力并让安全客户端进入quarantine，禁止继续自称safe；保留原日志，恢复新版后换epoch、重建快照，不续接可能有盲区的旧流。
- 不删除mutation log，不降低cursor，不破坏已迁移客户端状态。恢复较旧DB备份也必须换epoch。Redis故障只影响提示，不触发DB回退或清日志。
- 新客户端检测服务端downgrade/版本消失/epoch变化后阻断正文/自动队列，保留无正文失败原因、待处理草稿与安全墓碑。禁止自动加载旧缓存namespace；旧二进制安装后若不能识别迁移标记，则该降级组合不支持，必须明确提示而不是承诺数据安全。
- 旧Web+新server仍有已知不安全行为；关闭新能力不会神奇修复它。组织强制客户端最低能力/版本政策须另经产品部署批准。

## 1I实施清单与完成证据

| ID | 责任路径/工作 | 必须证明 |
|---|---|---|
| I01 | services/server消息/会话/好友/群/清理事务入口盘点，SQL仅后续批准编写 | 所有HTTP/WS同源写事实；无直接mapper旁路；回滚无业务/日志/outbox；鉴权先于幂等命中 |
| I02 | user stream/counter/epoch与记录、接收范围 | per-user连续提交；多设备各自位置；旧成员仍获最小移除，前好友仍获消息终态，非成员无正文/成员泄露 |
| I03 | lock/并发/dispatch outbox | 重复mutation、并发recall/burn、remove/send两个提交顺序、多实例dispatch、Redis临时失败、死锁重试均可复现 |
| I04 | 新recovery REST/WS和schema生成（另在实现任务审阅生产契约diff） | capability明确、401/403/409/410/503真实HTTP、未知版本阻断；旧v1操作/sequence/ACK零语义漂移 |
| I05 | 一致manifest H、局部重建、固定cut F、page/session pin | 分页mutation/权限变更/物理删除、最后页/空流/缺号/重试/过期、READY竞态、持续写入饥饿测试 |
| I06 | Dart纯Core、TS domain独立状态转换 | 所有批准目标向量读取同JSON并实际断言；不把参考reducer结果当业务实现通过；旧characterization保留或标注对应已修复版本 |
| I07 | Flutter FileChatStore与Web IndexedDB原子generation/format2迁移 | 回滚/磁盘满/rename失败/IndexedDB abort/重启中断；旧副本不复活；cursor与正文/权限/outbox/路由同提交 |
| I08 | access与outbox策略 | revoke清正文；read allowed/send denied不清合法历史但不自动发；重入不复活旧队列；新发送server事务重查 |
| I09 | Web/Dart可见性读位点 | 前台300ms/长消息/遮挡/切换/未打开/后台/重连/history、连续前缀与权威缺口；ACK不当READ，不泄露peer私有读位点 |
| I10 | 通知资格与持久effect outbox | mutation/SYNC静默、失效路由、延迟OS取消失败重试、旧owner完成回调不发新通知；完整点击设备E2E仍按后续范围 |
| I11 | 真实Server→Web、Server→Flutter完整链条 | cache→offline→HTTP和WS变更→resume→restart正文正确；全历史窗口之外/大历史/旧账号/旧节点/多设备，不能只测reducer |
| I12 | 兼容与上线/回滚演练 | old/new四组合、版本消失、DB restore换epoch、retention边界、legacy fallback仍默认禁用、无安全门禁降级 |

既有server/contracts/web/flutter/core/dart-core/workspace/tooling/hygiene/security先dry-run再按改动执行；新增事务测试必须含真实MySQL集成，H2/mock不能证明MySQL MVCC/锁边界。新增结构JSON schema和TS/Dart/Java生成需审阅而不是直接accept baseline。真实设备/发布/CI在相应后续授权下运行，不因1I代码测试通过自动关闭A07C。

## 单独fallback验收卡（未认证）

quarantine→权威全目录→全部历史分页→屏障内变更/权限一致性→完整替换→安全开放。逐项必测：物理删除（缺行不能留旧正文）、分页中撤回、群移除、半程失败、200k历史、进程/浏览器重启、游标无进展、空目录、离线重试、开放后的新mutation。旧server若不能提供可信写入/权限屏障与持续恢复保证，结论必须FALLBACK_UNAVAILABLE，不能只测静态数据后启用。

## 审批包与停止

本包冻结的是一个明确候选：per-user流、独立cursor、严格quarantine、默认阻断旧server、30天retention、原子恢复、300ms连续可见已读、加法迁移。需要整体审批这些产品与兼容取舍；任何替换必须版本化修改设计与目标向量。

批准本包不自动授权数据库执行、发布或设备身份变更。1I仍需单独明确启动；本轮在READY_FOR_APPROVAL停止。剩余事项是实现/性能/真实并发验收，不把这些未运行项记IMPLEMENTED，也不伪称已关闭B-1安全问题。
