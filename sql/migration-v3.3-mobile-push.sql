-- Optional mobile push. Apply explicitly before enabling meshx.push.enabled.
CREATE TABLE IF NOT EXISTS mobile_push_subscription (
    device_id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    platform VARCHAR(16) CHARACTER SET ascii NOT NULL,
    endpoint VARCHAR(1000) NOT NULL,
    scope VARCHAR(80) CHARACTER SET ascii NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_mobile_push_user (user_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS mobile_push_delivery (
    device_id BIGINT NOT NULL,
    event_key VARCHAR(160) CHARACTER SET ascii NOT NULL,
    conversation_id VARCHAR(128) NULL,
    scope VARCHAR(80) CHARACTER SET ascii NOT NULL,
    lease_token VARCHAR(36) CHARACTER SET ascii NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (device_id, event_key),
    KEY idx_mobile_push_due (next_attempt_at)
) ENGINE=InnoDB;
