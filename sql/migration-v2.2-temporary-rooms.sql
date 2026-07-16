-- LanChat V2.2：临时协作房间领域数据。
-- 适用：已完成 migration-v2.0-reliable-messaging.sql 的 MySQL 8.0 数据库。
-- 本脚本可安全重复执行；房间成员复用 conversation_member。

USE lan_chat;

CREATE TABLE IF NOT EXISTS `temporary_room` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `room_name`             VARCHAR(50)  NOT NULL COMMENT '房间名称',
    `purpose`               VARCHAR(500) DEFAULT '' COMMENT '使用目的',
    `owner_id`              BIGINT       NOT NULL COMMENT '房间所有者用户ID',
    `room_code`             VARCHAR(12)  NOT NULL COMMENT '高熵房间码',
    `expires_at`            DATETIME     NOT NULL COMMENT '房间到期时间',
    `max_members`           INT          NOT NULL DEFAULT 50 COMMENT '成员上限',
    `allow_guests`          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否允许访客：0-否 1-是',
    `allow_member_invite`   TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许成员分享房间码',
    `allow_file_upload`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许上传文件',
    `allow_file_download`   TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许下载文件',
    `allow_forward`         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否允许转发',
    `message_retention_days` INT         NOT NULL DEFAULT 7 COMMENT '消息保存天数',
    `allow_external_sync`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否允许外部节点同步',
    `expire_action`         VARCHAR(20)  NOT NULL DEFAULT 'FREEZE' COMMENT 'FREEZE/ARCHIVE/DESTROY',
    `status`                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/FROZEN/ARCHIVED/DESTROYED',
    `create_time`           DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_temporary_room_code` (`room_code`),
    KEY `idx_temporary_room_owner` (`owner_id`),
    KEY `idx_temporary_room_expiry` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='临时协作房间扩展信息';
