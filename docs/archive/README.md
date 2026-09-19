# 历史材料

现行规范从 [docs 索引](../README.md) 进入。`local/PRD`、旧根 `DESIGN.md` 和 `MeshX Architecture Evolution TODO.md` 是本地历史资料，保留原日期与内容，继续受 Git 忽略；新克隆不要求存在。

旧 Compose 原型源码保存在 [archive](../../archive/README.md)。旧根 `out / target / test-results` 已归入 `output/legacy-build`，原 `outputs` 证据统一至 `output/history`。这些是本地证据，不参与当前构建。

本轮每个移动文件的原位置、新位置、大小与 SHA-256 记录在 `output/protocol-design-workspace-2026-09-08/cleanup-manifest.json`；归档移动已逐项验哈希。后续新增产物直接放对应模块的标准构建目录或根 `output/`，不要重新创建重复输出根目录。
