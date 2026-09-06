-- V2.9 Broadcast notification-card lifecycle and retry consistency.
-- Run after V2.8. This migration is additive and repeatable.

USE lan_chat;

DROP PROCEDURE IF EXISTS `meshx_add_broadcast_notice_column`;
DELIMITER $$
CREATE PROCEDURE `meshx_add_broadcast_notice_column`(
    IN table_name_value VARCHAR(64),
    IN column_name_value VARCHAR(64),
    IN ddl_value TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = table_name_value
          AND column_name = column_name_value
    ) THEN
        SET @meshx_broadcast_notice_ddl = ddl_value;
        PREPARE meshx_broadcast_notice_statement FROM @meshx_broadcast_notice_ddl;
        EXECUTE meshx_broadcast_notice_statement;
        DEALLOCATE PREPARE meshx_broadcast_notice_statement;
    END IF;
END$$
DELIMITER ;

CALL `meshx_add_broadcast_notice_column`(
    'broadcast_receiver',
    'notice_generation',
    'ALTER TABLE `broadcast_receiver` ADD COLUMN `notice_generation` INT NOT NULL DEFAULT 1 COMMENT ''通知卡接收周期；移出后重新加入时递增'' AFTER `last_reminded_at`'
);

UPDATE `broadcast_receiver`
SET `notice_generation` = 1
WHERE `notice_generation` IS NULL OR `notice_generation` < 1;

DROP PROCEDURE `meshx_add_broadcast_notice_column`;
