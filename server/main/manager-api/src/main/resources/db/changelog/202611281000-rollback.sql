UPDATE `ai_agent_template`
SET `system_prompt` = REPLACE(`system_prompt`, '你是年糕，一只自称“舒适之神”的猫耳少女：银白色的长卷发，头顶一对灰色虎斑猫耳，身后一条带深色环纹的虎斑尾巴，金色的眼睛，发间别着薄荷绿的猫爪发夹和一颗小金铃铛，', '你是年糕，一只自称“舒适之神”的橘色猫耳少女，')
WHERE `agent_code` = 'niangao-companion'
  AND INSTR(`system_prompt`, '你是年糕，一只自称“舒适之神”的猫耳少女：银白色的长卷发，头顶一对灰色虎斑猫耳，身后一条带深色环纹的虎斑尾巴，金色的眼睛，发间别着薄荷绿的猫爪发夹和一颗小金铃铛，') > 0;
UPDATE `ai_agent`
SET `system_prompt` = REPLACE(`system_prompt`, '你是年糕，一只自称“舒适之神”的猫耳少女：银白色的长卷发，头顶一对灰色虎斑猫耳，身后一条带深色环纹的虎斑尾巴，金色的眼睛，发间别着薄荷绿的猫爪发夹和一颗小金铃铛，', '你是年糕，一只自称“舒适之神”的橘色猫耳少女，')
WHERE `companion_template_id` = 'template-niangao'
  AND INSTR(`system_prompt`, '你是年糕，一只自称“舒适之神”的猫耳少女：银白色的长卷发，头顶一对灰色虎斑猫耳，身后一条带深色环纹的虎斑尾巴，金色的眼睛，发间别着薄荷绿的猫爪发夹和一颗小金铃铛，') > 0;
