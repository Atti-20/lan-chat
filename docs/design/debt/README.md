# 当前设计债

日期：2026-09-09；从真实 Vue 实现提取。A04 不进行品牌改版；以下未因 Token 建立而自动关闭。

| 编号 | 事实与影响 | 后续验收 |
|---|---|---|
| D01 | 三个代表组件之外仍有旧 alias、局部硬编码、重叠 CSS 覆盖；UserAvatar 用户渐变属于身份数据 | 按功能逐步迁移、保留同主题截图；不批量清理全前端 |
| D02 | 消息 meta 11px / 小操作、徽章 20px / 9px，部分状态按钮不足舒适触摸面积 | 单独评估触摸目标；320px 大字体下元信息碎行仍可见但体验欠佳 |
| D03 | 昵称/会话预览省略、徽章与头像字固定大小；动态渐变未全量对比度验证 | 中英长名、屏幕阅读器、系统字体放大和用户自定义色专项验收 |
| D04 | MessageThread 尚无批量 selected，selectstart.prevent 限制文本选择 | 在明确消息交互任务处理，不用 A04 新造业务状态 |
| D05 | ConnectionStatusBar 无生产父级消费；sessionExpired 也非其枚举，窄屏隐藏数量，恢复按钮无处理中状态 | 接入前核对持久化能力/文案、防重复、播报频率和触摸目标 |
| D06 | Button/Input 是 CSS 类，loading/error 多由调用方组合，尚未审计所有表单标签 | 逐表单检查可访问名称、错误描述、忙碌/禁用语义 |
| D07 | useTheme 仅启动时读取系统，手动偏好优先；运行中不跟随系统 | 是否新增 system 模式需产品决定，A04 保留语义 |
| D08 | CSS 玻璃/阴影/渐变仅 webOnly；Flutter 没有一对一材质，图标 settings/warning 未映射资产 | 后续按平台适配，实机检查字体/材质/交互；不能承诺逐像素相同 |
| D09 | A04 fixture 不覆盖真实附件传输、完整键盘导航、VoiceOver/TalkBack、安装 Tauri WebKit 窗口 | 专项真实流程/窗口/设备验收；Chromium data-runtime=tauri 仅样式上下文 |

A04 已修复的局部可访问性缺口：UserAvatar 增加 role=img/在线名称；当前会话增加 aria-current。DOM 16 项和局部颜色对比度不等于 WCAG 全面合规证明。
