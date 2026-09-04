INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT 202609050900, 'product.identity', 'zixuan', 'string', 1, 'Product machine identity'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'product.identity'
);

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT 202609050901, 'product.contract_version', 'zixuan-cutover-v1', 'string', 1, 'Cross-layer product contract version'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'product.contract_version'
);
