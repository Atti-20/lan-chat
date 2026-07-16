-- v2.2 广播发布权限迁移
-- 先执行 migration-v2.2-emergency-broadcast.sql，再执行本文件。

SET @permission_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'user'
      AND column_name = 'can_send_broadcast'
);

SET @permission_ddl = IF(
    @permission_column_exists = 0,
    'ALTER TABLE `user` ADD COLUMN `can_send_broadcast` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否允许发布广播：0-否 1-是'' AFTER `status`',
    'SELECT 1'
);
PREPARE permission_statement FROM @permission_ddl;
EXECUTE permission_statement;
DEALLOCATE PREPARE permission_statement;

UPDATE `user`
SET `can_send_broadcast` = 1
WHERE `username` = 'admin';

-- 管理员只负责发布和查看统计，不作为接收者；清理旧版本生成的管理员回执。
SET @receiver_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'broadcast_receiver'
);
SET @receiver_cleanup = IF(
    @receiver_table_exists = 1,
    'DELETE br FROM `broadcast_receiver` br INNER JOIN `user` u ON u.id = br.user_id WHERE u.username = ''admin''',
    'SELECT 1'
);
PREPARE receiver_statement FROM @receiver_cleanup;
EXECUTE receiver_statement;
DEALLOCATE PREPARE receiver_statement;
