-- E2E-only seed for the disposable database created by compose.e2e.yaml.
-- Known test credential: admin / E2eAdminPassword-2026
-- Never mount this fixture in private or production deployments.
USE lan_chat;
SET NAMES utf8mb4;

INSERT INTO `user` (
    `username`,
    `password`,
    `nickname`,
    `avatar`,
    `signature`,
    `online`,
    `status`,
    `can_send_broadcast`
) VALUES (
    'admin',
    '$2y$12$NzthS3ay5DwPw9FWI/SEMOEmXHDUYdDjLBJPGlgqdirrC8MR92cxa',
    'E2E 管理员',
    '',
    '',
    0,
    1,
    1
);

-- The bootstrap account is inserted after init.sql. Provision its organization
-- membership explicitly, since E2E intentionally disables the private initializer.
INSERT INTO organization_member (organization_id, user_id, status)
SELECT organization.id, user.id, 'ACTIVE'
FROM organization CROSS JOIN user
WHERE user.username = 'admin';

INSERT INTO member_role (member_id, role_id)
SELECT organization_member.id, role.id
FROM organization_member
JOIN user ON user.id = organization_member.user_id
JOIN role ON role.organization_id = organization_member.organization_id
WHERE user.username = 'admin' AND role.code IN ('MEMBER', 'ORG_OWNER');

-- E2E-only failure injection: proves that login's deactivate-then-insert
-- sequence is one transaction and therefore restores the old active session.
DELIMITER //
CREATE TRIGGER `e2e_fail_device_login_insert`
BEFORE INSERT ON `device_login`
FOR EACH ROW
BEGIN
    IF NEW.`device_name` = '__E2E_FAIL_DEVICE_LOGIN_INSERT__' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'E2E injected device session insert failure';
    END IF;
END//
DELIMITER ;
