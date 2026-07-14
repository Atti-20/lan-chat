-- LanChat v1.1: make attachment permission checks indexable.
-- Run once against an existing database before restarting the application.

USE lan_chat;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_message'
      AND column_name = 'file_path'
);
SET @sql := IF(
    @column_exists = 0,
    'ALTER TABLE chat_message ADD COLUMN file_path VARCHAR(100) DEFAULT NULL COMMENT ''附件原始存储文件名（用于权限查询）'' AFTER type',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_message'
      AND index_name = 'idx_file_path'
);
SET @sql := IF(
    @index_exists = 0,
    'ALTER TABLE chat_message ADD KEY idx_file_path (file_path)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Backfill current JSON and legacy /file/ attachment messages. The optional
-- thumb_ prefix is removed so thumbnails and originals share one permission key.
UPDATE chat_message
SET file_path = REGEXP_REPLACE(
    REGEXP_SUBSTR(content, '(thumb_)?[0-9A-Fa-f]{32}[.][A-Za-z0-9]{1,10}'),
    '^thumb_',
    ''
)
WHERE file_path IS NULL
  AND type IN ('image', 'file', 'voice', 'video')
  AND content REGEXP '(thumb_)?[0-9A-Fa-f]{32}[.][A-Za-z0-9]{1,10}';
