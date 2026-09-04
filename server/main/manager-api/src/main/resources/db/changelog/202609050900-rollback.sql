DELETE FROM `sys_params`
WHERE `param_code` IN ('product.identity', 'product.contract_version')
  AND `param_value` IN ('zixuan', 'zixuan-cutover-v1');
