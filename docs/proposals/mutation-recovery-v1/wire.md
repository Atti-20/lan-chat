# REST / WS 与记录提案 — meshx.mutation-recovery/1

状态READY_FOR_APPROVAL；**以下端点、字段、表和事件均未实现/未注册到生产schema**。只供1I批准后一次性实现，不能作为当前服务可调用API。业务语义见[设计](design.md)，对象结构见[JSON Schema proposal](mutation-record.schema.json)。保留现有REST Result、Bearer、Cookie、CHAT_ACK、SYNC语义，不修改原WS事件含义。

## W01 明确能力协商

新增认证端点 `GET /api/v1/chat/recovery/capabilities`。目标成功为HTTP200和`Result.code=200`，data明确包含：

```json
{"capability":"meshx.mutation-recovery","versions":[1],"recordVersion":1,"recoveryRequiredForSafeMode":true,"legacyFullRebuildCertified":false,"retentionSeconds":2592000,"sessionTtlSeconds":900,"maxPageSize":200,"pollIntervalSeconds":5,"staleAfterSeconds":30}
```

只有明确兼容的version1值可进入协商；404为UNSUPPORTED，401按原登录恢复，403为无该能力权限，网络错误/未知字段缺失为不能确定支持，均不放行安全模式。legacyFullRebuildCertified固定false，未来开启需另行评审而不是动态猜测。服务端必须先完成覆盖所有写入口、回填/epoch及自检才广告versions:[1]。

## W02 REST操作冻结

全部绑定鉴权用户、canonical origin、epoch和session；请求不能指定另一个userId。成功沿用`Result<T>`；新端点对错误使用明确HTTP状态和`Result.code`同值，并在`data.reason`给稳定枚举；客户端先查HTTP再查Result，不改变旧端点的错误语义。响应带`Cache-Control: no-store`，日志不记录正文或session token。

| 方法 / path（基于/api/v1） | 请求 | 成功data |
|---|---|---|
| POST `/chat/recovery/sessions` | `{protocolVersion:1, mode:"resume"或"rebuild", cursor?:{streamEpoch,position}}`；resume必须有cursor | `{recoveryId,streamEpoch,mode,startCursor,floor,latest,snapshotId?,snapshotBoundary?,expiresAt}`；resume的start=C；rebuild的start=H，snapshot字段必有；不会静默忽略无效cursor |
| GET `/chat/recovery/sessions/{id}/snapshot` | `pageToken`（首省略），`limit`1..200 | `{snapshotId,boundary:H,items,nextPageToken,snapshotComplete}`；仅rebuild。items的kind为CONVERSATION或MESSAGE，每页属同manifest。最后next=null且complete=true |
| POST `/chat/recovery/sessions/{id}/cut` | `{}` | `{through:F,floor,streamEpoch}`，固定本轮追赶上界；不得降低已给出的F。pin保留区间至session过期 |
| GET `/chat/recovery/sessions/{id}/mutations` | `after`十进制string，`through`必须为该session当前cut，`limit`1..200 | `{records,fromExclusive,through,nextCursor,hasMore,floor,latest,streamEpoch}`；严格升序连续，不按timestamp排序；最后nextCursor=through且hasMore=false |
| POST `/chat/recovery/sessions/{id}/ready` | `{appliedCursor:F,snapshotComplete:true/false}`；rebuild必须true | `{ready,acceptedCursor:F,latest:L,streamEpoch,receiptId?}`；L=F且session完整时ready=true，否则ready=false继续cut；不是让客户端用L覆盖F |
| DELETE `/chat/recovery/sessions/{id}` | 无body | 204；显式释放pin/manifest。过期服务端自行释放 |

session ID和pageToken由服务端生成至少128bit不可预测opaque值，绑定账号和恢复上下文；不是替代Bearer的授权凭据，不跨设备共享。并发设备session独立。POST重试客户端可携带`Idempotency-Key`（仅本新端点），同owner同输入复用结果，差异输入409；密钥不成为cursor。ready不存用户级消费游标，不驱动提前日志清理。

resume时首次请求cursor>latest、过期/epoch变动返回409而非默默rebuild；客户端先quarantine再显式mode=rebuild。空流H=F=0合法，必须仍完成权威空目录和ready。非空区间(C,F]却返回空页、next不前进、漏号、重复cursor异event、页范围不匹配均PROTOCOL_ERROR，不能跳至latest。

限制：snapshot manifest包含当前账号在S下全部可读目录与全部保留消息ID，而不是最新50/200条；每个CONVERSATION携带`conversationId, accessVersion, readAllowed, sendAllowed, messageSequenceAtH`。MESSAGE携带`conversationId,messageId,objectVersion,state,messageSequence,content?`，NORMAL可以携带当前正文，终态content必须null/缺失；页禁止发送权限检查失败的正文。NORMAL之外仅允许RECALLED/BURNED/UNAVAILABLE；未知协商状态必须fail closed。分页中被删除的manifest ID返回UNAVAILABLE+对应版本，不能只省略并留下旧缓存。

1I实现补充：NORMAL消息同时携带`details`，包括`fromUserId, clientMsgId?, contentType, createTime, isBurn, burnDuration?, replyToId?, mentionUserIds?`，与content在同一次持锁当前读取中取得。它们供无旧缓存客户端重建既有消息模型，不要求额外读取未经版本验证的历史来猜作者、类型或时间。createTime沿用现有消息API日期语义，排序仍以messageSequence为准。终态及CONVERSATION失权投影不带details；NORMAL缺作者、类型、创建时间或有效焚毁标记时失败，不补虚构默认值。此加法字段仅在未广告的候选接口中启用，须同步生成和审阅REST契约。

manifest顺序冻结为CONVERSATION先、MESSAGE后，每类按conversationId/messageSequence/messageId稳定排序；opaque token隐含位置，客户端不得自制offset跳页。page重试返回同一ID集合，但当前正文可被更高版本终态替代；这是明确允许的单调更新，不保证返回旧正文快照。

上述同一ID集合约束适用于仍有读权限的消息投影。失权时按前述“最小失效状态”返回`kind:CONVERSATION, conversationId, accessVersion, readAllowed:false, sendAllowed:false`，不返回消息ID/正文，也不伪造该消息的全局UNAVAILABLE版本；同页多个原消息位置可以重复返回这一幂等会话失效项。两端以accessVersion撤销整个会话，而不能因没有MESSAGE项保留旧正文。服务端分页位置仍遍历原manifest，记录连续已服务位置；重建READY同时要求客户端声明snapshotComplete与服务端实际服务到末页，空manifest也必须显式读取首个空页。

### 新增消息与局部快照

v1新增CHAT_DELIVER/SYNC仅作为候选和调度信号。为了确认新消息objectVersion，新增 `POST /chat/recovery/sessions` 可使用`mode:"rebuild-conversations",conversationIds:[...]`，一次最多100，服务端逐个验权并用同一S/H创建完整该会话manifest。其余流程同rebuild；ready前必须把该区间全部用户级mutation也应用到其余本地缓存。输入缺失/越权cid以CONVERSATION_ACCESS_REVOKED的最小item说明，无正文。

新目录grant触发这个模式；批量新消息可按会话合并请求。这是正确性优先的首版方案，可能高成本，不承诺现有性能预算已满足；1I必须测大历史并优化为经过同样边界证明的增量manifest，若要改变响应覆盖定义须返回设计评审，不能靠未验证v1正文绕过。消息cursor只能推进到快照已覆盖的messageSequenceAtH或既有连续接收规则允许的位置；mutation cursor不参与计算。

D03允许局部恢复退回完整账号rebuild。当前候选在请求cid缺少该账号的持久accessVersion时采用此回退：响应`mode:rebuild`，返回整个账号权威清单，不能编造失权版本。调用端必须以**响应mode**决定替换范围；收到rebuild就替换整个账号generation，不能按原请求的conversationIds作局部merge。已有持久失权版本的cid仍以rebuild-conversations返回最小失效项，即使目录或会话实体已不存在。Idempotency-Key绑定排序去重后的原请求cid集合，回退不改变原请求身份。

## W03 错误枚举

| HTTP / reason | 客户端动作 |
|---|---|
| 401 AUTH_REQUIRED | 原登录恢复；quarantine，不混用新账号凭据 |
| 403 RECOVERY_FORBIDDEN | 阻断该能力，不降级旧聊天 |
| 409 CURSOR_EXPIRED / CURSOR_AHEAD / STREAM_RESET | rebuildRequired=true，显式返回floor/latest/当前epoch；旧正文隔离，重建 |
| 409 PROTOCOL_VERSION_UNSUPPORTED | 升级提示，不能宣称safe |
| 410 SESSION_EXPIRED / SNAPSHOT_EXPIRED | rebuildRequired=true；丢弃staging，不发布cursor |
| 400 INVALID_CURSOR / INVALID_PAGE_TOKEN / RANGE_MISMATCH | 不重试成跳游标，报告协议/调用错误 |
| 503 RECOVERY_CAPACITY / RECOVERY_UNAVAILABLE | 有限退避，保留隔离；不会返回partial-complete |

503不能伪装旧服务器“无变更”；错误体不含他人身份/消息数据。被移出群的用户对自己的mutation流仍返回最小REVOKED记录，不因当前群canAccess=false而过滤掉该记录。非法他人sessionId按404返回，不泄露存在性。

## W04 WS只提供加速提示

保持`version=1`信封与现有事件不动。新增可选 `RECOVERY_SUBSCRIBE`（client→server）payload `{capability:"meshx.mutation-recovery",protocolVersion:1,streamEpoch}`；只在AUTH_OK后且该用户REST能力支持时订阅，server返回`RECOVERY_SUBSCRIBED`含同version/epoch。新订阅通过后，服务端可发 `MUTATION_AVAILABLE` payload `{streamEpoch,latestCursor}`，不含正文/对象详情，不直接推进本地cursor。

未知旧客户端不订阅，不要求理解新事件；不能将老CHAT_RECALL/BURN当持久mutation record或以其sequence推进mutation cursor。新客户端收到老实时终态提示可立即隔离目标并拉持久流，持久事实校验成功后才提交。漏提示、重复、乱序、多实例Redis失败均靠REST轮询恢复。订阅不成功但REST可用仍能安全恢复，延迟按设计轮询界限；不能将REST失败时的WS在线状态当ONLINE_SAFE。

## W05 结构与语义验证边界

schema拒绝敏感附加字段，精确约束类型需要的message/object或access字段，READ不在枚举。cursor/objectVersion/accessVersion范围上限需要额外十进制BigInt验证；JSON Schema的字符串模式只限制规范十进制和最多19位。committedAt为事务内写入的服务端UTC ISO8601诊断时间，仅在提交后对外可见；不是数据库物理commit瞬间的精确时间，也不用于排序/幂等。

事件ID、相同版本状态一致性、owner绑定、缺口、snapshot覆盖、事务回滚不是结构schema能证明的；对应目标向量及1I真实测试必须覆盖。严格recordVersion=1下未来未知type不得被旧safe客户端忽略并推进cursor，必须隔离并提示升级；未来扩大状态集须协商新recordVersion。
