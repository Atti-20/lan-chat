# MX-A08 — 可选的目录归整

前置：关键回归稳定，且用户确认目录整理确有价值。本任务不是 Monorepo 达标必需。

## 目标

可将 frontend 移为 apps/web，将根服务端源码/POM 移为 services/server。只改路径/构建装配，不改业务、协议、依赖版本、应用身份或数据格式。

## 操作

先生成旧路径→新路径清单，查找 Vite base/outDir、Tauri beforeDevCommand/beforeBuildCommand/frontendDist、Docker COPY、compose build、Maven 静态资源、workspace glob/lock、CI path filters、脚本、文档与工具配置的全部引用。

建议先只移动 frontend 并验证，再另一个 PR 移动服务端。Maven Wrapper 可保留根目录并通过明确 -f 指定新 POM，或采用经验证的其他方式；Windows 与 Mac 入口一致更新。不通过两份复制源码维持表面兼容。

更新 docs/ai/repo-map 和模块 AGENTS，保证新会话定位准确。旧启动命令有薄兼容包装时注明迁移周期；不要留永远分叉的第二构建体系。

## 验收

根安装与统一验证、Web /app/ 部署、Tauri发布打包、Docker/compose、所有相应 CI 通过；旧用户升级行为不变。比较产物与基线的必要结构及运行结果，而非只看文件是否成功 git mv。

## 回滚

整个纯路径迁移独立可回滚，不与数据库变更或新功能同批。回滚只影响代码/构建引用，不删除用户生成内容。无需为了“目录漂亮”承担回归时，可明确不执行本任务。
