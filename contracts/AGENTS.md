# 协议事实入口

先读 [README.md](README.md)，区分 REST 结构、WS 字段和服务端行为的覆盖边界。

- REST 快照由 `services/server` 的 `RestContractTest` 导出；只在测试 classpath 引入 springdoc。普通测试比较快照，更新使用 `-Dmeshx.contract.update=true`，审阅真实差异。
- `websocket/events.schema.json` 维护事件方向和 payload，`x-events.coverage` 明确字段完整程度；不能用开放结构声称全面校验。
- `python3 tooling/workspace.py generate` 生成 WS Java/TS/Dart、REST 操作/Dart 切片和设计值；`npm --prefix tooling/contracts run generate` 生成 REST TS 模型。生成不更新兼容基线，见 compatibility/README.md。
- `./tooling/verify contracts` 检查静态漂移、JSON 正反例、生成物和 MVC 快照。行为修改另跑 server/Web/Flutter/E2E 对应测试。
- 不更改既有鉴权、令牌/Cookie、文件流、幂等键、ACK 或序列语义来迁就生成器。模型不是运行时授权检查器。
