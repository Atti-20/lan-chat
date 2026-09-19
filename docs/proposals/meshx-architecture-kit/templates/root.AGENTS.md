# MeshX — 仓库工作规则（候选模板，核对后合并）

MeshX 是 LAN-first 私有协作系统。路线：Spring Boot；Vue/Tauri；Flutter Android/iOS。
不要引入 Capacitor，不承诺 Flutter 官方直接支持 HarmonyOS。

## 开始任务

先确认分支、HEAD、git status，保留未提交/未追踪的用户改动。
定位并读取目标路径适用的根/祖先/模块 AGENTS.md 和 override；不能假设已自动加载所有子目录。
按需读取 docs/ai/INDEX.md、当前任务卡与交接；必要时看相关 ADR/契约。
从相关目录搜索起步，不默认扫描整个仓库、历史任务、lock 或生成 SDK；需要时扩大范围。

## 入口

ARCHITECTURE.md：当前架构和依赖方向。
docs/ai/INDEX.md：任务定位、模块规则与验证入口。
docs/tasks/active/：当前任务与跨会话交接。
实际源码/工具链版本以当前配置为准；文档冲突必须记录并核对。

## 不可破坏的边界

TS core 不依赖 Vue/Tauri/DOM 存储；Dart core 不依赖 Flutter/原生插件。
UI/应用依赖核心和 ports；adapters 实现 ports；bootstrap 负责装配。
TS/Dart 不强行共享源码；共享契约、测试向量和设计 token。
服务端始终负责授权与关键业务校验，客户端不能替代。
生成代码不手改；已采用的契约/token 只有一个编辑源。
平台差异留在 adapters/bootstrap/适配 UI，不散布到核心业务规则。

## 安全与变更

一次只完成当前任务；不顺便升级主依赖、更换包管理器或全仓重命名。
不得擅改 minSdk、iOS 最低版本、应用 identifier、协议版本、数据库/存储标识。
不得关闭 TLS/ATS/CSP/鉴权、安全扫描或必要测试来让构建通过。
不记录或提交 secrets、签名材料、生产凭据、真实聊天内容。
不清除用户数据，不 reset/clean 用户工作树，不自动推送/合并/发布。

## 验证与交接

运行 docs/runbooks/ 中当前范围真实存在的验证入口；新脚本先验证再宣称可用。
共享契约、核心或 token 变化需要检查所有受影响消费者。
区分 PASS、FAIL、BLOCKED、NOT_RUN；没有运行不得声称通过。
结束时给出变更文件、实际命令/结果、未验证项、风险与下一步。
多会话任务更新自己的 docs/tasks/active/<task-id>.md，不保存聊天流水。
