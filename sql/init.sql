-- ============================================
-- 网络聊天室 - 数据库初始化脚本 V1.0
-- ============================================

CREATE DATABASE IF NOT EXISTS lan_chat DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE lan_chat;
-- 明确指定初始化脚本的客户端字符集，避免中文种子数据被按 latin1 写入。
SET NAMES utf8mb4;

-- ----------------------------
-- 用户表
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`      VARCHAR(50)  NOT NULL COMMENT '用户名（手机号或邮箱）',
    `password`      VARCHAR(100) NOT NULL COMMENT '密码（BCrypt加密）',
    `nickname`      VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `avatar`        VARCHAR(255) DEFAULT '' COMMENT '头像URL',
    `signature`     VARCHAR(200) DEFAULT '' COMMENT '个性签名',
    `online`        TINYINT      DEFAULT 0 COMMENT '在线状态：0-离线 1-在线',
    `last_login_at` DATETIME     DEFAULT NULL COMMENT '最后登录时间',
    `status`        TINYINT      DEFAULT 1 COMMENT '账号状态：0-锁定 1-正常',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `mute_start`    VARCHAR(5)   DEFAULT NULL COMMENT '全局免打扰开始时段（如22:00）',
    `mute_end`      VARCHAR(5)   DEFAULT NULL COMMENT '全局免打扰结束时段（如08:00）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ----------------------------
-- 好友关系表
-- ----------------------------
DROP TABLE IF EXISTS `friendship`;
CREATE TABLE `friendship` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `friend_id`     BIGINT       NOT NULL COMMENT '好友ID',
    `remark`        VARCHAR(50)  DEFAULT '' COMMENT '好友备注名',
    `group_name`    VARCHAR(50)  DEFAULT '我的好友' COMMENT '好友分组名',
    `is_blocked`    TINYINT      DEFAULT 0 COMMENT '是否拉黑：0-否 1-是',
    `is_muted`      TINYINT      DEFAULT 0 COMMENT '是否免打扰：0-否 1-是',
    `is_pinned`     TINYINT      DEFAULT 0 COMMENT '是否置顶：0-否 1-是',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_friend` (`user_id`, `friend_id`),
    KEY `idx_friend_id` (`friend_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='好友关系表';

-- ----------------------------
-- 好友申请表
-- ----------------------------
DROP TABLE IF EXISTS `friend_request`;
CREATE TABLE `friend_request` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `from_user_id` BIGINT       NOT NULL COMMENT '申请者ID',
    `to_user_id`   BIGINT       NOT NULL COMMENT '被申请者ID',
    `message`      VARCHAR(200) DEFAULT '' COMMENT '验证信息',
    `status`       TINYINT      DEFAULT 0 COMMENT '状态：0-待处理 1-已同意 2-已拒绝',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `handle_time`  DATETIME     DEFAULT NULL COMMENT '处理时间',
    PRIMARY KEY (`id`),
    KEY `idx_to_user` (`to_user_id`),
    KEY `idx_from_user` (`from_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='好友申请表';

-- ----------------------------
-- 群组表
-- ----------------------------
DROP TABLE IF EXISTS `chat_group`;
CREATE TABLE `chat_group` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `group_name`    VARCHAR(100) NOT NULL COMMENT '群名称',
    `avatar`        VARCHAR(255) DEFAULT '' COMMENT '群头像',
    `announcement`  TEXT         COMMENT '群公告',
    `owner_id`      BIGINT       NOT NULL COMMENT '群主用户ID',
    `max_members`   INT          DEFAULT 200 COMMENT '最大成员数',
    `join_mode`     TINYINT      DEFAULT 0 COMMENT '入群方式：0-允许任何人 1-需审核 2-禁止加入',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群组表';

-- ----------------------------
-- 群成员表
-- ----------------------------
DROP TABLE IF EXISTS `group_member`;
CREATE TABLE `group_member` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `group_id`     BIGINT      NOT NULL COMMENT '群组ID',
    `user_id`      BIGINT      NOT NULL COMMENT '用户ID',
    `role`         TINYINT     DEFAULT 0 COMMENT '角色：0-普通成员 1-管理员 2-群主',
    `mute_until`   DATETIME    DEFAULT NULL COMMENT '禁言截止时间',
    `join_time`    DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_user` (`group_id`, `user_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='群成员表';

-- ----------------------------
-- 统一会话表（LAN-first V2.0）
-- ----------------------------
DROP TABLE IF EXISTS `conversation_member`;
DROP TABLE IF EXISTS `conversation`;
CREATE TABLE `conversation` (
    `id`              VARCHAR(64)  NOT NULL COMMENT '确定性会话ID：private:min:max / group:id',
    `type`            VARCHAR(20)  NOT NULL COMMENT 'PRIVATE/GROUP/TEMPORARY/SYSTEM/BROADCAST',
    `source_id`       BIGINT       DEFAULT NULL COMMENT '群组或扩展资源ID',
    `last_message_id` VARCHAR(64)  DEFAULT NULL COMMENT '最后一条消息ID',
    `last_sequence`   BIGINT       NOT NULL DEFAULT 0 COMMENT '会话最后序列号',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/READ_ONLY/ARCHIVED/DESTROYED',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_type_source` (`type`, `source_id`),
    KEY `idx_conversation_update` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一会话表';

CREATE TABLE `conversation_member` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `conversation_id`    VARCHAR(64) NOT NULL COMMENT '会话ID',
    `user_id`            BIGINT      NOT NULL COMMENT '成员用户ID',
    `role`               VARCHAR(20) NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER/ADMIN/MEMBER/READ_ONLY',
    `last_read_sequence` BIGINT      NOT NULL DEFAULT 0 COMMENT '最后已读序列号',
    `unread_count`       INT         NOT NULL DEFAULT 0 COMMENT '未读数量缓存',
    `is_muted`           TINYINT     NOT NULL DEFAULT 0 COMMENT '是否免打扰',
    `is_pinned`          TINYINT     NOT NULL DEFAULT 0 COMMENT '是否置顶',
    `join_time`          DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    `left_time`          DATETIME    DEFAULT NULL COMMENT '退出时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_user` (`conversation_id`, `user_id`),
    KEY `idx_member_user` (`user_id`, `left_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话成员与已读位置';

-- ----------------------------
-- 聊天消息表
-- ----------------------------
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `message_id`      VARCHAR(64)  NOT NULL COMMENT '服务端消息唯一标识（UUID）',
    `client_msg_id`   VARCHAR(64)  NOT NULL COMMENT '客户端幂等消息ID',
    `conversation_id` VARCHAR(64)  NOT NULL COMMENT '统一会话ID',
    `sequence`        BIGINT       NOT NULL COMMENT '会话内递增序列号',
    `from_user_id`    BIGINT       NOT NULL COMMENT '发送者用户ID',
    `sender_device_id` BIGINT      DEFAULT NULL COMMENT '发送设备会话ID',
    `to_user_id`      BIGINT       DEFAULT NULL COMMENT '接收者用户ID（私聊）',
    `group_id`        BIGINT       DEFAULT NULL COMMENT '群组ID（群聊）',
    `type`            VARCHAR(20)  DEFAULT 'text' COMMENT '消息类型：text/image/file/voice/video',
    `file_path`       VARCHAR(100) DEFAULT NULL COMMENT '附件原始存储文件名（用于权限查询）',
    `content`         MEDIUMTEXT   COMMENT '消息内容',
    `reply_to_id`     VARCHAR(64)  DEFAULT NULL COMMENT '引用回复的消息ID',
    `mention_user_ids` VARCHAR(500) DEFAULT NULL COMMENT '@提及的用户ID列表（逗号分隔）',
    `is_burn`         TINYINT      DEFAULT 0 COMMENT '是否阅后即焚：0-否 1-是',
    `burn_duration`   INT          DEFAULT 5 COMMENT '焚毁倒计时（秒）',
    `is_recalled`     TINYINT      DEFAULT 0 COMMENT '是否已撤回：0-否 1-是',
    `status`          TINYINT      DEFAULT 0 COMMENT '消息状态：0-未读 1-已读 2-已焚毁',
    `client_created_at` DATETIME   DEFAULT NULL COMMENT '客户端创建时间（诊断用途）',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`),
    UNIQUE KEY `uk_sender_client_msg` (`from_user_id`, `client_msg_id`),
    UNIQUE KEY `uk_conversation_sequence` (`conversation_id`, `sequence`),
    KEY `idx_conversation_sequence` (`conversation_id`, `sequence`),
    KEY `idx_from_user` (`from_user_id`),
    KEY `idx_to_user` (`to_user_id`),
    KEY `idx_group` (`group_id`),
    KEY `idx_file_path` (`file_path`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息表';

-- ----------------------------
-- 消息撤回记录表
-- ----------------------------
DROP TABLE IF EXISTS `message_recall`;
CREATE TABLE `message_recall` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `message_id`   VARCHAR(64) NOT NULL COMMENT '被撤回的消息ID',
    `operator_id`  BIGINT      NOT NULL COMMENT '操作者ID',
    `recall_time`  DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '撤回时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息撤回记录表';

-- ----------------------------
-- 文件元数据表
-- ----------------------------
DROP TABLE IF EXISTS `file_metadata`;
CREATE TABLE `file_metadata` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `file_hash`     VARCHAR(64)  NOT NULL COMMENT '文件哈希值（SHA-256）',
    `file_name`     VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_path`     VARCHAR(500) NOT NULL COMMENT '存储路径',
    `file_size`     BIGINT       NOT NULL COMMENT '文件大小（字节）',
    `file_type`     VARCHAR(255) DEFAULT '' COMMENT '文件MIME类型',
    `file_suffix`   VARCHAR(20)  DEFAULT '' COMMENT '文件后缀',
    `upload_user_id` BIGINT      NOT NULL COMMENT '上传者ID',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_hash` (`file_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据表';

-- ----------------------------
-- 文件对象访问授权（哈希存在不等于有权引用）
-- ----------------------------
DROP TABLE IF EXISTS `file_access_grant`;
CREATE TABLE `file_access_grant` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `file_id`     BIGINT      NOT NULL COMMENT '文件元数据ID',
    `user_id`     BIGINT      NOT NULL COMMENT '获授权用户ID',
    `grant_type`  VARCHAR(20) NOT NULL COMMENT 'UPLOADER/UPLOAD_PROOF',
    `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_user` (`file_id`, `user_id`),
    KEY `idx_grant_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件对象访问授权';

-- ----------------------------
-- 文件访问审计（不记录签名 Token 或文件正文）
-- ----------------------------
DROP TABLE IF EXISTS `file_access_log`;
CREATE TABLE `file_access_log` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `file_id`        BIGINT      NOT NULL COMMENT '文件元数据ID',
    `user_id`        BIGINT      NOT NULL COMMENT '访问用户ID',
    `action`         VARCHAR(20) NOT NULL COMMENT 'PREVIEW_URL/PREVIEW/DOWNLOAD/CONTENT',
    `result`         VARCHAR(20) NOT NULL COMMENT 'ALLOWED/DENIED/REVOKED',
    `request_id`     VARCHAR(80) DEFAULT NULL COMMENT '请求追踪ID',
    `client_address` VARCHAR(64) DEFAULT NULL COMMENT '客户端地址',
    `create_time`    DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '访问时间',
    PRIMARY KEY (`id`),
    KEY `idx_file_access_file_time` (`file_id`, `create_time`),
    KEY `idx_file_access_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件访问审计日志';

-- ----------------------------
-- 设备登录表
-- ----------------------------
DROP TABLE IF EXISTS `device_login`;
CREATE TABLE `device_login` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `device_type`  VARCHAR(20)  NOT NULL COMMENT '设备类型：web/android/ios',
    `device_name`  VARCHAR(100) DEFAULT '' COMMENT '设备名称',
    `token`        VARCHAR(500) NOT NULL COMMENT 'JWT Token',
    `refresh_token` VARCHAR(500) NOT NULL COMMENT '刷新令牌',
    `login_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    `expire_time`  DATETIME     NOT NULL COMMENT '过期时间',
    `status`       TINYINT      DEFAULT 1 COMMENT '状态：0-已退出 1-有效',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_token` (`token`(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备登录表';

-- ----------------------------
-- 临时协作房间（成员复用 conversation_member）
-- ----------------------------
DROP TABLE IF EXISTS `temporary_room`;
CREATE TABLE `temporary_room` (
    `id`                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `room_name`              VARCHAR(50)  NOT NULL COMMENT '房间名称',
    `purpose`                VARCHAR(500) DEFAULT '' COMMENT '使用目的',
    `owner_id`               BIGINT       NOT NULL COMMENT '房间所有者用户ID',
    `room_code`              VARCHAR(12)  NOT NULL COMMENT '高熵房间码',
    `expires_at`             DATETIME     NOT NULL COMMENT '房间到期时间',
    `max_members`            INT          NOT NULL DEFAULT 50 COMMENT '成员上限',
    `allow_guests`           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否允许访客：0-否 1-是',
    `allow_member_invite`    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许成员分享房间码',
    `allow_file_upload`      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许上传文件',
    `allow_file_download`    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许下载文件',
    `allow_forward`          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否允许转发',
    `message_retention_days` INT          NOT NULL DEFAULT 7 COMMENT '消息保存天数',
    `allow_external_sync`    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否允许外部节点同步',
    `expire_action`          VARCHAR(20)  NOT NULL DEFAULT 'FREEZE' COMMENT 'FREEZE/ARCHIVE/DESTROY',
    `status`                 VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/FROZEN/ARCHIVED/DESTROYED',
    `create_time`            DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`            DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_temporary_room_code` (`room_code`),
    KEY `idx_temporary_room_owner` (`owner_id`),
    KEY `idx_temporary_room_expiry` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='临时协作房间扩展信息';

-- ----------------------------
-- WebRTC 文件直传与节点中转状态（逻辑关联 file_metadata）
-- ----------------------------
DROP TABLE IF EXISTS `file_transfer`;
CREATE TABLE `file_transfer` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `transfer_id`        VARCHAR(64)  NOT NULL COMMENT '服务端传输唯一标识',
    `client_transfer_id` VARCHAR(64)  NOT NULL COMMENT '发送者范围内的幂等键',
    `conversation_id`    VARCHAR(64)  NOT NULL COMMENT '私聊会话ID',
    `sender_user_id`     BIGINT       NOT NULL COMMENT '发送用户ID',
    `sender_device_id`   BIGINT       NOT NULL COMMENT '发起设备会话ID',
    `receiver_user_id`   BIGINT       NOT NULL COMMENT '接收用户ID',
    `receiver_device_id` BIGINT       DEFAULT NULL COMMENT '首个认领的接收设备会话ID',
    `file_name`          VARCHAR(180) NOT NULL COMMENT '净化后的原始文件名',
    `file_size`          BIGINT       NOT NULL COMMENT '文件大小（字节）',
    `file_type`          VARCHAR(120) NOT NULL COMMENT '客户端声明的MIME，仅作元数据',
    `file_hash`          CHAR(64)     NOT NULL COMMENT '双方校验的SHA-256',
    `status`             VARCHAR(24)  NOT NULL COMMENT 'OFFERED/CLAIMED/NEGOTIATING/TRANSFERRING/P2P_COMPLETED/RELAY_PENDING/RELAY_COMPLETED/FAILED/EXPIRED',
    `transport_path`     VARCHAR(20)  NOT NULL COMMENT 'PENDING/PEER_TO_PEER/NODE_RELAY',
    `file_metadata_id`   BIGINT       DEFAULT NULL COMMENT '节点中转完成后的文件元数据ID',
    `stored_file_name`   VARCHAR(100) DEFAULT NULL COMMENT '节点中转完成后的安全存储名',
    `fallback_reason`    VARCHAR(64)  DEFAULT NULL COMMENT '脱敏机器原因码',
    `expires_at`         DATETIME     NOT NULL COMMENT '未完成阶段截止时间',
    `claimed_time`       DATETIME     DEFAULT NULL COMMENT '接收设备认领时间',
    `completed_time`     DATETIME     DEFAULT NULL COMMENT 'P2P或节点中转完成时间',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_transfer_id` (`transfer_id`),
    UNIQUE KEY `uk_file_transfer_sender_client` (`sender_user_id`, `client_transfer_id`),
    KEY `idx_file_transfer_receiver_status` (`receiver_user_id`, `status`, `expires_at`),
    KEY `idx_file_transfer_sender_status` (`sender_user_id`, `status`, `expires_at`),
    KEY `idx_file_transfer_conversation` (`conversation_id`, `create_time`),
    KEY `idx_file_transfer_expiry` (`status`, `expires_at`),
    KEY `idx_file_transfer_metadata` (`file_metadata_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WebRTC文件直传与节点中转状态';

-- ----------------------------
-- 应急广播及持久接收回执
-- ----------------------------
DROP TABLE IF EXISTS `broadcast_receiver`;
DROP TABLE IF EXISTS `broadcast`;
CREATE TABLE `broadcast` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '广播ID',
    `sender_id`             BIGINT        NOT NULL COMMENT '创建者用户ID',
    `title`                 VARCHAR(100)  NOT NULL COMMENT '广播标题',
    `content`               TEXT          NOT NULL COMMENT '广播正文',
    `priority`              VARCHAR(20)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/IMPORTANT/EMERGENCY',
    `scope_type`            VARCHAR(20)   NOT NULL COMMENT 'ALL/GROUP/USERS',
    `scope_group_id`        BIGINT        DEFAULT NULL COMMENT 'GROUP范围对应群ID',
    `confirmation_required` TINYINT       NOT NULL DEFAULT 0 COMMENT '是否要求确认',
    `confirmation_options`  VARCHAR(1000) NOT NULL DEFAULT '[]' COMMENT '允许的确认值JSON数组',
    `deadline_at`           DATETIME      DEFAULT NULL COMMENT '确认截止时间',
    `bypass_mute`           TINYINT       NOT NULL DEFAULT 0 COMMENT '是否绕过普通免打扰',
    `repeat_reminder`       TINYINT       NOT NULL DEFAULT 0 COMMENT '是否允许重复提醒',
    `status`                VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CANCELLED',
    `create_time`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_broadcast_sender_time` (`sender_id`, `create_time`),
    KEY `idx_broadcast_status_deadline` (`status`, `deadline_at`),
    KEY `idx_broadcast_group_time` (`scope_group_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应急广播';

CREATE TABLE `broadcast_receiver` (
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '接收记录ID',
    `broadcast_id`        BIGINT      NOT NULL COMMENT '广播ID',
    `user_id`             BIGINT      NOT NULL COMMENT '接收者用户ID',
    `delivered_at`        DATETIME    DEFAULT NULL COMMENT '首次送达时间',
    `viewed_at`           DATETIME    DEFAULT NULL COMMENT '首次查看时间',
    `confirm_status`      VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/NOT_REQUIRED/确认值',
    `confirmed_at`        DATETIME    DEFAULT NULL COMMENT '确认时间',
    `confirm_device_type` VARCHAR(50) DEFAULT NULL COMMENT '确认设备类型',
    `create_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_broadcast_receiver` (`broadcast_id`, `user_id`),
    KEY `idx_receiver_user_pending` (`user_id`, `confirm_status`, `viewed_at`),
    KEY `idx_receiver_broadcast_status` (`broadcast_id`, `confirm_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广播接收、查看与确认状态';

-- 不在结构脚本中写入任何默认账号或口令。
-- 私有部署由 LANCHAT_BOOTSTRAP_ADMIN_PASSWORD 首次创建 admin；
-- 如需本地演示账号，可在开发数据库中手动执行 sql/demo-data.sql。
