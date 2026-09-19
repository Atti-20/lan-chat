# Vue/Web 范围规则（候选模板）

当前前端位于 frontend/，同时供 Web 和 Tauri 使用；以实际迁移状态为准。
遵循根规则。先看 package.json/scripts 和 vite.config.ts，不猜包管理器/构建输出。

本目录负责页面、Vue响应式绑定、导航和应用装配；纯业务逻辑按阶段抽取。
不要把 Vue ref/computed/watch/toRaw 直接迁入纯 TS core。
不要在业务组件直接访问 Tauri 插件，使用已确定的能力接口。
现有 NativeBridge 和 API facade 在迁移期间保持兼容，禁止另建长期竞争实现。
Web、桌面发布环境的 endpoint 与开发代理分别验证。
主题优先消费 design token；不在无行为变化重构中改变样式或用户主题偏好。
保留 /app/ 路由与后端静态资源打包行为；任何路径调整单独验收。

验证用统一 web scope；该入口尚未建立时使用已核实的本地 scripts。
改 core/契约/token 时验证共享消费者；记录静态构建产生的文件差异。
