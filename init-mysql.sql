-- Cortex2 数据库初始化脚本
-- 由 Docker MySQL 容器自动执行

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` VARCHAR(64) UNIQUE,
    `username` VARCHAR(64) NOT NULL UNIQUE,
    `password_hash` VARCHAR(256) NOT NULL,
    `email` VARCHAR(128),
    `avatar` VARCHAR(512),
    `role` VARCHAR(32) DEFAULT 'USER',
    `status` VARCHAR(32) DEFAULT 'ACTIVE',
    `last_login_time` DATETIME,
    `personality_tags` TEXT,
    `work_habits` TEXT,
    `preferences` TEXT,
    `total_study_hours` DOUBLE DEFAULT 0,
    `total_questions_answered` INT DEFAULT 0,
    `total_correct` INT DEFAULT 0,
    `study_streak` INT DEFAULT 0,
    `last_study_date` DATE,
    `weak_areas` TEXT,
    `preferred_direction` VARCHAR(256),
    `learning_goal` VARCHAR(256),
    `skill_level` VARCHAR(32) DEFAULT 'BEGINNER',
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 题库表
CREATE TABLE IF NOT EXISTS `lab_question_bank` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `title` VARCHAR(512) NOT NULL,
    `description` TEXT,
    `trap_code` LONGTEXT,
    `expected_pitfall` VARCHAR(1024),
    `correct_explanation` TEXT,
    `hints` TEXT,
    `category` VARCHAR(128),
    `knowledge_path` VARCHAR(512),
    `difficulty` INT DEFAULT 1,
    `status` VARCHAR(32) DEFAULT 'ACTIVE',
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 题目选项表
CREATE TABLE IF NOT EXISTS `question_option` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `question_id` BIGINT NOT NULL,
    `option_label` VARCHAR(16),
    `option_text` TEXT,
    `is_correct` TINYINT(1) DEFAULT 0,
    `guidance` TEXT,
    `hint_level` INT DEFAULT 0,
    `order_num` INT DEFAULT 0,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 场景表
CREATE TABLE IF NOT EXISTS `lab_scenario` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `node_id` VARCHAR(128),
    `knowledge_point` VARCHAR(256),
    `category` VARCHAR(128),
    `trap_code` LONGTEXT,
    `expected_pitfall` VARCHAR(1024),
    `correct_explanation` TEXT,
    `hints` TEXT,
    `difficulty` INT DEFAULT 1,
    `type` VARCHAR(32),
    `generated_content` LONGTEXT,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话表
CREATE TABLE IF NOT EXISTS `lab_session` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `session_id` VARCHAR(64) UNIQUE,
    `scenario_id` BIGINT,
    `user_id` VARCHAR(64),
    `status` VARCHAR(32) DEFAULT 'ACTIVE',
    `attempts` INT DEFAULT 0,
    `chat_history` LONGTEXT,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 知识卡片表
CREATE TABLE IF NOT EXISTS `lab_knowledge_card` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `question_id` BIGINT,
    `title` VARCHAR(512),
    `knowledge_path` VARCHAR(512),
    `key_points` TEXT,
    `detail_explanation` TEXT,
    `code_snippet` LONGTEXT,
    `common_pitfalls` TEXT,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 社区陷阱表
CREATE TABLE IF NOT EXISTS `lab_community_trap` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `title` VARCHAR(512) NOT NULL,
    `knowledge_point` VARCHAR(256),
    `knowledge_path` VARCHAR(512),
    `category` VARCHAR(128),
    `java_version` VARCHAR(16),
    `trap_code` LONGTEXT,
    `expected_pitfall` VARCHAR(1024),
    `correct_explanation` TEXT,
    `hints` TEXT,
    `difficulty` INT DEFAULT 1,
    `submitter` VARCHAR(64),
    `status` VARCHAR(32) DEFAULT 'PENDING',
    `vote_count` INT DEFAULT 0,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI 对话表
CREATE TABLE IF NOT EXISTS `assistant_conversation` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `conversation_id` VARCHAR(64) UNIQUE,
    `user_id` VARCHAR(64),
    `title` VARCHAR(256),
    `message_count` INT DEFAULT 0,
    `status` VARCHAR(32) DEFAULT 'ACTIVE',
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI 消息表
CREATE TABLE IF NOT EXISTS `assistant_message` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `conversation_id` VARCHAR(64),
    `role` VARCHAR(16),
    `content` LONGTEXT,
    `metadata` TEXT,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI 配置表
CREATE TABLE IF NOT EXISTS `assistant_config` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `config_key` VARCHAR(128),
    `config_value` TEXT,
    `config_type` VARCHAR(32),
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每日学习表
CREATE TABLE IF NOT EXISTS `daily_learning` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` VARCHAR(64),
    `log_date` DATE,
    `questions_answered` INT DEFAULT 0,
    `correct_count` INT DEFAULT 0,
    `study_minutes` INT DEFAULT 0,
    `knowledge_points_studied` TEXT,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 学习报告表
CREATE TABLE IF NOT EXISTS `learning_report` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` VARCHAR(64),
    `report_type` VARCHAR(32),
    `report_data` LONGTEXT,
    `period_start` DATE,
    `period_end` DATE,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 题目进度表
CREATE TABLE IF NOT EXISTS `lab_question_progress` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `question_id` BIGINT,
    `user_id` VARCHAR(64),
    `mastered` TINYINT(1) DEFAULT 0,
    `review_count` INT DEFAULT 0,
    `last_review_time` DATETIME,
    `next_review_time` DATETIME,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 讨论表
CREATE TABLE IF NOT EXISTS `lab_discussion` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `question_id` BIGINT,
    `parent_id` BIGINT,
    `user_id` VARCHAR(64),
    `content` TEXT,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 引导会话表
CREATE TABLE IF NOT EXISTS `guidance_session` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` VARCHAR(64),
    `question_id` BIGINT,
    `stage` VARCHAR(32),
    `selected_option` VARCHAR(16),
    `hint_count` INT DEFAULT 0,
    `correct_count` INT DEFAULT 0,
    `wrong_count` INT DEFAULT 0,
    `resolved` TINYINT(1) DEFAULT 0,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 规划会话表
CREATE TABLE IF NOT EXISTS `planning_session` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` VARCHAR(64),
    `goal` VARCHAR(512),
    `current_step` INT DEFAULT 0,
    `total_steps` INT DEFAULT 0,
    `steps_json` LONGTEXT,
    `completed_steps` INT DEFAULT 0,
    `status` VARCHAR(32) DEFAULT 'ACTIVE',
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 通知配置表
CREATE TABLE IF NOT EXISTS `user_notification_config` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` VARCHAR(64),
    `email_notifications` TINYINT(1) DEFAULT 0,
    `email_address` VARCHAR(128),
    `push_notifications` TINYINT(1) DEFAULT 0,
    `review_reminder` TINYINT(1) DEFAULT 0,
    `report_weekly` TINYINT(1) DEFAULT 0,
    `report_monthly` TINYINT(1) DEFAULT 0,
    `ai_active_mode` TINYINT(1) DEFAULT 1,
    `gmt_create` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 插入默认用户（密码 123456 的 SHA-256 哈希）
INSERT IGNORE INTO `user` (`user_id`, `username`, `password_hash`, `role`, `skill_level`) VALUES
('admin', 'admin', '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 'ROOT', 'ADVANCED'),
('demo', 'demo', '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 'USER', 'BEGINNER'),
('test', 'test', '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 'USER', 'INTERMEDIATE'),
('senior', 'senior', '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 'USER', 'ADVANCED');