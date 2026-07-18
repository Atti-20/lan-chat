# LANChat macOS Desktop Foundation

这是 LANChat V3.0 的首个桌面纵向切片：Tauri 2 壳、独立桌面前端构建和最小 Native Bridge。

## 当前范围

- 复用 `frontend/` 的 Vue 3/Vite UI；
- Web 构建仍输出到 Spring Boot 静态目录；
- Desktop 构建输出到 `frontend/dist-desktop/`；
- Tauri Rust 端提供 `runtime_info` 命令；
- macOS 生成 `.app` 或 `.dmg`；
- 预先声明本地网络与 `_lanchat._tcp` Bonjour 用途。

暂未实现：原生 mDNS 浏览、托盘、系统通知、单实例、开机自启、自动更新、签名和公证。

## macOS 环境

```bash
xcode-select --install

# 推荐使用 rustup 安装 Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source "$HOME/.cargo/env"
```

## 安装依赖

从仓库根目录执行：

```bash
npm install --prefix frontend
npm install --prefix apps/desktop
```

## 启动开发版

先保证 Spring Boot 服务端运行在 `http://localhost:8080`，然后执行：

```bash
npm --prefix apps/desktop run dev
```

开发模式通过 Vite 代理访问 `/api` 和 `/ws`，因此现有 SameSite=Strict Refresh Cookie 链路仍能工作。

## 构建 macOS 应用

```bash
npm --prefix apps/desktop run build:app
npm --prefix apps/desktop run build:dmg
```

产物位于：

```text
apps/desktop/src-tauri/target/release/bundle/macos/
apps/desktop/src-tauri/target/release/bundle/dmg/
```

## 已知边界

当前生产包仍需要“Desktop Auth/Transport Adapter”。现有前端大量使用同源 `/api`、`/ws`，Refresh Token 又是 `SameSite=Strict` HttpOnly Cookie；打包后直接跨源连接任意 LAN 节点时，不能简单依赖浏览器 Cookie。下一阶段应由 Tauri Rust 层接管节点级 HTTP/WS 传输或增加受控的桌面认证协议，不能把 Refresh Token 暴露给普通 Web 存储。
