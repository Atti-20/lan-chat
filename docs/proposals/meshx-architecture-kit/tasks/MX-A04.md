# MX-A04 — 从现有主题沉淀 Design System

前置：A01。与 A03 并行时，token/共享包/root lock 由同一协调任务管理。

## 读取

frontend/src/assets/main.css、useTheme.ts、现有 Avatar/消息气泡/会话项等实际组件、现有合法图标资产。读取完整相关主题，不能只复制本次审查前 125 行。

## 操作

建立颜色/间距/圆角/字体/动效 token 源，区分 primitive 与 semantic，包含亮暗主题。先保持现有颜色、圆角和用户主题设置，不以重构名义改视觉。

实现确定性 token 生成工具和 schema 校验，输出 CSS 与 Dart 可消费值。旧 CSS 名称通过别名过渡。渐变/阴影/系统字体有显式平台映射，不能机械将 CSS 表达式复制到 Dart。

先交付 Button、Input、Avatar、MessageBubble、ConversationItem、ConnectionStatus 六类规范；组件的状态、空态/错误/禁用/焦点规则与无障碍一并定义。只有有实际消费者的组件才提取 ui-vue 包。Dart 侧若尚无消费工程，记录为生成基础，不能声称 Flutter 页面验收通过。

建立 Web 视觉基线；A05 创建 Flutter 后，用同组数据创建相应 golden 基线。基线变更要审查，不能自动全量接受截图。

## 验收

同一 token 编辑能确定性更新 CSS 和 Dart 产物；重新生成无无意义 diff；旧 Vue 亮/暗界面不发生未批准变化；至少一个真实组件消费新 token。大字体、长中文、长 URL、焦点可见性有测试/核查。

## 非目标与回滚

不重新设计所有页面，不追求 Vue/Flutter 像素完全相同，不引入无授权字体。旧 CSS 别名保持到所有消费者迁移且通过验证。回滚生成配置和消费者时不改变用户主题偏好。
