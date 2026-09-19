# 共享协议

- `src/envelope.ts`、`src/events.ts` 来自 `contracts/websocket`；`src/rest.ts` 来自 REST OpenAPI。不要手改生成物。
- `src/index.ts` 导出 WS 信封与 payload；应用直接消费 REST 的 `components / paths / operations` 类型。不得反向依赖 domain、ports 或 apps。
- 根 `python3 tooling/workspace.py generate` 更新 WS TS/Dart；`npm --prefix tooling/contracts run generate` 更新 REST TS。
- 根 `./tooling/verify contracts` 检查生成物、源码漂移、JSON Schema 正反例和 MVC 快照；行为变更还需对应业务测试。
- 生成模型不含 UI、网络、存储或认证状态。REST Map/必填/nullable 和部分 WS payload 的覆盖边界见 `contracts/README.md`。
