# V1 兼容检查

日期：2026-09-09。当前契约事实和覆盖范围见 [盘点](../inventory.md)，认证和地址决策见 [ADR 0006](../../docs/adr/0006-endpoints-auth-and-contract-generation.md)。

`tooling/contract_compatibility.py` 比较当前契约与 [已审阅基线](v1-baseline.json)。这是保守的结构门禁：已有 REST path/method、请求/响应、字段、required、nullable、enum、约束、security 和媒体/空响应语义发生变化时返回非零。WS 信封、版本、事件、方向、payload 及生成配置同样被冻结。新增 REST 操作/模型允许通过；已有结构内即使只是新增可选字段，也会要求人工审阅。

这个选择会报告一些实际兼容的变更，但不会尝试通过不完整的 union、开放 Map 或业务权限信息证明自动兼容。未知 schema 关键字也保留在比较中。描述、标题、示例和源码导航元数据不影响兼容判断；错误码触发条件、事务和运行时状态机必须另外运行行为回归。

共享向量文件的 SHA-256 也进入基线，防止只改测试期望便把红灯改成绿灯。它与代码审阅配合，不能阻止拥有仓库写权限的人同时修改门禁和基线。

```sh
# 日常验证：生成检查不更新兼容基线。
python3 tooling/contract_compatibility.py
./tooling/verify contracts
./tooling/verify flutter

# 仅在完成兼容影响审阅、版本/迁移安排和行为测试之后使用。
python3 tooling/contract_compatibility.py --accept-reviewed-change '具体审阅记录、变更原因及兼容窗口'
```

`workspace.py generate`、npm generate、CI 都不会自动接受新基线。先查看非零输出，再修复无意漂移；有意变更必须把基线差异与实现、测试和审阅理由一起保存。初始基线来自 MX-A02 对实际 V1 MVC/Handler 的恢复，不是发布后兼容性的追溯保证。

测试 `tooling/tests/test_contract_compatibility.py` 覆盖删除端点/方法/字段、增加必填、收紧 nullable、改变设备值、响应类型、安全入口、WS 版本/序列/限制，以及篡改样例期望；另有隔离副本中的真实 CLI 退出码测试。`test_contract_generators.py` 和 MVC 快照测试负责来源/生成物漂移，三种检查互相补充。
