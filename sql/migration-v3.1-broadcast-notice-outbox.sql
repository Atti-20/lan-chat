-- V3.1 Durable post-commit routing for technical broadcast notification cards.
-- Apply after V3.0. The card and its routing intent are created in one
-- transaction; target removal persists both redaction and a recall intent.

USE lan_chat;

CREATE TABLE IF NOT EXISTS `broadcast_notice_outbox_task` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `idempotency_key`   VARCHAR(128) NOT NULL,
    `task_type`         VARCHAR(24)  NOT NULL COMMENT 'SYNC/RECALL',
    `notice_kind`       VARCHAR(24)  DEFAULT NULL COMMENT 'OVERVIEW/REMINDER for SYNC',
    `broadcast_id`      BIGINT       DEFAULT NULL,
    `receiver_user_id`  BIGINT       NOT NULL,
    `notice_generation` INT          DEFAULT NULL,
    `message_id`        VARCHAR(64)  DEFAULT NULL,
    `conversation_id`   VARCHAR(64)  DEFAULT NULL,
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DISPATCHED/REVOKED',
    `attempts`          INT          NOT NULL DEFAULT 0,
    `lease_token`       VARCHAR(64)  DEFAULT NULL,
    `lease_until`       DATETIME     DEFAULT NULL,
    `next_retry_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_error`        VARCHAR(240) DEFAULT NULL,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_broadcast_notice_outbox_key` (`idempotency_key`),
    KEY `idx_broadcast_notice_outbox_retry` (`status`, `next_retry_at`),
    KEY `idx_broadcast_notice_outbox_receiver` (`broadcast_id`, `receiver_user_id`, `notice_generation`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广播通知卡持久化投递与撤回任务';
