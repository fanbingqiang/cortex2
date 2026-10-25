-- 已有数据库升级：为知识树内容缓存增加 node_id 列
-- 用法: docker exec -i cortex-mysql mysql -uroot -p密码 cortex2 < migrate-node-id.sql

USE cortex2;
SET NAMES utf8mb4;

ALTER TABLE `lab_scenario`
    ADD COLUMN IF NOT EXISTS `node_id` VARCHAR(100) DEFAULT NULL COMMENT '知识树节点ID' AFTER `id`;

-- MySQL 8.0.12+ 不支持 ADD COLUMN IF NOT EXISTS，若报错请手动执行：
-- ALTER TABLE lab_scenario ADD COLUMN node_id VARCHAR(100) DEFAULT NULL COMMENT '知识树节点ID' AFTER id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_node_id ON lab_scenario (node_id);

-- AI 导师主动模式字段
ALTER TABLE `user_notification_config`
    ADD COLUMN IF NOT EXISTS `ai_active_mode` TINYINT NOT NULL DEFAULT 1 COMMENT 'AI导师主动模式（0关闭 1开启）' AFTER `report_monthly`;
