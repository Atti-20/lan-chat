# ADR 0004：Vue Web/桌面与 Flutter 移动的目标路线

日期：2026-09-08。状态：目标路线已由用户确定；分阶段迁移，尚未完成正式移动端替换。本决策更新 ADR 0002/0003 中“尚未决定长期迁移”的部分，保留其历史验证边界。

## 决策

- Web 使用 Vue，桌面继续 Vue + Tauri；共享 TypeScript 的协议类型、客户端领域规则及平台接口。
- Android / iOS 的目标 UI 使用 Flutter；Dart 实现自己的认证、同步、缓存与交互，原生插件负责系统能力。
- Spring Boot 继续作为统一后端；REST / WebSocket 是跨语言边界。设计 tokens 和组件语义独立于框架。
- 当前 `apps/android / apps/ios` 仍是现有 Capacitor 客户端；在业务、数据迁移和设备验收达标前保留。`apps/flutter-prototype` 不因决策更新就改名成“正式已迁移”。

## 本轮落地

Controller 导出 OpenAPI 结构并生成 TS DTO；WS schema 生成 TS / Dart，两个客户端的发送 payload 已消费同源定义。设计值由中立 JSON 生成 CSS / Dart，组件状态、消息语义与平台差异进入统一规范和检查流程。

工作区主入口为 `apps / services / packages / contracts / tooling / docs`。历史代码进入 `archive`，历史本地资料进入 `docs/archive/local`，构建和证据进入 `output` 或对应模块标准目录；不继续在根保留 PRD/TODO 和重复输出目录。

## 退出旧客户端的验收条件

正式 Flutter 客户端须覆盖登录/安全存储、设备会话与令牌恢复、消息持久队列/连续同步、附件与传输、好友/群/房间/广播、原生通知与权限、后台生命周期、系统返回及键盘。验证数据迁移，保留账号、应用标识和分发升级策略，完成 Android/iPhone 真机和发布构建验收后，再单独移除旧壳。

OpenAPI 不能自动复用 TypeScript 状态机；Flutter 也不自动完成 HarmonyOS、后台收信、推送或全平台发布。协议和设计统一减少重复定义，平台行为仍需要实现和验证。

## 入口

[协议覆盖](../../contracts/README.md)、[跨端设计](../design/README.md)、[当前模块地图](../../ARCHITECTURE.md)、[Flutter 原型](../../apps/flutter-prototype/README.md)。
