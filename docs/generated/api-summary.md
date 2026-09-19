# REST API 导航（自动生成）

来源：[OpenAPI 快照](../../contracts/rest/openapi.json)，由 RestContractTest 的实际 MVC 映射导出；更新：`python3 tooling/workspace.py generate`。
这是可定位的结构快照，是否与当前实现一致由 MVC 契约测试验证；业务权限、动态模型与错误条件见 [覆盖说明](../../contracts/rest/README.md)。

108 个路径、116 个操作、126 个 schema。先按 Controller 定位，再按需展开操作清单；无需默认读取完整 OpenAPI。

| MVC 源码 | 操作数 | 操作清单 |
|---|---:|---|
| [ControlInfoController](../../services/server/src/main/java/com/lanchat/control/api/ControlInfoController.java) | 2 | [展开](api-routes.md#com-lanchat-control-api-controlinfocontroller) |
| [ControlAuditController](../../services/server/src/main/java/com/lanchat/control/audit/ControlAuditController.java) | 2 | [展开](api-routes.md#com-lanchat-control-audit-controlauditcontroller) |
| [ControlDeviceController](../../services/server/src/main/java/com/lanchat/control/device/ControlDeviceController.java) | 6 | [展开](api-routes.md#com-lanchat-control-device-controldevicecontroller) |
| [ControlSessionController](../../services/server/src/main/java/com/lanchat/control/device/ControlSessionController.java) | 1 | [展开](api-routes.md#com-lanchat-control-device-controlsessioncontroller) |
| [OrganizationPolicyController](../../services/server/src/main/java/com/lanchat/control/policy/OrganizationPolicyController.java) | 2 | [展开](api-routes.md#com-lanchat-control-policy-organizationpolicycontroller) |
| [ControlRbacController](../../services/server/src/main/java/com/lanchat/control/rbac/ControlRbacController.java) | 4 | [展开](api-routes.md#com-lanchat-control-rbac-controlrbaccontroller) |
| [AdminController](../../services/server/src/main/java/com/lanchat/controller/AdminController.java) | 11 | [展开](api-routes.md#com-lanchat-controller-admincontroller) |
| [AuthController](../../services/server/src/main/java/com/lanchat/controller/AuthController.java) | 4 | [展开](api-routes.md#com-lanchat-controller-authcontroller) |
| [BroadcastController](../../services/server/src/main/java/com/lanchat/controller/BroadcastController.java) | 14 | [展开](api-routes.md#com-lanchat-controller-broadcastcontroller) |
| [ChatController](../../services/server/src/main/java/com/lanchat/controller/ChatController.java) | 10 | [展开](api-routes.md#com-lanchat-controller-chatcontroller) |
| [FileController](../../services/server/src/main/java/com/lanchat/controller/FileController.java) | 13 | [展开](api-routes.md#com-lanchat-controller-filecontroller) |
| [FriendController](../../services/server/src/main/java/com/lanchat/controller/FriendController.java) | 10 | [展开](api-routes.md#com-lanchat-controller-friendcontroller) |
| [GroupController](../../services/server/src/main/java/com/lanchat/controller/GroupController.java) | 12 | [展开](api-routes.md#com-lanchat-controller-groupcontroller) |
| [NodeController](../../services/server/src/main/java/com/lanchat/controller/NodeController.java) | 3 | [展开](api-routes.md#com-lanchat-controller-nodecontroller) |
| [RecoveryController](../../services/server/src/main/java/com/lanchat/controller/RecoveryController.java) | 7 | [展开](api-routes.md#com-lanchat-controller-recoverycontroller) |
| [TemporaryRoomController](../../services/server/src/main/java/com/lanchat/controller/TemporaryRoomController.java) | 5 | [展开](api-routes.md#com-lanchat-controller-temporaryroomcontroller) |
| [UserController](../../services/server/src/main/java/com/lanchat/controller/UserController.java) | 10 | [展开](api-routes.md#com-lanchat-controller-usercontroller) |
