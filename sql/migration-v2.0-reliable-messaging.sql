-- LanChat V2.0：统一会话、clientMsgId 幂等与会话序列号
-- 适用：已经运行过 V1.x init.sql 的 MySQL 8.0 数据库。
-- 本脚本通过 information_schema 检查字段和索引，可安全重复执行。

USE lan_chat;

CREATE TABLE IF NOT EXISTS `file_access_grant` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `file_id`     BIGINT      NOT NULL,
    `user_id`     BIGINT      NOT NULL,
    `grant_type`  VARCHAR(20) NOT NULL,
    `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_user` (`file_id`, `user_id`),
    KEY `idx_grant_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件对象访问授权';

INSERT IGNORE INTO `file_access_grant` (`file_id`, `user_id`, `grant_type`, `create_time`)
SELECT `id`, `upload_user_id`, 'UPLOADER', `create_time`
FROM `file_metadata`;

CREATE TABLE IF NOT EXISTS `conversation` (
    `id`              VARCHAR(64)  NOT NULL,
    `type`            VARCHAR(20)  NOT NULL,
    `source_id`       BIGINT       DEFAULT NULL,
    `last_message_id` VARCHAR(64)  DEFAULT NULL,
    `last_sequence`   BIGINT       NOT NULL DEFAULT 0,
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_type_source` (`type`, `source_id`),
    KEY `idx_conversation_update` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一会话表';

CREATE TABLE IF NOT EXISTS `conversation_member` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT,
    `conversation_id`    VARCHAR(64) NOT NULL,
    `user_id`            BIGINT      NOT NULL,
    `role`               VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    `last_read_sequence` BIGINT      NOT NULL DEFAULT 0,
    `unread_count`       INT         NOT NULL DEFAULT 0,
    `is_muted`           TINYINT     NOT NULL DEFAULT 0,
    `is_pinned`          TINYINT     NOT NULL DEFAULT 0,
    `join_time`          DATETIME    DEFAULT CURRENT_TIMESTAMP,
    `left_time`          DATETIME    DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_user` (`conversation_id`, `user_id`),
    KEY `idx_member_user` (`user_id`, `left_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话成员与已读位置';

DELIMITER $$

DROP PROCEDURE IF EXISTS add_lanchat_column_if_missing$$
CREATE PROCEDURE add_lanchat_column_if_missing(
    IN table_name_value VARCHAR(64),
    IN column_name_value VARCHAR(64),
    IN column_definition_value VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = table_name_value
          AND column_name = column_name_value
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', table_name_value, '` ADD COLUMN `',
                          column_name_value, '` ', column_definition_value);
        PREPARE statement_value FROM @ddl;
        EXECUTE statement_value;
        DEALLOCATE PREPARE statement_value;
    END IF;
END$$

DROP PROCEDURE IF EXISTS add_lanchat_index_if_missing$$
CREATE PROCEDURE add_lanchat_index_if_missing(
    IN table_name_value VARCHAR(64),
    IN index_name_value VARCHAR(64),
    IN index_definition_value VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = table_name_value
          AND index_name = index_name_value
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', table_name_value, '` ADD ', index_definition_value);
        PREPARE statement_value FROM @ddl;
        EXECUTE statement_value;
        DEALLOCATE PREPARE statement_value;
    END IF;
END$$

DELIMITER ;

CALL add_lanchat_column_if_missing('chat_message', 'client_msg_id',
     'VARCHAR(64) NULL COMMENT ''客户端幂等消息ID'' AFTER `message_id`');
CALL add_lanchat_column_if_missing('chat_message', 'conversation_id',
     'VARCHAR(64) NULL COMMENT ''统一会话ID'' AFTER `client_msg_id`');
CALL add_lanchat_column_if_missing('chat_message', 'sequence',
     'BIGINT NULL COMMENT ''会话内递增序列号'' AFTER `conversation_id`');
CALL add_lanchat_column_if_missing('chat_message', 'sender_device_id',
     'BIGINT NULL COMMENT ''发送设备会话ID'' AFTER `from_user_id`');
CALL add_lanchat_column_if_missing('chat_message', 'client_created_at',
     'DATETIME NULL COMMENT ''客户端创建时间'' AFTER `status`');

UPDATE `chat_message`
SET `client_msg_id` = `message_id`
WHERE `client_msg_id` IS NULL OR `client_msg_id` = '';

UPDATE `chat_message`
SET `conversation_id` = CASE
    WHEN `group_id` IS NOT NULL THEN CONCAT('group:', `group_id`)
    WHEN `to_user_id` IS NOT NULL THEN CONCAT(
        'private:', LEAST(`from_user_id`, `to_user_id`), ':',
        GREATEST(`from_user_id`, `to_user_id`)
    )
    ELSE NULL
END
WHERE `conversation_id` IS NULL OR `conversation_id` = '';

INSERT IGNORE INTO `conversation`
    (`id`, `type`, `source_id`, `last_sequence`, `status`, `create_time`, `update_time`)
SELECT CONCAT('group:', `id`), 'GROUP', `id`, 0, 'ACTIVE', `create_time`, `update_time`
FROM `chat_group`;

INSERT IGNORE INTO `conversation`
    (`id`, `type`, `source_id`, `last_sequence`, `status`, `create_time`, `update_time`)
SELECT CONCAT('private:', `user_low`, ':', `user_high`),
       'PRIVATE', NULL, 0, 'ACTIVE', `first_created_at`, `first_created_at`
FROM (
    SELECT LEAST(`user_id`, `friend_id`) AS `user_low`,
           GREATEST(`user_id`, `friend_id`) AS `user_high`,
           MIN(`create_time`) AS `first_created_at`
    FROM `friendship`
    GROUP BY LEAST(`user_id`, `friend_id`), GREATEST(`user_id`, `friend_id`)
) AS `private_pairs`;

INSERT IGNORE INTO `conversation`
    (`id`, `type`, `source_id`, `last_sequence`, `status`, `create_time`, `update_time`)
SELECT DISTINCT `conversation_id`,
       IF(`group_id` IS NULL, 'PRIVATE', 'GROUP'),
       `group_id`, 0, 'ACTIVE', MIN(`create_time`), MAX(`create_time`)
FROM `chat_message`
WHERE `conversation_id` IS NOT NULL
GROUP BY `conversation_id`, `group_id`;

INSERT IGNORE INTO `conversation_member`
    (`conversation_id`, `user_id`, `role`, `join_time`)
SELECT CONCAT('group:', `group_id`), `user_id`,
       CASE `role` WHEN 2 THEN 'OWNER' WHEN 1 THEN 'ADMIN' ELSE 'MEMBER' END,
       `join_time`
FROM `group_member`;

INSERT IGNORE INTO `conversation_member`
    (`conversation_id`, `user_id`, `role`, `join_time`)
SELECT CONCAT('private:', LEAST(`user_id`, `friend_id`), ':', GREATEST(`user_id`, `friend_id`)),
       `user_id`, 'MEMBER', `create_time`
FROM `friendship`;

INSERT IGNORE INTO `conversation_member`
    (`conversation_id`, `user_id`, `role`, `join_time`)
SELECT `conversation_id`, `from_user_id`, 'MEMBER', MIN(`create_time`)
FROM `chat_message`
WHERE `conversation_id` LIKE 'private:%'
GROUP BY `conversation_id`, `from_user_id`;

INSERT IGNORE INTO `conversation_member`
    (`conversation_id`, `user_id`, `role`, `join_time`)
SELECT `conversation_id`, `to_user_id`, 'MEMBER', MIN(`create_time`)
FROM `chat_message`
WHERE `conversation_id` LIKE 'private:%' AND `to_user_id` IS NOT NULL
GROUP BY `conversation_id`, `to_user_id`;

DROP TEMPORARY TABLE IF EXISTS `tmp_lanchat_ranked_message`;
CREATE TEMPORARY TABLE `tmp_lanchat_ranked_message` AS
SELECT `id`, ROW_NUMBER() OVER (
    PARTITION BY `conversation_id`
    ORDER BY `create_time`, `id`
) AS `assigned_sequence`
FROM `chat_message`
WHERE `conversation_id` IS NOT NULL;

UPDATE `chat_message` AS message
JOIN `tmp_lanchat_ranked_message` AS ranked ON ranked.`id` = message.`id`
SET message.`sequence` = ranked.`assigned_sequence`
WHERE message.`sequence` IS NULL;

DROP TEMPORARY TABLE IF EXISTS `tmp_lanchat_ranked_message`;

UPDATE `conversation` AS conversation_value
JOIN (
    SELECT `conversation_id`, MAX(`sequence`) AS `last_sequence`
    FROM `chat_message`
    WHERE `conversation_id` IS NOT NULL
    GROUP BY `conversation_id`
) AS message_summary
    ON message_summary.`conversation_id` = conversation_value.`id`
SET conversation_value.`last_sequence` = message_summary.`last_sequence`;

UPDATE `conversation` AS conversation_value
JOIN `chat_message` AS message
    ON message.`conversation_id` = conversation_value.`id`
   AND message.`sequence` = conversation_value.`last_sequence`
SET conversation_value.`last_message_id` = message.`message_id`,
    conversation_value.`update_time` = message.`create_time`;

ALTER TABLE `chat_message`
    MODIFY COLUMN `client_msg_id` VARCHAR(64) NOT NULL,
    MODIFY COLUMN `conversation_id` VARCHAR(64) NOT NULL,
    MODIFY COLUMN `sequence` BIGINT NOT NULL;

CALL add_lanchat_index_if_missing('chat_message', 'uk_sender_client_msg',
     'UNIQUE KEY `uk_sender_client_msg` (`from_user_id`, `client_msg_id`)');
CALL add_lanchat_index_if_missing('chat_message', 'uk_conversation_sequence',
     'UNIQUE KEY `uk_conversation_sequence` (`conversation_id`, `sequence`)');
CALL add_lanchat_index_if_missing('chat_message', 'idx_conversation_sequence',
     'KEY `idx_conversation_sequence` (`conversation_id`, `sequence`)');

DROP PROCEDURE IF EXISTS add_lanchat_column_if_missing;
DROP PROCEDURE IF EXISTS add_lanchat_index_if_missing;
