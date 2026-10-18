DROP TABLE IF EXISTS app_user_token;

ALTER TABLE sys_user DROP INDEX uk_phone;

ALTER TABLE sys_user DROP COLUMN phone;
