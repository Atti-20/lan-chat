-- ============================================
-- 网络聊天室 - 数据库初始化脚本 V1.0
-- ============================================

CREATE DATABASE IF NOT EXISTS lan_chat DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE lan_chat;

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
-- 聊天消息表
-- ----------------------------
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `message_id`      VARCHAR(64)  NOT NULL COMMENT '消息唯一标识（UUID）',
    `from_user_id`    BIGINT       NOT NULL COMMENT '发送者用户ID',
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
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`),
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
-- 插入测试用户（密码: 123456，BCrypt加密）
-- ----------------------------
INSERT INTO `user` (`username`, `password`, `nickname`, `avatar`, `status`) VALUES
('admin', '$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6', '管理员', '', 1),
('alice', '$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6', '爱丽丝', '', 1),
('bob',   '$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6', '鲍勃', '', 1);
