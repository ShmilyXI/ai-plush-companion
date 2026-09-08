UPDATE sys_user
SET phone = NULL
WHERE phone = CONCAT('+86', username)
  AND username REGEXP '^1[3-9][0-9]{9}$';
