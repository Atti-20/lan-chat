# REST 操作清单（自动生成）

来源：[OpenAPI 快照](../../contracts/rest/openapi.json)，由 RestContractTest 的实际 MVC 映射导出；更新：`python3 tooling/workspace.py generate`。
这是可定位的结构快照，是否与当前实现一致由 MVC 契约测试验证；业务权限、动态模型与错误条件见 [覆盖说明](../../contracts/rest/README.md)。

认证列仅表示网关入口；公开入口的刷新/退出仍可能要求 Cookie 或请求体令牌，业务权限继续由服务代码保证。

<a id="com-lanchat-control-api-controlinfocontroller"></a>
## ControlInfoController

源码：[com.lanchat.control.api.ControlInfoController](../../services/server/src/main/java/com/lanchat/control/api/ControlInfoController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v2/control/health` | `health / health` | 公开入口 |
| GET | `/api/v2/control/info` | `info / info` | 公开入口 |

<a id="com-lanchat-control-audit-controlauditcontroller"></a>
## ControlAuditController

源码：[com.lanchat.control.audit.ControlAuditController](../../services/server/src/main/java/com/lanchat/control/audit/ControlAuditController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v1/admin/audit` | `recent / recent` | bearerAuth |
| GET | `/api/v2/control/audit` | `recent_1 / recent` | bearerAuth |

<a id="com-lanchat-control-device-controldevicecontroller"></a>
## ControlDeviceController

源码：[com.lanchat.control.device.ControlDeviceController](../../services/server/src/main/java/com/lanchat/control/device/ControlDeviceController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v2/control/devices` | `list_1 / list` | bearerAuth |
| POST | `/api/v2/control/devices/registrations` | `register / register` | bearerAuth |
| GET | `/api/v2/control/devices/revocations` | `revocations / revocations` | bearerAuth |
| POST | `/api/v2/control/devices/{deviceId}/approve` | `approve / approve` | bearerAuth |
| POST | `/api/v2/control/devices/{deviceId}/reject` | `reject / reject` | bearerAuth |
| POST | `/api/v2/control/devices/{deviceId}/revoke` | `revoke / revoke` | bearerAuth |

<a id="com-lanchat-control-device-controlsessioncontroller"></a>
## ControlSessionController

源码：[com.lanchat.control.device.ControlSessionController](../../services/server/src/main/java/com/lanchat/control/device/ControlSessionController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v2/control/session` | `current / current` | bearerAuth |

<a id="com-lanchat-control-policy-organizationpolicycontroller"></a>
## OrganizationPolicyController

源码：[com.lanchat.control.policy.OrganizationPolicyController](../../services/server/src/main/java/com/lanchat/control/policy/OrganizationPolicyController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v2/control/policy` | `current_1 / current` | bearerAuth |
| PUT | `/api/v2/control/policy/device-identity` | `updateDevicePolicy / updateDevicePolicy` | bearerAuth |

<a id="com-lanchat-control-rbac-controlrbaccontroller"></a>
## ControlRbacController

源码：[com.lanchat.control.rbac.ControlRbacController](../../services/server/src/main/java/com/lanchat/control/rbac/ControlRbacController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v2/control/rbac/members/{userId}/roles` | `memberRoles / memberRoles` | bearerAuth |
| PUT | `/api/v2/control/rbac/members/{userId}/roles/{roleCode}` | `changeRole / changeRole` | bearerAuth |
| POST | `/api/v2/control/rbac/owner-transfer` | `transferOwner_1 / transferOwner` | bearerAuth |
| GET | `/api/v2/control/rbac/roles` | `roles / roles` | bearerAuth |

<a id="com-lanchat-controller-admincontroller"></a>
## AdminController

源码：[com.lanchat.controller.AdminController](../../services/server/src/main/java/com/lanchat/controller/AdminController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v1/admin/diagnostics` | `diagnostics / diagnostics` | bearerAuth |
| GET | `/api/v1/admin/logs` | `runtimeLogs / runtimeLogs` | bearerAuth |
| GET | `/api/v1/admin/logs/export` | `exportRuntimeLog / exportRuntimeLog` | bearerAuth |
| POST | `/api/v1/admin/user/mute` | `muteUserGlobally / muteUserGlobally` | bearerAuth |
| POST | `/api/v1/admin/user/status` | `changUserStatus / changUserStatus` | bearerAuth |
| DELETE | `/api/v1/admin/user/{userId}` | `deleteUser / deleteUser` | bearerAuth |
| PUT | `/api/v1/admin/user/{userId}/broadcast-permission` | `setBroadcastPermission / setBroadcastPermission` | bearerAuth |
| PUT | `/api/v1/admin/user/{userId}/password` | `resetUserPassword / resetUserPassword` | bearerAuth |
| POST | `/api/v1/admin/user/{userId}/physical-erasure` | `physicallyEraseUser / physicallyEraseUser` | bearerAuth |
| GET | `/api/v1/admin/users` | `listAllUsers / listAllUsers` | bearerAuth |
| POST | `/api/v1/admin/users` | `createUser / createUser` | bearerAuth |

<a id="com-lanchat-controller-authcontroller"></a>
## AuthController

源码：[com.lanchat.controller.AuthController](../../services/server/src/main/java/com/lanchat/controller/AuthController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| POST | `/api/v1/auth/login` | `login / login` | 公开入口 |
| POST | `/api/v1/auth/logout` | `logout / logout` | 公开入口 |
| POST | `/api/v1/auth/refresh` | `refreshToken / refreshToken` | 公开入口 |
| POST | `/api/v1/auth/register` | `register_1 / register` | 公开入口 |

<a id="com-lanchat-controller-broadcastcontroller"></a>
## BroadcastController

源码：[com.lanchat.controller.BroadcastController](../../services/server/src/main/java/com/lanchat/controller/BroadcastController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v1/broadcast` | `list / list` | bearerAuth |
| POST | `/api/v1/broadcast` | `create / create` | bearerAuth |
| GET | `/api/v1/broadcast/pending` | `pending / pending` | bearerAuth |
| GET | `/api/v1/broadcast/{broadcastId}` | `detail / detail` | bearerAuth |
| DELETE | `/api/v1/broadcast/{broadcastId}` | `delete / delete` | bearerAuth |
| POST | `/api/v1/broadcast/{broadcastId}/cancel` | `cancel / cancel` | bearerAuth |
| POST | `/api/v1/broadcast/{broadcastId}/complete` | `complete / complete` | bearerAuth |
| POST | `/api/v1/broadcast/{broadcastId}/confirm` | `confirm / confirm` | bearerAuth |
| GET | `/api/v1/broadcast/{broadcastId}/export.xlsx` | `exportExcel / exportExcel` | bearerAuth |
| GET | `/api/v1/broadcast/{broadcastId}/receivers` | `receivers / receivers` | bearerAuth |
| PATCH | `/api/v1/broadcast/{broadcastId}/receivers` | `updateTargets / updateTargets` | bearerAuth |
| POST | `/api/v1/broadcast/{broadcastId}/receivers/{receiverUserId}/remind` | `remind / remind` | bearerAuth |
| GET | `/api/v1/broadcast/{broadcastId}/stats` | `stats / stats` | bearerAuth |
| POST | `/api/v1/broadcast/{broadcastId}/view` | `view / view` | bearerAuth |

<a id="com-lanchat-controller-chatcontroller"></a>
## ChatController

源码：[com.lanchat.controller.ChatController](../../services/server/src/main/java/com/lanchat/controller/ChatController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| POST | `/api/v1/chat/burn` | `burnMessage / burnMessage` | bearerAuth |
| PUT | `/api/v1/chat/conversation/read` | `markConversationAsRead / markConversationAsRead` | bearerAuth |
| GET | `/api/v1/chat/conversations` | `getConversationSummaries / getConversationSummaries` | bearerAuth |
| GET | `/api/v1/chat/history` | `getConversationHistory / getConversationHistory` | bearerAuth |
| GET | `/api/v1/chat/history/group` | `getGroupHistory / getGroupHistory` | bearerAuth |
| GET | `/api/v1/chat/history/private` | `getPrivateHistory / getPrivateHistory` | bearerAuth |
| GET | `/api/v1/chat/messages/{messageId}/mention-receipts` | `getMentionReadReceipt / getMentionReadReceipt` | bearerAuth |
| PUT | `/api/v1/chat/read` | `markAsRead / markAsRead` | bearerAuth |
| POST | `/api/v1/chat/recall` | `recallMessage / recallMessage` | bearerAuth |
| GET | `/api/v1/chat/search` | `searchMessages / searchMessages` | bearerAuth |

<a id="com-lanchat-controller-filecontroller"></a>
## FileController

源码：[com.lanchat.controller.FileController](../../services/server/src/main/java/com/lanchat/controller/FileController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| POST | `/api/v1/file/avatar` | `uploadAvatar / uploadAvatar` | bearerAuth |
| POST | `/api/v1/file/broadcast-image` | `uploadBroadcastImage / uploadBroadcastImage` | bearerAuth |
| POST | `/api/v1/file/check` | `checkFile / checkFile` | bearerAuth |
| GET | `/api/v1/file/content/{fileName}` | `getFileContent / getFileContent` | bearerAuth |
| POST | `/api/v1/file/preview-url` | `generatePreviewUrl / generatePreviewUrl` | bearerAuth |
| GET | `/api/v1/file/preview/{signToken}` | `previewFile / previewFile` | 公开入口 |
| GET | `/api/v1/file/preview/{signToken}/{displayName}` | `previewFileWithDisplayName / previewFileWithDisplayName` | 公开入口 |
| POST | `/api/v1/file/upload` | `upload / upload` | bearerAuth |
| POST | `/api/v1/file/uploads` | `initializeUpload / initializeUpload` | bearerAuth |
| GET | `/api/v1/file/uploads/{uploadId}` | `getUpload / getUpload` | bearerAuth |
| DELETE | `/api/v1/file/uploads/{uploadId}` | `cancelUpload / cancelUpload` | bearerAuth |
| POST | `/api/v1/file/uploads/{uploadId}/complete` | `completeUpload / completeUpload` | bearerAuth |
| PUT | `/api/v1/file/uploads/{uploadId}/parts/{partNumber}` | `uploadPart / uploadPart` | bearerAuth |

<a id="com-lanchat-controller-friendcontroller"></a>
## FriendController

源码：[com.lanchat.controller.FriendController](../../services/server/src/main/java/com/lanchat/controller/FriendController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| POST | `/api/v1/friend/handle` | `handleFriendRequest / handleFriendRequest` | bearerAuth |
| GET | `/api/v1/friend/list` | `getFriendList / getFriendList` | bearerAuth |
| POST | `/api/v1/friend/request` | `sendFriendRequest / sendFriendRequest` | bearerAuth |
| GET | `/api/v1/friend/requests` | `getFriendRequests / getFriendRequests` | bearerAuth |
| DELETE | `/api/v1/friend/{friendId}` | `deleteFriend / deleteFriend` | bearerAuth |
| PUT | `/api/v1/friend/{friendId}/block` | `toggleBlock / toggleBlock` | bearerAuth |
| PUT | `/api/v1/friend/{friendId}/group` | `setGroup / setGroup` | bearerAuth |
| PUT | `/api/v1/friend/{friendId}/mute` | `toggleMute / toggleMute` | bearerAuth |
| PUT | `/api/v1/friend/{friendId}/pin` | `togglePin / togglePin` | bearerAuth |
| PUT | `/api/v1/friend/{friendId}/remark` | `setRemark / setRemark` | bearerAuth |

<a id="com-lanchat-controller-groupcontroller"></a>
## GroupController

源码：[com.lanchat.controller.GroupController](../../services/server/src/main/java/com/lanchat/controller/GroupController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| POST | `/api/v1/group` | `createGroup / createGroup` | bearerAuth |
| GET | `/api/v1/group/my` | `getMyGroups / getMyGroups` | bearerAuth |
| GET | `/api/v1/group/{groupId}` | `getGroupInfo / getGroupInfo` | bearerAuth |
| PUT | `/api/v1/group/{groupId}` | `updateGroup / updateGroup` | bearerAuth |
| PUT | `/api/v1/group/{groupId}/admin` | `setAdmin / setAdmin` | bearerAuth |
| DELETE | `/api/v1/group/{groupId}/dissolve` | `dissolveGroup / dissolveGroup` | bearerAuth |
| POST | `/api/v1/group/{groupId}/leave` | `leaveGroup / leaveGroup` | bearerAuth |
| GET | `/api/v1/group/{groupId}/members` | `getGroupMembers / getGroupMembers` | bearerAuth |
| POST | `/api/v1/group/{groupId}/members` | `addMembers / addMembers` | bearerAuth |
| DELETE | `/api/v1/group/{groupId}/members/{memberId}` | `removeMember / removeMember` | bearerAuth |
| PUT | `/api/v1/group/{groupId}/mute` | `muteMember / muteMember` | bearerAuth |
| PUT | `/api/v1/group/{groupId}/transfer` | `transferOwner / transferOwner` | bearerAuth |

<a id="com-lanchat-controller-nodecontroller"></a>
## NodeController

源码：[com.lanchat.controller.NodeController](../../services/server/src/main/java/com/lanchat/controller/NodeController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v1/node/discoveries` | `discoveries / discoveries` | 公开入口 |
| GET | `/api/v1/node/health` | `health_1 / health` | 公开入口 |
| GET | `/api/v1/node/info` | `info_1 / info` | 公开入口 |

<a id="com-lanchat-controller-recoverycontroller"></a>
## RecoveryController

源码：[com.lanchat.controller.RecoveryController](../../services/server/src/main/java/com/lanchat/controller/RecoveryController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v1/chat/recovery/capabilities` | `capabilities / capabilities` | bearerAuth |
| POST | `/api/v1/chat/recovery/sessions` | `open / open` | bearerAuth |
| DELETE | `/api/v1/chat/recovery/sessions/{id}` | `release / release` | bearerAuth |
| POST | `/api/v1/chat/recovery/sessions/{id}/cut` | `cut / cut` | bearerAuth |
| GET | `/api/v1/chat/recovery/sessions/{id}/mutations` | `mutations / mutations` | bearerAuth |
| POST | `/api/v1/chat/recovery/sessions/{id}/ready` | `ready / ready` | bearerAuth |
| GET | `/api/v1/chat/recovery/sessions/{id}/snapshot` | `snapshot / snapshot` | bearerAuth |

<a id="com-lanchat-controller-temporaryroomcontroller"></a>
## TemporaryRoomController

源码：[com.lanchat.controller.TemporaryRoomController](../../services/server/src/main/java/com/lanchat/controller/TemporaryRoomController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v1/rooms` | `getMyRooms / getMyRooms` | bearerAuth |
| POST | `/api/v1/rooms` | `createRoom / createRoom` | bearerAuth |
| POST | `/api/v1/rooms/join` | `joinRoom / joinRoom` | bearerAuth |
| GET | `/api/v1/rooms/{roomId}` | `getRoom / getRoom` | bearerAuth |
| POST | `/api/v1/rooms/{roomId}/leave` | `leaveRoom / leaveRoom` | bearerAuth |

<a id="com-lanchat-controller-usercontroller"></a>
## UserController

源码：[com.lanchat.controller.UserController](../../services/server/src/main/java/com/lanchat/controller/UserController.java)

| 方法 | 路径 | operationId / Java 方法 | 网关认证 |
|---|---|---|---|
| GET | `/api/v1/user/devices` | `getDevices / getDevices` | bearerAuth |
| DELETE | `/api/v1/user/devices/{deviceId}` | `logoutDevice / logoutDevice` | bearerAuth |
| GET | `/api/v1/user/info` | `getCurrentUserInfo / getCurrentUserInfo` | bearerAuth |
| PUT | `/api/v1/user/mute-period` | `setMutePeriod / setMutePeriod` | bearerAuth |
| GET | `/api/v1/user/mute-status` | `getMuteStatus / getMuteStatus` | bearerAuth |
| GET | `/api/v1/user/online` | `getOnlineUsers / getOnlineUsers` | bearerAuth |
| PUT | `/api/v1/user/password` | `changePassword / changePassword` | bearerAuth |
| PUT | `/api/v1/user/profile` | `updateProfile / updateProfile` | bearerAuth |
| GET | `/api/v1/user/search` | `searchUsers / searchUsers` | bearerAuth |
| GET | `/api/v1/user/{id}` | `getUserInfo / getUserInfo` | bearerAuth |
