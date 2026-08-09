package xiaozhi.modules.timbre.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import xiaozhi.common.page.PageData;
import xiaozhi.common.service.BaseService;
import xiaozhi.modules.model.dto.VoiceDTO;
import xiaozhi.modules.timbre.dto.TimbreDataDTO;
import xiaozhi.modules.timbre.dto.TimbrePageDTO;
import xiaozhi.modules.timbre.entity.TimbreEntity;
import xiaozhi.modules.timbre.vo.TimbreDetailsVO;

/**
 * 音色的业务层的定义
 * 
 * @author zjy
 * @since 2025-3-21
 */
public interface TimbreService extends BaseService<TimbreEntity> {
    /**
     * 分页获取音色指定tts的下的音色
     * 
     * @param dto 分页查找参数
     * @return 音色列表分页数据
     */
    PageData<TimbreDetailsVO> page(TimbrePageDTO dto);

    /**
     * 获取音色指定id的详情信息
     * 
     * @param timbreId 音色表id
     * @return 音色信息
     */
    TimbreDetailsVO get(String timbreId);

    /**
     * 保存音色信息
     * 
     * @param dto 需要保存数据
     */
    boolean save(TimbreDataDTO dto);

    /**
     * 保存音色信息
     * 
     * @param timbreId 需要修改的id
     * @param dto      需要修改的数据
     */
    boolean update(String timbreId, TimbreDataDTO dto);

    /**
     * 批量删除音色
     * 
     * @param ids 需要被删除的音色id列表
     */
    void delete(String[] ids);

    List<VoiceDTO> getVoiceNames(String ttsModelId, String voiceName);

    /**
     * 获取普通音色或克隆音色配置的首个有效语言。
     *
     * @param id 音色ID
     * @return 默认语言；音色不存在或未配置有效语言时返回null
     */
    String getDefaultLanguageById(String id);

    /**
     * 获取语言配置中的首个有效语言。
     *
     * @param languages 音色语言配置
     * @return 首个有效语言；未配置时返回null
     */
    String getDefaultLanguage(String languages);

    /**
     * 判断指定 TTS 模型是否配置了普通音色。
     *
     * @param ttsModelId TTS 模型ID
     * @return 存在普通音色时返回true
     */
    boolean hasTimbresForModel(String ttsModelId);

    /**
     * 根据ID获取音色名称
     * 
     * @param id 音色ID
     * @return 音色名称
     */
    String getTimbreNameById(String id);

    Map<String, String> getTimbreNamesByIds(Collection<String> ids);

    /**
     * 根据音色编码获取音色信息
     * 
     * @param ttsModelId 音色模型ID
     * @param voiceCode  音色编码
     * @return 音色信息
     */
    VoiceDTO getByVoiceCode(String ttsModelId, String voiceCode);
}
