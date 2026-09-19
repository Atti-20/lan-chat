# ADR 0003：用独立 Flutter 原型验证移动端路线

日期：2026-09-08。状态：原型已实施。长期路线现由 [ADR 0004](0004-client-target-and-contracts.md) 决定，以下保留原型验证依据，正式业务迁移仍未完成。

## 背景

用户关注 WebView 在复杂界面和原生集成上的长期边界，授权实现“真实聊天列表、输入框、关键原生能力”的 Flutter 对照原型。ADR 0002 对现有运行时的保留不构成长期最优结论。

## 实施

新增 `apps/flutter-prototype`，独立标识，不覆盖已有应用。Dart UI / HTTP / WebSocket 直接使用当前 Spring Boot 契约；Kotlin / Swift 通过平台通道实现局域网发现。设计值由 packages/design-tokens 的中立 JSON 生成 CSS / Dart。聊天模型和令牌/同步状态重新用 Dart 编写，没有自动复用 TypeScript 核心。

## 判断

这个切片已证明 Flutter 路线可实施，也暴露了第二套客户端需要单独处理的认证事件、令牌旋转、发送确认、消息补拉和权限生命周期。它支持继续做移动端专项验证，尚不足以据此删除正式 Capacitor 客户端。

当前 Vue 的 MessageThread 对已加载消息直接 v-for，Flutter 使用 ListView.builder。原型组件测试检查1200条数据时仅构建少量可见行；这体现当前实现策略差异。给 Vue 增加列表虚拟化也能减少 DOM 数量，不能将差异直接归因于框架上限。

实测还发现 Android 17 的权限边界不仅涉及扫描，也涉及手动连接局域网节点。原型现已在连接本地地址前申请权限，拒绝时不提交凭据；该问题体现系统适配成本。依据：[Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)。

本轮 Vue 对照运行于桌面 Edge 的窄视口，Flutter 运行于 Android / iOS 模拟器。运行模式、消息加载量和宿主不同，不构成 FPS、冷启动、内存或耗电基准。不能宣称 Flutter 已经比当前 WebView 更快。

## 下一步决策门槛

在同一 Android 真机、同一数据集、发布构建中比较两个移动客户端的长列表、键盘输入、复杂手势、内存和冷启动；再对 iPhone 验证权限、后台切换、原生发现。系统推送、后台传输、通话和离线数据迁移需独立方案与成本清单。通过这些门槛后，才能决定正式移动端迁移及旧客户端退出安排。

本次代码与运行方式：[原型 README](../../apps/flutter-prototype/README.md)。实际证据由 [docs 索引](../README.md) 链接的日期报告保存。

## 依据

- [Flutter 平台通道](https://docs.flutter.dev/platform-integration/platform-channels)：原生能力仍需平台插件或通道实现。
- [Flutter 架构](https://docs.flutter.dev/resources/architectural-overview)：移动端 UI 由 Flutter 引擎渲染。
- [Flutter 性能测试](https://docs.flutter.dev/perf/ui-performance)：性能结论需选择合适运行模式和设备。
- [Capacitor 自定义插件](https://capacitorjs.com/docs/plugins/creating-plugins)：现有路线也可扩展原生 API，原生能力本身不构成强制迁移理由。
