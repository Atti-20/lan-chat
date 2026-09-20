-- V3.0 Group @-message immutable read receipts.
-- Run after V2.9 with application instances stopped. Safe to re-run.

USE lan_chat;

DROP PROCEDURE IF EXISTS `meshx_upgrade_mention_read_receipts`;
DELIMITER $$
CREATE PROCEDURE `meshx_upgrade_mention_read_receipts`()
BEGIN
    DECLARE mention_column_length BIGINT DEFAULT NULL;
    DECLARE receipt_start_column_exists INT DEFAULT 0;

    SELECT character_maximum_length INTO mention_column_length
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_message'
      AND column_name = 'mention_user_ids'
    LIMIT 1;

    IF mention_column_length IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'chat_message.mention_user_ids is required before V3.0';
    END IF;

    IF mention_column_length < 4096 THEN
        ALTER TABLE `chat_message`
            MODIFY COLUMN `mention_user_ids` VARCHAR(4096) DEFAULT NULL
            COMMENT '@提及的用户ID快照（逗号分隔，最多200人）';
    END IF;

    SELECT COUNT(*) INTO receipt_start_column_exists
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'conversation_member'
      AND column_name = 'receipt_start_sequence';

    IF receipt_start_column_exists = 0 THEN
        ALTER TABLE `conversation_member`
            ADD COLUMN `receipt_start_sequence` BIGINT NOT NULL DEFAULT 1
            COMMENT '本轮成员资格可记录@收据的起始序列'
            AFTER `last_read_sequence`;
    END IF;

    CREATE TABLE IF NOT EXISTS `mention_read_receipt` (
        `message_id`  VARCHAR(64) NOT NULL COMMENT '@消息服务端ID',
        `user_id`     BIGINT      NOT NULL COMMENT '实际阅读该消息的被@成员ID',
        `read_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次确认已读时间',
        PRIMARY KEY (`message_id`, `user_id`),
        KEY `idx_mention_receipt_user_time` (`user_id`, `read_at`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变的群聊@成员已读收据';
END$$
DELIMITER ;

CALL `meshx_upgrade_mention_read_receipts`();
DROP PROCEDURE `meshx_upgrade_mention_read_receipts`;
