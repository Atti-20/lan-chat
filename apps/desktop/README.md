# MeshX Desktop

LANChat Desktop 是基于 Tauri 2 的 macOS、Windows、Linux 桌面客户端。它复用
`frontend/` 的 Vue 3 UI；生产环境中的 REST、WebSocket、附件和图片请求由 Rust
原生网络层转发，并且只允许访问已完成原生握手的节点 Origin。WebView 的生产 CSP
不开放任意 `http`、`https`、`ws` 或 `wss`。

## 已实现能力

- Rust 原生 `_lanchat._tcp.local.` mDNS-SD 发现、手动节点和服务端 fallback 节点；
- `/api/v1/node/info` 与健康端点握手，强制校验 V1 协议、Desktop Auth 和路径契约；
- 节点去重、RTT、健康状态、连续失败降级，以及最多 32 条的本地 JSON 缓存；
- Rust 原生登录、刷新和退出；Refresh Cookie 仅存在于按节点 Origin 隔离的
  `reqwest` Cookie Jar，JavaScript 只接收 Access Token；
- 原生受控 REST/WebSocket 通道、分片上传取消，以及临时文件下载、进度、长度/哈希
  校验和原子替换；
- 托盘打开/重新扫描/检查更新/退出、关闭窗口时隐藏、显式退出和 `--hidden`
  开机自启；
- 单实例、`lanchat://node|room|conversation|broadcast` 安全深链；
- 系统通知、开机自启、进程重启、Deep Link 和 Updater 插件；
- macOS、Windows、Linux 的 capability 和 bundle 配置。

Refresh Cookie 只保存在桌面进程内存中，不会写入 Web Storage 或磁盘。完全退出
LANChat 后 Cookie Jar 会销毁，下次启动需要重新登录；仅关闭主窗口会隐藏到托盘，
不会丢失当前原生会话。

## 安装与开发

从仓库根目录执行：

```bash
npm install --prefix frontend
npm install --prefix apps/desktop
npm --prefix apps/desktop run dev
```

macOS 首次开发还需要 Xcode Command Line Tools 和 Rust：

```bash
xcode-select --install
rustup toolchain install stable
```

## 本地构建

以下命令可生成使用 ad-hoc Bundle 签名的本地产物，用于开发验证：

```bash
npm --prefix apps/desktop run build

# macOS 可单独构建
npm --prefix apps/desktop run build:app
npm --prefix apps/desktop run build:dmg
```

产物位于 `apps/desktop/src-tauri/target/release/bundle/`。平台目标分别由
`tauri.macos.conf.json`、`tauri.windows.conf.json` 和
`tauri.linux.conf.json` 配置。

仓库已从主图标生成 macOS `.icns`、Windows `.ico` 和桌面 PNG 尺寸，避免只提供
1024×1024 PNG 时 Tauri 无法选择平台图标。当前 v0.3.0 候选版已在 Apple Silicon
macOS 上实际生成、严格校验并启动 ad-hoc 签名的 `MeshX.app`；DMG、Developer ID、
公证、universal 架构与 Updater 仍必须在正式发布门禁中重新生成和验证。
无交互构建环境可设置 `CI=true`，跳过 Finder 图标位置排版。

## v0.3.0 正式发布边界

仓库基础配置只保留空的 Updater 本地配置，不包含假的公钥、发布端点或发布签名凭据。
本地 ad-hoc 构建不依赖这些 secrets；正式发布流水线必须在外部安全注入对应平台的
代码签名材料：

- macOS Developer ID、签名与 Apple 公证凭据；
- Windows 代码签名证书；
- Tauri Updater 私钥和密码，并通过受控的 release 配置提供真实公钥与 HTTPS
  更新端点。

私钥、证书密码和公证凭据不得提交到仓库。没有上述材料的构建只能作为本地或测试产物，
不能视为可分发的正式版本。正式工作流先生成各平台候选产物，再执行平台验证，全部通过后
才创建草稿 Release：

- macOS 必须是 `arm64 + x86_64` universal 应用，通过 Developer ID、Team ID、
  `codesign --strict`、Gatekeeper、`stapler validate`、DMG 校验与挂载后复验；
- Windows 的 NSIS/MSI 必须有有效 Authenticode 签名，证书指纹与受保护配置一致；
- 三个平台的 Updater 产物必须带 Tauri 签名，并使用受保护配置中的公钥逐一验签，
  再生成覆盖 Linux x86_64、Windows x86_64、macOS arm64/x86_64 的 `latest.json`；
- 真实 Mac 的拖放安装、首次启动和旧版本升级仍是发布前的实体机验收项，CI 产物检查
  不能替代该步骤。

当前清单版本统一由仓库根目录 `VERSION` 管理。可在仓库根目录执行
`python3 scripts/ci/check-version-consistency.py` 检查 Maven、npm、Cargo、Tauri、
Gradle 与根 README 是否一致。

## Rust 验证

```bash
cargo fmt --manifest-path apps/desktop/src-tauri/Cargo.toml -- --check
cargo check --manifest-path apps/desktop/src-tauri/Cargo.toml
cargo test --manifest-path apps/desktop/src-tauri/Cargo.toml
git diff --check -- apps/desktop
```
