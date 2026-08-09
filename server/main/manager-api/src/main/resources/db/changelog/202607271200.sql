INSERT INTO `sys_params`
(`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT 202607271200, 'server.http', 'http://127.0.0.1:8003', 'string', 1,
       'xiaozhi-server 内部 HTTP 地址，用于记忆清除'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'server.http'
);
