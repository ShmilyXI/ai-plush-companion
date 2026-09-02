DELETE FROM `ai_companion_model_preset_meta`
WHERE `global_model_id` IN ('ASR_FunASR','ASR_SherpaASR','TTS_EdgeTTS',
 'Memory_nomem','Memory_mem_local_short','Memory_mem_report_only');
