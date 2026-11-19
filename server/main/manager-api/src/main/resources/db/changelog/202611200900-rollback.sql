DELETE FROM `sys_params` WHERE `param_code` = 'app.invite_required';
DROP TABLE IF EXISTS `ai_invite_code_use`;
DROP TABLE IF EXISTS `ai_invite_code`;
