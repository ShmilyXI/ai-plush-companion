-- 消费者 App 身份体系：sys_user 增加手机号列，新建 App 多端 token 表
-- 手机号沿用国际区号格式（与 ValidatorUtils.isValidPhone 一致），回填历史"手机号即用户名"的账号

ALTER TABLE sys_user
    ADD COLUMN phone varchar(20) NULL COMMENT '手机号（含国际区号）' AFTER username;

UPDATE sys_user
SET phone = username
WHERE phone IS NULL
  AND username REGEXP '^[+][0-9]{6,20}$';

ALTER TABLE sys_user
    ADD UNIQUE KEY uk_phone (phone);

-- App 用户多端会话 token：只存哈希，明文仅在签发响应中返回一次
CREATE TABLE app_user_token (
  id bigint NOT NULL COMMENT 'id',
  user_id bigint NOT NULL COMMENT '用户id',
  token_hash varchar(64) NOT NULL COMMENT '访问令牌SHA-256哈希',
  refresh_hash varchar(64) NOT NULL COMMENT '刷新令牌SHA-256哈希',
  device_label varchar(100) COMMENT '客户端设备标识',
  ip varchar(64) COMMENT '签发时来源IP',
  expire_date datetime COMMENT '访问令牌过期时间',
  refresh_expire_date datetime COMMENT '刷新令牌过期时间',
  update_date datetime COMMENT '更新时间',
  create_date datetime COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_token_hash (token_hash),
  UNIQUE KEY uk_refresh_hash (refresh_hash),
  KEY idx_app_user_token_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='App用户Token';
