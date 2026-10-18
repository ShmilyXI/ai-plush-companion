-- App 认证手机号归一化：历史"11 位手机号即用户名"的账号回填 phone（统一 +86 国际格式），
-- 使这些账号可以用手机号直接登录 App。
-- 202609060900 已回填带 + 前缀的用户名，此处补充不带区号的国内手机号用户名。

UPDATE sys_user
SET phone = CONCAT('+86', username)
WHERE phone IS NULL
  AND username REGEXP '^1[3-9][0-9]{9}$';
