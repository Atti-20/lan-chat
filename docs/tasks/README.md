# 架构治理任务记录

方案顺序见 [实施包索引](../proposals/meshx-architecture-kit/TASK_INDEX.md)，实时结果保存在这里。当前用户已授权持续完成Flutter改造，并要求在同一对话按大批次推进：恢复链路闭环→跨端体验与验收→各端应用构建及统一图标。任务卡用于记录证据，不再以单项修复或单张卡作为停止、重新批准或新开对话的条件；历史卡片中的阶段授权和停止说明仅代表当时范围。生产发布、签名与应用身份变更仍遵循对应授权边界。

| 任务 | 状态记录 | 范围 |
|---|---|---|
| MX-A07C-3 | [新主导航真实跨端流程验收](active/MX-A07C-3.md) | C01_PARTIAL；Vue与Android模拟器基础流程、附件取消及HTTP失败→重选→ACK→Web下载哈希PASS；完整业务与设备矩阵未关闭 |
| MX-A00 | [本地事实与基线](active/MX-A00.md) | 已完成的审查与交接；数字仅代表该轮 |
| MX-A01 | [AI 上下文与统一验证](active/MX-A01.md) | 已完成交付；全量安全门禁 FAIL（已核实误报），含遗留问题和 A02 启动条件 |
| MX-A02 | [契约治理与跨端基础](active/MX-A02.md) | REST/WS 来源、生成、共享向量、兼容门禁与隔离快照复现；实际结果见卡片 |
| MX-A03 | [TypeScript Shared Core 与平台适配](active/MX-A03.md) | 纯聊天规则、小型 Outbox/Realtime Ports、现有 Web/Tauri adapter 与隔离验证；实际状态见卡片 |
| MX-A04 | [Design System Foundation](active/MX-A04.md) | 三层 Token、双主题 CSS/Dart、六类规范、三个现有 Vue 组件、DOM/视觉回归；实际状态见卡片 |
| MX-A05 | [Flutter 真实文字聊天](active/MX-A05.md) | 模拟器文字闭环完成；持久化、认证/同步、双端 Web 互通，保留真机/LAN/发布债，实际状态见卡片 |
| MX-A06 | [Flutter 系统能力与原生质量](active/MX-A06.md) | LAN/生命周期/本地通知/文件平台边界，真机和权限矩阵以卡片为准 |
| MX-A07A | [功能矩阵与移动首发冻结](active/MX-A07A.md) | 已完成的36项跨端事实、v0.3.1范围及发布门禁冻结 |
| MX-A07B-1 | [消息变更与缓存一致性](active/MX-A07B-1.md) | BLOCKED_BY_CONTRACT；真实离线变更/重启复现、TS/Dart回归和契约提案；1I/C未启动 |
| MX-A07B-1I | [消息变更恢复实现](active/MX-A07B-1I.md) | IN_PROGRESS；后端事务/恢复服务、Web/Flutter控制器/持久化与通知已接线，200k容量及移动响应性验收推进中；设备、多实例和生产迁移/发布未关闭 |
| MX-A07B-1R | [消息变更恢复契约评审与冻结](active/MX-A07B-1R.md) | APPROVED_FOR_LOCAL_IMPLEMENTATION；冻结方案及1I本地实施已批准，历史审批记录保留 |
| MX-A07B-2 | [Flutter 好友、申请与搜索](active/MX-A07B-2.md) | B04 本阶段完成；代码、回归、双平台模拟器构建及真实 Spring/WS 好友闭环 PASS；设备/发布另验 |
| MX-A07B-3 | [Flutter 个人资料与基础设置](active/MX-A07B-3.md) | B09 本阶段完成；代码、87 项回归、双平台模拟器构建及真实 Spring 资料/头像/密码闭环 PASS；设备/发布另验 |
| MX-A07B-4 | [Flutter 基本群聊](active/MX-A07B-4.md) | B05 本阶段完成；代码、94 项回归、双平台模拟器构建及真实 Spring/WS 建群、群聊、退群闭环 PASS；设备/发布另验 |
| MX-A07B-5 | [Flutter 基础文件与图片消息](active/MX-A07B-5.md) | B06 本阶段完成；分块上传、ACK outbox、授权下载/哈希、图片预览与系统分享实现，102 项回归、双平台模拟器构建及真实 Spring 闭环 PASS；设备/发布另验 |
| MX-A07B-6 | [Flutter 广播接收与回执](active/MX-A07B-6.md) | B07 本阶段实现与本地验证完成；待办/详情/查看/确认/图片完成、技术账号卡片与定位只读边界，110 项回归、双平台模拟器构建及 57 项服务规则 PASS；真实 Web/手机闭环另验 |
| MX-A07B-7 | [Flutter 权限恢复与通知路由](active/MX-A07B-7.md) | B08 本地与真机回调阶段完成；广播/普通通知安全路由、账号竞态、Settings公开API与人工fallback，115项回归、双平台模拟器及iPhone三轮回调PASS；C04视觉/点击另验 |
| MX-A07B-8 | [Flutter 平台可用性与最小诊断](active/MX-A07B-8.md) | B10 本地实现阶段完成；先预览后复制的脱敏支持信息、版本端口、连接阶段/错误码，117项回归、双平台模拟器构建与iPhone Profile原生读取PASS；C02/C08/G14/G15完整矩阵另验 |
| MX-A07B-9 | [Flutter 候选构建与 CI 准备](active/MX-A07B-9.md) | B11本地准备完成，PREPARED_NOT_APPROVED；9月20日当前main的Android/iOS工件、macOS DMG与Web包已构建，Pixel圆形图标实显通过；iOS最低15已获授权，Windows/Linux改由GitHub构建，优先四端闭环；正式发布仍待 |
| MX-A07C-1 | [Flutter C08 Profile 性能切片](active/MX-A07C-1.md) | C08_PARTIAL；iPhone 16 Pro Max真机Profile分段预算PASS，Android 17虚拟机流程PASS但性能FAIL；正式main、真实冷启/OS恢复、Android真机、读屏/旧OS仍未验，G08/G14/G15不关闭 |
| MX-A07C-2 | [Flutter C08 冷进程与真实OS恢复](active/MX-A07C-2.md) | C08_PARTIAL；iPhone真机Profile 5轮冷进程与5轮真实Settings前后台恢复PASS，双时钟/单连接/2050条持久恢复有证；仍为开发签名测试入口与HTTP，正式main/HTTPS、Android真机及旧OS未验 |
| MX-A07B-10 | [Flutter 移动导航与会话整合](active/MX-A07B-10.md) | B02常驻四入口、消息/申请/广播徽标及导航文案统一；到期自动刷新，最新176项Flutter回归PASS，设备/完整跨端/发布另验 |

复用 [执行计划模板](../exec-plans/template.md)。编号任务写在 `active/<任务号>.md`，包含 phase 状态、基线 HEAD/branch、变更清单、命令结果、证据、未验证项及 handoff；完成后在原卡标注结果，用户未要求时不搬路径。其他跨会话工作仍用 exec-plans，参见 [协作流程](../ai/WORKFLOW.md)。

PASS 表示已完成声明的验收范围；FAIL/BLOCKED/NOT_RUN 不能隐藏。报告按 docs/reports 分类，本目录只保存任务状态及证据引用，不复制完整报告或运行日志。
