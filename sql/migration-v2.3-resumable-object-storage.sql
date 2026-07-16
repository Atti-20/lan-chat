-- LanChat v2.3: resumable chunk uploads and LOCAL/MinIO object storage.
-- Run once against an existing v2.2 schema. Existing file rows remain LOCAL.

ALTER TABLE `file_metadata`
    ADD COLUMN `storage_type` VARCHAR(16) NOT NULL DEFAULT 'LOCAL'
        COMMENT '对象存储提供者：LOCAL/MINIO' AFTER `file_suffix`;

UPDATE `file_metadata`
SET `storage_type` = 'LOCAL'
WHERE `storage_type` IS NULL OR `storage_type` = '';

CREATE TABLE `file_upload_session` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `upload_id`         VARCHAR(32)  NOT NULL COMMENT '服务端上传会话ID',
    `client_upload_id`  VARCHAR(80)  NOT NULL COMMENT '客户端稳定幂等ID',
    `user_id`           BIGINT       NOT NULL COMMENT '上传用户ID',
    `conversation_id`   VARCHAR(64)  NOT NULL COMMENT '目标会话ID',
    `file_name`         VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_size`         BIGINT       NOT NULL COMMENT '完整文件大小',
    `file_type`         VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream' COMMENT '浏览器声明MIME',
    `file_hash`         CHAR(64)     NOT NULL COMMENT '完整文件SHA-256',
    `chunk_size`        BIGINT       NOT NULL COMMENT '服务端分片大小',
    `total_parts`       INT          NOT NULL COMMENT '总分片数',
    `status`            VARCHAR(20)  NOT NULL COMMENT 'UPLOADING/COMPLETED/CANCELLED/EXPIRED',
    `storage_type`      VARCHAR(16)  NOT NULL DEFAULT 'LOCAL' COMMENT '分片存储提供者',
    `completed_file_id` BIGINT       DEFAULT NULL COMMENT '完成后的文件元数据ID',
    `expires_at`        DATETIME     NOT NULL COMMENT '会话过期时间',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_upload_id` (`upload_id`),
    UNIQUE KEY `uk_file_upload_user_client` (`user_id`, `client_upload_id`),
    KEY `idx_file_upload_expiry` (`status`, `expires_at`),
    KEY `idx_file_upload_conversation` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复分片上传会话';

CREATE TABLE `file_upload_part` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `upload_id`    VARCHAR(32)  NOT NULL COMMENT '服务端上传会话ID',
    `part_number`  INT          NOT NULL COMMENT '从1开始的分片序号',
    `part_size`    BIGINT       NOT NULL COMMENT '分片字节数',
    `part_hash`    CHAR(64)     NOT NULL COMMENT '分片SHA-256',
    `storage_path` VARCHAR(500) NOT NULL COMMENT '私有对象存储键',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_upload_part` (`upload_id`, `part_number`),
    KEY `idx_file_upload_part_upload` (`upload_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复上传分片';

CREATE TABLE `file_object_cleanup_task` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `storage_type`  VARCHAR(16)  NOT NULL COMMENT '对象存储提供者：LOCAL/MINIO',
    `object_key`    VARCHAR(500) NOT NULL COMMENT '待删除的私有对象键',
    `reason`        VARCHAR(80)  NOT NULL COMMENT '清理原因',
    `task_type`     VARCHAR(32)  NOT NULL DEFAULT 'DELETE' COMMENT 'DELETE/RECONCILE_UPLOAD_PART',
    `upload_id`     VARCHAR(32)  DEFAULT NULL COMMENT '待核对的上传会话ID',
    `part_number`   INT          DEFAULT NULL COMMENT '待核对的分片序号',
    `attempts`      INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `next_retry_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
    `last_error`    VARCHAR(240) DEFAULT NULL COMMENT '最近一次失败摘要',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_cleanup_object` (`storage_type`, `object_key`),
    KEY `idx_file_cleanup_retry` (`next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件对象持久化清理任务';
