# LanChat - 网络聊天室应用

一款支持私聊、群聊、文件共享与在线协作的即时通讯平台，基于 Java / Spring Boot / MyBatis Plus / WebSocket / Redis 构建。

## 技术栈

| 层面 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.5 + Java 17 |
| Web 前端 | Vue 3.5 + TypeScript + Vite 8 |
| 安全认证 | Spring Security + JWT（JJWT 0.12.6） |
| 数据库 | MySQL 8.0 + MyBatis Plus 3.5.7 |
| 缓存 | Redis（登录锁定 / 预览签名 / 会话缓存） |
| 实时通信 | Spring WebSocket |
| 工具库 | Hutool 5.8 + Lombok |

## 功能模块

### 用户账户与认证
- 注册（密码强度校验：8-20位含字母和数字，昵称2-16字符）
- 登录（JWT双令牌：Access Token 2h + Refresh Token 7d）
- 登录失败锁定（连续5次错误锁定30分钟，基于Redis计数）
- 多端设备管理（Web + App 同时在线，同类型设备互踢）
- 设备列表查看与主动下线

### 好友与私聊
- 好友申请（验证信息20字内，7天自动过期，黑名单互斥校验）
- 好友管理（备注名、分组、置顶上限5个、免打扰、拉黑/删除）
- 好友列表按最后通信时间排序，置顶优先
- 私聊消息类型：文本、图片、文件、语音
- 消息引用回复、@提及、正在输入提示
- 已读回执多端同步
- 非好友关系消息发送拦截

### 群聊管理
- 创建群组（群名2-20字符，上限200人）
- 群成员管理（添加/移除/禁言1分钟~30天）
- 群主转让、管理员设置（上限3名）
- 解散群聊
- 群聊消息发送前校验成员身份和禁言状态
- @群成员功能

### 消息增强
- 消息撤回（2分钟内，仅发送者可操作，撤回时清理文件存储）
- 阅后即焚（文本/图片/视频，5秒倒计时，file/voice不支持，截屏检测提醒）
- 多端同步焚毁
- 焚毁消息未阅读才可撤回

### 文件服务
- 文件上传（SHA-256哈希秒传检测，类型白名单校验，100MB限制）
- 图片自动生成300x300缩略图
- 临时签名预览URL（10分钟有效期，与用户Token绑定）
- 文件消息卡片展示

### 通知与免打扰
- 全局免打扰时段设置（如22:00-08:00，支持跨天）
- 单会话免打扰
- WebSocket实时推送上下线通知、在线用户列表

## 项目结构

```
src/main/java/com/lanchat/
├── LanChatServerApplication.java     # 启动类
├── common/                           # 通用层
│   ├── Result.java                   # 统一返回格式
│   └── GlobalExceptionHandler.java   # 全局异常处理
├── config/                           # 配置层
│   ├── WebSocketConfig.java          # WebSocket配置
│   └── WebMvcConfig.java             # Vue 应用入口映射
├── security/                         # 安全层
│   ├── JwtUtil.java                  # JWT工具类
│   ├── JwtAuthenticationFilter.java  # JWT认证过滤器
│   ├── SecurityConfig.java           # Spring Security配置
│   ├── LoginUser.java               # 登录用户信息
│   └── UserContextHolder.java       # 用户上下文工具
├── controller/                       # 控制器层
│   ├── AuthController.java           # 认证接口
│   ├── UserController.java           # 用户接口
│   ├── FriendController.java         # 好友接口
│   ├── GroupController.java          # 群组接口
│   ├── ChatController.java           # 消息接口
│   └── FileController.java           # 文件接口
├── dto/                              # 数据传输对象
├── entity/                           # 实体类
├── mapper/                           # MyBatis Plus Mapper
├── service/                          # 服务接口
│   └── impl/                         # 服务实现
└── websocket/                        # WebSocket处理器
    └── ChatWebSocketHandler.java     # 聊天WebSocket核心处理器
frontend/                              # Vue 3 + Vite 前端源码
└── src/
    ├── components/                    # 液态玻璃 UI 与聊天组件
    ├── composables/                   # 认证、聊天、WebSocket 状态
    ├── services/                      # 类型安全 API 封装
    └── views/                         # 登录、欢迎、聊天页面
```

## API 接口

所有接口统一前缀 `/api/v1`，返回格式 `{"code":200, "data":{}, "msg":"success"}`。

### 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/register` | 注册 |
| POST | `/api/v1/auth/login` | 登录 |
| POST | `/api/v1/auth/refresh` | 刷新Token |
| POST | `/api/v1/auth/logout` | 退出登录 |

### 用户
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/user/info` | 当前用户信息 |
| GET | `/api/v1/user/{id}` | 指定用户信息 |
| GET | `/api/v1/user/online` | 在线用户列表 |
| GET | `/api/v1/user/search` | 搜索用户 |
| GET | `/api/v1/user/devices` | 登录设备列表 |
| DELETE | `/api/v1/user/devices/{id}` | 退出指定设备 |
| PUT | `/api/v1/user/mute-period` | 设置免打扰时段 |
| GET | `/api/v1/user/mute-status` | 查询免打扰状态 |

### 好友
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/friend/request` | 发送好友申请 |
| GET | `/api/v1/friend/requests` | 好友申请列表 |
| POST | `/api/v1/friend/handle` | 处理好友申请 |
| GET | `/api/v1/friend/list` | 好友列表 |
| DELETE | `/api/v1/friend/{id}` | 删除好友 |
| PUT | `/api/v1/friend/{id}/block` | 拉黑/取消 |
| PUT | `/api/v1/friend/{id}/remark` | 设置备注 |
| PUT | `/api/v1/friend/{id}/group` | 设置分组 |
| PUT | `/api/v1/friend/{id}/pin` | 置顶切换 |
| PUT | `/api/v1/friend/{id}/mute` | 免打扰切换 |

### 群组
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/group` | 创建群组 |
| GET | `/api/v1/group/{id}` | 群组信息 |
| PUT | `/api/v1/group/{id}` | 更新群组 |
| GET | `/api/v1/group/my` | 我的群组 |
| GET | `/api/v1/group/{id}/members` | 群成员列表 |
| POST | `/api/v1/group/{id}/members` | 添加成员 |
| DELETE | `/api/v1/group/{id}/members/{memberId}` | 移除成员 |
| POST | `/api/v1/group/{id}/leave` | 退群 |
| PUT | `/api/v1/group/{id}/transfer` | 转让群主 |
| PUT | `/api/v1/group/{id}/admin` | 设置管理员 |
| PUT | `/api/v1/group/{id}/mute` | 禁言成员 |
| DELETE | `/api/v1/group/{id}/dissolve` | 解散群聊 |

### 消息
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/chat/history/group` | 群聊历史 |
| GET | `/api/v1/chat/history/private` | 私聊历史 |
| PUT | `/api/v1/chat/read` | 标记已读 |
| POST | `/api/v1/chat/recall` | 撤回消息 |
| POST | `/api/v1/chat/burn` | 焚毁消息 |
| GET | `/api/v1/chat/search` | 搜索消息 |

### 文件
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/file/check` | 秒传检测 |
| POST | `/api/v1/file/upload` | 文件上传 |
| GET | `/api/v1/file/content/{fileName}` | 鉴权读取文件内容 |
| POST | `/api/v1/file/preview-url` | 生成预览URL |
| GET | `/api/v1/file/preview/{signToken}` | 签名预览 |

### WebSocket
| 路径 | 说明 |
|------|------|
| `/ws/chat?token={accessToken}` | WebSocket连接（握手校验访问令牌） |

消息类型：`chat` / `recall` / `burn` / `read` / `typing` / `screenshot` / `system` / `online-list`

## 快速开始

### 环境要求
- JDK 17+
- Node.js 20.19+
- MySQL 8.0+
- Redis

### 步骤

1. 克隆仓库
```bash
git clone git@github.com:Atti-20/lan-chat.git
cd lan-chat
```

2. 初始化数据库
```bash
mysql -u root -p < sql/init.sql
```

已有数据库升级到文件权限索引和 CDN 缓存支持时，执行：
```bash
mysql -u root -p lan_chat < sql/migration-v1.1-file-access-cache.sql
```

3. 设置运行环境（生产环境务必设置独立的 `JWT_SECRET`）
```bash
export DB_URL='jdbc:mysql://localhost:3306/lan_chat?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USERNAME='root'
export DB_PASSWORD='your_password'
export REDIS_HOST='localhost'
export JWT_SECRET='replace-with-a-long-random-production-secret'
```

4. 构建 Vue 前端
```bash
cd frontend
npm install
npm run build
cd ..
```

构建产物会写入 `src/main/resources/static/app/`，继续由 Spring Boot 同源托管。

5. 创建文件上传目录
```bash
mkdir -p uploads
```

6. 启动项目
```bash
./mvnw spring-boot:run
```

### 测试账号

| 用户名 | 密码 | 昵称 |
|--------|------|------|
| admin | admin123456 | 管理员 |
| alice | 123456 | 爱丽丝 |
| bob | 123456 | 鲍勃 |

## 数据库表结构

| 表名 | 说明 |
|------|------|
| user | 用户表 |
| friendship | 好友关系表 |
| friend_request | 好友申请表 |
| chat_group | 群组表 |
| group_member | 群成员表 |
| chat_message | 聊天消息表 |
| message_recall | 消息撤回记录表 |
| file_metadata | 文件元数据表 |
| device_login | 设备登录表 |

## 文档

- [需求分析文档](需求分析.md)
- [功能分析文档](功能分析.md)

## License

MIT
