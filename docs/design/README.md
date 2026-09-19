# MeshX 跨端设计体系

更新：2026-09-09（MX-A04）。目标客户端为 Web / Vue、桌面 / Vue + Tauri、移动 / Flutter；现有 Capacitor 客户端在迁移验收完成前继续保留。统一产品语言、语义颜色和状态规则，各平台采用适合其输入方式的组件实现。

## 事实入口

| 层次 | 维护入口 | 消费方式 |
|---|---|---|
| 颜色、字号、间距、圆角、动效参数 | [tokens.json](../../packages/design-tokens/tokens.json) | 中立 JSON；CSS 与 Dart 均由此生成 |
| 组件状态与平台交互 | [components.json](../../packages/design-tokens/components.json) | 命名、状态、token 引用和消息协议事件均受检查 |
| Vue 控件与布局 | [Web 实现规范](../../apps/web/DESIGN.md) | `main.css`、`UiIcon.vue` 与业务组件 |
| Flutter 主题 | [theme.dart](../../apps/flutter-prototype/lib/ui/theme.dart) | 消费生成的 `tokens.g.dart`，保持系统字体与原生返回/键盘 |
| 信息架构与导航 | [跨端信息架构](information-architecture.md) | 四个普通用户目的地、账户层、返回和深链语义统一，布局按平台适配 |
| 消息状态语义 | [WebSocket 协议](../../contracts/websocket/README.md) | 以 ACK、幂等 ID 和连续序列驱动，不根据动画或网络写入成功推断 |
| 验证方法 | [验证矩阵](validation.md) | 机器生成检查、对比度、组件行为、实际渲染分层验证 |

## 统一内容

保持现有蓝色交互、系统字体、浅灰画布和浅深色主题。文字、状态和颜色按语义使用：`ink` 是正文，`ink-soft` 是辅助信息，`action-bg/on-accent` 是实心主操作，`danger` 是错误文字。不能只用红绿颜色表示成功或失败。

A04 六类规范为 Button、Input、UserAvatar、ConversationItem、MessageBubble、ConnectionStatus；[组件规范](component-specs/README.md) 标注已实现/组合/未实现/不适用状态。原有 composer/collection/dialog 目录保留，本轮不扩成完整组件库。

消息的 `sending / sent / failed / recalled / burned` 使用同一语义。`CHAT_ACK` 表示服务端已经持久化；它不表示接收者已读。当前 `CHAT_READ` 通知同步阅读者自己的设备，不可将其展示成所有参与者可见的读回执。提及回执详情仍通过服务端鉴权接口取得。

## 转换规则

`tokens.json` 使用显式类型：`color / dimension / number / duration / cubicBezier / fontFamily / webOnly`。source schemaVersion=2；Primitive / Semantic / Component 分层，semantic/component 每项显式给出 light/dark 引用；CSS 两个主题完整生成。更改类型或加入新单位时生成器必须先支持，不能默默丢弃。

CSS 保留 `px / rem / ms` 等单位。Dart 中 `px` 转为逻辑像素，`rem` 以 16 的基础字号换算，文本仍交给 Flutter 系统缩放。RGBA 按四舍五入转换到 8 位 ARGB。颜色、尺寸、行高、时间、曲线与字体列表都生成，避免继续维护“只抄几个颜色”的第二份来源。

CSS 的阴影、渐变、组合边框属于 `webOnly`，不会生成虚假的 Flutter 材质。Flutter 使用语义面板、边线与 Material 层次；字体默认跟随系统，CSS 的 `-apple-system / BlinkMacSystemFont` 不是可直接使用的 Flutter 字体资源名。新材质需分别实现并实际渲染验收。

## 平台交互

| 场景 | Web / 桌面 | Flutter 移动 |
|---|---|---|
| 导航 | 宽屏多栏，窄屏逐层进入 | 单栏层级、系统返回、安全区 |
| 操作 | 右键、快捷键、hover、可见键盘焦点 | 触摸、长按、底部弹层；触摸区域至少 44 逻辑像素 |
| 输入 | Enter 发送、Shift+Enter 换行，组合输入中不发送 | 多行、系统键盘、发送按钮始终可触达 |
| 弹层 | 焦点约束、Esc、关闭后恢复焦点 | 系统返回、键盘与拖动关闭协同；危险操作显式确认 |
| 长列表 | 根据真实数据量决定虚拟化 | 懒构建列表；稳定消息标识与滚动锚点 |
| 辅助偏好 | 文字缩放、减少动态效果、高对比度 | 系统文字缩放、减少动态效果、屏幕阅读器 |

统一体系不要求每个像素相同；消息含义、操作名称、状态恢复与权限边界必须一致。原型尚未覆盖的文件传输、广播全流程、持久离线数据、推送和后台能力，不能因为存在 token 就标记为已迁移。

一级产品入口、账户层和返回规则由 [跨端信息架构](information-architecture.md) 维护。Web / 桌面使用左侧主导航，Flutter 手机使用底部主导航；两者共享“消息 / 联系人 / 群聊 / 广播”的产品结构，不把移动端 P0 入口收进“更多”菜单。

## 修改流程

先修改中立 JSON 和对应规则，再运行 `python3 tooling/workspace.py generate`。不得直接修改 CSS / Dart 生成文件。执行 `./tooling/verify contracts`、`./tooling/verify web` 与 `./tooling/verify flutter`；改变可见规则时补齐浅深色、宽窄屏与键盘渲染证据。

## MX-A04 输入与边界

[Token source/schema/转换说明](../../packages/design-tokens/README.md) · [平台、主题、字体与图标](platform-and-theme.md) · [设计债](debt/README.md) · [A04 handoff](../tasks/active/MX-A04.md)。新 UI 优先消费 `--mx-*` 语义；87 个旧 alias 保留。已迁移 UserAvatar、ConversationSidebar、MessageThread 的代表样式，Button/Input 公共类也逐步接入；无 Flutter 产品页面。
