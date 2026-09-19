# 共享设计变量

- `tokens.json` 是跨平台的唯一值来源，`components.json` 维护状态、token 引用与平台差异。先读 [跨端设计体系](../../docs/design/README.md) 与 [Web 规范](../../apps/web/DESIGN.md)。
- `python3 tooling/workspace.py generate` 生成 CSS 和 Flutter `tokens.g.dart`；不手工修改生成文件。新增类型/单位先完善生成器，webOnly 材质不伪称可直接转 Dart。
- Vue 主 CSS 只导入一次 tokens.css；Flutter Theme 使用生成色板/尺寸，系统字体、键盘、返回和材质按平台实现。
- 设计值或组件规范修改后执行 `./tooling/verify design`（结构、引用、双主题、确定性与漂移负例），并按消费者影响执行 web/flutter；协议或生成契约变化再执行 contracts。仅文档说明变更执行 workspace。可见变化做浅深色宽窄屏及键盘渲染复查。
- Flutter 当前仍是原型；统一设计数据不意味着完整业务或正式移动端迁移完成。
