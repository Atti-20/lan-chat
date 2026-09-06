-- ============================================
-- LanChat V2.3.0 - 新环境完整数据库初始化脚本
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
    `can_send_broadcast` TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许发布广播：0-否 1-是',
    `archived_at`   DATETIME     DEFAULT NULL COMMENT '管理员归档时间；非空账号不可恢复为可登录状态',
    `archived_by`   BIGINT       DEFAULT NULL COMMENT '执行归档的管理员用户ID',
    `archive_reason` VARCHAR(255) DEFAULT NULL COMMENT '不含个人信息的归档原因',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `mute_start`    VARCHAR(5)   DEFAULT NULL COMMENT '全局免打扰开始时段（如22:00）',
    `mute_end`      VARCHAR(5)   DEFAULT NULL COMMENT '全局免打扰结束时段（如08:00）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_user_archive` (`status`, `archived_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ----------------------------
-- 管理员用户生命周期审计
-- ----------------------------
DROP TABLE IF EXISTS `admin_user_lifecycle_audit`;
CREATE TABLE `admin_user_lifecycle_audit` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '审计ID',
    `actor_user_id`  BIGINT       NOT NULL COMMENT '执行操作的管理员用户ID',
    `target_user_id` BIGINT       NOT NULL COMMENT '目标用户ID；物理擦除后仍保留',
    `action`         VARCHAR(30)  NOT NULL COMMENT 'ARCHIVED/PHYSICALLY_ERASED',
    `reason`         VARCHAR(500) NOT NULL COMMENT '不含敏感个人信息的操作原因',
    `detail`         VARCHAR(500) DEFAULT NULL COMMENT '仅保存保留/清理数量等机器信息',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_lifecycle_target_time` (`target_user_id`, `create_time`),
    KEY `idx_user_lifecycle_actor_time` (`actor_user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员账号归档与物理擦除审计';

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
    `receipt_start_sequence` BIGINT  NOT NULL DEFAULT 1 COMMENT '本轮成员资格可记录@收据的起始序列',
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
DROP TABLE IF EXISTS `mention_read_receipt`;
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
    `mention_user_ids` VARCHAR(4096) DEFAULT NULL COMMENT '@提及的用户ID快照（逗号分隔，最多200人）',
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
-- 群聊 @ 成员已读收据
-- ----------------------------
CREATE TABLE `mention_read_receipt` (
    `message_id`  VARCHAR(64) NOT NULL COMMENT '@消息服务端ID',
    `user_id`     BIGINT      NOT NULL COMMENT '实际阅读该消息的被@成员ID',
    `read_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次确认已读时间',
    PRIMARY KEY (`message_id`, `user_id`),
    KEY `idx_mention_receipt_user_time` (`user_id`, `read_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变的群聊@成员已读收据';

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
    `storage_type`  VARCHAR(16)  NOT NULL DEFAULT 'LOCAL' COMMENT '对象存储提供者：LOCAL/MINIO',
    `upload_user_id` BIGINT      NOT NULL COMMENT '上传者ID',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_hash` (`file_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据表';

-- ----------------------------
-- 可恢复分片上传会话
-- ----------------------------
DROP TABLE IF EXISTS `file_upload_part`;
DROP TABLE IF EXISTS `file_upload_session`;
DROP TABLE IF EXISTS `file_object_cleanup_task`;
CREATE TABLE `file_upload_session` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `upload_id`         VARCHAR(32)  NOT NULL COMMENT '服务端上传会话ID',
    `client_upload_id`  VARCHAR(80)  NOT NULL COMMENT '客户端稳定幂等ID',
    `user_id`           BIGINT       NOT NULL COMMENT '上传用户ID',
    `conversation_id`   VARCHAR(64)  NOT NULL COMMENT '目标会话ID',
    `file_name`         VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_size`         BIGINT       NOT NULL COMMENT '完整文件大小',
    `file_type`         VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream' COMMENT '浏览器声明MIME',
    `file_hash`         CHAR(64)     NOT NULL COMMENT '完整文件SHA-256',
    `chunk_size`        BIGINT       NOT NULL COMMENT '服务端分片大小',
    `total_parts`       INT          NOT NULL COMMENT '总分片数',
    `status`            VARCHAR(20)  NOT NULL COMMENT 'UPLOADING/COMPLETED/CANCELLED/EXPIRED',
    `storage_type`      VARCHAR(16)  NOT NULL DEFAULT 'LOCAL' COMMENT '分片存储提供者',
    `completed_file_id` BIGINT       DEFAULT NULL COMMENT '完成后的文件元数据ID',
    `expires_at`        DATETIME     NOT NULL COMMENT '会话过期时间',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_upload_id` (`upload_id`),
    UNIQUE KEY `uk_file_upload_user_client` (`user_id`, `client_upload_id`),
    KEY `idx_file_upload_expiry` (`status`, `expires_at`),
    KEY `idx_file_upload_conversation` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复分片上传会话';

CREATE TABLE `file_upload_part` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `upload_id`    VARCHAR(32)  NOT NULL COMMENT '服务端上传会话ID',
    `part_number`  INT          NOT NULL COMMENT '从1开始的分片序号',
    `part_size`    BIGINT       NOT NULL COMMENT '分片字节数',
    `part_hash`    CHAR(64)     NOT NULL COMMENT '分片SHA-256',
    `storage_path` VARCHAR(500) NOT NULL COMMENT '私有对象存储键',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_upload_part` (`upload_id`, `part_number`),
    KEY `idx_file_upload_part_upload` (`upload_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复上传分片';

CREATE TABLE `file_object_cleanup_task` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `storage_type`  VARCHAR(16)  NOT NULL COMMENT '对象存储提供者：LOCAL/MINIO',
    `object_key`    VARCHAR(500) NOT NULL COMMENT '待删除的私有对象键',
    `reason`        VARCHAR(80)  NOT NULL COMMENT '清理原因',
    `task_type`     VARCHAR(32)  NOT NULL DEFAULT 'DELETE' COMMENT 'DELETE/RECONCILE_UPLOAD_PART',
    `upload_id`     VARCHAR(32)  DEFAULT NULL COMMENT '待核对的上传会话ID',
    `part_number`   INT          DEFAULT NULL COMMENT '待核对的分片序号',
    `attempts`      INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `next_retry_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
    `last_error`    VARCHAR(240) DEFAULT NULL COMMENT '最近一次失败摘要',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_cleanup_object` (`storage_type`, `object_key`),
    KEY `idx_file_cleanup_retry` (`next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件对象持久化清理任务';

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
    `active_device_type` VARCHAR(20)
        GENERATED ALWAYS AS (CASE WHEN `status` = 1 THEN `device_type` ELSE NULL END) STORED
        COMMENT '仅活跃会话映射设备类型，用于单活唯一约束',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_token` (`token`(100)),
    KEY `idx_device_user_status` (`user_id`, `status`, `id`),
    KEY `idx_device_user_type_status` (`user_id`, `device_type`, `status`, `id`),
    UNIQUE KEY `uk_device_active_type` (`user_id`, `active_device_type`)
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
DROP TABLE IF EXISTS `broadcast_notice_outbox_task`;
DROP TABLE IF EXISTS `broadcast_evidence`;
DROP TABLE IF EXISTS `broadcast_receiver`;
DROP TABLE IF EXISTS `broadcast`;
CREATE TABLE `broadcast` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '广播ID',
    `sender_id`             BIGINT        NOT NULL COMMENT '创建者用户ID',
    `title`                 VARCHAR(100)  NOT NULL COMMENT '广播标题',
    `content`               TEXT          NOT NULL COMMENT '广播正文',
    `priority`              VARCHAR(20)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/IMPORTANT/EMERGENCY',
    `scope_type`            VARCHAR(20)   NOT NULL COMMENT 'ALL/USERS；GROUP为历史保留值',
    `scope_group_id`        BIGINT        DEFAULT NULL COMMENT '历史GROUP范围保留字段；V2.3创建流程不写入',
    `confirmation_required` TINYINT       NOT NULL DEFAULT 0 COMMENT '是否要求确认',
    `confirmation_options`  VARCHAR(1000) NOT NULL DEFAULT '[]' COMMENT '允许的确认值JSON数组',
    `deadline_at`           DATETIME      DEFAULT NULL COMMENT '确认截止时间',
    `bypass_mute`           TINYINT       NOT NULL DEFAULT 0 COMMENT '是否绕过普通免打扰',
    `repeat_reminder`       TINYINT       NOT NULL DEFAULT 0 COMMENT '是否允许重复提醒',
    `require_image_proof`   TINYINT       NOT NULL DEFAULT 0 COMMENT '完成时是否需要图片证据',
    `require_location_proof` TINYINT      NOT NULL DEFAULT 0 COMMENT '完成时是否需要定位证据',
    `completed_at`          DATETIME      DEFAULT NULL COMMENT '全体目标完成时间',
    `status`                VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/COMPLETED/CANCELLED',
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
    `target_status`       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REMOVED',
    `completed_at`        DATETIME DEFAULT NULL COMMENT '执行完成时间',
    `removed_at`          DATETIME DEFAULT NULL,
    `removed_by`          BIGINT DEFAULT NULL,
    `remind_count`        INT NOT NULL DEFAULT 0,
    `last_reminded_at`    DATETIME DEFAULT NULL,
    `notice_generation`  INT NOT NULL DEFAULT 1 COMMENT '通知卡接收周期；移出后重新加入时递增',
    `create_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_broadcast_receiver` (`broadcast_id`, `user_id`),
    KEY `idx_receiver_user_pending` (`user_id`, `confirm_status`, `viewed_at`),
    KEY `idx_receiver_broadcast_status` (`broadcast_id`, `confirm_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广播接收、查看与确认状态';

CREATE TABLE `broadcast_notice_outbox_task` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `idempotency_key`   VARCHAR(128) NOT NULL,
    `task_type`         VARCHAR(24)  NOT NULL COMMENT 'SYNC/RECALL',
    `notice_kind`       VARCHAR(24)  DEFAULT NULL COMMENT 'OVERVIEW/REMINDER for SYNC',
    `broadcast_id`      BIGINT       DEFAULT NULL,
    `receiver_user_id`  BIGINT       NOT NULL,
    `notice_generation` INT          DEFAULT NULL,
    `message_id`        VARCHAR(64)  DEFAULT NULL,
    `conversation_id`   VARCHAR(64)  DEFAULT NULL,
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DISPATCHED/REVOKED',
    `attempts`          INT          NOT NULL DEFAULT 0,
    `lease_token`       VARCHAR(64)  DEFAULT NULL,
    `lease_until`       DATETIME     DEFAULT NULL,
    `next_retry_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_error`        VARCHAR(240) DEFAULT NULL,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_broadcast_notice_outbox_key` (`idempotency_key`),
    KEY `idx_broadcast_notice_outbox_retry` (`status`, `next_retry_at`),
    KEY `idx_broadcast_notice_outbox_receiver` (`broadcast_id`, `receiver_user_id`, `notice_generation`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广播通知卡持久化投递与撤回任务';

CREATE TABLE `broadcast_evidence` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `broadcast_id` BIGINT NOT NULL,
    `receiver_id` BIGINT DEFAULT NULL,
    `user_id` BIGINT NOT NULL,
    `evidence_type` VARCHAR(30) NOT NULL COMMENT 'CONTENT_IMAGE/CONTENT_LOCATION/COMPLETION_IMAGE/COMPLETION_LOCATION',
    `file_id` BIGINT DEFAULT NULL,
    `latitude` DECIMAL(10, 7) DEFAULT NULL,
    `longitude` DECIMAL(10, 7) DEFAULT NULL,
    `accuracy_meters` DECIMAL(10, 2) DEFAULT NULL,
    `address_text` VARCHAR(255) DEFAULT NULL,
    `captured_at` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_broadcast_evidence_broadcast` (`broadcast_id`),
    KEY `idx_broadcast_evidence_receiver` (`receiver_id`),
    KEY `idx_broadcast_evidence_file` (`file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广播正文附件及完成证据';

-- ----------------------------
-- Control Server 组织、RBAC、设备身份与追加式审计（V2.7）
-- ----------------------------
SET @meshx_organization_key = COALESCE(NULLIF(@meshx_organization_key, ''), 'org-local');

CREATE TABLE IF NOT EXISTS `organization` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '组织内部主键',
    `organization_key` VARCHAR(64)  NOT NULL COMMENT '跨接口稳定组织标识',
    `name`             VARCHAR(100) NOT NULL COMMENT '组织名称',
    `status`           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `control_id`       VARCHAR(64)  DEFAULT NULL COMMENT '当前 Control Server 标识',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_organization_key` (`organization_key`),
    KEY `idx_organization_control` (`control_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Control Server 组织';

CREATE TABLE IF NOT EXISTS `organization_member` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '成员主键，与用户ID分离',
    `organization_id` BIGINT      NOT NULL,
    `user_id`         BIGINT      NOT NULL,
    `status`          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `department_id`   BIGINT      DEFAULT NULL,
    `joined_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `disabled_at`     DATETIME    DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_organization_member_user` (`organization_id`, `user_id`),
    KEY `idx_organization_member_user` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织成员身份';

CREATE TABLE IF NOT EXISTS `role` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id` BIGINT       NOT NULL,
    `code`            VARCHAR(50)  NOT NULL,
    `name`            VARCHAR(100) NOT NULL,
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    `system_role`     TINYINT      NOT NULL DEFAULT 1 COMMENT '系统角色不可由客户端改写',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_organization_code` (`organization_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织角色';

CREATE TABLE IF NOT EXISTS `permission` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(80)  NOT NULL,
    `description` VARCHAR(200) NOT NULL,
    `risk_level`  VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/HIGH/CRITICAL',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务端权限代码';

CREATE TABLE IF NOT EXISTS `role_permission` (
    `role_id`       BIGINT   NOT NULL,
    `permission_id` BIGINT   NOT NULL,
    `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`role_id`, `permission_id`),
    KEY `idx_role_permission_permission` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联';

CREATE TABLE IF NOT EXISTS `member_role` (
    `member_id`  BIGINT   NOT NULL,
    `role_id`    BIGINT   NOT NULL,
    `assigned_by` BIGINT  DEFAULT NULL COMMENT '授权者成员ID；迁移数据为空',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`, `role_id`),
    KEY `idx_member_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成员角色关联';

CREATE TABLE IF NOT EXISTS `device` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id` BIGINT       NOT NULL,
    `member_id`       BIGINT       DEFAULT NULL,
    `device_key`      VARCHAR(100) NOT NULL COMMENT '组织内稳定设备标识',
    `platform`        VARCHAR(30)  NOT NULL,
    `display_name`    VARCHAR(100) NOT NULL DEFAULT '',
    `app_version`     VARCHAR(50)  NOT NULL DEFAULT '',
    `capabilities_json` JSON       DEFAULT NULL,
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/REJECTED/REVOKED',
    `approved_by`     BIGINT       DEFAULT NULL,
    `approved_at`     DATETIME     DEFAULT NULL,
    `rejection_reason` VARCHAR(200) DEFAULT NULL,
    `revoked_by`      BIGINT       DEFAULT NULL,
    `revoked_at`      DATETIME     DEFAULT NULL,
    `last_seen_at`    DATETIME     DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_organization_key` (`organization_id`, `device_key`),
    KEY `idx_device_member_status` (`member_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织设备目录';

CREATE TABLE IF NOT EXISTS `device_credential` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `device_id`       BIGINT       NOT NULL,
    `credential_id`   VARCHAR(64)  NOT NULL,
    `algorithm`       VARCHAR(30)  NOT NULL,
    `public_key`      TEXT         NOT NULL,
    `fingerprint`     VARCHAR(128) NOT NULL,
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/REVOKED',
    `certificate_payload` TEXT     DEFAULT NULL,
    `certificate_signature` VARCHAR(128) DEFAULT NULL,
    `control_key_fingerprint` VARCHAR(128) DEFAULT NULL,
    `requested_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `approved_by`     BIGINT       DEFAULT NULL,
    `issued_at`       DATETIME     DEFAULT NULL,
    `expires_at`      DATETIME     DEFAULT NULL,
    `revoked_at`      DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_credential_id` (`credential_id`),
    UNIQUE KEY `uk_device_credential_fingerprint` (`fingerprint`),
    KEY `idx_device_credential_device` (`device_id`, `revoked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备公钥凭据';

CREATE TABLE IF NOT EXISTS `device_session` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id`    BIGINT       NOT NULL,
    `device_id`          BIGINT       NOT NULL,
    `user_id`            BIGINT       NOT NULL,
    `legacy_device_login_id` BIGINT   DEFAULT NULL,
    `access_token_hash`  VARCHAR(128) NOT NULL COMMENT '只保存访问令牌摘要',
    `refresh_token_hash` VARCHAR(128) NOT NULL COMMENT '只保存刷新令牌摘要',
    `status`             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    `issued_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expires_at`         DATETIME     NOT NULL,
    `revoked_at`         DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_session_access_hash` (`access_token_hash`),
    UNIQUE KEY `uk_device_session_legacy_login` (`legacy_device_login_id`),
    KEY `idx_device_session_device_status` (`device_id`, `status`, `expires_at`),
    KEY `idx_device_session_user_status` (`user_id`, `status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2 设备会话；迁移期与 device_login 并存';

CREATE TABLE IF NOT EXISTS `revocation_entry` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id` BIGINT       NOT NULL,
    `subject_type`    VARCHAR(30)  NOT NULL COMMENT 'DEVICE/CREDENTIAL/SESSION',
    `subject_id`      VARCHAR(128) NOT NULL,
    `version`         BIGINT       NOT NULL,
    `reason`          VARCHAR(200) NOT NULL,
    `signed_payload`  TEXT         NOT NULL,
    `signature`       VARCHAR(128) NOT NULL,
    `control_key_fingerprint` VARCHAR(128) NOT NULL,
    `revoked_by`      BIGINT       DEFAULT NULL,
    `revoked_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expires_at`      DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_revocation_subject` (`organization_id`, `subject_type`, `subject_id`),
    UNIQUE KEY `uk_revocation_version` (`organization_id`, `version`),
    KEY `idx_revocation_time` (`organization_id`, `revoked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线可查询的设备与会话吊销目录';

CREATE TABLE IF NOT EXISTS `organization_policy` (
    `id`                BIGINT      NOT NULL AUTO_INCREMENT,
    `organization_id`   BIGINT      NOT NULL,
    `registration_mode` VARCHAR(30) NOT NULL DEFAULT 'ADMIN_CREATED',
    `p2p_enabled`       TINYINT     NOT NULL DEFAULT 0,
    `device_approval_mode` VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    `credential_validity_days` INT NOT NULL DEFAULT 90,
    `max_offline_hours` INT NOT NULL DEFAULT 72,
    `revocation_version` BIGINT NOT NULL DEFAULT 0,
    `policy_json`       JSON        DEFAULT NULL,
    `version`           BIGINT      NOT NULL DEFAULT 1,
    `updated_by`        BIGINT      DEFAULT NULL,
    `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_organization_policy` (`organization_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织离线策略';

CREATE TABLE IF NOT EXISTS `audit_event` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id` BIGINT       NOT NULL,
    `actor_member_id` BIGINT       DEFAULT NULL,
    `actor_device_id` BIGINT       DEFAULT NULL,
    `action`          VARCHAR(80)  NOT NULL,
    `target_type`     VARCHAR(50)  DEFAULT NULL,
    `target_id`       VARCHAR(128) DEFAULT NULL,
    `outcome`         VARCHAR(20)  NOT NULL COMMENT 'SUCCEEDED/DENIED/FAILED',
    `request_id`      VARCHAR(80)  DEFAULT NULL,
    `detail_json`     JSON         DEFAULT NULL COMMENT '禁止写入口令、令牌和正文',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_organization_time` (`organization_id`, `created_at`),
    KEY `idx_audit_actor_time` (`actor_member_id`, `created_at`),
    KEY `idx_audit_target_time` (`target_type`, `target_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='追加式 Control 管理审计事件';

-- Enforce append-only semantics in the database, not merely in the controller surface.
DROP TRIGGER IF EXISTS `trg_audit_event_no_update`;
DROP TRIGGER IF EXISTS `trg_audit_event_no_delete`;
DELIMITER $$
CREATE TRIGGER `trg_audit_event_no_update`
BEFORE UPDATE ON `audit_event`
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_event is append-only';
END$$
CREATE TRIGGER `trg_audit_event_no_delete`
BEFORE DELETE ON `audit_event`
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_event is append-only';
END$$
DELIMITER ;

INSERT INTO `organization` (`organization_key`, `name`, `status`)
VALUES (@meshx_organization_key, '本地组织', 'ACTIVE')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `status` = VALUES(`status`);

INSERT INTO `role` (`organization_id`, `code`, `name`, `system_role`)
SELECT organization.id, seed.code, seed.name, 1
FROM `organization`
JOIN (
    SELECT 'ORG_OWNER' code, '组织所有者' name UNION ALL
    SELECT 'ORG_ADMIN', '组织管理员' UNION ALL
    SELECT 'SECURITY_ADMIN', '安全管理员' UNION ALL
    SELECT 'DEVICE_ADMIN', '设备管理员' UNION ALL
    SELECT 'DEPARTMENT_ADMIN', '部门管理员' UNION ALL
    SELECT 'AUDITOR', '审计员' UNION ALL
    SELECT 'MEMBER', '普通成员'
) seed
WHERE organization.organization_key = @meshx_organization_key
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `status` = 'ACTIVE';

INSERT INTO `permission` (`code`, `description`, `risk_level`) VALUES
    ('USER_READ', '查看组织成员', 'NORMAL'),
    ('USER_CREATE', '创建组织成员账号', 'HIGH'),
    ('USER_DISABLE', '停用或恢复组织成员', 'HIGH'),
    ('USER_DELETE', '归档或物理擦除组织成员', 'CRITICAL'),
    ('USER_PASSWORD_RESET', '重置组织成员口令', 'CRITICAL'),
    ('BROADCAST_CREATE', '创建应急广播', 'NORMAL'),
    ('BROADCAST_ALL', '向全组织创建广播', 'HIGH'),
    ('BROADCAST_PERMISSION_UPDATE', '修改成员广播发布资格', 'HIGH'),
    ('DIAGNOSTICS_READ', '读取 Control 诊断', 'NORMAL'),
    ('RUNTIME_LOG_READ', '读取或导出运行日志', 'HIGH'),
    ('DEVICE_APPROVE', '批准设备加入组织', 'HIGH'),
    ('DEVICE_REVOKE', '吊销设备与凭据', 'CRITICAL'),
    ('ROLE_ASSIGN', '分配组织角色', 'CRITICAL'),
    ('POLICY_UPDATE', '修改组织策略', 'CRITICAL'),
    ('AUDIT_READ', '读取组织审计事件', 'HIGH'),
    ('LICENSE_READ', '读取组织授权状态', 'NORMAL'),
    ('AI_TOOL_EXECUTE', '执行已批准的 AI 工具', 'HIGH'),
    ('AI_TOOL_APPROVE', '批准有副作用的 AI 工具', 'CRITICAL')
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`), `risk_level` = VALUES(`risk_level`);

-- The owner is the only initial role with every permission.
INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT role.id, permission.id
FROM `role`
JOIN `organization` ON organization.id = role.organization_id
CROSS JOIN `permission`
WHERE organization.organization_key = @meshx_organization_key AND role.code = 'ORG_OWNER';

-- Other built-in roles receive explicit least-privilege permission sets.
INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT role.id, permission.id
FROM `role`
JOIN `organization` ON organization.id = role.organization_id
JOIN `permission` ON
    (role.code = 'ORG_ADMIN' AND permission.code IN
        ('USER_READ','USER_CREATE','USER_DISABLE','USER_PASSWORD_RESET','BROADCAST_CREATE',
         'BROADCAST_ALL','BROADCAST_PERMISSION_UPDATE','DIAGNOSTICS_READ','RUNTIME_LOG_READ',
         'DEVICE_APPROVE','DEVICE_REVOKE','ROLE_ASSIGN','POLICY_UPDATE','AUDIT_READ','LICENSE_READ'))
 OR (role.code = 'SECURITY_ADMIN' AND permission.code IN
        ('USER_READ','USER_DISABLE','USER_PASSWORD_RESET','DEVICE_REVOKE','AUDIT_READ','RUNTIME_LOG_READ'))
 OR (role.code = 'DEVICE_ADMIN' AND permission.code IN
        ('USER_READ','DEVICE_APPROVE','DEVICE_REVOKE'))
 OR (role.code = 'DEPARTMENT_ADMIN' AND permission.code IN
        ('USER_READ','USER_CREATE','USER_DISABLE','BROADCAST_PERMISSION_UPDATE'))
 OR (role.code = 'AUDITOR' AND permission.code IN
        ('USER_READ','DIAGNOSTICS_READ','RUNTIME_LOG_READ','AUDIT_READ','LICENSE_READ'))
WHERE organization.organization_key = @meshx_organization_key;

INSERT IGNORE INTO `organization_policy`
    (`organization_id`, `registration_mode`, `p2p_enabled`, `version`)
SELECT id, 'ADMIN_CREATED', 0, 1 FROM `organization`
WHERE organization_key = @meshx_organization_key;

INSERT IGNORE INTO `organization_member` (`organization_id`, `user_id`, `status`)
SELECT organization.id, user.id,
       CASE WHEN user.status = 1 AND user.archived_at IS NULL THEN 'ACTIVE' ELSE 'DISABLED' END
FROM `organization`
CROSS JOIN `user`
WHERE organization.organization_key = @meshx_organization_key;

INSERT IGNORE INTO `member_role` (`member_id`, `role_id`)
SELECT member.id, role.id
FROM `organization_member` member
JOIN `organization` ON organization.id = member.organization_id
JOIN `role` ON role.organization_id = organization.id AND role.code = 'MEMBER'
WHERE organization.organization_key = @meshx_organization_key;

-- Bootstrap-name lookup is intentionally confined to this one-time compatibility migration.
INSERT IGNORE INTO `member_role` (`member_id`, `role_id`)
SELECT member.id, role.id
FROM `organization_member` member
JOIN `organization` ON organization.id = member.organization_id
JOIN `user` ON user.id = member.user_id
JOIN `role` ON role.organization_id = organization.id AND role.code = 'ORG_OWNER'
WHERE organization.organization_key = @meshx_organization_key AND user.username = 'admin';

-- 不在结构脚本中写入任何默认账号或口令。
-- 私有部署由 LANCHAT_BOOTSTRAP_ADMIN_PASSWORD 首次创建 admin；
-- 其他账号由管理员创建，或在允许自助注册的开发环境中注册。
