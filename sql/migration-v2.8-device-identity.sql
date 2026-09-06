-- V2.8 signed device identity, approval policy, V2 session binding and revocation versioning.
-- Run after V2.7. Additive and repeatable; legacy device_login sessions remain compatible.

USE lan_chat;

DROP PROCEDURE IF EXISTS `meshx_add_column_if_missing`;
DELIMITER $$
CREATE PROCEDURE `meshx_add_column_if_missing`(
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
        SET @meshx_device_ddl = ddl_value;
        PREPARE meshx_device_statement FROM @meshx_device_ddl;
        EXECUTE meshx_device_statement;
        DEALLOCATE PREPARE meshx_device_statement;
    END IF;
END$$
DELIMITER ;

CALL `meshx_add_column_if_missing`('device', 'app_version',
    'ALTER TABLE `device` ADD COLUMN `app_version` VARCHAR(50) NOT NULL DEFAULT '''' AFTER `display_name`');
CALL `meshx_add_column_if_missing`('device', 'capabilities_json',
    'ALTER TABLE `device` ADD COLUMN `capabilities_json` JSON DEFAULT NULL AFTER `app_version`');
CALL `meshx_add_column_if_missing`('device', 'rejection_reason',
    'ALTER TABLE `device` ADD COLUMN `rejection_reason` VARCHAR(200) DEFAULT NULL AFTER `approved_at`');
CALL `meshx_add_column_if_missing`('device', 'revoked_by',
    'ALTER TABLE `device` ADD COLUMN `revoked_by` BIGINT DEFAULT NULL AFTER `rejection_reason`');
CALL `meshx_add_column_if_missing`('device', 'revoked_at',
    'ALTER TABLE `device` ADD COLUMN `revoked_at` DATETIME DEFAULT NULL AFTER `revoked_by`');

CALL `meshx_add_column_if_missing`('device_credential', 'credential_id',
    'ALTER TABLE `device_credential` ADD COLUMN `credential_id` VARCHAR(64) DEFAULT NULL AFTER `device_id`');
CALL `meshx_add_column_if_missing`('device_credential', 'certificate_payload',
    'ALTER TABLE `device_credential` ADD COLUMN `certificate_payload` TEXT DEFAULT NULL AFTER `fingerprint`');
CALL `meshx_add_column_if_missing`('device_credential', 'status',
    'ALTER TABLE `device_credential` ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT ''PENDING'' AFTER `fingerprint`');
CALL `meshx_add_column_if_missing`('device_credential', 'certificate_signature',
    'ALTER TABLE `device_credential` ADD COLUMN `certificate_signature` VARCHAR(128) DEFAULT NULL AFTER `certificate_payload`');
CALL `meshx_add_column_if_missing`('device_credential', 'control_key_fingerprint',
    'ALTER TABLE `device_credential` ADD COLUMN `control_key_fingerprint` VARCHAR(128) DEFAULT NULL AFTER `certificate_signature`');
CALL `meshx_add_column_if_missing`('device_credential', 'requested_at',
    'ALTER TABLE `device_credential` ADD COLUMN `requested_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER `control_key_fingerprint`');
CALL `meshx_add_column_if_missing`('device_credential', 'approved_by',
    'ALTER TABLE `device_credential` ADD COLUMN `approved_by` BIGINT DEFAULT NULL AFTER `requested_at`');

CALL `meshx_add_column_if_missing`('device_session', 'legacy_device_login_id',
    'ALTER TABLE `device_session` ADD COLUMN `legacy_device_login_id` BIGINT DEFAULT NULL AFTER `user_id`');

CALL `meshx_add_column_if_missing`('revocation_entry', 'version',
    'ALTER TABLE `revocation_entry` ADD COLUMN `version` BIGINT DEFAULT NULL AFTER `subject_id`');
CALL `meshx_add_column_if_missing`('revocation_entry', 'signed_payload',
    'ALTER TABLE `revocation_entry` ADD COLUMN `signed_payload` TEXT DEFAULT NULL AFTER `reason`');
CALL `meshx_add_column_if_missing`('revocation_entry', 'signature',
    'ALTER TABLE `revocation_entry` ADD COLUMN `signature` VARCHAR(128) DEFAULT NULL AFTER `signed_payload`');
CALL `meshx_add_column_if_missing`('revocation_entry', 'control_key_fingerprint',
    'ALTER TABLE `revocation_entry` ADD COLUMN `control_key_fingerprint` VARCHAR(128) DEFAULT NULL AFTER `signature`');

CALL `meshx_add_column_if_missing`('organization_policy', 'device_approval_mode',
    'ALTER TABLE `organization_policy` ADD COLUMN `device_approval_mode` VARCHAR(20) NOT NULL DEFAULT ''MANUAL'' AFTER `p2p_enabled`');
CALL `meshx_add_column_if_missing`('organization_policy', 'credential_validity_days',
    'ALTER TABLE `organization_policy` ADD COLUMN `credential_validity_days` INT NOT NULL DEFAULT 90 AFTER `device_approval_mode`');
CALL `meshx_add_column_if_missing`('organization_policy', 'max_offline_hours',
    'ALTER TABLE `organization_policy` ADD COLUMN `max_offline_hours` INT NOT NULL DEFAULT 72 AFTER `credential_validity_days`');
CALL `meshx_add_column_if_missing`('organization_policy', 'revocation_version',
    'ALTER TABLE `organization_policy` ADD COLUMN `revocation_version` BIGINT NOT NULL DEFAULT 0 AFTER `max_offline_hours`');

DROP PROCEDURE `meshx_add_column_if_missing`;

ALTER TABLE `device_credential`
    MODIFY COLUMN `issued_at` DATETIME DEFAULT NULL;

ALTER TABLE `device`
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/ACTIVE/REJECTED/REVOKED';

UPDATE `device_credential`
SET credential_id = CONCAT('legacy-', id)
WHERE credential_id IS NULL;

UPDATE `revocation_entry`
SET version = id
WHERE version IS NULL;

SET @credential_id_nullable = (
    SELECT is_nullable FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'device_credential'
      AND column_name = 'credential_id'
);
SET @credential_id_ddl = IF(
    @credential_id_nullable = 'YES',
    'ALTER TABLE `device_credential` MODIFY COLUMN `credential_id` VARCHAR(64) NOT NULL',
    'SELECT 1'
);
PREPARE credential_id_statement FROM @credential_id_ddl;
EXECUTE credential_id_statement;
DEALLOCATE PREPARE credential_id_statement;

SET @revocation_version_nullable = (
    SELECT is_nullable FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'revocation_entry'
      AND column_name = 'version'
);
SET @revocation_version_ddl = IF(
    @revocation_version_nullable = 'YES',
    'ALTER TABLE `revocation_entry` MODIFY COLUMN `version` BIGINT NOT NULL',
    'SELECT 1'
);
PREPARE revocation_version_statement FROM @revocation_version_ddl;
EXECUTE revocation_version_statement;
DEALLOCATE PREPARE revocation_version_statement;

DROP PROCEDURE IF EXISTS `meshx_add_index_if_missing`;
DELIMITER $$
CREATE PROCEDURE `meshx_add_index_if_missing`(
    IN table_name_value VARCHAR(64),
    IN index_name_value VARCHAR(64),
    IN ddl_value TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = table_name_value
          AND index_name = index_name_value
    ) THEN
        SET @meshx_device_index_ddl = ddl_value;
        PREPARE meshx_device_index_statement FROM @meshx_device_index_ddl;
        EXECUTE meshx_device_index_statement;
        DEALLOCATE PREPARE meshx_device_index_statement;
    END IF;
END$$
DELIMITER ;

CALL `meshx_add_index_if_missing`('device_credential', 'uk_device_credential_id',
    'ALTER TABLE `device_credential` ADD UNIQUE KEY `uk_device_credential_id` (`credential_id`)');
CALL `meshx_add_index_if_missing`('device_session', 'uk_device_session_legacy_login',
    'ALTER TABLE `device_session` ADD UNIQUE KEY `uk_device_session_legacy_login` (`legacy_device_login_id`)');
CALL `meshx_add_index_if_missing`('revocation_entry', 'uk_revocation_version',
    'ALTER TABLE `revocation_entry` ADD UNIQUE KEY `uk_revocation_version` (`organization_id`, `version`)');

DROP PROCEDURE `meshx_add_index_if_missing`;
