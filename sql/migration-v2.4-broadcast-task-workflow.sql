ALTER TABLE broadcast
    ADD COLUMN require_image_proof TINYINT NOT NULL DEFAULT 0
        COMMENT '完成时是否必须提交图片',
    ADD COLUMN require_location_proof TINYINT NOT NULL DEFAULT 0
        COMMENT '完成时是否必须提交定位',
    ADD COLUMN completed_at DATETIME DEFAULT NULL
        COMMENT '广播全部完成时间';

ALTER TABLE broadcast_receiver
    ADD COLUMN target_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE/REMOVED',
    ADD COLUMN completed_at DATETIME DEFAULT NULL
        COMMENT '接收者完成时间',
    ADD COLUMN removed_at DATETIME DEFAULT NULL,
    ADD COLUMN removed_by BIGINT DEFAULT NULL,
    ADD COLUMN remind_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_reminded_at DATETIME DEFAULT NULL;

CREATE TABLE broadcast_evidence (
                                    id BIGINT NOT NULL AUTO_INCREMENT,
                                    broadcast_id BIGINT NOT NULL,
                                    receiver_id BIGINT DEFAULT NULL,
                                    user_id BIGINT NOT NULL,

                                    evidence_type VARCHAR(30) NOT NULL
                                        COMMENT 'CONTENT_IMAGE/CONTENT_LOCATION/COMPLETION_IMAGE/COMPLETION_LOCATION',

                                    file_id BIGINT DEFAULT NULL,

                                    latitude DECIMAL(10, 7) DEFAULT NULL,
                                    longitude DECIMAL(10, 7) DEFAULT NULL,
                                    accuracy_meters DECIMAL(10, 2) DEFAULT NULL,
                                    address_text VARCHAR(255) DEFAULT NULL,
                                    captured_at DATETIME DEFAULT NULL,

                                    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                    PRIMARY KEY (id),
                                    KEY idx_broadcast_evidence_broadcast (broadcast_id),
                                    KEY idx_broadcast_evidence_receiver (receiver_id),
                                    KEY idx_broadcast_evidence_file (file_id)
) COMMENT='广播正文附件及接收者完成证据';