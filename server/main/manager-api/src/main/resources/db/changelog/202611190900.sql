-- 模型连接：一个连接保存一家服务商的接口地址和密钥，对话类模型挂在连接下。
CREATE TABLE `ai_model_channel` (
  `id` varchar(32) NOT NULL COMMENT '主键',
  `name` varchar(64) NOT NULL COMMENT '连接名称',
  `vendor_code` varchar(64) DEFAULT NULL COMMENT '厂商预设编码',
  `provider_code` varchar(50) NOT NULL COMMENT '接口协议(openai/ollama)',
  `base_url` varchar(512) NOT NULL COMMENT '接口地址',
  `secret_config_ciphertext` text COMMENT '加密后的凭据',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `sort` int unsigned DEFAULT '0' COMMENT '排序',
  `creator` bigint DEFAULT NULL COMMENT '创建者',
  `create_date` datetime DEFAULT NULL COMMENT '创建时间',
  `updater` bigint DEFAULT NULL COMMENT '更新者',
  `update_date` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型连接表';

ALTER TABLE `ai_model_config`
  ADD COLUMN `channel_id` varchar(32) DEFAULT NULL COMMENT '所属模型连接' AFTER `model_name`,
  ADD KEY `idx_ai_model_config_channel` (`channel_id`);
