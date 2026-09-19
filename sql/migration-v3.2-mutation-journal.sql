-- MX-A07B-1I: additive journal foundation. Select an explicitly reviewed database.
-- No capability advertisement, backfill, epoch reset or production execution.
-- Requires MySQL 8 / InnoDB; business writes and append MUST share a transaction.
CREATE TABLE IF NOT EXISTS recovery_session (
    id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    origin VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stream_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(32) NOT NULL,
    start_cursor BIGINT NOT NULL,
    initial_floor BIGINT NOT NULL,
    initial_latest BIGINT NOT NULL,
    cut_cursor BIGINT NULL,
    next_cut_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at DATETIME(6) NOT NULL,
    idempotency_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recovery_session_request (user_id, origin, idempotency_hash),
    KEY idx_recovery_session_pin (user_id, expires_at, start_cursor),
    CHECK (start_cursor >= 0 AND initial_floor >= 0 AND initial_latest >= start_cursor),
    CHECK (cut_cursor IS NULL OR cut_cursor >= start_cursor)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recovery_snapshot (
    session_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_count BIGINT NOT NULL,
    served_through BIGINT NOT NULL DEFAULT 0,
    page_complete BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (session_id),
    UNIQUE KEY uk_recovery_snapshot_id (snapshot_id),
    FOREIGN KEY (session_id) REFERENCES recovery_session(id) ON DELETE CASCADE,
    CHECK (item_count >= 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recovery_snapshot_item (
    session_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    position BIGINT NOT NULL,
    page_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) NOT NULL,
    conversation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    message_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    object_version BIGINT NULL,
    access_version BIGINT NULL,
    message_sequence BIGINT NOT NULL,
    state VARCHAR(16) NULL,
    read_allowed BOOLEAN NULL,
    send_allowed BOOLEAN NULL,
    PRIMARY KEY (session_id, position),
    UNIQUE KEY uk_recovery_snapshot_token (session_id, page_token),
    KEY idx_recovery_snapshot_conversation (session_id, kind, conversation_id),
    FOREIGN KEY (session_id) REFERENCES recovery_snapshot(session_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recovery_access_state (
    conversation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    user_id BIGINT NOT NULL,
    access_version BIGINT NOT NULL,
    read_allowed BOOLEAN NOT NULL,
    send_allowed BOOLEAN NOT NULL,
    PRIMARY KEY (conversation_id, user_id),
    CHECK (user_id > 0 AND access_version > 0),
    CHECK (read_allowed OR NOT send_allowed)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recovery_message_state (
    message_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    conversation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    message_sequence BIGINT NOT NULL,
    object_version BIGINT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (message_id),
    KEY idx_recovery_message_conversation (conversation_id, message_sequence, message_id),
    CHECK (message_sequence > 0 AND object_version > 0),
    CHECK (state IN ('NORMAL','RECALLED','BURNED','UNAVAILABLE'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recovery_user_stream (
    user_id BIGINT NOT NULL,
    stream_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    latest_cursor BIGINT NOT NULL DEFAULT 0,
    floor_cursor BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CHECK (latest_cursor >= 0 AND floor_cursor >= 0 AND floor_cursor <= latest_cursor)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recovery_mutation (
    user_id BIGINT NOT NULL,
    stream_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mutation_cursor BIGINT NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mutation_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    message_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    object_version BIGINT NULL,
    access_version BIGINT NULL,
    read_allowed BOOLEAN NULL,
    send_allowed BOOLEAN NULL,
    rebuild_conversation BOOLEAN NULL,
    reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    committed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, stream_epoch, mutation_cursor),
    UNIQUE KEY uk_recovery_event (user_id, stream_epoch, event_id),
    KEY idx_recovery_retention (committed_at, user_id, mutation_cursor),
    CHECK (mutation_cursor > 0),
    CHECK (object_version IS NULL OR object_version > 0),
    CHECK (access_version IS NULL OR access_version > 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recovery_dispatch_outbox (
    user_id BIGINT NOT NULL,
    stream_epoch CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mutation_cursor BIGINT NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    lease_token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    next_retry_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, stream_epoch, mutation_cursor),
    UNIQUE KEY uk_recovery_dispatch_event (user_id, stream_epoch, event_id),
    KEY idx_recovery_dispatch_claim (status, next_retry_at, lease_until)
) ENGINE=InnoDB;
