-- V2.6 eliminate active-session range locks and enforce one active device type.
-- Run while application instances are stopped. Repeatable and data-preserving:
-- historical sessions remain, while duplicate active rows are deactivated.

USE lan_chat;

SET @active_device_type_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'device_login'
      AND column_name = 'active_device_type'
);
SET @active_device_type_ddl = IF(
    @active_device_type_exists = 0,
    'ALTER TABLE `device_login` ADD COLUMN `active_device_type` VARCHAR(20) GENERATED ALWAYS AS (CASE WHEN `status` = 1 THEN `device_type` ELSE NULL END) STORED COMMENT ''仅活跃会话映射设备类型，用于单活唯一约束'' AFTER `status`',
    'SELECT 1'
);
PREPARE active_device_type_statement FROM @active_device_type_ddl;
EXECUTE active_device_type_statement;
DEALLOCATE PREPARE active_device_type_statement;

-- Keep the newest row active for each user/device type before creating the unique key.
UPDATE `device_login` stale
JOIN `device_login` newer
  ON newer.user_id = stale.user_id
 AND newer.device_type = stale.device_type
 AND newer.status = 1
 AND newer.id > stale.id
SET stale.status = 0
WHERE stale.status = 1;

SET @device_user_status_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'device_login'
      AND index_name = 'idx_device_user_status'
);
SET @device_user_status_index_ddl = IF(
    @device_user_status_index_exists = 0,
    'ALTER TABLE `device_login` ADD KEY `idx_device_user_status` (`user_id`, `status`, `id`)',
    'SELECT 1'
);
PREPARE device_user_status_index_statement FROM @device_user_status_index_ddl;
EXECUTE device_user_status_index_statement;
DEALLOCATE PREPARE device_user_status_index_statement;

SET @device_user_type_status_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'device_login'
      AND index_name = 'idx_device_user_type_status'
);
SET @device_user_type_status_index_ddl = IF(
    @device_user_type_status_index_exists = 0,
    'ALTER TABLE `device_login` ADD KEY `idx_device_user_type_status` (`user_id`, `device_type`, `status`, `id`)',
    'SELECT 1'
);
PREPARE device_user_type_status_index_statement FROM @device_user_type_status_index_ddl;
EXECUTE device_user_type_status_index_statement;
DEALLOCATE PREPARE device_user_type_status_index_statement;

SET @device_active_unique_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'device_login'
      AND index_name = 'uk_device_active_type'
);
SET @device_active_unique_ddl = IF(
    @device_active_unique_exists = 0,
    'ALTER TABLE `device_login` ADD UNIQUE KEY `uk_device_active_type` (`user_id`, `active_device_type`)',
    'SELECT 1'
);
PREPARE device_active_unique_statement FROM @device_active_unique_ddl;
EXECUTE device_active_unique_statement;
DEALLOCATE PREPARE device_active_unique_statement;
