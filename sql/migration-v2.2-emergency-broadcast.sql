-- LanChat V2.2 emergency broadcasts.
-- Safe to execute repeatedly on MySQL 8.

CREATE TABLE IF NOT EXISTS `broadcast` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '广播ID',
    `sender_id`             BIGINT       NOT NULL COMMENT '创建者用户ID',
    `title`                 VARCHAR(100) NOT NULL COMMENT '广播标题',
    `content`               TEXT         NOT NULL COMMENT '广播正文',
    `priority`              VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/IMPORTANT/EMERGENCY',
    `scope_type`            VARCHAR(20)  NOT NULL COMMENT 'ALL/GROUP/USERS',
    `scope_group_id`        BIGINT       DEFAULT NULL COMMENT 'GROUP范围对应群ID',
    `confirmation_required` TINYINT      NOT NULL DEFAULT 0 COMMENT '是否要求确认',
    `confirmation_options`  VARCHAR(1000) NOT NULL DEFAULT '[]' COMMENT '允许的确认值JSON数组',
    `deadline_at`           DATETIME     DEFAULT NULL COMMENT '确认截止时间',
    `bypass_mute`           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否绕过普通免打扰',
    `repeat_reminder`       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否允许重复提醒',
    `status`                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CANCELLED',
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_broadcast_sender_time` (`sender_id`, `create_time`),
    KEY `idx_broadcast_status_deadline` (`status`, `deadline_at`),
    KEY `idx_broadcast_group_time` (`scope_group_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应急广播';

CREATE TABLE IF NOT EXISTS `broadcast_receiver` (
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
