# Tauri 桌面范围规则（候选模板）

遵循根规则。共享 Vue UI，不复制一份桌面业务界面。
先核对 package.json、src-tauri/tauri.conf.json、Cargo.toml、capabilities 与插件实现。
Tauri before*Command/frontendDist 与前端目录高度相关，搬路径必须联动验证。
保留应用 identifier、旧数据路径、升级兼容和用户设置，不趁重构改品牌身份。
Rust 承担需要的系统能力，不复制服务端授权和聊天业务规则。
命令参数、文件路径、远程 URL/深链必须校验；仅申请必要 permissions/scopes。
禁止以全量文件/shell权限、关闭 CSP 或关闭 TLS 修复联调。

分别验证开发模式、发布打包与生产 endpoint；Vite proxy 通过不代表安装包通过。
Cargo 检查和对应 OS 打包分列；当前机器不能验证的平台写 NOT_RUN/BLOCKED。
不自动签名、公证、推送自动更新或发布安装包。
