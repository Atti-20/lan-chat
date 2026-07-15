-- 仅供隔离的本地开发环境使用，禁止在私有/生产部署执行。
-- 三个账号的统一演示密码为 LanChat123!。
USE lan_chat;
SET NAMES utf8mb4;

INSERT INTO `user` (`username`, `password`, `nickname`, `avatar`, `status`) VALUES
('admin', '$2a$10$VWOWjY7EBONKq/JPLNs/oO69k7SM4xG2qMskNP5MIH55T6ZwciU.C', '管理员', '', 1),
('alice', '$2a$10$VWOWjY7EBONKq/JPLNs/oO69k7SM4xG2qMskNP5MIH55T6ZwciU.C', '爱丽丝', '', 1),
('bob',   '$2a$10$VWOWjY7EBONKq/JPLNs/oO69k7SM4xG2qMskNP5MIH55T6ZwciU.C', '鲍勃', '', 1)
ON DUPLICATE KEY UPDATE `username` = VALUES(`username`);
