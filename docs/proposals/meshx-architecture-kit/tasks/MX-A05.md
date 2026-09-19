# MX-A05 — Flutter Android/iOS 真实文字聊天闭环

前置：A02、A03、A04 的必要交付已验证。若本地已有 Flutter 工程，复用而非再建。

## 目标

手机与现有 Web/Tauri 通过同一真实测试后端互发文本，完成认证、同步、重连和会话恢复，不是只有占位页面。

## 操作

固定经核对的 Flutter/Dart 版本和依赖，设定显式 Android/iOS 支持政策。候选 Android API 24、iOS 15 需和 SDK/插件联合确认。compile/target、JDK/Gradle/Kotlin/Xcode 使用可验证组合，不盲目设置“最新”。升级最低版本必须 ADR。

新增 apps/mobile，分别组织 features、presentation、bootstrap；建立纯 Dart meshx_core、API 边界、meshx_ui 与平台封装所需最小包。只采用一种应用状态管理方案，锁定决策，不同时引入多个框架。

先实现手动配置可信服务器与受控开发环境联网；接入真实登录/刷新/退出、会话目录/历史、WS 认证/同步、文本发送/接收与失败反馈。复用已冻结契约与测试向量，不照着 Vue 页面手写新 API。

实现本地 store、outbox、游标及账号/服务器隔离。UI 消费 Design System token；导航、键盘、安全区按平台适配。网络恢复后补同步，认证/同步未完成不得错误标记全部就绪。

原生接口只在必要位置经 meshx_platform 访问。此阶段不要求完整 LAN discovery、后台推送或每个管理页面。

## 验收

Dart core 一致性测试；flutter analyze/test；Android debug 构建和真实测试后端 smoke；具备 macOS 环境时 iOS simulator build/test。没有运行的平台明确 NOT_RUN/BLOCKED。

跨端测试：Web发→手机收，手机发→Web收；断网/恢复不丢、不重复；应用重启恢复；token过期有界刷新；退出/换账号无泄漏；大字体下输入区可用。

## 非目标与回滚

不删除 Web/Tauri，不要求所有页面完成，不接生产密钥或自动发版。新移动端可独立停用；若后端有兼容性新增，旧客户端路径继续可用。仅编译通过不能把本任务标为完成。
