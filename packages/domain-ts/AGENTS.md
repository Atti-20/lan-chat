# 共享客户端模型与规则

- `src/models.ts` 保存客户端模型（含展示状态）；`conversation.ts`/`sequence.ts` 保存标识与连续游标；`messages.ts`、`outbox.ts`、`realtime.ts` 保存消息合并/排序、队列恢复和重连规则。文件 Blob 记录只在应用 adapter。
- 只能依赖 protocol 和本模块。不得导入 Vue、原生 SDK、DOM、IndexedDB 或应用适配器。
- 身份、权限和业务写入的最终判定仍由服务端负责；不要在这里实现绕过服务端的授权。
- 原应用类型/工具文件只转导出这里的定义，避免复制。
- 根运行 `./tooling/verify core`、`workspace` 与 `web`。Core 检查包含 AST import 方向、无 DOM/Node ambient 类型编译及非法依赖负例；不以类型断言绕过边界。
