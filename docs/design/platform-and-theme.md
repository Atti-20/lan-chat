# 平台、主题、字体与图标

日期：2026-09-09；MX-A04。**Design consistency != pixel identity**。统一品牌、色彩语义、文字层级、间距/圆角、消息含义、图标语义和组件状态；交互依平台选择。下表是后续实现要求，不是 A04 已实现平台能力清单。

| 平台 | 允许且需要的差异 |
|---|---|
| Web / Desktop | hover、键盘可见焦点、上下文菜单、多栏、鼠标；Tauri 原生窗口仍需独立渲染验收 |
| Android / 后续 Flutter | predictive back、系统导航、通知/toast 系统行为、触摸目标和 Material 兼容交互；A04 不实现返回、通知或后台运行 |
| iOS / 后续 Flutter | safe area、返回手势、合理使用 Cupertino/系统 sheet、系统字体和辅助功能；不以 CSS 像素强制替换原生行为 |

## 主题数据与偏好分离

[tokens.json](../../packages/design-tokens/tokens.json) 每个 semantic/component 明确 light/dark 引用；`:root` 是浅色默认，`data-theme=dark` 覆盖深色，未知 CSS 模式落回根浅色。颜色与 webOnly 材质可随模式变化；当前非颜色尺寸/时间/字体两模式相同，若引入差异，生成器会要求先实现显式 Dart adapter。

[useTheme.ts](../../apps/web/src/composables/useTheme.ts) 保留当前行为：启动读取有效持久化 light/dark；没有有效记录时读取一次系统主题；运行中不自动跟随系统变化。用户切换立即应用、保存并同步 favicon/native appearance；重载继续使用手动选择。无有效深色系统偏好则浅色。没有“system”第三种持久化模式；不要把愿景写成已实现。浏览器 theme-color 从当前生成的 canvas 变量读取，避免复制颜色常量；生产入口先加载 CSS 再求值 App/主题模块，启动顺序由真实 /app/ 回归覆盖。

偏好存储、系统监听、生命周期属于应用/平台层，JSON 只负责视觉数据。Flutter 原型已有主题适配继续消费生成值；新增页面/持久化策略留待 A05。CSS 透明材质在 prefers-reduced-transparency 下通过语义层回退不透明表面，兼容 alias 一起生效。

## 字体和放大

使用已有系统栈：`-apple-system, BlinkMacSystemFont, Segoe UI, PingFang SC, Microsoft YaHei, sans-serif` 等，精确列表见 source；等宽使用现有系统等宽栈。没有新增/提交本机专有字体文件，也不跨平台强行声明本机字体资源。

| 语义 | 默认值/来源 |
|---|---|
| body / label | body 14px，label 12px（复用 caption 档位） |
| title / headline | title 20px / headline 24px，沿用已有档位 |
| caption | 12px；辅助说明 |
| monospace | 语义字体栈复用现有 mono；尺寸按内容层级 |

Web rem 以现有根 16px 表达；Dart rem 先换算为逻辑像素，Text 再跟随系统 TextScaler。CSS 字体栈中的 `-apple-system` 不是 Flutter 字体资源名，Flutter 默认系统字体。中英文/中文 fallback、字体基线和字宽在平台间可不同。

控件使用 min-height、文本可换行和 min-width:0；未来 Flutter 支持 Dynamic Type / Android font scale，不能固定正文 TextScaler 或用固定高裁切多行。A04 的 Chromium 2 倍根字号和 Flutter 最小 TextScaler(2) 测试是局部证据，不是设备 Dynamic Type/VoiceOver/TalkBack 全验收。现有徽章/头像字/元信息仍有固定 px 与触摸目标债。

## 图标语义和资产

语义表位于 [components.json](../../packages/design-tokens/components.json) 的 iconSemantics，资产仍由 [UiIcon.vue](../../apps/web/src/components/base/UiIcon.vue) 提供，未替换图标库。send→send、add→plus、search→search、success→check、file→file、image→image、user→users、group→groups、broadcast→bell。settings/warning 的资产为 null，并保留明确含义；不虚构当前图标。生成检查核对非空映射确实存在于 IconName。

图标 currentColor/24×24/线宽规范沿用现状；装饰图标隐藏，交互父级提供名称。success 图标不能自动表示消息已读。新增资产应在真实功能需要时独立选择并检查许可，不为了本轮填满图标目录。


## 原生启动器图标（2026-09-13）

原生品牌源为仓库 `apps/web/public/MeshX_dark.png`，与用户指定 `/Users/atti/Documents/MeshX_dark.png` 的SHA-256一致：`2d6160abec97e9cb6574f5776bc836eefc4b62423c469a944bf75f3652e7407e`。现有桌面 `apps/desktop/src-tauri/icons` 为已导出资源入口；Flutter及旧壳Android逐档复用android子树，Flutter iOS按Contents.json槽位复用ios子树。不重新绘制标记，不修改页面Logo或favicon。

Flutter Android同时声明icon与roundIcon，并复用v26 adaptive-icon前景和深色背景；不再使用Flutter默认图标。Android前景采用108dp画布，白色品牌需保持在中心直径66dp安全圆内，参照[Android官方图标说明](https://developer.android.com/codelabs/basic-android-kotlin-compose-training-change-app-icon)。静态圆形预览和像素边界检查不代替Pixel/OEM设备桌面验收。

执行 `python3 scripts/ci/check-native-icon-parity.py` 校验50个栅格引用、adaptive配置及两套Android manifest入口；它同时接入hygiene与Flutter工作流quality阶段。像素安全圆审计与本轮构建证据见[候选任务卡](../tasks/active/MX-A07B-9.md)。原型身份、签名与升级策略不因图标相同而变化。

## Shared Core

业务核心仅输出语义状态：FAILED → Vue UI → color.status.danger。domain-ts/platform-ports 不导入 CSS、Dart Theme、design-tokens 实现或具体组件；AST 边界检查和无 DOM 编译保持。A04 增加 CSS/样式/Dart 导入负例，不将颜色写入投递状态。
