-- V2.7 Control Server organization, RBAC, device identity and immutable audit foundation.
-- Additive and repeatable. Existing accounts join the default local organization;
-- the historical bootstrap account is mapped to ORG_OWNER only during migration.

USE lan_chat;

-- Operators using a non-default MESHX_ORGANIZATION_ID may set this session variable
-- before sourcing the migration, for example: SET @meshx_organization_key = 'org-acme';
SET @meshx_organization_key = COALESCE(NULLIF(@meshx_organization_key, ''), 'org-local');

CREATE TABLE IF NOT EXISTS `organization` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '组织内部主键',
    `organization_key` VARCHAR(64)  NOT NULL COMMENT '跨接口稳定组织标识',
    `name`             VARCHAR(100) NOT NULL COMMENT '组织名称',
    `status`           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `control_id`       VARCHAR(64)  DEFAULT NULL COMMENT '当前 Control Server 标识',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_organization_key` (`organization_key`),
    KEY `idx_organization_control` (`control_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Control Server 组织';

CREATE TABLE IF NOT EXISTS `organization_member` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '成员主键，与用户ID分离',
    `organization_id` BIGINT      NOT NULL,
    `user_id`         BIGINT      NOT NULL,
    `status`          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `department_id`   BIGINT      DEFAULT NULL,
    `joined_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `disabled_at`     DATETIME    DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_organization_member_user` (`organization_id`, `user_id`),
    KEY `idx_organization_member_user` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织成员身份';

CREATE TABLE IF NOT EXISTS `role` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id` BIGINT       NOT NULL,
    `code`            VARCHAR(50)  NOT NULL,
    `name`            VARCHAR(100) NOT NULL,
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    `system_role`     TINYINT      NOT NULL DEFAULT 1 COMMENT '系统角色不可由客户端改写',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_organization_code` (`organization_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织角色';

CREATE TABLE IF NOT EXISTS `permission` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(80)  NOT NULL,
    `description` VARCHAR(200) NOT NULL,
    `risk_level`  VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/HIGH/CRITICAL',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务端权限代码';

CREATE TABLE IF NOT EXISTS `role_permission` (
    `role_id`       BIGINT   NOT NULL,
    `permission_id` BIGINT   NOT NULL,
    `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`role_id`, `permission_id`),
    KEY `idx_role_permission_permission` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联';

CREATE TABLE IF NOT EXISTS `member_role` (
    `member_id`  BIGINT   NOT NULL,
    `role_id`    BIGINT   NOT NULL,
    `assigned_by` BIGINT  DEFAULT NULL COMMENT '授权者成员ID；迁移数据为空',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`member_id`, `role_id`),
    KEY `idx_member_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成员角色关联';

CREATE TABLE IF NOT EXISTS `device` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id` BIGINT       NOT NULL,
    `member_id`       BIGINT       DEFAULT NULL,
    `device_key`      VARCHAR(100) NOT NULL COMMENT '组织内稳定设备标识',
    `platform`        VARCHAR(30)  NOT NULL,
    `display_name`    VARCHAR(100) NOT NULL DEFAULT '',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/REVOKED',
    `approved_by`     BIGINT       DEFAULT NULL,
    `approved_at`     DATETIME     DEFAULT NULL,
    `last_seen_at`    DATETIME     DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_organization_key` (`organization_id`, `device_key`),
    KEY `idx_device_member_status` (`member_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织设备目录';

CREATE TABLE IF NOT EXISTS `device_credential` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `device_id`       BIGINT       NOT NULL,
    `algorithm`       VARCHAR(30)  NOT NULL,
    `public_key`      TEXT         NOT NULL,
    `fingerprint`     VARCHAR(128) NOT NULL,
    `issued_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expires_at`      DATETIME     DEFAULT NULL,
    `revoked_at`      DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_credential_fingerprint` (`fingerprint`),
    KEY `idx_device_credential_device` (`device_id`, `revoked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备公钥凭据';

CREATE TABLE IF NOT EXISTS `device_session` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id`    BIGINT       NOT NULL,
    `device_id`          BIGINT       NOT NULL,
    `user_id`            BIGINT       NOT NULL,
    `access_token_hash`  VARCHAR(128) NOT NULL COMMENT '只保存访问令牌摘要',
    `refresh_token_hash` VARCHAR(128) NOT NULL COMMENT '只保存刷新令牌摘要',
    `status`             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    `issued_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expires_at`         DATETIME     NOT NULL,
    `revoked_at`         DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_session_access_hash` (`access_token_hash`),
    KEY `idx_device_session_device_status` (`device_id`, `status`, `expires_at`),
    KEY `idx_device_session_user_status` (`user_id`, `status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2 设备会话；迁移期与 device_login 并存';

CREATE TABLE IF NOT EXISTS `revocation_entry` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id` BIGINT       NOT NULL,
    `subject_type`    VARCHAR(30)  NOT NULL COMMENT 'DEVICE/CREDENTIAL/SESSION',
    `subject_id`      VARCHAR(128) NOT NULL,
    `reason`          VARCHAR(200) NOT NULL,
    `revoked_by`      BIGINT       DEFAULT NULL,
    `revoked_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expires_at`      DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_revocation_subject` (`organization_id`, `subject_type`, `subject_id`),
    KEY `idx_revocation_time` (`organization_id`, `revoked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线可查询的设备与会话吊销目录';

CREATE TABLE IF NOT EXISTS `organization_policy` (
    `id`                BIGINT      NOT NULL AUTO_INCREMENT,
    `organization_id`   BIGINT      NOT NULL,
    `registration_mode` VARCHAR(30) NOT NULL DEFAULT 'ADMIN_CREATED',
    `p2p_enabled`       TINYINT     NOT NULL DEFAULT 0,
    `policy_json`       JSON        DEFAULT NULL,
    `version`           BIGINT      NOT NULL DEFAULT 1,
    `updated_by`        BIGINT      DEFAULT NULL,
    `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_organization_policy` (`organization_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织离线策略';

CREATE TABLE IF NOT EXISTS `audit_event` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `organization_id` BIGINT       NOT NULL,
    `actor_member_id` BIGINT       DEFAULT NULL,
    `actor_device_id` BIGINT       DEFAULT NULL,
    `action`          VARCHAR(80)  NOT NULL,
    `target_type`     VARCHAR(50)  DEFAULT NULL,
    `target_id`       VARCHAR(128) DEFAULT NULL,
    `outcome`         VARCHAR(20)  NOT NULL COMMENT 'SUCCEEDED/DENIED/FAILED',
    `request_id`      VARCHAR(80)  DEFAULT NULL,
    `detail_json`     JSON         DEFAULT NULL COMMENT '禁止写入口令、令牌和正文',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_organization_time` (`organization_id`, `created_at`),
    KEY `idx_audit_actor_time` (`actor_member_id`, `created_at`),
    KEY `idx_audit_target_time` (`target_type`, `target_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='追加式 Control 管理审计事件';

-- Enforce append-only semantics in the database, not merely in the controller surface.
DROP TRIGGER IF EXISTS `trg_audit_event_no_update`;
DROP TRIGGER IF EXISTS `trg_audit_event_no_delete`;
DELIMITER $$
CREATE TRIGGER `trg_audit_event_no_update`
BEFORE UPDATE ON `audit_event`
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_event is append-only';
END$$
CREATE TRIGGER `trg_audit_event_no_delete`
BEFORE DELETE ON `audit_event`
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_event is append-only';
END$$
DELIMITER ;

INSERT INTO `organization` (`organization_key`, `name`, `status`)
VALUES (@meshx_organization_key, '本地组织', 'ACTIVE')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `status` = VALUES(`status`);

INSERT INTO `role` (`organization_id`, `code`, `name`, `system_role`)
SELECT organization.id, seed.code, seed.name, 1
FROM `organization`
JOIN (
    SELECT 'ORG_OWNER' code, '组织所有者' name UNION ALL
    SELECT 'ORG_ADMIN', '组织管理员' UNION ALL
    SELECT 'SECURITY_ADMIN', '安全管理员' UNION ALL
    SELECT 'DEVICE_ADMIN', '设备管理员' UNION ALL
    SELECT 'DEPARTMENT_ADMIN', '部门管理员' UNION ALL
    SELECT 'AUDITOR', '审计员' UNION ALL
    SELECT 'MEMBER', '普通成员'
) seed
WHERE organization.organization_key = @meshx_organization_key
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `status` = 'ACTIVE';

INSERT INTO `permission` (`code`, `description`, `risk_level`) VALUES
    ('USER_READ', '查看组织成员', 'NORMAL'),
    ('USER_CREATE', '创建组织成员账号', 'HIGH'),
    ('USER_DISABLE', '停用或恢复组织成员', 'HIGH'),
    ('USER_DELETE', '归档或物理擦除组织成员', 'CRITICAL'),
    ('USER_PASSWORD_RESET', '重置组织成员口令', 'CRITICAL'),
    ('BROADCAST_CREATE', '创建应急广播', 'NORMAL'),
    ('BROADCAST_ALL', '向全组织创建广播', 'HIGH'),
    ('BROADCAST_PERMISSION_UPDATE', '修改成员广播发布资格', 'HIGH'),
    ('DIAGNOSTICS_READ', '读取 Control 诊断', 'NORMAL'),
    ('RUNTIME_LOG_READ', '读取或导出运行日志', 'HIGH'),
    ('DEVICE_APPROVE', '批准设备加入组织', 'HIGH'),
    ('DEVICE_REVOKE', '吊销设备与凭据', 'CRITICAL'),
    ('ROLE_ASSIGN', '分配组织角色', 'CRITICAL'),
    ('POLICY_UPDATE', '修改组织策略', 'CRITICAL'),
    ('AUDIT_READ', '读取组织审计事件', 'HIGH'),
    ('LICENSE_READ', '读取组织授权状态', 'NORMAL'),
    ('AI_TOOL_EXECUTE', '执行已批准的 AI 工具', 'HIGH'),
    ('AI_TOOL_APPROVE', '批准有副作用的 AI 工具', 'CRITICAL')
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`), `risk_level` = VALUES(`risk_level`);

-- The owner is the only initial role with every permission.
INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT role.id, permission.id
FROM `role`
JOIN `organization` ON organization.id = role.organization_id
CROSS JOIN `permission`
WHERE organization.organization_key = @meshx_organization_key AND role.code = 'ORG_OWNER';

-- Other built-in roles receive explicit least-privilege permission sets.
INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT role.id, permission.id
FROM `role`
JOIN `organization` ON organization.id = role.organization_id
JOIN `permission` ON
    (role.code = 'ORG_ADMIN' AND permission.code IN
        ('USER_READ','USER_CREATE','USER_DISABLE','USER_PASSWORD_RESET','BROADCAST_CREATE',
         'BROADCAST_ALL','BROADCAST_PERMISSION_UPDATE','DIAGNOSTICS_READ','RUNTIME_LOG_READ',
         'DEVICE_APPROVE','DEVICE_REVOKE','ROLE_ASSIGN','POLICY_UPDATE','AUDIT_READ','LICENSE_READ'))
 OR (role.code = 'SECURITY_ADMIN' AND permission.code IN
        ('USER_READ','USER_DISABLE','USER_PASSWORD_RESET','DEVICE_REVOKE','AUDIT_READ','RUNTIME_LOG_READ'))
 OR (role.code = 'DEVICE_ADMIN' AND permission.code IN
        ('USER_READ','DEVICE_APPROVE','DEVICE_REVOKE'))
 OR (role.code = 'DEPARTMENT_ADMIN' AND permission.code IN
        ('USER_READ','USER_CREATE','USER_DISABLE','BROADCAST_PERMISSION_UPDATE'))
 OR (role.code = 'AUDITOR' AND permission.code IN
        ('USER_READ','DIAGNOSTICS_READ','RUNTIME_LOG_READ','AUDIT_READ','LICENSE_READ'))
WHERE organization.organization_key = @meshx_organization_key;

INSERT IGNORE INTO `organization_policy`
    (`organization_id`, `registration_mode`, `p2p_enabled`, `version`)
SELECT id, 'ADMIN_CREATED', 0, 1 FROM `organization`
WHERE organization_key = @meshx_organization_key;

INSERT IGNORE INTO `organization_member` (`organization_id`, `user_id`, `status`)
SELECT organization.id, user.id,
       CASE WHEN user.status = 1 AND user.archived_at IS NULL THEN 'ACTIVE' ELSE 'DISABLED' END
FROM `organization`
CROSS JOIN `user`
WHERE organization.organization_key = @meshx_organization_key;

INSERT IGNORE INTO `member_role` (`member_id`, `role_id`)
SELECT member.id, role.id
FROM `organization_member` member
JOIN `organization` ON organization.id = member.organization_id
JOIN `role` ON role.organization_id = organization.id AND role.code = 'MEMBER'
WHERE organization.organization_key = @meshx_organization_key;

-- Bootstrap-name lookup is intentionally confined to this one-time compatibility migration.
INSERT IGNORE INTO `member_role` (`member_id`, `role_id`)
SELECT member.id, role.id
FROM `organization_member` member
JOIN `organization` ON organization.id = member.organization_id
JOIN `user` ON user.id = member.user_id
JOIN `role` ON role.organization_id = organization.id AND role.code = 'ORG_OWNER'
WHERE organization.organization_key = @meshx_organization_key AND user.username = 'admin';
