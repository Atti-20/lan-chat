# 本地开发

以下命令从仓库根执行；目录包含空格时，用引号包裹 `cd` 参数。工具不会自动安装依赖或修改本机密钥。

## 环境与首次安装

- Python >=3.10：工作区工具；Java 17：服务端；Android 使用 Java 21 和 Android SDK（当前 compile/target 37）。
- Node >=22 可覆盖所有应用（Web 最低版本见其 package.json）；Rust/Tauri 原生依赖按桌面平台 CI；iOS 需要 macOS/Xcode。
- 每个应用保留自己的 npm lockfile；共享 packages 直接作为源码编译，无额外安装步骤。

```sh
npm ci --prefix apps/web
npm ci --prefix apps/desktop
npm ci --prefix apps/android
npm ci --prefix apps/ios
```

按实际目标安装对应壳依赖即可。数据库、Redis、MinIO 和环境变量配置见 [完整运行手册](project-guide.md)。根 `.env.example` 是样例；不要提交本机凭据。

## 常用启动

```sh
# 终端一：已有正确本地数据库/Redis配置时启动服务
./mvnw -pl services/server spring-boot:run

# 终端二：Web，默认 /app/，代理到 8080
npm --prefix apps/web run dev

# 桌面开发
npm --prefix apps/desktop run dev

# Android 构建自动同步 Vue
npm --prefix apps/android run build:debug

# iOS 同步 UI 后打开 Xcode
npm --prefix apps/ios run sync:ios
npm --prefix apps/ios run open:ios
```

Maven 缓存不可写时，在统一入口使用 `MESHX_MAVEN_REPO=/tmp/lanchat-m2 ./tooling/verify server`，或给 mvnw 直接加 `-Dmaven.repo.local=/tmp/lanchat-m2`。

## 路径迁移

| 原路径/命令 | 当前路径/命令 |
|---|---|
| `frontend/` | `apps/web/` |
| 根 `src/` 与服务 POM | `services/server/src/`、`services/server/pom.xml` |
| `./mvnw test/package` | 命令保留，由根聚合器执行服务模块 |
| `./mvnw spring-boot:run` | `./mvnw -pl services/server spring-boot:run` |
| 根 `target/lan-chat-server-*.jar` | `services/server/target/lan-chat-server-*.jar` |

根启动命令保留上传/日志相对根目录的行为。既有 `uploads/`、`logs/`、`output/` 和历史构建产物未迁移；本地 IDE 需要重新导入根 Maven 工程并刷新原启动配置。
从旧目录启动的开发进程需重启；历史安装包不会因为源码移动而自动更新。
