# 共享 Vue 应用

Vue + TypeScript + Composition API，使用 `<script setup lang="ts">`，版本以 package.json 为准。本目录供 Web/Tauri 复用；旧移动壳仍消费现有输出，移动端目标为 Flutter，不继续采用 Capacitor 路线。

- UI 修改先读 [DESIGN.md](DESIGN.md)，复用 `src/assets/main.css`、根 `packages/design-tokens/tokens.css` 与 `src/components/base/UiIcon.vue`。
- 入口 `src/main.ts` / `App.vue`；视图 `views/`，功能组件 `components/`，状态编排 `composables/`，网络与本地存储 `services/`，宿主差异 `platform/`。
- 聊天/同步用根 `python3 tooling/workspace.py context chat` 定位；认证/缓存入口见 `../../docs/ai/INDEX.md`，不默认全文读取 useChat。
- `src/types.ts` 与部分 utils 只转导出 packages 的唯一定义；不在这些文件中另写模型。
- 新业务页面通过 `nativeBridge` 或应用服务访问原生能力。共享 domain/protocol/ports 不依赖 Vue、Capacitor、Tauri 或浏览器状态。
- `runtimeKind.ts` 根据真实宿主能力选择桥接；Vite 的 `web/desktop/mobile` 模式只决定输出，不决定 IPC 可用性。
- 保持消息连续序列游标、幂等 ID、节点隔离存储、失效令牌重试和卸载清理；修改这些行为要运行对应测试。
- 根执行 `./tooling/verify web`。跨壳资源变更还运行 `npm --prefix apps/web run build:desktop`、`build:mobile`；原生壳需要同步构建，不能只看到浏览器变化就宣称安装包更新。
- 修改视觉规则后做浅色/深色和宽窄屏渲染检查；证据存 `output/`，报告按根归档约定。
