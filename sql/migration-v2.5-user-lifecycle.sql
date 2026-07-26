-- V2.5 administrator account archive and high-risk physical-erasure audit.
-- Additive and repeatable: existing users, messages, receipts and audit rows are preserved.

USE lan_chat;

SET @archived_at_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user'
      AND column_name = 'archived_at'
);
SET @archived_at_ddl = IF(
    @archived_at_exists = 0,
    'ALTER TABLE `user` ADD COLUMN `archived_at` DATETIME DEFAULT NULL COMMENT ''管理员归档时间；非空账号不可恢复为可登录状态'' AFTER `can_send_broadcast`',
    'SELECT 1'
);
PREPARE archived_at_statement FROM @archived_at_ddl;
EXECUTE archived_at_statement;
DEALLOCATE PREPARE archived_at_statement;

SET @archived_by_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user'
      AND column_name = 'archived_by'
);
SET @archived_by_ddl = IF(
    @archived_by_exists = 0,
    'ALTER TABLE `user` ADD COLUMN `archived_by` BIGINT DEFAULT NULL COMMENT ''执行归档的管理员用户ID'' AFTER `archived_at`',
    'SELECT 1'
);
PREPARE archived_by_statement FROM @archived_by_ddl;
EXECUTE archived_by_statement;
DEALLOCATE PREPARE archived_by_statement;

SET @archive_reason_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user'
      AND column_name = 'archive_reason'
);
SET @archive_reason_ddl = IF(
    @archive_reason_exists = 0,
    'ALTER TABLE `user` ADD COLUMN `archive_reason` VARCHAR(255) DEFAULT NULL COMMENT ''不含个人信息的归档原因'' AFTER `archived_by`',
    'SELECT 1'
);
PREPARE archive_reason_statement FROM @archive_reason_ddl;
EXECUTE archive_reason_statement;
DEALLOCATE PREPARE archive_reason_statement;

SET @user_archive_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'user'
      AND index_name = 'idx_user_archive'
);
SET @user_archive_index_ddl = IF(
    @user_archive_index_exists = 0,
    'ALTER TABLE `user` ADD KEY `idx_user_archive` (`status`, `archived_at`)',
    'SELECT 1'
);
PREPARE user_archive_index_statement FROM @user_archive_index_ddl;
EXECUTE user_archive_index_statement;
DEALLOCATE PREPARE user_archive_index_statement;

CREATE TABLE IF NOT EXISTS `admin_user_lifecycle_audit` (
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
