CREATE DATABASE IF NOT EXISTS infi_ai_nocode CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE infi_ai_nocode;

CREATE TABLE IF NOT EXISTS `user` (
  id bigint AUTO_INCREMENT PRIMARY KEY COMMENT 'id',
  userAccount varchar(256) NOT NULL COMMENT '账号',
  userPassword varchar(512) NOT NULL COMMENT '密码哈希',
  userName varchar(256) NULL COMMENT '用户昵称',
  userAvatar varchar(1024) NULL COMMENT '用户头像',
  userProfile varchar(512) NULL COMMENT '用户简介',
  userRole varchar(256) NOT NULL DEFAULT 'user' COMMENT 'user/admin',
  editTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  createTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updateTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  isDelete tinyint NOT NULL DEFAULT 0,
  UNIQUE KEY uk_userAccount (userAccount),
  INDEX idx_userName (userName)
) COMMENT '用户' COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app (
  id bigint AUTO_INCREMENT PRIMARY KEY COMMENT 'id',
  appName varchar(256) NULL,
  cover varchar(512) NULL,
  initPrompt text NULL,
  codeGenType varchar(64) NULL,
  deployKey varchar(64) NULL,
  deployedTime datetime NULL,
  priority int NOT NULL DEFAULT 0,
  userId bigint NOT NULL,
  editTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  createTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updateTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  isDelete tinyint NOT NULL DEFAULT 0,
  UNIQUE KEY uk_deployKey (deployKey),
  INDEX idx_appName (appName),
  INDEX idx_userId (userId)
) COMMENT '应用' COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_history (
  id bigint AUTO_INCREMENT PRIMARY KEY,
  message text NOT NULL,
  messageType varchar(32) NOT NULL COMMENT 'user/ai',
  appId bigint NOT NULL,
  userId bigint NOT NULL,
  createTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updateTime datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  isDelete tinyint NOT NULL DEFAULT 0,
  INDEX idx_appId (appId),
  INDEX idx_createTime (createTime),
  INDEX idx_appId_createTime (appId, createTime)
) COMMENT '对话历史' COLLATE utf8mb4_unicode_ci;
