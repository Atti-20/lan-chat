# LANChat Android

此目录是 LANChat 的 Capacitor 8 Android 壳。它复用 `frontend/` 的 Vue 构建产物，
不复制业务页面或实时协议代码。

## 前置条件

- Node.js 20.19+
- JDK 17
- Android SDK（API 35 Build Tools、Platform Tools）和 Android Studio

首次安装依赖并生成 Android Studio 工程：

```bash
npm ci --prefix frontend
npm ci --prefix apps/mobile
npm run sync --prefix apps/mobile
```

调试 APK：

```bash
npm run build:apk:debug --prefix apps/mobile
```

安全 Debug APK 位于 `apps/mobile/android/app/build/outputs/apk/secure/debug/`。Android Studio 可直接打开
`apps/mobile/android/`。

受控局域网 HTTP 调试包必须明确调用下列命令；它使用独立的
`com.atti20.lanchat.lan` 应用 ID，且不会生成 LAN release 变体：

```bash
npm run build:apk:lan-debug --prefix apps/mobile
```

## 网络与安全边界

- 正式 `release` 构建默认只允许 HTTPS/WSS；不会在应用内放入服务器地址、密码或签名密钥。
- Capacitor WebView 的 Origin 为 `https://localhost`。部署节点需要在
  `CORS_ALLOWED_ORIGINS` 和 `WEBSOCKET_ALLOWED_ORIGINS` 中保留该精确 Origin。
- 受控的局域网 HTTP 仅提供给 `lanDebug` 变体，用于开发或管理员已确认的内网验证；不能作为
  正式发布渠道。生产节点优先 HTTPS/WSS。
- 当前 P1 基线支持登录、聊天、文件选择上传、手动节点连接、前后台恢复重连和前台本地通知。
  不持久化 Refresh Token；被系统终止后需要重新登录。后台可靠推送、mDNS/二维码导入和签名
  AAB 发布仍需后续交付。

## 签名

不提交 Android keystore。发布环境应通过受保护的 CI Secret 或 Android Studio 的签名配置
提供 keystore，再执行 `bundleRelease` 生成 AAB。
