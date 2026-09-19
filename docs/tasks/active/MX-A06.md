# MX-A06 — Flutter System Capabilities & Native Platform Quality

2026-09-09。状态：实现与受限验收已交付，停止在A06。真实iPhone Wi-Fi LAN+生命周期PASS；完整跨平台验收未完成。MX-A07 NOT_STARTED。

基线：`feature/v0.3.1` / `16efe67dcaae432afd62e4fa10e1d6abbf55c419`，保持dirty。起点1517路径与原有493删除路径已记录，不覆盖用户改动。A05卡片、ADR0008和历史证据保留。

## 已实现证据

纯Dart Discovery/Notification/FilePicker/Share/Lifecycle/NetworkChange/PermissionSettings Ports及统一状态；Kotlin官方NSD/通知/SAF，Swift NWBrowser/通知/security-scoped文件/分享；扫描session、stop/dispose/网络清理、账号与通知归属、后台关闭/前台AUTH-SYNC补齐。无第三方插件/GMS/远程Push/主要依赖或最低系统版本升级。

真实iPhone16 Pro Max iOS26.6.1合法开发签名Debug/Profile安装；显式local.修复Bonjour发现。Wi-Fi对端192.168.0.101→Mac192.168.0.100:18391；LAN权限拒绝/恢复、扫描停止、后台游标222→恢复223且单连接PASS。通知拒绝/设置恢复、OS接受show/cancel，文件选择/取消/缓存释放和分享面板取消PASS，含各自边界。

本轮Flutter56、Java313、Web110，contracts/dart-core/core/design/tooling、Android/iOS Debug构建PASS。workspace/hygiene PASS，34个变化文件Gitleaks无命中。Android模拟器已授权状态原生集成PASS（发现/停止/223消息同步/无效分享），不包含真实OS恢复。全历史安全保持FAIL—已核对原有误报，不放宽规则。

## 仍未完成边界

- FAIL：iPhone应用内公开设置跳转API返回失败，UI明确反馈；手动进入设置恢复PASS，原因未完全定位。
- BLOCKED：用户没有Android真机/OEM设备；iOS超限文件原生验证被文件提供方设备认证阻塞。
- NOT_RUN：全空LAN/节点上下线/独立多服务器/Wi-Fi切换完整矩阵、完整锁屏/挂起/Doze、大字体/IME/Predictive Back、通知横幅点击、完整文件异常提供方、正式release性能/签名发布/远程CI。
- Android模拟器不证明真机LAN。Debug driver的VM断联失败保留，Profile真实OS证据独立成立。A05 Debug输入ANR风险仍保留。

## Handoff

[实现决策ADR0009](../../adr/0009-mobile-platform-capability-boundaries.md)；[完整报告、命令、设备矩阵及失败](../../reports/实机验证/MX-A06_Flutter系统能力与真机验收_2026-09-09.md)；[本轮输出](../../../output/mx-a06-2026-09-09/)。没有自动Git发布操作。

A07若依赖Ports/真实LAN最小切片已有基础；完整设备质量前置条件尚未齐备。需要用户另行授权，当前不启动A07。后续优先补Android真机与设置跳转复现，其余设备矩阵保持显式未验收。
