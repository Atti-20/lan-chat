# ADR 0009 — 移动系统能力与生命周期边界

日期：2026-09-09。状态：接受（MX-A06 实现决策；设备验收状态以任务卡为准）。

A05 已建立文字消息、AUTH/SYNC、持久化 outbox 与安全凭据。A06 将发现、通知、文件和 OS 生命周期收敛到纯 Dart Port → Flutter Adapter → Kotlin/Swift。Core 不导入 Flutter、MethodChannel、NSD、系统 URI 或通知框架。Spring/Vue/Tauri/Flutter 路线、协议版本及最低系统版本不变。

## 能力结果

统一区分 available/success、permissionRequired、permissionDenied、permanentlyDenied、unsupported、temporarilyUnavailable、cancelled、timeout、failed。成功的空发现是成功；权限拒绝、后台暂停及超时带独立状态和稳定原因码。平台异常不返回系统路径、凭据或原始秘密内容。

## 发现与信任

复用服务端 `_meshx-control._tcp.local.`，兼容 `_lanchat._tcp.local.`；Android 使用官方 NsdManager，iOS 使用 NWBrowser/NWConnection。没有第三方插件、新增 GMS 或远程 Push 依赖。采用系统 API 保留当前 Android minSdk26、iOS13；不需要改变主要依赖和锁文件。

每次扫描有独立 session，回调发送完整的有界快照；服务丢失删除结果，旧 resolve 回调通过修订号失效，stop/dispose 后不发布。上层按节点身份合并接口地址，验证 URL 不含凭据/query/fragment；扫描窗口结束，展示结果最多保留30秒。网络变化、后台及页面销毁停止扫描；恢复只在已有扫描意图下自动重扫，首次权限由用户操作触发。

iOS 没有公开本地网络权限查询 API；曾成功启动是避免首次自动弹窗的提示，不当作持续授权证明。实际浏览的 policy denied 状态独立返回。Android37 的 LAN 权限请求与通知权限分开。发现节点仅提供候选地址，仍执行节点握手、认证和服务器授权。

## 恢复与通知

FlutterLifecyclePort 观察真实 OS 状态。inactive（权限框、文件面板）不关闭连接；background 关闭并使旧 socket 代次失效；foreground/network change 复用 A05 的受保护 AUTH → SYNC → ONLINE。串行恢复与网络去抖避免重复连接/outbox flush。后台没有永久服务、假音频/定位/VoIP；不承诺 iOS 挂起后即时收到 LAN 消息。

通知只处理实时 CHAT_DELIVER；历史补同步、已存消息、自己发送、当前可见会话不发通知。账号切换立即使原生 owner 失效并清理通知，阻止异步 show 越过退出登录。正文使用通用提示，不含消息正文；点击还需核对账号、在线状态与会话存在。APNs/FCM 不在本阶段。

## 文件与分享

FilePickerPort/SharePort 接受 opaque handle，不接受任意路径。Android 使用 SAF content URI，iOS 使用 security-scoped URL 和 NSFileCoordinator；选择后限量复制到应用缓存（默认25MiB），释放源访问。取消、不可读、权限、已删除、超限和超时分别反馈。账号切换/页面退出清理缓存和授权；异步复制有 epoch，旧结果不得归入新账号。

Android 使用私有只读 ContentProvider 与临时读取授权分享；iOS 使用 UIActivityViewController。Android SUCCESS 表示系统接收/展示分享面板，iOS completion 表示系统分享完成；均不证明远端收到文件。不扩展附件聊天、上传或持久保存系统 URI。

## 依据和验收

官方依据：[Android LAN 权限](https://developer.android.com/privacy-and-security/local-network-permission)、[NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager)、[Apple TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)、[Android SAF](https://developer.android.com/training/data-storage/shared/documents-files)、[UIDocumentPicker](https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller)。

[MX-A06](../tasks/active/MX-A06.md) 记录本轮真实测试、失败和设备边界。单元测试证明策略/代次；原生构建证明编译；模拟器不替代真机、Wi-Fi/LAN、锁屏、OEM 或签名发布验收。

### 本轮真机修正

iPhone26.6.1 对照中，NWBrowser 默认域返回0个服务，显式 `local.` 的各组返回4个。正式适配器固定 `local.`，与服务端协议一致；移除诊断广播后，真机仍发现真实 Spring 节点。默认浏览域不能当作 LAN 域保证。

连接验收必须记录服务端实际对端：Mac 的 `.local` 名称可能在数据线调试时优先解析到169.254链路。本轮先发现了这种情况，USB连接结果单列；后续固定Mac Wi-Fi地址，并用服务端已建立连接证明192.168.0.101 → 192.168.0.100。单次 Profile 探针只在输出目录的独立 Info.plist 中加入该IP的HTTP例外，正式 Release/仓库 Info.plist 不加全局任意加载开关。

权限设置跳转结果也必须可见。回到前台无提示查询通知状态，避免用户在设置中恢复权限后页面持续显示旧状态。设置页打开失败明确报告，不以点击按钮替代成功。
