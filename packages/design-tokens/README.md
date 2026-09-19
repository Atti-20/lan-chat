# MeshX Design Token source

`tokens.json` 是设计值的唯一维护入口；`components.json` 是组件状态与图标语义目录。A04 在现有 JSON/Python 标准库工具链内演进，不另建包管理器或设计系统框架。

## v2 结构与分层

| 字段 | 规则 |
|---|---|
| `schemaVersion` / `baseFontSize` | 当前版本 2；正的有限基础字号 16，用于 rem → Dart 逻辑像素，不覆盖用户文字缩放 |
| `primitives` | 原始色板/透明度、空间/字号/圆角档位、系统字体栈、时长和曲线；每项仅 `type/value` |
| `tokens` | 语义或必要组件规则；每项仅 `layer/type/light/dark`，两种模式都必须明确引用 |
| `aliases` | 原 CSS/Dart 名称 → 新 token；保留 87 个旧入口，新增组件优先用语义名称 |
| `web` | 原根字体/颜色/color-scheme 等 Web 属性，不保存主题偏好 |

`light/dark` 使用 `{ "ref": "primitives.palette.neutral.900" }` 或 `{ "ref": "tokens.color.text.primary" }`。Primitive 不引用上层；Semantic 只能引用 Primitive/Semantic；Component 可引用前两层或组件规则。禁止缺失、循环、类型不一致和语义层反向引用组件层。文件中的字段、重复 JSON key、单位、数值、两主题完整性和 CSS 归一化命名碰撞均由 `tooling/design_tokens.py` 严格检查。

例如 `color.message.own → color.action.primary → palette.blue.600`；Web 使用 `--mx-color-message-own`，旧 `--action-bg` 继续通过 alias 工作。`--ink/--panel/--radius-md` 不删除。只提取三项既有组件例外：消息尾角、消息行高、按钮按压比例；局部的 9px/13px 气泡 padding 等继续留在原组件，并在规范注明，不为每个像素发明 token。

## 类型与平台转换

| 类型 | Source / CSS | Dart |
|---|---|---|
| `color` | 六位 hex 或 rgba；颜色值和 alpha 范围校验 | `Color`，alpha 四舍五入到 8 位 ARGB |
| `dimension` | 非负有限 `value`，`px/rem` | `double`；px → 逻辑像素，rem × baseFontSize |
| `number` | 非负有限数，不丢失原 JSON 数值精度 | `double` |
| `duration` | 非负整数 ms | `Duration(milliseconds: …)` |
| `cubicBezier` | 四个有限数，两个 x 坐标在 0–1 | `List<double>`；UI 自行映射 Curve |
| `fontFamily` | 有序、不重复字体栈 | `List<String>` 元数据；Flutter 仍选择系统字体，不把 CSS 系统关键词当字体文件 |
| `webOnly` | 原渐变/阴影/组合边框；内部 var 引用也检测缺失和循环 | 不生成虚假的原生材质 |

颜色和 webOnly 可按主题变化。字号/尺寸/曲线等当前保持两主题一致；不同模式的这些值会明确报错，需以后先定义 Dart adapter。不支持任意 CSS 单位或自定义 CSS 计算语言。没有私有字体文件；中文/英文由原系统 font stack 和客户端系统字体 fallback 处理。

生成的 Dart 保留 `meshXLight/meshXDark/meshXSizes/meshXNumbers/meshXDurations/meshXCurves/meshXFontFamilies` 类型化 maps，同时包含新语义 key 与旧兼容 key。后续 Flutter 可用 `meshXLight['color.text.primary']`，不执行 TypeScript，也不依赖 CSS。

## 生成与验收

在仓库根执行：

```sh
python3 tooling/workspace.py generate
./tooling/verify design
./tooling/verify contracts
./tooling/verify core
./tooling/verify web
./tooling/verify flutter
```

输出为 `packages/design-tokens/tokens.css` 与 `apps/flutter-prototype/lib/ui/tokens.g.dart`。均带 generated header，不能手改；对象 key 顺序不影响生成结果。`python3 tooling/design_tokens.py --check` 及兼容 Flutter 生成入口的 `--check` 只检查、不回写。Web typecheck/build 也执行该检查，原 workspace/contracts 门禁继续包含生成漂移。

当前 `main.css`、UserAvatar、ConversationSidebar、MessageThread 已消费语义变量；其余现有 Vue 样式渐进保留 aliases。减少透明度覆盖语义材质变量，旧变量随 alias 一起生效，避免新旧消费者产生两种视觉结果。

主题数据与偏好管理分开：现有 `useTheme` 启动时优先有效持久化偏好，否则取系统主题；没有运行中跟随系统变化的监听。手动切换继续持久化并更新 favicon/浏览器主题色和现有原生外观接口。非法旧偏好按系统初始主题回退；CSS 未指定/未知主题采用 root 的浅色数据。详细组件、平台规则及未迁移债见 [设计入口](../../docs/design/README.md)。
