-- 用号码认证服务的“短信认证”（SendSmsVerifyCode）发登录验证码。它可以用阿里云赠送的签名和模板，不用自己申请签名。
-- aliyun.sms.api 选接口：verify 是短信认证，sms 是原来的短信服务（SendSms，要自己的签名和模板）。
delete from sys_params where id = 614;
INSERT INTO sys_params
(id, param_code, param_value, value_type, param_type, remark, creator, create_date, updater, update_date)
    VALUES
(614, 'aliyun.sms.api', 'verify', 'string', 1, '阿里云发验证码用哪个接口：verify 是号码认证服务的短信认证（可用赠送的签名和模板），sms 是短信服务', NULL, NULL, NULL, NULL);
UPDATE sys_params SET remark = '阿里云短信签名。短信认证可以用控制台“赠送签名配置”里的签名，例如 恒创联众' WHERE param_code = 'aliyun.sms.sign_name';
UPDATE sys_params SET remark = '阿里云短信模板。短信认证的赠送模板 100001 是登录/注册，要和赠送签名一起用' WHERE param_code = 'aliyun.sms.sms_code_template_code';
