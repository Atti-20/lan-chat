-- LanChat V2.1：文件访问审计。
-- 适用：已完成 migration-v2.0-reliable-messaging.sql 的 MySQL 8.0 数据库。
-- 本脚本可重复执行。

USE lan_chat;

CREATE TABLE IF NOT EXISTS `file_access_log` (
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
