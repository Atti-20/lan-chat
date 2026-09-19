# 检查基线：已知事实与待核查事项

日期：2026-09-08。仓库：`Atti-20/lan-chat`。远程默认分支 `master` 在检查时指向 `d7571ded11fa4d6e66fd0c05e37e0541c2004b23`。[R01]

## 已核对的事实

| 位置 | 实际观察 | 对方案的影响 |
|---|---|---|
| 根 `pom.xml` | Java 17、Spring Boot 3.5.0；项目版本 2.3.0；MyBatis-Plus、JWT、Redis、MinIO、JmDNS 等依赖 | 保留后端，不因移动端出现就重写；依赖升级单独任务 |
| `frontend/package.json` | Vue 3.5.39、Vite 8.1.3、TypeScript 5.9.3；Node >=20.19.0；npm scripts 有 test/typecheck/build/build:desktop | 基于实际 npm 工程渐进增加 workspace；这里没有 Pinia 依赖，不默认引入 |
| `apps/desktop/package.json` | Tauri CLI 2.11.4；dev/build/build:app/build:dmg | 保留现有桌面构建入口 |
| `apps/desktop/src-tauri/tauri.conf.json` | 直接调用 `../../frontend`；frontendDist 为 `../../../frontend/dist-desktop`；identifier 为 `com.atti20.lanchat` | 搬前端要同步修路径；不得顺手改应用身份 |
| `frontend/vite.config.ts` | Web base `/app/`；Web 输出到 `../src/main/resources/static/app`；桌面独立输出；开发代理 `/api` 与 `/ws` | 开发代理和发布运行不是同一回事；先保留打包链再改目录 |
| `frontend/src/platform/nativeBridge.ts` | 已有 RuntimeInfo、confirm、Web/Tauri 实现与动态导入 | 扩展/拆分现有桥接，不新建并长期保留第二个竞争入口 |
| `frontend/src/services/api.ts` 的已读前段 | 相对 `/api/v1` 路径、会话读写调用、单飞 refreshPromise、刷新时 `credentials: same-origin`、`deviceType: web`、navigator/window 等 | 不能把该文件直接搬成纯 core；先分离 endpoint、认证与平台依赖 |
| `frontend/src/composables/useWebSocket.ts` 的已读前段 | Vue 响应式状态；连接地址由 window.location 推导；AUTH/AUTH_OK；认证后先 SYNCING；入站串行队列；同步完成后 ONLINE | 保留现有顺序约束和状态语义，再做 TS/Dart 一致性测试 |
| `frontend/src/services/localChatDb.ts` 的已读前段 | Vue toRaw + IndexedDB；数据库 `lanchat_local_v2`，版本 4；outbox/messages/positions 等 store | 它是存储适配实现，不是语言无关 core；不得改名清库作为迁移 |
| `frontend/src/assets/main.css` 的已读前段 | 已有亮/暗主题变量；--blue 为 #007aff / #0a84ff，--radius-md 为 14px | 从现有视觉提取 token，而不是另造一套主题 |
| `.github/workflows/repository-hygiene.yml` | 已有受追踪文件与全历史 Gitleaks 检查；只读权限；固定 Action SHA | 必须保留并扩展，不能用新 CI 覆盖现有安全门禁 |
| 本次根目录树、`apps/` 递归树 | 根目录未见 AGENTS.md、packages/、contracts/、docs/；apps/ 中只看到 desktop | 这是检查提交的状态，不能推断用户本地、其他分支或未推送工程不存在 |

详细来源见 `SOURCES.md`。[R02–R13]

## 已找到的可迁移入口

`frontend/src/composables/` 下有 useAuth、useChat、useWebSocket、useOutbox、useNodeDiscovery、usePeerFileTransfer、useResumableUpload、useBroadcasts、useTheme 等。[R14]

`useChat.ts` 在目录记录中的文件大小为 54,793 字节。它是优先定位和分阶段拆分的候选，不凭大小断言其逻辑存在缺陷。不要求每个新会话全文读取它。

## 本次没有完成的验证

没有运行 Maven、Vue、Tauri、Flutter 的编译或测试；没有真机测试；没有全面审计每个 Controller、SQL、SecurityConfig 或所有 WebSocket 事件；没有验证线上部署；没有比较全部分支。本次读取部分文件前段不代表完整源码审查。

因此不能断言现有功能全部正常、鉴权设计有漏洞、仓库所有子目录都没有 AGENTS，或本地绝对没有 Flutter。MX-A00 要补全本地事实。

## MX-A00 必须补齐

确认当前工作树是否比检查基线新；实际数据库版本/迁移机制；API/WS 权威定义；认证与刷新 token 在 Web、桌面、移动端的可行设计；Tauri 安装包的生产联网行为；已有测试所需外部服务；本地未推送的移动端工程；Node/npm、Rust、Java、Flutter/Xcode/Android 工具链可用性。

“计划支持”和“已经验证支持”必须分列，不能把技术栈表当测试结果。
