# MX-A03 — 从现有 Vue 逐步抽出 TS Core 和平台适配

前置：A02。依赖方向见 IMPLEMENTATION_PLAN 第 3 节。

## 迁移候选与目标

- types.ts：区分网络 DTO、领域模型和页面状态；已纳入契约的 DTO 由生成物提供，不全部照搬。
- api.ts：保留兼容 facade，端点/认证/错误包装进入合适边界。
- useWebSocket：纯状态和重连策略进 core；浏览器传输进 adapter；Vue composable 留为薄绑定。
- localChatDb：IndexedDB 实现进 platform-web，存储接口进 core；Vue toRaw 在展示映射边界完成。
- nativeBridge：在兼容现有导出的前提下逐步拆 Runtime/Dialog 等能力；Tauri 实现进 platform-tauri。
- useChat/useOutbox/useResumableUpload/usePeerFileTransfer：在前面稳定后，按一个用例一次拆分，不一次重写。

## 执行方法

每个小步先增加描述既有行为的回归，再迁移实现，再切换一个真实消费者，最后移除死代码。不同时重构所有 composable。包必须有清晰 exports、依赖和实际消费者，不创建空架构层。

core 不可 import Vue/Tauri/DOM 存储/浏览器 globals；其纯逻辑测试不需要浏览器。平台能力通过 ports 注入，bootstrap 负责选择 Web/Tauri 适配，产品规则中不散布 isTauri 判断。

建立可执行 import 边界检查，覆盖静态和动态 import；可用现有 lint/分析工具，不能只靠 AGENTS 口头约定。测试中故意加入非法 import 应失败。

## 验收

同一套 Vue 界面仍用于 Web/桌面；登录/刷新、连接状态、文字收发、断线补齐、outbox、主题与文件核心路径不劣化。保留客户端消息关联/幂等语义及已知入站处理顺序。退出/换账号不会混用缓存或旧请求。

IndexedDB 名称/版本/已有记录不被无故重建，现有账号和服务器配置不丢失。Tauri release 网络路径要验证，不能只看 Vite 开发代理。

## 回滚

一个能力一个可回滚变更；迁移期间原入口可作薄 facade，不长期维持两套竞争逻辑。出现无法覆盖的回归先停止拆分，保留已验证子步骤，不用删数据库或关鉴权“修复”。
