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
