# MeshX 架构地图（候选模板，按当前实现更新）

## 已确定路线

Spring Boot 服务端；Vue Web/桌面 + Tauri 壳；Flutter Android/iOS。
HarmonyOS 独立评估，不计入本阶段已支持平台。

## 当前事实

此处填写当前实际路径与入口，不把计划目录写成已经存在。
当前远程审查参考：frontend/、apps/desktop/、根 pom.xml + src/。
完整事实见 docs/architecture/current-state.md。

## 依赖方向

UI/应用 → domain/usecase/ports；adapter → ports；bootstrap 创建并注入。
core 不能反向依赖平台实现、UI 框架或环境 globals。
TS 与 Dart 分别实现，用同源契约、测试向量及设计 token 对齐。

## 唯一编辑源

列出当前采用的 REST、WS、共享模型、token 的源→生成物→消费者。
尚未建立的源写“计划”，不得指向不存在的文件并假称权威。
服务端代码仍须运行契约/授权测试；发现文档与代码冲突先核对。

## 优先不变项

已发布协议、应用身份、数据库/缓存标识和用户数据保持兼容。
认证→同步→在线顺序、消息幂等/去重、账号服务器隔离必须受测试保护。
局域网能力有平台差异；iOS后台不能承诺无限运行。

## 定位与验证

模块/任务入口：docs/ai/INDEX.md。
精确命令：docs/runbooks/。
架构取舍：docs/adr/，只按需读相关决策。
