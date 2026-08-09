CREATE TABLE `ai_companion_model_preset_meta` (
  `global_model_id` varchar(64) NOT NULL,
  `vendor_code` varchar(50) NOT NULL,
  `vendor_name` varchar(80) NOT NULL,
  `protocol` varchar(50) NOT NULL,
  `default_api_url` varchar(512) DEFAULT NULL,
  `credential_requirement` varchar(20) NOT NULL,
  `credential_fields_json` text,
  `key_url` varchar(512) DEFAULT NULL,
  `docs_url` varchar(512) DEFAULT NULL,
  `setup_guide_json` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`global_model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统模型厂商元数据';

CREATE TABLE `ai_companion_global_model_credential` (
  `id` varchar(32) NOT NULL,
  `user_id` bigint NOT NULL,
  `global_model_id` varchar(64) NOT NULL,
  `api_url_override` varchar(512) DEFAULT NULL,
  `model_id_override` varchar(255) DEFAULT NULL,
  `secret_config_ciphertext` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_companion_global_credential_user_model` (`user_id`,`global_model_id`),
  KEY `idx_companion_global_credential_model` (`global_model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号级系统模型凭据';

ALTER TABLE `ai_companion_private_model`
  ADD COLUMN `vendor_name` varchar(80) DEFAULT NULL AFTER `provider_code`,
  ADD COLUMN `protocol` varchar(50) DEFAULT NULL AFTER `vendor_name`,
  ADD COLUMN `credential_required` tinyint NOT NULL DEFAULT 1 AFTER `protocol`;

INSERT INTO `ai_companion_model_preset_meta`
(`global_model_id`,`vendor_code`,`vendor_name`,`protocol`,`default_api_url`,`credential_requirement`,`credential_fields_json`,`key_url`,`docs_url`,`setup_guide_json`)
VALUES
('LLM_ChatGLMLLM','zhipu','智谱 AI','OpenAI 兼容','https://open.bigmodel.cn/api/paas/v4','required',JSON_ARRAY(JSON_OBJECT('key','api_key','label','API Key','type','string','required',true,'secret',true)),'https://bigmodel.cn/usercenter/proj-mgmt/apikeys','https://docs.bigmodel.cn/cn/api/introduction',JSON_ARRAY('登录智谱开放平台','进入 API Key 页面创建密钥','复制密钥并粘贴到这里','保存后测试连接')),
('LLM_OllamaLLM','ollama','Ollama','OpenAI 兼容','http://localhost:11434/v1','not_required',JSON_ARRAY(),NULL,'https://docs.ollama.com/api/openai-compatibility',JSON_ARRAY('确认 Ollama 已在服务端运行','确认模型已经下载','保存 API 地址后测试连接')),
('LLM_AliLLM','aliyun-bailian','阿里云百炼','OpenAI 兼容',NULL,'required',JSON_ARRAY(JSON_OBJECT('key','api_key','label','API Key','type','string','required',true,'secret',true)),'https://bailian.console.aliyun.com/?tab=model#/api-key','https://help.aliyun.com/zh/model-studio/get-api-key',JSON_ARRAY('登录阿里云百炼控制台','选择模型所在地域和业务空间','创建 API Key 并复制控制台显示的 API Host','填写 API Host 与 API Key','保存后测试连接')),
('LLM_AliAppLLM','aliyun-bailian','阿里云百炼','百炼应用',NULL,'required',JSON_ARRAY(JSON_OBJECT('key','api_key','label','API Key','type','string','required',true,'secret',true)),'https://bailian.console.aliyun.com/?tab=model#/api-key','https://help.aliyun.com/zh/model-studio/get-api-key',JSON_ARRAY('登录阿里云百炼控制台','选择模型所在地域和业务空间','创建 API Key 并复制控制台显示的 API Host','填写 API Host 与 API Key','保存后测试连接')),
('LLM_DoubaoLLM','volcengine-ark','火山方舟','OpenAI 兼容','https://ark.cn-beijing.volces.com/api/v3','required',JSON_ARRAY(JSON_OBJECT('key','api_key','label','API Key','type','string','required',true,'secret',true)),'https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey','https://www.volcengine.com/docs/82379/1541594',JSON_ARRAY('登录火山方舟控制台','开通目标模型','创建 API Key','粘贴 Key 并确认模型 ID','保存后测试连接')),
('LLM_DeepSeekLLM','deepseek','DeepSeek','OpenAI 兼容','https://api.deepseek.com','required',JSON_ARRAY(JSON_OBJECT('key','api_key','label','API Key','type','string','required',true,'secret',true)),'https://platform.deepseek.com/api_keys','https://api-docs.deepseek.com/',JSON_ARRAY('登录 DeepSeek 开放平台','进入 API Keys 页面创建密钥','粘贴 Key','保存后测试连接')),
('LLM_GeminiLLM','google-gemini','Google Gemini','Gemini 原生',NULL,'required',JSON_ARRAY(JSON_OBJECT('key','api_key','label','API Key','type','string','required',true,'secret',true)),'https://aistudio.google.com/apikey','https://ai.google.dev/gemini-api/docs/api-key',JSON_ARRAY('登录 Google AI Studio','创建或选择项目','创建 Gemini API Key','粘贴 Key 并保存')),
('LLM_LMStudioLLM','lm-studio','LM Studio','OpenAI 兼容','http://localhost:1234/v1','not_required',JSON_ARRAY(),NULL,'https://lmstudio.ai/docs/developer/openai-compat',JSON_ARRAY('启动 LM Studio 本地服务','确认服务端能够访问该地址','保存后测试连接')),
('LLM_XinferenceLLM','xinference','Xinference','Xinference 原生','http://localhost:9997','not_required',JSON_ARRAY(),NULL,'https://inference.readthedocs.io/',JSON_ARRAY('确认 Xinference 已在服务端运行','确认目标模型已经启动','保存 API 地址后测试连接')),
('LLM_XinferenceSmallLLM','xinference','Xinference','Xinference 原生','http://localhost:9997','not_required',JSON_ARRAY(),NULL,'https://inference.readthedocs.io/',JSON_ARRAY('确认 Xinference 已在服务端运行','确认目标模型已经启动','保存 API 地址后测试连接')),
('LLM_XunfeiSparkLLM','xfyun-spark','讯飞星火','OpenAI 兼容','https://spark-api-open.xf-yun.com/v1','required',JSON_ARRAY(JSON_OBJECT('key','api_key','label','API Key','type','string','required',true,'secret',true)),'https://console.xfyun.cn/app/myapp','https://www.xfyun.cn/doc/spark/HTTP调用文档.html',JSON_ARRAY('登录讯飞开放平台','创建或进入应用','开通星火模型并获取 API Key','粘贴 Key','保存后测试连接')),
('VLLM_ChatGLMVLLM','zhipu','智谱 AI','OpenAI 兼容','https://open.bigmodel.cn/api/paas/v4','required',JSON_ARRAY(JSON_OBJECT('key','api_key','label','API Key','type','string','required',true,'secret',true)),'https://bigmodel.cn/usercenter/proj-mgmt/apikeys','https://docs.bigmodel.cn/cn/api/introduction',JSON_ARRAY('登录智谱开放平台','进入 API Key 页面创建密钥','复制密钥并粘贴到这里','保存后测试连接')),
('VLLM_QwenVLVLLM','aliyun-bailian','阿里云百炼','OpenAI 兼容',NULL,'required',JSON_ARRAY(JSON_OBJECT('key','api_key','label','API Key','type','string','required',true,'secret',true)),'https://bailian.console.aliyun.com/?tab=model#/api-key','https://help.aliyun.com/zh/model-studio/get-api-key',JSON_ARRAY('登录阿里云百炼控制台','选择模型所在地域和业务空间','创建 API Key 并复制控制台显示的 API Host','填写 API Host 与 API Key','保存后测试连接'));
